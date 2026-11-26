import type { DictationContext } from './context'

/** Worker REST client (main process, Node fetch). Same API the phone uses. */

// Bounds for every request — a hung socket must fail the session (the
// dictation watchdog recovers) instead of wedging the app until relaunch.
const DEFAULT_TIMEOUT_MS = 15_000
const SYNC_TIMEOUT_MS = 30_000
const TRANSCRIBE_TIMEOUT_MS = 90_000

export const MAX_GLOSSARY_ITEMS = 100
export const MAX_GLOSSARY_CHARS = 4_000
export const MAX_GLOSSARY_TERM_CHARS = 80

export type PolishLevel = 'off' | 'light' | 'strong'

export interface WorkerUser {
  id: string
  email: string
  vaultConfigured: boolean
  vaultKeyVersion: number | null
  role: 'user' | 'admin'
  accountStatus: {
    state: 'active' | 'suspended' | 'banned'
    suspendedUntil: number | null
    publicMessage: string | null
    supportEmail: string
  }
}

export interface WorkerTokens {
  accessToken: string
  accessExpiresIn: number
  refreshToken: string
  refreshExpiresIn: number
  user: WorkerUser
}

export interface WorkerQuota {
  limitAudioSeconds: number
  usedAudioSeconds: number
  reservedAudioSeconds: number
  remainingAudioSeconds: number
  resetAt: number
}

export interface WorkerSession {
  id: string
  deviceName: string
  createdAt: number
  lastSeenAt: number
  current: boolean
}

export interface WorkerUsage {
  pricingVersion: string | null
  inputTokens: number | null
  outputTokens: number | null
  asrNeurons: number | null
  polishNeurons: number | null
  totalNeurons: number | null
  estimatedCostUsd: number | null
}

export interface TranscribeOptions {
  locale?: string
  polish?: PolishLevel
}

export class WorkerError extends Error {
  constructor(
    readonly code: string,
    message: string,
    readonly retryable: boolean,
    readonly status: number,
    readonly conflicts: CloudRecord[] = []
  ) {
    super(message)
  }
}

export type RecordType = 'history' | 'dictionary' | 'analytics'

/** A cloud-sync record as the server returns it: a plain payload, or null when deleted. */
export interface CloudRecord {
  id: string
  type: RecordType
  version: number
  deleted: boolean
  payload: Record<string, unknown> | null
}

export interface CloudSettings {
  historySyncEnabled: boolean
  historyRetentionDays: number | null
  legacyVault: boolean
}

export interface CloudPage {
  nextCursor: number
  hasMore: boolean
  items: CloudRecord[]
  settings?: CloudSettings
}

/** A record to upload; `payload: null` uploads a deletion. */
export interface CloudWrite {
  id: string
  type: RecordType
  baseVersion: number
  payload: Record<string, unknown> | null
}

export interface LegacyPage {
  nextCursor: number
  hasMore: boolean
  items: Array<{
    id: string
    type: RecordType
    version: number
    keyVersion: number
    nonce: string | null
    ciphertext: string | null
    deleted: boolean
  }>
}

export interface RemoteVault {
  wrappedKey: string
  nonce: string
  keyVersion: number
}

/**
 * Enforces the Worker's dual glossary contract: at most 100 entries AND at most
 * 4,000 joined characters (`glossary.join('\n').length <= 4_000`), with each
 * term trimmed to 1..80 characters. Preserves caller rank order greedily.
 */
export function boundGlossary(glossary: readonly string[]): string[] {
  const result: string[] = []
  const seen = new Set<string>()
  let joinedChars = 0

  for (const raw of glossary) {
    if (result.length >= MAX_GLOSSARY_ITEMS) break
    if (typeof raw !== 'string') continue
    const term = raw.trim()
    if (term.length === 0 || term.length > MAX_GLOSSARY_TERM_CHARS) continue
    const key = term.normalize('NFKC').toLowerCase()
    if (seen.has(key)) continue
    const added = result.length === 0 ? term.length : term.length + 1
    if (joinedChars + added > MAX_GLOSSARY_CHARS) {
      if (MAX_GLOSSARY_CHARS - joinedChars < 2) break
      continue
    }
    seen.add(key)
    result.push(term)
    joinedChars += added
  }

  return result
}

function classifyFetchError(error: unknown): WorkerError {
  if (error instanceof Error && (error.name === 'TimeoutError' || error.name === 'AbortError')) {
    return new WorkerError(
      'REQUEST_TIMEOUT',
      'LiVoice took too long to respond. Please try again.',
      true,
      0
    )
  }
  return new WorkerError('NETWORK_UNAVAILABLE', 'Could not reach LiVoice.', true, 0)
}

