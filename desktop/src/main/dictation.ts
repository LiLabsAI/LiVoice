import { BrowserWindow, clipboard, globalShortcut, screen } from 'electron'
import { join } from 'path'
import { WorkerError, type PolishLevel, type TranscribeOptions, type WorkerUsage } from './worker'
import { pasteText } from './insert'
import { playSound } from './sounds'
import {
  ContextReader,
  restrictContext,
  type ContextLevel,
  type ContextStyle,
  type ContextTarget,
  type DictationContext
} from './context'
import { contextLabel } from '../../../worker/src/context'
import { DictationSession } from './dictation-session'

export interface DictationDeps {
  settings: {
    workerUrl: string
    contextLevel: ContextLevel
    contextStyle: ContextStyle
    contextOwner: string
    locale: string
    polish: PolishLevel
    soundsEnabled: boolean
    micDeviceId: string
  }
  isAccountAllowed: () => { allowed: boolean; message?: string }
  onAccountRestricted?: (state: 'suspended' | 'banned', message: string) => void
  getToken: () => Promise<string>
  /** Drops a cached access token the Worker rejected so getToken() refreshes. */
  invalidateToken: (token: string) => void
  getGlossary: () => Promise<string[]>
  transcribe: (
    token: string,
    wav: Buffer,
    glossary: string[],
    context?: DictationContext,
    sentenceStart?: boolean,
    options?: TranscribeOptions
  ) => Promise<{
    text: string
    polished: boolean
    asrModel: string
    requestId: string
    timingsMs: { asr: number; polish: number; total: number }
    usage: WorkerUsage | null
  }>
  recordHistory: (entry: {
    text: string
    asrModel: string
    requestId: string
    durationMs: number
    polished: boolean
    timingsMs: { asr: number; polish: number; total: number }
    usage: WorkerUsage | null
  }) => void
  /** A session ended without the trigger being released (Esc, cancel, 60 s cap). */
  onSessionAborted?: () => void
}

export type OverlayState =
  | { state: 'transcribing'; contextLabel?: string }
  | { state: 'success'; text: string; polished: boolean }
  | { state: 'error'; message: string; copied?: boolean }
  | { state: 'cancelled' }
  | { state: 'silent-cancel' }

// Pill visibility windows (the renderer mirrors these for its exit animation).
const SUCCESS_VISIBLE_MS = 2200
const ERROR_VISIBLE_MS = 3800
// Fails the session if the transcribe→paste pipeline never resolves (hung
// request, crashed renderer) — otherwise the trigger stays dead until relaunch.
const PIPELINE_TIMEOUT_MS = 120_000
const OVERLAY_WIDTH = 580
const OVERLAY_HEIGHT = 160

/**
 * Owns the overlay window and the dictation state machine:
 * begin → overlay records → end → WAV → transcribe → paste → result label.
 * Every transition is surfaced on the pill (animated) and with an optional sound.
 */
export class DictationService {
  private overlay: BrowserWindow | null = null
  private readonly contextReader = new ContextReader()
  private readonly sessions = new DictationSession()
  private sessionId = 0
  private contextTarget: Promise<ContextTarget> | null = null
  private recording = false
  private processing = false
  private watchdog: ReturnType<typeof setTimeout> | null = null
  private hideTimer: ReturnType<typeof setTimeout> | null = null

  constructor(private readonly deps: DictationDeps) {}

  isBusy(): boolean {
    return this.recording || this.processing
  }

  getOverlayWebContentsId(): number | null {
    const win = this.liveOverlay()
    return win ? win.webContents.id : null
  }

  private liveOverlay(): BrowserWindow | null {
    if (this.overlay === null || this.overlay.isDestroyed()) {
      this.overlay = null
      return null
    }
    return this.overlay
  }

  private positionOverlay(win: BrowserWindow): void {
    try {
      const display = screen.getDisplayNearestPoint(screen.getCursorScreenPoint())
      const { workArea } = display
      const x = workArea.x + Math.round((workArea.width - OVERLAY_WIDTH) / 2)
      const y = workArea.y + 56
      win.setBounds({ x, y, width: OVERLAY_WIDTH, height: OVERLAY_HEIGHT }, false)
    } catch {
      // Fall back to existing bounds if screen query fails transiently.
    }
  }

