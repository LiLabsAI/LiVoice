import { spawn } from 'child_process'
import { existsSync } from 'fs'

/**
 * Subtle state-change sounds played by the main process via afplay, so the
 * (never-focusable, never-activated) overlay window needs no audio policy
 * workarounds. Volumes are kept low — these are confirmations, not alerts.
 */
export type SoundName = 'start' | 'stop' | 'success' | 'error' | 'cancel'

const SOUND_FILES: Record<SoundName, string> = {
  start: '/System/Library/Sounds/Pop.aiff',
  stop: '/System/Library/Sounds/Tink.aiff',
  success: '/System/Library/Sounds/Glass.aiff',
  error: '/System/Library/Sounds/Sosumi.aiff',
  cancel: '/System/Library/Sounds/Purr.aiff'
}

export function playSound(name: SoundName, enabled = true, volume = 0.45): void {
  if (!enabled) return
  const file = SOUND_FILES[name]
  if (!existsSync(file)) return
  try {
    const child = spawn('afplay', ['-v', String(volume), file], {
      stdio: 'ignore',
      detached: true
    })
    // Prevent unhandled 'error' events (e.g. ENOENT) from crashing Electron main.
    child.on('error', () => {})
    child.unref()
  } catch {
    // Sound is cosmetic; never let it break dictation.
  }
}
