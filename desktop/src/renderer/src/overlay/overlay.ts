import { SpeechSignalDetector } from '../lib/speech'
import { encodeWav } from '../lib/wav'

/**
 * Overlay bubble: an animated pill that walks through the dictation story —
 * recording (live waveform + timer) → uploading → transcribing (shimmer +
 * thinking dots) → inserted (✓ + text preview) / error / cancelled.
 * Never focusable — the target app keeps focus.
 */
const MAX_DURATION_MS = 60_000
// Mirrors the main-process visibility windows minus a beat for the exit anim.
const SUCCESS_EXIT_AT_MS = 1900
const ERROR_EXIT_AT_MS = 3500
const CANCELLED_EXIT_AT_MS = 1300
const BAR_COUNT = 18

const pill = document.getElementById('pill') as HTMLDivElement
const stateLine = document.getElementById('stateLine') as HTMLDivElement
const subLine = document.getElementById('subLine') as HTMLDivElement
const timerEl = document.getElementById('timer') as HTMLDivElement
const barsEl = document.getElementById('bars') as HTMLDivElement
const cancel = document.getElementById('cancel') as HTMLButtonElement

const bars: HTMLDivElement[] = []
for (let i = 0; i < BAR_COUNT; i++) {
  const bar = document.createElement('div')
  bar.className = 'bar'
  barsEl.appendChild(bar)
  bars.push(bar)
}

type UiState = 'idle' | 'recording' | 'processing' | 'success' | 'error' | 'cancelled'
let uiState: UiState = 'idle'

let sessionId = 0
let transcriptionLabel = 'Transcribing…'
let chunks: Float32Array[] = []
let detector: SpeechSignalDetector | null = null
let audioContext: AudioContext | null = null
let mediaStream: MediaStream | null = null
// Strong module-level references prevent Chromium GC from collecting live audio
// graph nodes mid-recording and silently halting onaudioprocess callbacks.
let audioSource: MediaStreamAudioSourceNode | null = null
let audioProcessor: ScriptProcessorNode | null = null
let audioMute: GainNode | null = null
let autoStop: ReturnType<typeof setTimeout> | null = null
const stateTimers: ReturnType<typeof setTimeout>[] = []
const phaseTimers: ReturnType<typeof setTimeout>[] = []
let tickInterval: ReturnType<typeof setInterval> | null = null
let tickStart = 0

const runningFlag = { value: false }
function running(): boolean {
  return runningFlag.value
}

function setState(state: UiState): void {
  uiState = state
  document.body.dataset.state = state
  if (state === 'error') {
    cancel.title = 'Dismiss'
    cancel.setAttribute('aria-label', 'Dismiss')
  } else {
    cancel.title = 'Cancel dictation'
    cancel.setAttribute('aria-label', 'Cancel dictation')
  }
}

function clearTimers(list: ReturnType<typeof setTimeout>[]): void {
  for (const t of list) clearTimeout(t)
  list.length = 0
}

function schedule(fn: () => void, ms: number): void {
  clearTimers(stateTimers)
  const t = setTimeout(fn, ms)
  stateTimers.push(t)
}

function setLine(text: string): void {
  stateLine.textContent = text
}

function setSub(text: string): void {
  subLine.textContent = text
}

function setRecordingHint(actionText: string): void {
  subLine.textContent = `${actionText} · `
  const esc = document.createElement('kbd')
  esc.textContent = 'esc'
  subLine.append(esc, ' cancels')
}

function startTick(): void {
  stopTick()
  tickStart = Date.now()
  tickInterval = setInterval(() => {
    const s = Math.floor((Date.now() - tickStart) / 1000)
    timerEl.textContent = `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`
  }, 200)
}

function stopTick(): void {
  if (tickInterval !== null) clearInterval(tickInterval)
  tickInterval = null
}

function enterRecording(releaseHint: string): void {
  setState('recording')
  pill.classList.remove('closing')
  pill.classList.add('show')
  pill.dataset.tone = ''
  setLine('Listening…')
  setRecordingHint(`${releaseHint} to transcribe`)
  timerEl.textContent = '0:00'
  startTick()
  for (const bar of bars) bar.style.height = '5px'
}

async function openMicStream(micDeviceId: string): Promise<MediaStream> {
  const baseAudio: MediaTrackConstraints = {
    channelCount: 1,
    echoCancellation: true,
    noiseSuppression: true,
    autoGainControl: true
  }
  if (micDeviceId) {
    try {
      return await navigator.mediaDevices.getUserMedia({
        audio: { ...baseAudio, deviceId: { exact: micDeviceId } }
      })
    } catch (error) {
      // Selected mic disconnected or unavailable — fall back to the default input.
      const name = error instanceof Error ? error.name : ''
      if (name === 'NotAllowedError' || name === 'SecurityError') throw error
    }
  }
  return navigator.mediaDevices.getUserMedia({ audio: baseAudio })
}