  private ensureOverlay(): BrowserWindow {
    const existing = this.liveOverlay()
    if (existing !== null) return existing

    const display = screen.getDisplayNearestPoint(screen.getCursorScreenPoint())
    const { workArea } = display
    const win = new BrowserWindow({
      width: OVERLAY_WIDTH,
      height: OVERLAY_HEIGHT,
      show: false,
      x: workArea.x + Math.round((workArea.width - OVERLAY_WIDTH) / 2),
      y: workArea.y + 56,
      frame: false,
      transparent: true,
      resizable: false,
      movable: false,
      focusable: false,
      alwaysOnTop: true,
      skipTaskbar: true,
      center: false,
      hasShadow: false,
      webPreferences: {
        preload: join(__dirname, '../preload/index.js'),
        sandbox: true,
        contextIsolation: true,
        nodeIntegration: false
      }
    })
    win.setVisibleOnAllWorkspaces(true, { visibleOnFullScreen: true })
    win.setAlwaysOnTop(true, 'floating')

    win.webContents.setWindowOpenHandler(() => ({ action: 'deny' }))
    win.webContents.on('will-navigate', (event) => {
      event.preventDefault()
    })
    win.on('closed', () => {
      if (this.overlay === win) this.overlay = null
      if (this.recording || this.processing) {
        this.resetInFlight()
        this.deps.onSessionAborted?.()
      }
    })
    win.webContents.on('render-process-gone', () => {
      if (this.overlay === win) this.overlay = null
      if (!win.isDestroyed()) win.destroy()
      if (this.recording || this.processing) {
        this.resetInFlight()
        this.deps.onSessionAborted?.()
      }
    })

    this.overlay = win
    void win.loadFile(join(__dirname, '../renderer/overlay.html'))
    return win
  }

  /** Load the overlay page at startup so the first dictation isn't lost. */
  prepare(): void {
    this.ensureOverlay()
    this.contextReader.prepare()
  }

  /** Tap toggles (start; tap again to stop). */
  toggle(): void {
    if (this.recording) this.end()
    else if (!this.processing) this.begin()
  }

  /** `releaseHint` names what ends the recording, e.g. "Release ⌘". */
  begin(releaseHint = 'Release ⌥'): void {
    if (this.recording || this.processing) return
    this.clearHideTimer()

    const gate = this.deps.isAccountAllowed()
    if (!gate.allowed) {
      const overlay = this.ensureOverlay()
      this.positionOverlay(overlay)
      overlay.showInactive()
      this.fail(gate.message || 'Your account cannot use voice dictation right now.')
      this.deps.onSessionAborted?.()
      return
    }

    this.sessionId = this.sessions.begin(this.deps.settings.contextOwner)
    this.contextTarget = this.contextReader.capture(
      this.deps.settings.contextLevel,
      this.deps.settings.contextStyle
    )
    console.log('[dictation] begin')
    const overlay = this.ensureOverlay()
    this.positionOverlay(overlay)
    overlay.setVisibleOnAllWorkspaces(true, { visibleOnFullScreen: true })
    overlay.setAlwaysOnTop(true, 'floating')
    overlay.showInactive()
    this.recording = true
    playSound('start', this.deps.settings.soundsEnabled)
    this.sendToOverlay(
      overlay,
      'overlay:begin',
      releaseHint,
      this.sessionId,
      this.deps.settings.micDeviceId
    )
    // Esc cancels the in-flight recording (a global grab for a few seconds).
    const escOk = globalShortcut.register('Escape', () => this.cancelUser())
    console.log('[dictation] esc-to-cancel registered:', escOk)
  }

  notifyLatched(hint: string): void {
    if (!this.recording) return
    const overlay = this.liveOverlay()
    if (overlay !== null) this.sendToOverlay(overlay, 'overlay:latch', hint)
  }

  end(): void {
    if (!this.recording) return
    console.log('[dictation] end — requesting WAV')
    this.clearHideTimer()
    this.recording = false
    this.processing = true
    this.releaseEscape()
    playSound('stop', this.deps.settings.soundsEnabled)
    const overlay = this.liveOverlay()
    if (overlay !== null) this.sendToOverlay(overlay, 'overlay:end')
    this.armWatchdog()
  }

  cancelUser(): void {
    if (this.recording || this.processing) {
      this.resetInFlight()
      console.log('[dictation] cancelled by user')
      playSound('cancel', this.deps.settings.soundsEnabled)
      this.sendState({ state: 'cancelled' })
      this.hideAfter(1600)
      // The trigger is still latched/held; without a reset the next tap only
      // "stops" this already-cancelled session and is swallowed.
      this.deps.onSessionAborted?.()
    }
  }

  /**
   * Quiet cancellation used when a held modifier key turned out to be part of
   * a shortcut combo (e.g. Opt+Left, Cmd+C). Releases the mic immediately
   * without playing the cancel sound or flashing an error pill.
   */
  cancelSilent(): void {
    if (!this.recording && !this.processing) return
    this.resetInFlight()
    this.sendState({ state: 'silent-cancel' })
    this.hide()
  }

  /** Dismisses a visible error/cancelled overlay when the user clicks ✕. */
  dismissOverlay(): void {
    if (this.recording || this.processing) {
      this.cancelUser()
      return
    }
    this.clearHideTimer()
    this.hide()
  }

