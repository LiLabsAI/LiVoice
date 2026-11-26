import { app, safeStorage } from 'electron'
import { chmodSync, readFileSync, renameSync, unlinkSync, writeFileSync } from 'fs'
import { join } from 'path'
import { WorkerError } from './worker'

export interface TokenStorage {
  isEncryptionAvailable(): boolean
  encryptString(plainText: string): Buffer
  decryptString(encrypted: Buffer): string
}

/**
 * Refresh-token vault sealed with the macOS Keychain via Electron safeStorage.
 * The access token stays in memory only (15-minute TTL, 30-second skew) and is
 * refreshed single-flight, mirroring the phone's SessionManager.
 */
export class SessionStore {
  private readonly file: string
  private readonly storage: TokenStorage
  private currentAccessToken: string | null = null
  private accessExpiresAt = 0
  private refreshing: Promise<string> | null = null
  private generation = 0
  private cachedHasRefreshToken: boolean | null = null

  constructor(
    private readonly refreshFn: (refreshToken: string) => Promise<{
      accessToken: string
      accessExpiresInSeconds: number
      refreshToken: string
    }>,
    private readonly onInvalid: () => void,
    dir?: string,
    storage?: TokenStorage
  ) {
    const resolvedDir = dir ?? join(app.getPath('userData'))
    this.file = join(resolvedDir, 'refresh-token.bin')
    this.storage = storage ?? safeStorage
  }

  get hasRefreshToken(): boolean {
    if (this.cachedHasRefreshToken !== null) return this.cachedHasRefreshToken
    const available = this.storage.isEncryptionAvailable() && this.peekRefreshToken() !== null
    this.cachedHasRefreshToken = available
    return available
  }

  peekRefreshToken(): string | null {
    try {
      const sealed = readFileSync(this.file)
      if (sealed.length === 0 || !this.storage.isEncryptionAvailable()) return null
      return this.storage.decryptString(sealed)
    } catch {
      return null
    }
  }

  get validAccessToken(): string | null {
    if (this.currentAccessToken && Date.now() < this.accessExpiresAt - 30_000) {
      return this.currentAccessToken
    }
    return null
  }

  /** Single-flight access-token resolution: memory → Keychain refresh → refresh call. */
  async accessToken(): Promise<string> {
    const cached = this.validAccessToken
    if (cached) return cached
    if (this.refreshing === null) {
      const inflight = this.refresh().finally(() => {
        if (this.refreshing === inflight) this.refreshing = null
      })
      this.refreshing = inflight
    }
    return this.refreshing
  }

  storeTokens(accessToken: string, accessExpiresInSeconds: number, refreshToken: string): void {
    this.generation++
    this.refreshing = null
    this.writeTokens(accessToken, accessExpiresInSeconds, refreshToken)
  }

  private writeTokens(
    accessToken: string,
    accessExpiresInSeconds: number,
    refreshToken: string
  ): void {
    if (!this.storage.isEncryptionAvailable()) {
      throw new Error(
        'Keychain encryption is unavailable; refusing to store the refresh token in plaintext.'
      )
    }
    const sealed = this.storage.encryptString(refreshToken)
    const tmp = `${this.file}.tmp.${process.pid}.${Date.now()}`
    try {
      writeFileSync(tmp, sealed, { mode: 0o600 })
      try {
        chmodSync(tmp, 0o600)
      } catch {
        // Best-effort permissions tightening.
      }
      renameSync(tmp, this.file)
    } catch (error) {
      try {
        unlinkSync(tmp)
      } catch {
        // ignore cleanup failure
      }
      throw error
    }
    this.currentAccessToken = accessToken
    this.accessExpiresAt = Date.now() + accessExpiresInSeconds * 1_000
    this.cachedHasRefreshToken = true
  }

  /** Drops an access token the server rejected so the next call refreshes it. */
  invalidateAccessToken(token: string): void {
    if (this.currentAccessToken !== token) return
    this.currentAccessToken = null
    this.accessExpiresAt = 0
  }

  clear(notify = true): void {
    this.generation++
    this.refreshing = null
    this.currentAccessToken = null
    this.accessExpiresAt = 0
    this.cachedHasRefreshToken = false
    try {
      unlinkSync(this.file)
    } catch {
      try {
        writeFileSync(this.file, Buffer.alloc(0), { mode: 0o600 })
      } catch {
        // best effort
      }
    }
    if (notify) this.onInvalid()
  }

  private async refresh(): Promise<string> {
    const gen = this.generation
    const refreshToken = this.peekRefreshToken()
    if (!refreshToken) {
      this.cachedHasRefreshToken = false
      if (gen === this.generation) this.onInvalid()
      // A WorkerError, so callers show "sign in" rather than a generic failure.
      throw new WorkerError('AUTH_REQUIRED', 'Sign in to use voice dictation.', false, 401)
    }
    let rotated: Awaited<ReturnType<typeof this.refreshFn>>
    try {
      rotated = await this.refreshFn(refreshToken)
    } catch (error) {
      // If clear() or a newer sign-in occurred while refresh was in flight,
      // never touch the newer state or double-clear.
      if (gen !== this.generation) {
        throw new WorkerError('AUTH_REQUIRED', 'Sign in to use voice dictation.', false, 401)
      }
      // A revoked, expired, or replayed refresh token never recovers. Keeping it
      // left the app showing "signed in" while every request failed until the
      // user happened to sign out; drop it so the dashboard asks to sign in.
      if (error instanceof WorkerError && error.code === 'AUTH_REQUIRED') this.clear()
      throw error
    }
    // Generation guard: a refresh resolving after clear() MUST NEVER resurrect
    // a signed-out session or overwrite a newer sign-in.
    if (gen !== this.generation) {
      if (this.validAccessToken) return this.validAccessToken
      throw new WorkerError('AUTH_REQUIRED', 'Sign in to use voice dictation.', false, 401)
    }
    this.writeTokens(rotated.accessToken, rotated.accessExpiresInSeconds, rotated.refreshToken)
    return rotated.accessToken
  }
}
