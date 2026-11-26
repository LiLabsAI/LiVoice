import { app } from 'electron'
import { CONTEXT_STYLES, type ContextLevel, type ContextStyle } from '../../../worker/src/context'
import { chmodSync, mkdirSync, readFileSync, renameSync, unlinkSync, writeFileSync } from 'fs'
import { dirname, join } from 'path'

export const DEFAULT_WORKER_URL = 'https://wovoice.aliahad.com'
export const DEFAULT_SUPPORT_EMAIL = 'support@aliahad.com'
export const DEFAULT_LOCALE = 'en-IN'
export const DEFAULT_POLISH = 'light' as const

export type PolishSetting = 'off' | 'light' | 'strong'
export type AccountRole = 'user' | 'admin'
export type AccountState = 'active' | 'suspended' | 'banned'

export interface AccountStatusSnapshot {
  role: AccountRole
  state: AccountState
  publicMessage: string | null
  suspendedUntil: number | null
  supportEmail: string
}

const LOCALE_PATTERN = /^[a-z]{2}(-[A-Z]{2})?$/u
const POLISH_LEVELS: readonly PolishSetting[] = ['off', 'light', 'strong']

export function sanitizeWorkerUrl(value: unknown): string {
  if (typeof value !== 'string') return DEFAULT_WORKER_URL
  const trimmed = value.trim()
  if (!trimmed) return DEFAULT_WORKER_URL
  try {
    const parsed = new URL(trimmed)
    if (parsed.protocol !== 'https:' || !parsed.hostname) return DEFAULT_WORKER_URL
    return parsed.origin + parsed.pathname.replace(/\/+$/u, '')
  } catch {
    return DEFAULT_WORKER_URL
  }
}

export function isValidLocale(value: unknown): value is string {
  return typeof value === 'string' && (value === 'auto' || LOCALE_PATTERN.test(value))
}

export function isValidPolish(value: unknown): value is PolishSetting {
  return typeof value === 'string' && POLISH_LEVELS.includes(value as PolishSetting)
}

/** JSON-backed app settings under ~/Library/Application Support/LiVoice. */
export class SettingsStore {
  private readonly file: string
  private readonly data: Record<string, unknown>

  constructor(dir = join(app.getPath('userData'))) {
    mkdirSync(dir, { recursive: true })
    this.file = join(dir, 'settings.json')
    this.data = this.read()
  }