  onAccountChanged(): void {
    if (
      (this.recording || this.processing) &&
      !this.sessions.active(this.sessionId, this.deps.settings.contextOwner)
    ) {
      this.cancel()
      this.deps.onSessionAborted?.()
    }
  }

  matchesSession(id: number): boolean {
    return id === this.sessionId && this.sessions.active(id, this.deps.settings.contextOwner)
  }

  /**
   * The overlay reached the 60-second cap. End the session cleanly so the WAV
   * is accepted and the trigger latch resets.
   */
  autoStop(): void {
    if (!this.recording) return
    console.log('[dictation] 60-second limit reached')
    this.end()
    this.deps.onSessionAborted?.()
  }

  dispose(): void {
    this.cancel()
    this.contextReader.dispose()
  }

  cancel(): void {
    this.resetInFlight()
    this.sendState({ state: 'silent-cancel' })
    this.hide()
  }

  fail(message: string, copied = false): void {
    this.resetInFlight()
    console.log('[dictation] fail:', message)
    playSound('error', this.deps.settings.soundsEnabled)
    const overlay = this.liveOverlay()
    if (overlay !== null && !overlay.isVisible()) {
      this.positionOverlay(overlay)
      overlay.showInactive()
    }
    this.sendState({ state: 'error', message, copied })
    this.hideAfter(ERROR_VISIBLE_MS)
  }

  private resetInFlight(): void {
    this.sessions.invalidate()
    this.contextReader.release()
    this.contextTarget = null
    this.recording = false
    this.processing = false
    this.clearWatchdog()
    this.clearHideTimer()
    this.releaseEscape()
  }

  private armWatchdog(): void {
    this.clearWatchdog()
    this.watchdog = setTimeout(() => {
      if (this.processing) this.fail('Dictation timed out — the service did not respond')
    }, PIPELINE_TIMEOUT_MS)
  }

  private clearWatchdog(): void {
    if (this.watchdog !== null) {
      clearTimeout(this.watchdog)
      this.watchdog = null
    }
  }

  private clearHideTimer(): void {
    if (this.hideTimer !== null) {
      clearTimeout(this.hideTimer)
      this.hideTimer = null
    }
  }

  private sendState(state: OverlayState): void {
    const overlay = this.liveOverlay()
    if (overlay !== null) {
      this.sendToOverlay(overlay, 'overlay:state', state)
    }
  }

  private releaseEscape(): void {
    if (globalShortcut.isRegistered('Escape')) globalShortcut.unregister('Escape')
  }

  private hide(): void {
    const overlay = this.liveOverlay()
    if (overlay !== null) overlay.hide()
  }

  private hideAfter(ms: number): void {
    this.clearHideTimer()
    this.hideTimer = setTimeout(() => {
      this.hideTimer = null
      if (!this.recording && !this.processing) this.hide()
    }, ms)
  }

  /**
   * The window is created lazily on first use; a send during page load is
   * silently dropped, which would swallow the whole first dictation.
   */
  private sendToOverlay(overlay: BrowserWindow, channel: string, ...args: unknown[]): void {
    if (overlay.isDestroyed()) return
    const webContents = overlay.webContents
    if (webContents.isDestroyed()) return
    if (webContents.isLoading()) {
      webContents.once('did-finish-load', () => {
        if (!overlay.isDestroyed() && !webContents.isDestroyed()) {
          webContents.send(channel, ...args)
        }
      })
    } else {
      webContents.send(channel, ...args)
    }
  }