function finiteNumberOrNull(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

function parseUsage(raw: unknown): WorkerUsage | null {
  if (raw === null || typeof raw !== 'object' || Array.isArray(raw)) return null
  const record = raw as Record<string, unknown>
  return {
    pricingVersion: typeof record.pricingVersion === 'string' ? record.pricingVersion : null,
    inputTokens: finiteNumberOrNull(record.inputTokens),
    outputTokens: finiteNumberOrNull(record.outputTokens),
    asrNeurons: finiteNumberOrNull(record.asrNeurons),
    polishNeurons: finiteNumberOrNull(record.polishNeurons),
    totalNeurons: finiteNumberOrNull(record.totalNeurons),
    estimatedCostUsd: finiteNumberOrNull(record.estimatedCostUsd)
  }
}

export class WorkerClient {
  constructor(private readonly baseUrl: string) {}

  private async json<T>(
    path: string,
    init: RequestInit,
    timeoutMs = DEFAULT_TIMEOUT_MS,
    allowEmpty = false
  ): Promise<T> {
    let response: Response
    try {
      response = await fetch(`${this.baseUrl}${path}`, {
        ...init,
        signal: AbortSignal.timeout(timeoutMs)
      })
    } catch (error) {
      throw classifyFetchError(error)
    }

    if (response.status === 204 && allowEmpty) {
      return undefined as T
    }

    const body = (await response.json().catch(() => null)) as
      (T & { error?: { code: string; message: string; retryable?: boolean } }) | null
    if (!response.ok) {
      const error = body?.error
      throw new WorkerError(
        error?.code ?? `HTTP_${response.status}`,
        error?.message ?? `LiVoice request failed (${response.status}).`,
        error?.retryable ?? response.status >= 500,
        response.status
      )
    }
    if (body === null || typeof body !== 'object') {
      if (allowEmpty) return undefined as T
      throw new WorkerError(
        'INVALID_RESPONSE',
        'LiVoice returned an unexpected response. Please try again.',
        true,
        response.status
      )
    }
    return body as T
  }

  private authHeaders(token: string): Record<string, string> {
    return { Accept: 'application/json', Authorization: `Bearer ${token}` }
  }

  async exchangeAuthorizationCode(body: {
    code: string
    codeVerifier: string
    deviceName: string
  }): Promise<WorkerTokens> {
    return this.json<WorkerTokens>('/v1/auth/token', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify({ grantType: 'authorization_code', ...body })
    })
  }

  async refresh(refreshToken: string, timeoutMs = DEFAULT_TIMEOUT_MS): Promise<WorkerTokens> {
    return this.json<WorkerTokens>(
      '/v1/auth/refresh',
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
        body: JSON.stringify({ refreshToken })
      },
      timeoutMs
    )
  }

  async profile(accessToken: string): Promise<{ user: WorkerUser; quota: WorkerQuota | null }> {
    return this.json('/v1/me', { method: 'GET', headers: this.authHeaders(accessToken) })
  }

  async listSessions(accessToken: string): Promise<WorkerSession[]> {
    const body = await this.json<{ sessions?: WorkerSession[] }>('/v1/me/sessions', {
      method: 'GET',
      headers: this.authHeaders(accessToken)
    })
    return Array.isArray(body.sessions) ? body.sessions : []
  }

  async revokeSession(accessToken: string, sessionId: string): Promise<void> {
    await this.json<void>(
      `/v1/me/sessions/${encodeURIComponent(sessionId)}`,
      {
        method: 'DELETE',
        headers: this.authHeaders(accessToken)
      },
      DEFAULT_TIMEOUT_MS,
      true
    )
  }

  async deleteAccount(
    accessToken: string,
    reauthToken: string,
    codeVerifier: string
  ): Promise<void> {
    await this.json<void>(
      '/v1/me',
      {
        method: 'DELETE',
        headers: { ...this.authHeaders(accessToken), 'Content-Type': 'application/json' },
        body: JSON.stringify({ reauthToken, codeVerifier })
      },
      DEFAULT_TIMEOUT_MS,
      true
    )
  }

  async logout(accessToken: string, timeoutMs = DEFAULT_TIMEOUT_MS): Promise<void> {
    await this.json<void>(
      '/v1/auth/logout',
      {
        method: 'POST',
        headers: { ...this.authHeaders(accessToken), 'Content-Type': 'application/json' },
        body: '{}'
      },
      timeoutMs,
      true
    )
  }

  // ---- Cloud sync (v2): the Worker encrypts records with the account's key ----

  async pullRecords(accessToken: string, cursor: number): Promise<CloudPage> {
    return this.json(
      `/v2/sync?cursor=${cursor}&limit=50`,
      { method: 'GET', headers: this.authHeaders(accessToken) },
      SYNC_TIMEOUT_MS
    )
  }

  async pushRecords(
    accessToken: string,
    writes: CloudWrite[]
  ): Promise<{ applied: Array<{ id: string; type: string; version: number }> }> {
    let response: Response
    try {
      response = await fetch(`${this.baseUrl}/v2/sync/batch`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', ...this.authHeaders(accessToken) },
        body: JSON.stringify({
          items: writes.map((write) => ({
            id: write.id,
            type: write.type,
            baseVersion: write.baseVersion,
            deleted: write.payload === null,
            ...(write.payload === null ? {} : { payload: write.payload })
          }))
        }),
        signal: AbortSignal.timeout(SYNC_TIMEOUT_MS)
      })
    } catch (error) {
      throw classifyFetchError(error)
    }
    const body = (await response.json().catch(() => null)) as
      | (Record<string, unknown> & {
          error?: { code: string; message: string; retryable?: boolean }
          conflicts?: CloudRecord[]
        })
      | null
    if (!response.ok) {
      const error = body?.error
      throw new WorkerError(
        error?.code ?? `HTTP_${response.status}`,
        error?.message ?? `Sync failed (${response.status}).`,
        error?.retryable ?? response.status >= 500,
        response.status,
        response.status === 409 ? (body?.conflicts ?? []) : []
      )
    }
    if (body === null || typeof body !== 'object' || !Array.isArray(body.applied)) {
      throw new WorkerError(
        'INVALID_RESPONSE',
        'LiVoice returned an invalid sync response. Please try again.',
        true,
        response.status
      )
    }
    return body as { applied: Array<{ id: string; type: string; version: number }> }
  }

  async syncSettings(accessToken: string): Promise<CloudSettings> {
    const body = await this.json<{ settings: CloudSettings }>(
      '/v2/sync/settings',
      { method: 'GET', headers: this.authHeaders(accessToken) },
      SYNC_TIMEOUT_MS
    )
    return body.settings
  }

  async updateSyncSettings(
    accessToken: string,
    changes: { historySyncEnabled?: boolean; historyRetentionDays?: number | null }
  ): Promise<CloudSettings> {
    const body = await this.json<{ settings: CloudSettings }>(
      '/v2/sync/settings',
      {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json', ...this.authHeaders(accessToken) },
        body: JSON.stringify(changes)
      },
      SYNC_TIMEOUT_MS
    )
    return body.settings
  }

  /** Tells the server this account's old vault has been imported, so it can retire it. */
  async completeLegacyMigration(accessToken: string): Promise<void> {
    await this.json(
      '/v2/sync/migration',
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', ...this.authHeaders(accessToken) },
        body: '{}'
      },
      SYNC_TIMEOUT_MS
    )
  }

  // ---- Old recovery-key vault (v1): read once to import its records ----

  async getVault(accessToken: string): Promise<RemoteVault | null> {
    const body = await this.json<{ vault: RemoteVault | null }>(
      '/v1/sync/vault',
      { method: 'GET', headers: this.authHeaders(accessToken) },
      SYNC_TIMEOUT_MS
    )
    return body.vault
  }

  async pullLegacy(accessToken: string, cursor: number): Promise<LegacyPage> {
    return this.json(
      `/v1/sync?cursor=${cursor}&limit=40`,
      { method: 'GET', headers: this.authHeaders(accessToken) },
      SYNC_TIMEOUT_MS
    )
  }

  async transcribe(
    accessToken: string,
    wav: Buffer,
    glossary: string[],
    context?: DictationContext,
    sentenceStart = true,
    options?: TranscribeOptions
  ): Promise<{
    text: string
    rawText: string
    polished: boolean
    asrModel: string
    requestId: string
    timingsMs: { asr: number; polish: number; total: number }
    usage: WorkerUsage | null
  }> {
    const form = new FormData()
    form.set('audio', new Blob([new Uint8Array(wav)], { type: 'audio/wav' }), 'voice.wav')
    form.set(
      'options',
      JSON.stringify({
        locale: options?.locale ?? 'en-IN',
        polish: options?.polish ?? 'light',
        sentenceStart,
        context,
        commands: ['new_line', 'new_paragraph'],
        glossary: boundGlossary(glossary)
      })
    )
    let response: Response
    try {
      response = await fetch(`${this.baseUrl}/v1/transcriptions`, {
        method: 'POST',
        headers: { Accept: 'application/json', Authorization: `Bearer ${accessToken}` },
        body: form,
        signal: AbortSignal.timeout(TRANSCRIBE_TIMEOUT_MS)
      })
    } catch (error) {
      throw classifyFetchError(error)
    }
    const body = (await response.json().catch(() => null)) as
      | (Record<string, unknown> & {
          error?: { code: string; message: string; retryable?: boolean }
        })
      | null
    if (!response.ok) {
      const error = body?.error
      throw new WorkerError(
        error?.code ?? `HTTP_${response.status}`,
        error?.message ?? `Transcription failed (${response.status}).`,
        error?.retryable ?? response.status >= 500,
        response.status
      )
    }
    if (body === null || typeof body !== 'object') {
      throw new WorkerError(
        'INVALID_RESPONSE',
        'LiVoice returned an invalid transcription response. Please try again.',
        true,
        response.status
      )
    }
    const timings = (body.timingsMs ?? {}) as Record<string, unknown>
    const millis = (value: unknown): number =>
      typeof value === 'number' && Number.isFinite(value) && value >= 0 ? Math.round(value) : 0
    return {
      text: String(body.text ?? '').trim(),
      rawText: String(body.rawText ?? '').trim(),
      polished: Boolean(body.polished),
      asrModel: String(body.asrModel ?? ''),
      requestId: String(body.requestId ?? ''),
      timingsMs: {
        asr: millis(timings.asr),
        polish: millis(timings.polish),
        total: millis(timings.total)
      },
      usage: parseUsage(body.usage)
    }
  }
}
