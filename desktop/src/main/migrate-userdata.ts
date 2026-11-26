import { chmodSync, constants, copyFileSync, existsSync, mkdirSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'

const MIGRATABLE_FILES = [
  'settings.json',
  'wovoice-desktop.db',
  'wovoice-desktop.db-wal',
  'wovoice-desktop.db-shm',
  'refresh-token.bin',
  'vault-key.bin',
  'recovery-secret.bin'
] as const

const PRIVATE_FILES = new Set<string>([
  'settings.json',
  'refresh-token.bin',
  'vault-key.bin',
  'recovery-secret.bin'
])

export interface MigrationResult {
  migrated: string[]
  skipped: string[]
}

/**
 * One-time, idempotent upgrade migration from ~/Library/Application Support/WoVoice
 * into the rebranded LiVoice userData directory. Must run before SettingsStore,
 * SessionStore, or openDatabase initialize. Never overwrites existing LiVoice data.
 */
export function migrateLegacyUserData(
  currentUserDataDir: string,
  legacyUserDataDir = join(dirname(currentUserDataDir), 'WoVoice')
): MigrationResult {
  const result: MigrationResult = { migrated: [], skipped: [] }

  const resolvedCurrent = resolve(currentUserDataDir)
  const resolvedLegacy = resolve(legacyUserDataDir)
  if (resolvedCurrent === resolvedLegacy || !existsSync(resolvedLegacy)) {
    return result
  }

  // If the new userData directory already has core state, never clobber or merge
  // stale files from the legacy directory into it.
  if (
    existsSync(join(resolvedCurrent, 'settings.json')) ||
    existsSync(join(resolvedCurrent, 'wovoice-desktop.db'))
  ) {
    return result
  }

  const candidates = MIGRATABLE_FILES.filter((name) => existsSync(join(resolvedLegacy, name)))
  if (candidates.length === 0) {
    return result
  }

  mkdirSync(resolvedCurrent, { recursive: true })

  for (const name of candidates) {
    const src = join(resolvedLegacy, name)
    const dst = join(resolvedCurrent, name)
    if (existsSync(dst)) {
      result.skipped.push(name)
      continue
    }
    try {
      copyFileSync(src, dst, constants.COPYFILE_EXCL)
      if (PRIVATE_FILES.has(name)) {
        try {
          chmodSync(dst, 0o600)
        } catch {
          // Best-effort permissions tightening.
        }
      }
      result.migrated.push(name)
    } catch {
      result.skipped.push(name)
    }
  }

  return result
}