async function begin(id: number, micDeviceId: string): Promise<void> {
  if (running()) return
  // Claim the session synchronously: a release that lands while the mic is
  // still starting must not leave a ghost recording running after finish().
  runningFlag.value = true
  try {
    const opened = await openMicStream(micDeviceId)
    if (id !== sessionId || !running()) {
      opened.getTracks().forEach((track) => track.stop())
      return
    }
    mediaStream = opened
  } catch {
    if (id !== sessionId || !running()) return
    runningFlag.value = false
    // Notify main once — main drives the single error state transition & sound.
    window.api.overlay.fail('Microphone access was denied. Enable it and try again.', id)
    return
  }
  if (!running() || id !== sessionId) {
    teardownAudio()
    return
  }

  chunks = []
  const openedContext = new AudioContext()
  audioContext = openedContext
  // Chromium starts contexts suspended without user activation; the overlay
  // is never focused, so resume explicitly or no audio callbacks ever fire.
  if (openedContext.state === 'suspended') await openedContext.resume()
  if (!running() || id !== sessionId) {
    if (audioContext === openedContext) teardownAudio()
    else void openedContext.close()
    return
  }

  detector = new SpeechSignalDetector(audioContext.sampleRate)
  audioSource = audioContext.createMediaStreamSource(mediaStream)
  audioProcessor = audioContext.createScriptProcessor(4096, 1, 1)
  audioProcessor.onaudioprocess = (event) => {
    if (!running()) return
    const input = event.inputBuffer.getChannelData(0)
    const copy = new Float32Array(input)
    chunks.push(copy)
    const rms = detector!.observe(copy)
    animateBars(rms)
  }
  audioSource.connect(audioProcessor)
  // ScriptProcessorNode only pumps while connected to the destination; a
  // zero-gain node keeps it running without monitoring the mic out loud.
  audioMute = audioContext.createGain()
  audioMute.gain.value = 0
  audioProcessor.connect(audioMute)
  audioMute.connect(audioContext.destination)

  autoStop = setTimeout(() => {
    autoStop = null
    window.api.overlay.autoStop(id)
  }, MAX_DURATION_MS)
}

let barPhase = 0
function animateBars(rms: number): void {
  barPhase += 0.55
  for (let i = 0; i < bars.length; i++) {
    const wobble = 0.55 + 0.45 * Math.abs(Math.sin(barPhase + i * 0.7))
    const level = Math.min(1, rms * 9)
    const height = 5 + level * 29 * (0.55 + 0.45 * wobble)
    bars[i].style.height = `${height}px`
  }
}

function settleBars(): void {
  for (const bar of bars) bar.style.height = '5px'
}

function enterProcessing(): void {
  clearTimers(phaseTimers)
  setState('processing')
  pill.dataset.tone = ''
  settleBars()
  startTick()
  setLine('Uploading audio…')
  setSub('')
  phaseTimers.push(
    setTimeout(() => {
      if (uiState === 'processing') setLine(transcriptionLabel)
    }, 750)
  )
}

function setSuccess(text: string, polished: boolean): void {
  clearTimers(phaseTimers)
  stopTick()
  setState('success')
  pill.classList.remove('closing')
  pill.classList.add('show')
  pill.dataset.tone = 'success'
  setLine(text)
  setSub(polished ? 'Inserted at your cursor · ' : 'Inserted at your cursor')
  if (polished) {
    const chip = document.createElement('span')
    chip.className = 'chip'
    chip.textContent = '✨ polished'
    subLine.appendChild(chip)
  }
  schedule(() => pill.classList.add('closing'), SUCCESS_EXIT_AT_MS)
}

function fail(message: string, copied = false): void {
  clearTimers(phaseTimers)
  stopTick()
  setState('error')
  pill.classList.remove('closing')
  pill.classList.add('show')
  pill.dataset.tone = 'error'
  setLine(message)
  setSub(copied ? 'Saved to History and copied to clipboard' : 'Nothing was inserted')
  schedule(() => pill.classList.add('closing'), ERROR_EXIT_AT_MS)
}

function setCancelled(): void {
  discardCapture()
  clearTimers(phaseTimers)
  setState('cancelled')
  pill.classList.remove('closing')
  pill.classList.add('show')
  pill.dataset.tone = ''
  setLine('Dictation cancelled')
  setSub('')
  settleBars()
  schedule(() => pill.classList.add('closing'), CANCELLED_EXIT_AT_MS)
}

