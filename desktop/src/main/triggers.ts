import { spawn, ChildProcess } from 'child_process'
import { existsSync } from 'fs'
import { join } from 'path'

export type Trigger = 'keyboard' | 'middle-click'

export interface TriggerSettings {
  keyboardShortcutEnabled: boolean
  middleClickEnabled: boolean
  triggerKey: string
}

export interface TriggerEngineOptions {
  /** Called when a modifier trigger turns out to be a keyboard shortcut (e.g. Opt+Left, Cmd+C). */
  onCancel?: () => void
  /** Called when a quick tap transitions recording into latch mode. */
  onLatch?: (hint: string) => void
  /** Returns true while dictation is actively recording or processing. */
  isBusy?: () => boolean
  /** Injectable clock for unit tests. */
  now?: () => number
}

// Raw macOS virtual keycodes (kVK_*) for each selectable trigger key.
export const TRIGGER_KEYS: readonly string[] = [
  'option',
  'option-right',
  'command',
  'command-right',
  'caps-lock',
  'fn'
]

const TRIGGER_KEY_CODES: Record<string, number[]> = {
  option: [58], // left Option — default
  'option-right': [61],
  command: [55], // left Command
  'command-right': [54],
  'caps-lock': [57],
  fn: [63] // Globe key
}

const TRIGGER_KEY_LABELS: Record<string, string> = {
  option: '⌥',
  'option-right': 'right ⌥',
  command: '⌘',
  'command-right': 'right ⌘',
  'caps-lock': '⇪',
  fn: 'fn'
}

/** What ends a recording started by `trigger`, for the overlay's hint line. */
export function releaseHint(trigger: Trigger, triggerKey: string): string {
  if (trigger === 'middle-click') return 'Release the middle button'
  if (triggerKey === 'caps-lock') return `Tap ${TRIGGER_KEY_LABELS[triggerKey]} again`
  return `Release ${TRIGGER_KEY_LABELS[triggerKey] ?? '⌥'}`
}

/** Overlay hint shown after a quick tap switches recording into hands-free latch mode. */
export function latchHint(triggerKey: string): string {
  return `Tap ${TRIGGER_KEY_LABELS[triggerKey] ?? '⌥'} again`
}

// CGEvent mouse button number for the center/middle button.
const MOUSE_MIDDLE = 2

/**
 * Global triggers via tapd, the bundled self-healing event-tap helper:
 * hold the configured trigger key to dictate, quick-tap to latch (start; tap
 * again to stop), and hold middle-click for the same. Runs as a child process
 * so macOS's tap-timeout disablement can never silently kill dictation — the
 * helper re-enables its own tap. Needs only the Accessibility permission;
 * events are not suppressed.
 */
export class TriggerEngine {
  private lock = false
  private activeTrigger: Trigger | null = null
  private keyDownAt = 0
  private keyboardLatched = false
  private triggerDown = false
  private latchStopPress = false
  private ignoredPress = false
  private comboCancelled = false
  private paused = false
  private helper: ChildProcess | null = null
  private activeCodes: number[] = []
  private pendingLine = ''

  constructor(
    private readonly settings: TriggerSettings,
    private readonly onStart: (trigger: Trigger) => void,
    private readonly onStop: (trigger: Trigger) => void,
    private readonly options: TriggerEngineOptions = {}
  ) {
    this.activeCodes = this.codes()
  }

  private now(): number {
    return this.options.now?.() ?? Date.now()
  }

  private codes(): number[] {
    const preset = TRIGGER_KEY_CODES[this.settings.triggerKey] ?? TRIGGER_KEY_CODES.option
    return [...preset]
  }

  private shouldRun(): boolean {
    return (
      !this.paused && (this.settings.keyboardShortcutEnabled || this.settings.middleClickEnabled)
    )
  }

  private helperPath(): string {
    const packaged = join(process.resourcesPath ?? '', 'tapd')
    if (process.resourcesPath && existsSync(packaged)) return packaged
    return join(__dirname, '../../native/tapd')
  }

  isPaused(): boolean {
    return this.paused
  }

  setPaused(paused: boolean): void {
    this.paused = paused
    this.syncRegistration()
  }

  /** Starts tapd if any trigger is active and unpaused; stops it cleanly otherwise. */
  syncRegistration(): void {
    if (!this.shouldRun()) {
      this.unregister()
      return
    }
    this.registerIfMissing()
  }

  register(): void {
    this.activeCodes = this.codes()
    if (!this.shouldRun()) {
      this.unregister()
      return
    }
    if (this.helper !== null) return
    try {
      const child = spawn(this.helperPath(), this.activeCodes.map(String), {
        stdio: ['pipe', 'pipe', 'pipe']
      })
      child.stdout?.setEncoding('utf-8')
      this.pendingLine = ''
      child.stdout?.on('data', (chunk: string) => {
        // A pipe read can end mid-line; buffer incomplete lines across chunks.
        const lines = (this.pendingLine + chunk).split('\n')
        this.pendingLine = lines.pop() ?? ''
        for (const line of lines) this.processLine(line)
      })
      child.stderr?.on('data', (chunk: Buffer) => {
        const text = chunk.toString().trim()
        if (text.length > 0) console.log('[trigger][tapd]', text)
      })
      child.on('exit', (code) => {
        if (this.helper === child) {
          this.helper = null
          console.log('[trigger] tapd exited with code', code)
        }
      })
      child.on('error', (error) => {
        if (this.helper === child) this.helper = null
        console.error('[trigger] tapd failed to run:', error)
      })
      this.helper = child
      console.log('[trigger] tap started (tapd), keycodes', this.activeCodes.join(', '))
    } catch (error) {
      console.error('[trigger] registration failed:', error)
      this.helper = null
    }
  }