  private read(): Record<string, unknown> {
    try {
      const parsed = JSON.parse(readFileSync(this.file, 'utf-8')) as unknown
      return parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed)
        ? (parsed as Record<string, unknown>)
        : {}
    } catch {
      return {}
    }
  }

  private persist(): void {
    const dir = dirname(this.file)
    mkdirSync(dir, { recursive: true })
    const tmp = `${this.file}.tmp.${process.pid}.${Date.now()}.${Math.random().toString(36).slice(2, 8)}`
    const payload = JSON.stringify(this.data, null, 2)
    try {
      writeFileSync(tmp, payload, { mode: 0o600 })
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
  }

  get<T>(key: string, fallback: T): T {
    const value = this.data[key]
    return (value === undefined ? fallback : value) as T
  }

  set<T>(key: string, value: T): void {
    if (value === undefined) delete this.data[key]
    else this.data[key] = value
    this.persist()
  }

  get workerUrl(): string {
    return sanitizeWorkerUrl(this.data['workerUrl'])
  }

  // Cloud sync keeps its own cursor: the old vault's 'syncCursor' counts a different feed.
  get syncCursor(): number {
    const value = this.get<number>('cloudSyncCursor', 0)
    return typeof value === 'number' && Number.isFinite(value) && value >= 0 ? value : 0
  }

  set syncCursor(value: number) {
    this.set('cloudSyncCursor', value)
  }

  /** Account whose one-time move from the old recovery-key vault is done on this Mac. */
  get cloudSyncMigratedAccount(): string | null {
    return this.get<string | null>('cloudSyncMigratedAccount', null)
  }

  set cloudSyncMigratedAccount(value: string | null) {
    this.set('cloudSyncMigratedAccount', value ?? undefined)
  }

  get historySyncEnabled(): boolean {
    return this.get<boolean>('historySyncEnabled', true)
  }

  set historySyncEnabled(value: boolean) {
    this.set('historySyncEnabled', value)
  }

  get historyRetentionDays(): number | null {
    return this.get<number | null>('historyRetentionDays', null)
  }

  set historyRetentionDays(value: number | null) {
    this.set('historyRetentionDays', value ?? undefined)
  }

  get lastSyncAt(): number {
    return this.get<number>('lastSyncAt', 0)
  }

  set lastSyncAt(value: number) {
    this.set('lastSyncAt', value)
  }

  get syncWarning(): string | null {
    const value = this.data['syncWarning']
    return typeof value === 'string' && value.length > 0 ? value : null
  }

  set syncWarning(value: string | null) {
    this.set('syncWarning', value ?? undefined)
  }

  get keyboardShortcutEnabled(): boolean {
    return this.get<boolean>('keyboardShortcutEnabled', true)
  }

  set keyboardShortcutEnabled(value: boolean) {
    this.set('keyboardShortcutEnabled', value)
  }

  get middleClickEnabled(): boolean {
    return this.get<boolean>('middleClickEnabled', false)
  }

  set middleClickEnabled(value: boolean) {
    this.set('middleClickEnabled', value)
  }

  get locale(): string {
    const value = this.data['locale']
    return isValidLocale(value) ? value : DEFAULT_LOCALE
  }

  set locale(value: string) {
    this.set('locale', isValidLocale(value) ? value : DEFAULT_LOCALE)
  }

  get polish(): PolishSetting {
    const value = this.data['polish']
    return isValidPolish(value) ? value : DEFAULT_POLISH
  }

  set polish(value: PolishSetting) {
    this.set('polish', isValidPolish(value) ? value : DEFAULT_POLISH)
  }

  get soundsEnabled(): boolean {
    return this.get<boolean>('soundsEnabled', true)
  }

  set soundsEnabled(value: boolean) {
    this.set('soundsEnabled', value)
  }

  get startHidden(): boolean {
    return this.get<boolean>('startHidden', false)
  }

  set startHidden(value: boolean) {
    this.set('startHidden', value)
  }

  get micDeviceId(): string {
    const value = this.data['micDeviceId']
    return typeof value === 'string' ? value : ''
  }

  set micDeviceId(value: string) {
    this.set('micDeviceId', value)
  }

  get accountRole(): AccountRole {
    return this.data['accountRole'] === 'admin' ? 'admin' : 'user'
  }

  set accountRole(value: AccountRole) {
    this.set('accountRole', value)
  }

  get accountState(): AccountState {
    const value = this.data['accountState']
    return value === 'suspended' || value === 'banned' ? value : 'active'
  }

  set accountState(value: AccountState) {
    this.set('accountState', value)
  }

  get accountPublicMessage(): string | null {
    const value = this.data['accountPublicMessage']
    return typeof value === 'string' && value.trim().length > 0 ? value : null
  }

  set accountPublicMessage(value: string | null) {
    this.set('accountPublicMessage', value ?? undefined)
  }

  get accountSuspendedUntil(): number | null {
    const value = this.data['accountSuspendedUntil']
    return typeof value === 'number' && Number.isFinite(value) && value > 0 ? value : null
  }

  set accountSuspendedUntil(value: number | null) {
    this.set('accountSuspendedUntil', value ?? undefined)
  }

  get accountSupportEmail(): string {
    const value = this.data['accountSupportEmail']
    return typeof value === 'string' && value.trim().length > 0
      ? value.trim()
      : DEFAULT_SUPPORT_EMAIL
  }

  set accountSupportEmail(value: string) {
    this.set('accountSupportEmail', value.trim() || DEFAULT_SUPPORT_EMAIL)
  }

  get accountStatus(): AccountStatusSnapshot {
    return {
      role: this.accountRole,
      state: this.accountState,
      publicMessage: this.accountPublicMessage,
      suspendedUntil: this.accountSuspendedUntil,
      supportEmail: this.accountSupportEmail
    }
  }

  isAccountActive(): boolean {
    return this.accountState === 'active'
  }

  restrictionMessage(): string {
    if (this.accountPublicMessage) return this.accountPublicMessage
    if (this.accountState === 'suspended') {
      if (this.accountSuspendedUntil !== null) {
        const until = new Date(this.accountSuspendedUntil).toLocaleString([], {
          month: 'short',
          day: 'numeric',
          hour: '2-digit',
          minute: '2-digit'
        })
        return `Your account is suspended until ${until}. Contact ${this.accountSupportEmail}.`
      }
      return `Your account is currently suspended. Contact ${this.accountSupportEmail}.`
    }
    if (this.accountState === 'banned') {
      return `Your account has been restricted. Contact ${this.accountSupportEmail}.`
    }
    return ''
  }

  applyAccountUser(user: {
    id: string
    email: string
    role?: AccountRole
    accountStatus?: {
      state: AccountState
      suspendedUntil: number | null
      publicMessage: string | null
      supportEmail: string
    }
  }): void {
    this.data['accountId'] = user.id
    this.data['accountEmail'] = user.email
    this.data['accountRole'] = user.role === 'admin' ? 'admin' : 'user'
    const status = user.accountStatus
    if (status) {
      this.data['accountState'] =
        status.state === 'suspended' || status.state === 'banned' ? status.state : 'active'
      if (status.publicMessage && status.publicMessage.trim().length > 0) {
        this.data['accountPublicMessage'] = status.publicMessage
      } else {
        delete this.data['accountPublicMessage']
      }
      if (typeof status.suspendedUntil === 'number' && Number.isFinite(status.suspendedUntil)) {
        this.data['accountSuspendedUntil'] = status.suspendedUntil
      } else {
        delete this.data['accountSuspendedUntil']
      }
      this.data['accountSupportEmail'] = status.supportEmail?.trim() || DEFAULT_SUPPORT_EMAIL
    }
    this.persist()
  }

  setAccountRestriction(state: 'suspended' | 'banned', message?: string | null): void {
    this.data['accountState'] = state
    if (message && message.trim().length > 0) {
      this.data['accountPublicMessage'] = message.trim()
    }
    this.persist()
  }

  get contextLevel(): ContextLevel {
    const value = this.get<string>(`contextLevel_${this.get('accountId', 'local')}`, 'off')
    return value === 'app' || value === 'nearby' ? value : 'off'
  }
  set contextLevel(value: ContextLevel) {
    this.set(`contextLevel_${this.get('accountId', 'local')}`, value)
  }
  get contextStyle(): ContextStyle {
    const value = this.get<ContextStyle>(`contextStyle_${this.get('accountId', 'local')}`, 'auto')
    return CONTEXT_STYLES.includes(value) ? value : 'auto'
  }
  set contextStyle(value: ContextStyle) {
    this.set(`contextStyle_${this.get('accountId', 'local')}`, value)
  }
  get contextOwner(): string {
    return this.get('accountId', 'local')
  }

  clearAccount(): void {
    // cloudSyncMigratedAccount survives sign-out: that account's vault import is done here.
    for (const key of [
      'accountId',
      'accountEmail',
      'accountRole',
      'accountState',
      'accountPublicMessage',
      'accountSuspendedUntil',
      'accountSupportEmail',
      'syncCursor',
      'vaultRecoveryAcknowledged',
      'cloudSyncCursor',
      'historySyncEnabled',
      'historyRetentionDays',
      'lastSyncAt',
      'syncWarning'
    ]) {
      delete this.data[key]
    }
    this.persist()
  }
}