function setSilentCancelled(): void {
  discardCapture()
  clearTimers(phaseTimers)
  clearTimers(stateTimers)
  setState('idle')
  pill.classList.remove('show', 'closing')
  pill.dataset.tone = ''
}

/** Releases every capture resource; safe to call from any state. */
function discardCapture(): void {
  if (autoStop !== null) {
    clearTimeout(autoStop)
    autoStop = null
  }
  stopTick()
  chunks = []
  detector = null
  teardownAudio()
  runningFlag.value = false
}

function finish(): void {
  if (!running()) return
  if (autoStop !== null) {
    clearTimeout(autoStop)
    autoStop = null
  }
  stopTick()
  const captured = chunks
  chunks = []
  const detection = detector?.result() ?? { containsSpeech: false, durationMs: 0 }
  detector = null
  // Read the live rate before teardownAudio() nulls the context — the
  // fallback would otherwise encode every recording as 48 kHz regardless
  // of the input device (44.1 kHz mics produced pitch-shifted WAVs).
  const sampleRate = audioContext?.sampleRate ?? 48_000
  teardownAudio()
  runningFlag.value = false

  // Reject silence BEFORE switching visuals to "Uploading audio…".
  if (!detection.containsSpeech) {
    console.log('overlay page: speech gate rejected —', JSON.stringify(detection))
    window.api.overlay.fail('No clear speech detected — try speaking a little longer', sessionId)
    return
  }

  enterProcessing()

  const maxSamples = Math.floor((sampleRate * MAX_DURATION_MS) / 1000)
  const total = Math.min(
    maxSamples,
    captured.reduce((sum, chunk) => sum + chunk.length, 0)
  )
  const merged = new Float32Array(total)
  let offset = 0
  for (const chunk of captured) {
    if (offset >= total) break
    const part = chunk.subarray(0, total - offset)
    merged.set(part, offset)
    offset += part.length
  }
  const wav = encodeWav(merged, sampleRate)
  console.log('overlay page: sending WAV,', merged.length, 'samples @', sampleRate, 'Hz')
  window.api.overlay.done({
    sessionId,
    wav,
    durationMs: Math.min(detection.durationMs, MAX_DURATION_MS),
    containsSpeech: detection.containsSpeech
  })
}

function handleCloseButton(): void {
  if (uiState === 'recording' || uiState === 'processing') {
    discardCapture()
    window.api.overlay.cancelled(sessionId)
    return
  }
  clearTimers(phaseTimers)
  clearTimers(stateTimers)
  setState('idle')
  pill.classList.remove('show', 'closing')
  window.api.overlay.dismiss()
}

function teardownAudio(): void {
  if (audioProcessor !== null) {
    audioProcessor.onaudioprocess = null
    try {
      audioProcessor.disconnect()
    } catch {
      // already disconnected
    }
    audioProcessor = null
  }
  if (audioSource !== null) {
    try {
      audioSource.disconnect()
    } catch {
      // already disconnected
    }
    audioSource = null
  }
  if (audioMute !== null) {
    try {
      audioMute.disconnect()
    } catch {
      // already disconnected
    }
    audioMute = null
  }
  mediaStream?.getTracks().forEach((track) => track.stop())
  mediaStream = null
  void audioContext?.close()
  audioContext = null
  settleBars()
}

window.api.overlay.onBegin((releaseHint, id, micDeviceId) => {
  discardCapture()
  sessionId = id
  transcriptionLabel = 'Transcribing…'
  clearTimers(stateTimers)
  clearTimers(phaseTimers)
  pill.classList.remove('closing')
  enterRecording(releaseHint)
  void begin(id, micDeviceId)
})
window.api.overlay.onEnd(() => finish())
window.api.overlay.onLatch((hint) => {
  if (uiState === 'recording') {
    setRecordingHint(`${hint} to finish`)
  }
})
window.api.overlay.onState((state) => {
  if (state.state === 'transcribing') {
    transcriptionLabel =
      state.contextLabel && state.contextLabel !== 'Context off'
        ? `Transcribing · ${state.contextLabel}`
        : 'Transcribing…'
    if (uiState === 'processing') setLine(transcriptionLabel)
    return
  }
  if (state.state === 'success') {
    setSuccess(state.text, state.polished)
    return
  }
  if (state.state === 'error') {
    fail(state.message, state.copied === true)
    return
  }
  if (state.state === 'cancelled') {
    setCancelled()
    return
  }
  if (state.state === 'silent-cancel') {
    setSilentCancelled()
  }
})
cancel.addEventListener('click', handleCloseButton)