  /** Exposed for deterministic unit tests and stdout line dispatch. */
  processLine(line: string): void {
    const trimmed = line.trim()
    if (trimmed.length === 0) return
    const [kind, value, state] = trimmed.split(' ')
    if (kind === 'k') this.onKeyLine(Number(value), state === '1')
    else if (kind === 'm') this.onMouseLine(Number(value), state === '1')
    else if (kind === 'c') this.onComboKey()
  }

  restart(): void {
    this.unregister()
    this.register()
  }

  isRegistered(): boolean {
    return this.helper !== null && this.helper.exitCode === null
  }

  registerIfMissing(): void {
    if (!this.shouldRun()) return
    if (!this.isRegistered()) this.register()
  }

  unregister(): void {
    if (this.helper === null) return
    this.helper.kill()
    this.helper = null
    // A restarted helper cannot know whether the key is still down; assume up so
    // a stale "down" doesn't swallow the next real press.
    this.triggerDown = false
    this.reset()
  }

  /**
   * Forgets the latch after a session ended without a trigger stop (Esc, the
   * overlay's cancel button, the 60 s cap). The physical key state is kept so a
   * still-held key's release is not mistaken for a new quick tap.
   */
  reset(): void {
    this.lock = false
    this.activeTrigger = null
    this.keyboardLatched = false
    this.comboCancelled = false
    this.ignoredPress = false
    this.latchStopPress = this.triggerDown
  }

  private onKeyLine(keycode: number, down: boolean): void {
    if (down) this.onKeyDown(keycode)
    else this.onKeyUp(keycode)
  }

  private onMouseLine(button: number, down: boolean): void {
    if (down) this.onMouseDown(button)
    else this.onMouseUp(button)
  }

  private onComboKey(): void {
    if (!this.triggerDown || this.paused) return
    this.comboCancelled = true
    if (this.lock && this.activeTrigger === 'keyboard' && !this.keyboardLatched) {
      this.lock = false
      this.activeTrigger = null
      this.options.onCancel?.()
    }
  }

  private tryStart(trigger: Trigger): boolean {
    if (this.paused || this.lock || this.options.isBusy?.()) return false
    this.lock = true
    this.activeTrigger = trigger
    this.onStart(trigger)
    return true
  }

  private tryStop(trigger: Trigger): boolean {
    if (!this.lock || this.activeTrigger !== trigger) return false
    this.lock = false
    this.activeTrigger = null
    this.onStop(trigger)
    return true
  }

  private onKeyDown(keycode: number): void {
    if (
      this.paused ||
      !this.settings.keyboardShortcutEnabled ||
      !this.activeCodes.includes(keycode)
    ) {
      return
    }
    if (this.triggerDown) return
    this.triggerDown = true
    this.comboCancelled = false
    this.ignoredPress = false

    if (this.keyboardLatched) {
      this.keyboardLatched = false
      this.latchStopPress = true
      this.tryStop('keyboard')
      return
    }

    this.keyDownAt = this.now()
    if (!this.tryStart('keyboard')) {
      // Dictation was busy (e.g. processing) or another trigger owns the lock —
      // ignore this key press completely so its release cannot enter phantom latch.
      this.ignoredPress = true
    }
  }

  private onKeyUp(keycode: number): void {
    if (!this.settings.keyboardShortcutEnabled || !this.activeCodes.includes(keycode)) {
      return
    }
    if (!this.triggerDown) return
    this.triggerDown = false

    if (this.comboCancelled) {
      this.comboCancelled = false
      return
    }
    if (this.ignoredPress) {
      this.ignoredPress = false
      return
    }
    if (this.keyboardLatched) return
    if (this.latchStopPress) {
      this.latchStopPress = false
      return
    }
    if (!this.lock || this.activeTrigger !== 'keyboard') return

    const held = this.now() - this.keyDownAt
    if (held < 300) {
      // Quick tap: keep recording in latch mode until the next tap.
      this.keyboardLatched = true
      this.options.onLatch?.(latchHint(this.settings.triggerKey))
    } else {
      this.tryStop('keyboard')
    }
  }

  private onMouseDown(button: number): void {
    if (this.paused || !this.settings.middleClickEnabled || button !== MOUSE_MIDDLE) return
    this.tryStart('middle-click')
  }

  private onMouseUp(button: number): void {
    if (!this.settings.middleClickEnabled || button !== MOUSE_MIDDLE) return
    this.tryStop('middle-click')
  }
}