  /** Called by the overlay once its WAV is ready. */
  async handleCapture(payload: {
    sessionId: number
    wav: ArrayBuffer
    durationMs: number
    containsSpeech: boolean
  }): Promise<void> {
    // Only accept a WAV for a session that actually reached "processing" —
    // a stale capture from a cancelled session must never be transcribed.
    if (
      !this.processing ||
      !this.sessions.claim(payload.sessionId, this.deps.settings.contextOwner)
    ) {
      console.log('[dictation] ignoring capture — no active session')
      return
    }
    const sessionId = payload.sessionId
    const active = (): boolean =>
      this.processing && this.sessions.active(sessionId, this.deps.settings.contextOwner)
    const targetPromise = this.contextTarget
    this.armWatchdog()
    try {
      console.log(
        '[dictation] capture received:',
        payload.durationMs,
        'ms, speech =',
        payload.containsSpeech
      )
      if (!payload.containsSpeech) {
        this.fail('No clear speech detected — try speaking a little longer')
        return
      }
      this.sendState({ state: 'transcribing' })
      const wav = Buffer.from(payload.wav)
      const glossary = await this.deps.getGlossary()
      const token = await this.deps.getToken()
      const target = await targetPromise
      if (!active()) return

      // Password / secure fields are ALWAYS blocked regardless of context level.
      if (target?.reason === 'secure') {
        this.fail('Password and secure fields are excluded from dictation.')
        return
      }
      if (target?.reason === 'accessibility') {
        this.fail('Allow Accessibility access for LiVoice in Settings, then try again.')
        return
      }
      if (!target?.token && this.deps.settings.contextLevel !== 'off') {
        this.fail(
          'This editor does not expose a safe insertion target. Select a supported text box and try again.'
        )
        return
      }

      const context = (): DictationContext | undefined => {
        const captured = restrictContext(target?.context, this.deps.settings.contextLevel)
        return captured ? { ...captured, style: this.deps.settings.contextStyle } : undefined
      }
      this.sendState({ state: 'transcribing', contextLabel: contextLabel(context()) })

      const transcribeOptions: TranscribeOptions = {
        locale: this.deps.settings.locale,
        polish: this.deps.settings.polish
      }
      let outcome: Awaited<ReturnType<DictationDeps['transcribe']>>
      try {
        outcome = await this.deps.transcribe(
          token,
          wav,
          glossary,
          context(),
          target?.sentenceStart ?? true,
          transcribeOptions
        )
      } catch (error) {
        if (error instanceof WorkerError) {
          if (error.code === 'ACCOUNT_SUSPENDED') {
            this.deps.onAccountRestricted?.('suspended', error.message)
            throw error
          }
          if (error.code === 'ACCOUNT_BANNED') {
            this.deps.onAccountRestricted?.('banned', error.message)
            throw error
          }
        }
        // A token the server no longer accepts (expired early, or rotated away)
        // gets one refresh-and-retry, as on the phone, before failing the dictation.
        const rejected =
          error instanceof WorkerError &&
          (error.code === 'TOKEN_EXPIRED' || error.code === 'AUTH_REQUIRED')
        if (!rejected) throw error
        if (!active()) return
        this.deps.invalidateToken(token)
        const refreshed = await this.deps.getToken()
        if (!active()) return
        outcome = await this.deps.transcribe(
          refreshed,
          wav,
          glossary,
          context(),
          target?.sentenceStart ?? true,
          transcribeOptions
        )
      }

      // Never log the transcript itself — stdout is a plaintext voice memo.
      const finalText = outcome.text.trim()
      console.log('[dictation] transcribed:', finalText.length, 'chars')
      if (!active()) return

      if (finalText.length === 0) {
        this.fail('No words were recognized — try speaking again')
        return
      }

      const validateTarget = async (): Promise<boolean> => {
        if (!active()) return false
        if (!target?.token) return this.deps.settings.contextLevel === 'off'
        return (await this.contextReader.validate(target.token)) && active()
      }

      const historyEntry = {
        text: finalText,
        asrModel: outcome.asrModel,
        requestId: outcome.requestId,
        durationMs: payload.durationMs,
        polished: outcome.polished,
        timingsMs: outcome.timingsMs,
        usage: outcome.usage
      }

      try {
        await pasteText(finalText, validateTarget, () => this.contextReader.paste())
      } catch (pasteError) {
        if (!active()) return
        // The transcript already succeeded and consumed quota: preserve it in
        // History and copy to the clipboard so the user's words are never lost.
        this.deps.recordHistory(historyEntry)
        clipboard.writeText(finalText)
        const changed = pasteError instanceof Error && pasteError.message === 'EDITOR_CHANGED'
        this.fail(
          changed
            ? 'The text box changed — copied to your clipboard instead.'
            : 'Could not paste automatically — copied to your clipboard.',
          true
        )
        return
      }

      if (!active()) return
      this.contextReader.release()
      this.contextTarget = null
      console.log('[dictation] pasted')
      this.deps.recordHistory(historyEntry)
      this.processing = false
      this.clearWatchdog()
      playSound('success', this.deps.settings.soundsEnabled)
      this.sendState({
        state: 'success',
        text: finalText.slice(0, 160),
        polished: outcome.polished
      })
      this.hideAfter(SUCCESS_VISIBLE_MS)
    } catch (error) {
      if (!active()) return
      // Error payloads can contain provider or editor details: do not log them.
      console.error('[dictation] request or insertion failed')
      if (
        error instanceof WorkerError &&
        (error.code === 'AUTH_REQUIRED' || error.code === 'HTTP_401')
      ) {
        this.fail('Sign in to use voice dictation')
        return
      }
      if (error instanceof WorkerError) {
        if (error.code === 'ACCOUNT_SUSPENDED') {
          this.deps.onAccountRestricted?.('suspended', error.message)
        } else if (error.code === 'ACCOUNT_BANNED') {
          this.deps.onAccountRestricted?.('banned', error.message)
        }
        this.fail(error.message)
        return
      }
      this.fail('Dictation error — try again')
    }
  }
}
