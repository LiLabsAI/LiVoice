import { safeStorage } from 'electron'
import { readFileSync, unlinkSync } from 'fs'
import { join } from 'path'
import {
  WorkerError,
  type CloudRecord,
  type CloudSettings,
  type CloudWrite,
  type RecordType,
  type WorkerClient
} from './worker'
import { decryptRecord, unwrapVaultKey } from './vault-crypto'
import type { AnalyticsEvent, DictationRecord, WoVoiceDb } from './db'

const SCHEMA_VERSION = 1
const MAX_BATCH = 100
// Up to 2,000 uploads per sync; anything beyond waits for the next one.
const MAX_PUSH_ROUNDS = 20
const MAX_PULL_PAGES = 100
const MAX_CONFLICT_ROUNDS = 3
const DAY_MS = 86_400_000

export type SyncOutcome =
  | { kind: 'ok'; uploaded: number; downloaded: number; warning?: string }
  | { kind: 'error'; message: string }

/** Where the sync engine keeps its per-account state (settings.json in the app). */
export interface SyncState {
  workerUrl: string
  getAccountId: () => string | null
  getCursor: () => number
  setCursor: (value: number) => void
  getMigratedAccount: () => string | null
  setMigratedAccount: (accountId: string) => void
  getHistorySyncEnabled: () => boolean
  setHistorySyncEnabled: (value: boolean) => void
  getRetentionDays: () => number | null
  setRetentionDays: (value: number | null) => void
  setLastSyncAt: (value: number) => void
  setSyncWarning?: (warning: string | null) => void
}

/** Keychain-sealed files left by the old recovery-key vault (injectable for tests). */
export interface SecretFiles {
  load(name: string): Buffer | null
  remove(name: string): void
}

type SyncWorker = Pick<
  WorkerClient,
  | 'pullRecords'
  | 'pushRecords'
  | 'updateSyncSettings'
  | 'completeLegacyMigration'
  | 'getVault'
  | 'pullLegacy'
>

interface Batch {
  writes: CloudWrite[]
  tombstones: Set<string>
  revisions: Map<string, number>
}

/**
 * Cloud sync, mirroring the phone's SyncCoordinator: history, dictionary, and
 * analytics follow the signed-in account. Records travel as plain JSON over TLS
 * and the Worker encrypts them with the account's key, so there is no vault or
 * recovery key. Conflicts resolve server-wins, except that a delete or an undo
 * made on this Mac is re-based onto the server version so the user's action still lands.
 */
export class SyncService {
  private activeOp: Promise<unknown> = Promise.resolve()
  private inflightSync: Promise<SyncOutcome> | null = null
  private readonly secrets: SecretFiles

  constructor(
    private readonly deps: {
      worker: SyncWorker
      settings: SyncState
      db: WoVoiceDb
      getToken: () => Promise<string>
      invalidateToken?: (token: string) => void
      onAccountRestricted?: (state: 'suspended' | 'banned', message: string) => void
      onSyncCompleted?: (outcome: SyncOutcome) => void
      vaultDir: string
      secrets?: SecretFiles
      now?: () => number
    }
  ) {
    this.secrets = deps.secrets ?? keychainSecretFiles(deps.vaultDir)
  }

  /** Single-flight: concurrent syncNow() callers share the sync already running. */
  async syncNow(): Promise<SyncOutcome> {
    if (this.inflightSync !== null) return this.inflightSync
    const prev = this.activeOp
    const promise = prev
      .catch(() => undefined)
      .then(() => this.run())
      .finally(() => {
        if (this.inflightSync === promise) this.inflightSync = null
      })
    this.inflightSync = promise
    this.activeOp = promise
    return promise
  }

  /** Changes the account's history choices; serialized cleanly against syncNow(). */
  async updateSettings(changes: {
    historySyncEnabled?: boolean
    historyRetentionDays?: number | null
  }): Promise<CloudSettings> {
    const prev = this.activeOp
    let release!: () => void
    const gate = new Promise<void>((resolve) => {
      release = resolve
    })
    this.activeOp = gate
    try {
      await prev.catch(() => undefined)
      const accountId = this.accountId()
      const settings = await this.call((token) =>
        this.deps.worker.updateSyncSettings(token, changes)
      )
      this.assertAccount(accountId)
      this.applySettings(settings)
      return settings
    } finally {
      release()
    }
  }

  private now(): number {
    return this.deps.now?.() ?? Date.now()
  }

  private accountId(): string {
    const id = this.deps.settings.getAccountId()
    if (id === null) throw new WorkerError('AUTH_REQUIRED', 'Sign in first.', false, 401)
    return id
  }

  private assertAccount(expectedAccountId: string): void {
    if (this.deps.settings.getAccountId() !== expectedAccountId) {
      throw new WorkerError('AUTH_REQUIRED', 'Signed out during sync.', false, 401)
    }
  }

  /** Runs a Worker call; an expired or rejected access token is refreshed once. */
  private async call<T>(request: (token: string) => Promise<T>): Promise<T> {
    const token = await this.deps.getToken()
    try {
      return await request(token)
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
        if (error.code === 'TOKEN_EXPIRED' || error.code === 'AUTH_REQUIRED') {
          this.deps.invalidateToken?.(token)
          try {
            return await request(await this.deps.getToken())
          } catch (retryError) {
            if (retryError instanceof WorkerError) {
              if (retryError.code === 'ACCOUNT_SUSPENDED') {
                this.deps.onAccountRestricted?.('suspended', retryError.message)
              } else if (retryError.code === 'ACCOUNT_BANNED') {
                this.deps.onAccountRestricted?.('banned', retryError.message)
              }
            }
            throw retryError
          }
        }
      }
      throw error
    }
  }

  private async run(): Promise<SyncOutcome> {
    let outcome: SyncOutcome
    try {
      const accountId = this.accountId()
      // Recover any rows stuck in 'queued' from an earlier interrupted run.
      this.deps.db.returnQueuedToLocal(accountId)
      this.applyCachedRetention()

      if (this.deps.settings.getMigratedAccount() !== accountId) {
        await this.importLegacyVault(accountId)
      }
      const downloaded = await this.pull(accountId)
      const pushResult = await this.push(accountId)
      this.assertAccount(accountId)
      this.applyCachedRetention()
      this.deps.settings.setLastSyncAt(this.now())
      this.deps.settings.setSyncWarning?.(pushResult.warning ?? null)
      outcome =
        pushResult.warning === undefined
          ? { kind: 'ok', uploaded: pushResult.uploaded, downloaded }
          : { kind: 'ok', uploaded: pushResult.uploaded, downloaded, warning: pushResult.warning }
    } catch (error) {
      outcome = { kind: 'error', message: error instanceof Error ? error.message : 'Sync failed' }
    }
    this.deps.onSyncCompleted?.(outcome)
    return outcome
  }

  // ---- Downloads ----

  private async pull(accountId: string): Promise<number> {
    let downloaded = 0
    for (let pageCount = 0; pageCount < MAX_PULL_PAGES; pageCount++) {
      this.assertAccount(accountId)
      const cursor = this.deps.settings.getCursor()
      const page = await this.call((token) => this.deps.worker.pullRecords(token, cursor))
      // Account may have signed out or switched while pullRecords was in flight.
      this.assertAccount(accountId)
      // Settings arrive before any upload, so a history-off choice made on
      // another device is honoured before this Mac sends history.
      if (page.settings) this.applySettings(page.settings)
      for (const record of page.items) {
        // One record that cannot be applied must not stall the feed.
        try {
          if (this.applyRecord(record, false)) downloaded++
        } catch (error) {
          console.error('[sync] skipped record', record.type, record.id, error)
        }
      }
      this.applyCachedRetention()
      const nextCursor =
        typeof page.nextCursor === 'number' && Number.isFinite(page.nextCursor)
          ? page.nextCursor
          : cursor
      if (nextCursor > cursor) {
        this.deps.settings.setCursor(nextCursor)
      }
      // Guard against non-monotonic cursors / infinite pagination loops.
      if (!page.hasMore || nextCursor <= cursor) return downloaded
    }
    return downloaded
  }

  private applyRecord(record: CloudRecord, force: boolean): boolean {
    if (record.type !== 'history' && record.type !== 'dictionary' && record.type !== 'analytics') {
      return false
    }
    const db = this.deps.db
    // A delete made here and not yet uploaded outranks incoming content.
    if (!force && !record.deleted && db.hasPendingTombstone(record.type, record.id)) return false
    // This Mac's own writes echo back on the next pull: the version it already
    // holds is not news. A lower remote version is (the account was reset).
    const known = db.knownSyncVersion(record.type, record.id)
    if (!force && known !== null && known === record.version) return false

    const localState = db.syncStateOf(record.type, record.id)
    const isDirty = localState === 'local' || localState === 'queued'

    if (record.deleted) {
      if (known === null) return false
      // Protect dirty local edits/undos against silent pull deletion; let push reconcile.
      if (!force && isDirty && known > 0) return false
      db.applyTombstone(record.type, record.id)
      return true
    }

    if (record.payload === null) return false

    if (record.type === 'history') {
      if (!force && !this.deps.settings.getHistorySyncEnabled()) return false
      const parsed = historyFromJson(record.id, record.payload)
      if (!force && isDirty) {
        // Allow dropped-response echo (syncVersion === 0 with identical text) to converge;
        // otherwise protect local edits/undos until push reconciles.
        const existing = db.historyByRequestId(record.id)
        if (known !== 0 || existing === null || existing.finalText !== parsed.finalText) {
          return false
        }
      }
      db.upsertRemoteHistory(parsed, record.version)
      return true
    }

    if (record.type === 'dictionary') {
      const parsed = dictionaryFromJson(record.payload)
      if (!force && isDirty) {
        const existing = db.dictionaryBySyncId(record.id)
        if (
          known !== 0 ||
          existing === null ||
          existing.term !== parsed.term ||
          existing.status !== parsed.status
        ) {
          return false
        }
      }
      db.upsertRemoteDictionary(parsed, record.id, record.version)
      return true
    }

    // analytics
    const parsed = analyticsFromJson(record.id, record.payload)
    if (!force && isDirty) {
      const existing = db.analyticsBySyncId(record.id)
      if (known !== 0 || existing === null || existing.corrected !== parsed.corrected) {
        return false
      }
    }
    db.upsertRemoteAnalytics(parsed, record.version)
    return true
  }

  // ---- Uploads ----

  private async push(accountId: string): Promise<{ uploaded: number; warning?: string }> {
    let uploaded = 0
    let conflictRounds = 0
    let skipNewTerms = false
    let warning: string | undefined
    let hitRoundCap = false

    try {
      for (let round = 0; round < MAX_PUSH_ROUNDS; round++) {
        this.assertAccount(accountId)
        const batch = this.stageBatch(skipNewTerms)
        if (batch.writes.length === 0) break
        try {
          const result = await this.call((token) =>
            this.deps.worker.pushRecords(token, batch.writes)
          )
          this.assertAccount(accountId)
          for (const applied of result.applied) this.settle(batch, applied)
          uploaded += result.applied.length
        } catch (error) {
          this.deps.db.returnQueuedToLocal(accountId)
          if (!(error instanceof WorkerError)) throw error
          if (error.code === 'SYNC_CONFLICT' && error.conflicts.length > 0) {
            if (++conflictRounds > MAX_CONFLICT_ROUNDS) {
              throw new Error('Changes from another device were merged. Sync again to finish.')
            }
            for (const conflict of error.conflicts) this.reconcile(conflict, batch)
          } else if (error.code === 'STORAGE_LIMIT_REACHED') {
            // The dictionary is full: keep syncing everything else.
            skipNewTerms = true
            warning = error.message
          } else if (error.code === 'HISTORY_SYNC_DISABLED') {
            this.applyHistorySyncDisabled()
          } else {
            throw error
          }
        }
        if (round === MAX_PUSH_ROUNDS - 1) {
          hitRoundCap = this.hasRemainingUploads(skipNewTerms)
        }
      }
    } finally {
      this.deps.db.returnQueuedToLocal(accountId)
    }

    if (hitRoundCap && warning === undefined) {
      warning = 'More items are waiting to sync. Sync again to finish.'
    }
    return { uploaded, warning }
  }

  private hasRemainingUploads(skipNewTerms: boolean): boolean {
    const db = this.deps.db
    const historyOn = this.deps.settings.getHistorySyncEnabled()
    if (db.tombstones(this.now()).length > 0) return true
    if (historyOn && db.historyNeedingSync().length > 0) return true
    if (db.analyticsNeedingSync().length > 0) return true
    const dict = db.dictionaryNeedingSync()
    return dict.some((entry) => !(skipNewTerms && entry.syncVersion === 0))
  }

  private stageBatch(skipNewTerms: boolean): Batch {
    const db = this.deps.db
    const historyOn = this.deps.settings.getHistorySyncEnabled()
    const writes: CloudWrite[] = []
    const keys = new Set<string>()
    const tombstones = new Set<string>()
    const revisions = new Map<string, number>()
    const queued: Array<{ type: RecordType; id: string; revision: number }> = []

    const add = (write: CloudWrite, revision?: number): void => {
      // The server refuses a batch that names the same record twice.
      const key = `${write.type}:${write.id}`
      if (keys.has(key) || writes.length >= MAX_BATCH) return
      keys.add(key)
      writes.push(write)
      if (write.payload === null) {
        tombstones.add(key)
      } else if (revision !== undefined) {
        revisions.set(key, revision)
        queued.push({ type: write.type, id: write.id, revision })
      }
    }

    for (const tombstone of db.tombstones(this.now())) {
      const type = tombstone.recordType as RecordType
      if (type === 'history' && !historyOn) {
        // The account keeps no history in the cloud; there is nothing to delete.
        db.clearTombstone(type, tombstone.recordId)
        continue
      }
      add({ id: tombstone.recordId, type, baseVersion: tombstone.baseVersion, payload: null })
    }
    if (historyOn) {
      for (const record of db.historyNeedingSync()) {
        // baseVersion: 0 for a new dictation; the server version after an undo.
        add(
          {
            id: record.requestId,
            type: 'history',
            baseVersion: record.syncVersion,
            payload: historyJson(record)
          },
          record.localRevision
        )
      }
    }
    for (const entry of db.dictionaryNeedingSync()) {
      if (skipNewTerms && entry.syncVersion === 0) continue
      add(
        {
          id: entry.syncId,
          type: 'dictionary',
          baseVersion: entry.syncVersion,
          payload: {
            schemaVersion: SCHEMA_VERSION,
            term: entry.term,
            normalizedTerm: entry.normalizedTerm,
            status: entry.status,
            source: entry.source,
            createdAtMs: entry.createdAtMs,
            lastUsedAtMs: entry.lastUsedAtMs,
            useCount: entry.useCount
          }
        },
        entry.localRevision
      )
    }
    for (const event of db.analyticsNeedingSync()) {
      add(
        {
          id: event.syncId,
          type: 'analytics',
          baseVersion: event.syncVersion,
          payload: analyticsJson(event)
        },
        event.localRevision
      )
    }

    db.markQueued(queued)
    return { writes, tombstones, revisions }
  }

  private settle(batch: Batch, applied: { id: string; type: string; version: number }): void {
    const db = this.deps.db
    const type: RecordType =
      applied.type === 'dictionary'
        ? 'dictionary'
        : applied.type === 'analytics'
          ? 'analytics'
          : 'history'
    const key = `${type}:${applied.id}`
    if (!batch.tombstones.has(key)) {
      const expectedRevision = batch.revisions.get(key)
      if (type === 'history') db.markHistorySynced(applied.id, applied.version, expectedRevision)
      else if (type === 'dictionary') {
        db.markDictionarySynced(applied.id, applied.version, expectedRevision)
      } else {
        db.markAnalyticsSynced(applied.id, applied.version, expectedRevision)
      }
      return
    }
    db.clearTombstone(type, applied.id)
    if (type !== 'history') return
    // The deletion landed. A row still marked deleted leaves the list; a row
    // restored (undo) while the upload was in flight is re-queued on top of it.
    const deleted = db.isHistoryDeleted(applied.id)
    if (deleted === true) db.hardDeleteHistory(applied.id)
    else if (deleted === false) db.rebaseHistory(applied.id, applied.version)
  }

  /** Resolves one refused write against the server's current copy of the record. */
  private reconcile(remote: CloudRecord, batch: Batch): void {
    if (remote.type !== 'history' && remote.type !== 'dictionary' && remote.type !== 'analytics') {
      return
    }
    const db = this.deps.db
    const type = remote.type
    const remoteExists = remote.version > 0
    if (batch.tombstones.has(`${type}:${remote.id}`)) {
      if (remoteExists && !remote.deleted) {
        // The user deleted it here: re-base the deletion so it still lands.
        db.queueTombstone(type, remote.id, remote.version, 0)
        return
      }
      db.clearTombstone(type, remote.id)
      if (type === 'history') db.hardDeleteHistory(remote.id)
      return
    }
    if (!remoteExists) {
      // The server has no copy: upload again as a new record.
      if (type === 'history') db.rebaseHistory(remote.id, 0)
      else if (type === 'dictionary') db.rebaseDictionary(remote.id, 0)
      else db.rebaseAnalytics(remote.id, 0)
      return
    }
    if (remote.deleted) {
      // A local write against a server deletion is an undo made here for history: keep it.
      if (type === 'history') db.rebaseHistory(remote.id, remote.version)
      else db.applyTombstone(type, remote.id)
      return
    }
    if (remote.payload === null) return
    if (type === 'history') {
      db.upsertRemoteHistory(historyFromJson(remote.id, remote.payload), remote.version)
    } else if (type === 'dictionary') {
      db.upsertRemoteDictionary(dictionaryFromJson(remote.payload), remote.id, remote.version)
    } else {
      db.upsertRemoteAnalytics(analyticsFromJson(remote.id, remote.payload), remote.version)
    }
  }

  // ---- Settings ----

  private applySettings(settings: CloudSettings): void {
    const state = this.deps.settings
    if (state.getHistorySyncEnabled() && !settings.historySyncEnabled) {
      this.applyHistorySyncDisabled()
    }
    state.setHistorySyncEnabled(settings.historySyncEnabled)
    state.setRetentionDays(settings.historyRetentionDays)
    this.applyCachedRetention()
  }

  private applyCachedRetention(): void {
    const retentionDays = this.deps.settings.getRetentionDays()
    if (retentionDays !== null && Number.isFinite(retentionDays) && retentionDays > 0) {
      this.deps.db.deleteHistoryBefore(this.now() - retentionDays * DAY_MS)
    }
  }

  private applyHistorySyncDisabled(): void {
    this.deps.settings.setHistorySyncEnabled(false)
    // The cloud copy is gone; mark local history unsynced so turning the setting
    // back on uploads it again.
    this.deps.db.markHistoryUnsynced()
  }

  // ---- One-time import of the old recovery-key vault ----

  /**
   * Moves this Mac onto cloud sync once per account. When it still holds the old
   * vault key, the vault's records (history, dictionary, and analytics) are merged
   * into local data first, the server is told so it can retire the vault, and the
   * key files are deleted. Every local record is then uploaded fresh.
   */
  private async importLegacyVault(accountId: string): Promise<void> {
    const vaultKey = this.secrets.load('vault-key.bin')
    const recovery = this.secrets.load('recovery-secret.bin')
    let imported = false
    if (vaultKey !== null && recovery !== null) {
      try {
        const remote = await this.call((token) => this.deps.worker.getVault(token))
        this.assertAccount(accountId)
        const unwrapped = remote === null ? null : unwrapVaultKey(remote, recovery, accountId)
        if (unwrapped !== null && unwrapped.equals(vaultKey)) {
          let cursor = 0
          for (let pageCount = 0; pageCount < MAX_PULL_PAGES; pageCount++) {
            const from = cursor
            const page = await this.call((token) => this.deps.worker.pullLegacy(token, from))
            this.assertAccount(accountId)
            for (const record of page.items) {
              try {
                this.importLegacyRecord(accountId, vaultKey, record)
              } catch (error) {
                console.error('[sync] skipped vault record', record.type, record.id, error)
              }
            }
            const nextCursor =
              typeof page.nextCursor === 'number' && Number.isFinite(page.nextCursor)
                ? page.nextCursor
                : cursor
            if (!page.hasMore || nextCursor <= cursor) break
            cursor = nextCursor
          }
          imported = true
        }
      } catch (error) {
        // Another device already moved this account and the vault is closed.
        if (!(error instanceof WorkerError) || error.code !== 'UPGRADE_REQUIRED') throw error
      }
    }
    this.assertAccount(accountId)
    if (imported) await this.call((token) => this.deps.worker.completeLegacyMigration(token))
    this.assertAccount(accountId)
    this.deps.db.resetSyncState()
    this.deps.settings.setCursor(0)
    this.secrets.remove('vault-key.bin')
    this.secrets.remove('recovery-secret.bin')
    this.deps.settings.setMigratedAccount(accountId)
  }

  private importLegacyRecord(
    accountId: string,
    vaultKey: Buffer,
    record: {
      id: string
      type: string
      keyVersion: number
      nonce: string | null
      ciphertext: string | null
      deleted: boolean
    }
  ): void {
    const db = this.deps.db
    if (record.type !== 'history' && record.type !== 'dictionary' && record.type !== 'analytics') {
      return
    }
    if (record.deleted) {
      db.applyTombstone(record.type, record.id)
      return
    }
    if (record.nonce === null || record.ciphertext === null) return
    const plaintext = decryptRecord(
      vaultKey,
      { nonce: record.nonce, ciphertext: record.ciphertext },
      accountId,
      record.type,
      record.id,
      record.keyVersion,
      SCHEMA_VERSION
    )
    if (plaintext === null) return
    const json = JSON.parse(plaintext.toString('utf-8')) as Record<string, unknown>
    // Local copies win: they are at least as new as anything in the vault.
    if (record.type === 'history') {
      if (db.historyByRequestId(record.id) === null) {
        db.upsertRemoteHistory(historyFromJson(record.id, json), 0)
      }
    } else if (record.type === 'dictionary') {
      if (db.dictionaryBySyncId(record.id) === null) {
        db.upsertRemoteDictionary(dictionaryFromJson(json), record.id, 0)
      }
    } else if (db.analyticsBySyncId(record.id) === null) {
      db.upsertRemoteAnalytics(analyticsFromJson(record.id, json), 0)
    }
  }
}

function finiteOr(value: unknown, fallback: number): number {
  const n = typeof value === 'number' ? value : Number(value)
  return Number.isFinite(n) ? n : fallback
}

function finiteOptional(value: unknown): number | null {
  if (value === null || value === undefined) return null
  const n = typeof value === 'number' ? value : Number(value)
  return Number.isFinite(n) ? n : null
}

function historyJson(record: DictationRecord): Record<string, unknown> {
  return {
    schemaVersion: SCHEMA_VERSION,
    text: record.finalText,
    createdAtMs: record.createdAtMs,
    zoneId: record.zoneId,
    offsetSeconds: -new Date(record.createdAtMs).getTimezoneOffset() * 60,
    wordCount: record.wordCount,
    audioDurationMs: record.audioDurationMs,
    asrModel: record.asrModel,
    polished: record.polished,
    asrMs: record.asrMs,
    polishMs: record.polishMs,
    totalMs: record.totalMs,
    pricingVersion: record.pricingVersion ?? null,
    inputTokens: record.inputTokens ?? null,
    outputTokens: record.outputTokens ?? null,
    asrNeurons: record.asrNeurons ?? null,
    polishNeurons: record.polishNeurons ?? null,
    totalNeurons: record.totalNeurons ?? null,
    estimatedCostUsd: record.estimatedCostUsd ?? null
  }
}

function analyticsJson(event: AnalyticsEvent): Record<string, unknown> {
  return {
    schemaVersion: SCHEMA_VERSION,
    createdAtMs: event.createdAtMs,
    zoneId: event.zoneId,
    audioDurationMs: event.audioDurationMs,
    wordCount: event.wordCount,
    processingMs: event.processingMs,
    polished: event.polished,
    corrected: event.corrected,
    asrNeurons: event.asrNeurons,
    polishNeurons: event.polishNeurons,
    estimatedCostUsd: event.estimatedCostUsd
  }
}

function historyFromJson(
  requestId: string,
  plaintext: Record<string, unknown>
): Omit<DictationRecord, 'deleted'> {
  if (typeof plaintext.text !== 'string') {
    throw new Error('History record missing text')
  }
  const createdAtMs = finiteOptional(plaintext.createdAtMs)
  if (createdAtMs === null || createdAtMs <= 0) {
    throw new Error('History record missing valid createdAtMs')
  }
  return {
    requestId,
    finalText: plaintext.text,
    createdAtMs,
    zoneId:
      typeof plaintext.zoneId === 'string' && plaintext.zoneId.length > 0
        ? plaintext.zoneId
        : 'UTC',
    wordCount: Math.max(0, Math.round(finiteOr(plaintext.wordCount, 0))),
    audioDurationMs: Math.max(0, Math.round(finiteOr(plaintext.audioDurationMs, 0))),
    asrModel: typeof plaintext.asrModel === 'string' ? plaintext.asrModel : '',
    polished: Boolean(plaintext.polished),
    asrMs: Math.max(0, Math.round(finiteOr(plaintext.asrMs, 0))),
    polishMs: Math.max(0, Math.round(finiteOr(plaintext.polishMs, 0))),
    totalMs: Math.max(0, Math.round(finiteOr(plaintext.totalMs, 0))),
    pricingVersion: typeof plaintext.pricingVersion === 'string' ? plaintext.pricingVersion : null,
    inputTokens: finiteOptional(plaintext.inputTokens),
    outputTokens: finiteOptional(plaintext.outputTokens),
    asrNeurons: finiteOptional(plaintext.asrNeurons),
    polishNeurons: finiteOptional(plaintext.polishNeurons),
    totalNeurons: finiteOptional(plaintext.totalNeurons),
    estimatedCostUsd: finiteOptional(plaintext.estimatedCostUsd)
  }
}

function dictionaryFromJson(plaintext: Record<string, unknown>): {
  term: string
  normalizedTerm: string
  status: string
  source: string
  createdAtMs: number
  lastUsedAtMs: number
  useCount: number
} {
  if (typeof plaintext.term !== 'string' || plaintext.term.trim().length === 0) {
    throw new Error('Dictionary record missing term')
  }
  if (
    typeof plaintext.normalizedTerm !== 'string' ||
    plaintext.normalizedTerm.trim().length === 0
  ) {
    throw new Error('Dictionary record missing normalizedTerm')
  }
  return {
    term: plaintext.term,
    normalizedTerm: plaintext.normalizedTerm,
    status:
      typeof plaintext.status === 'string' && plaintext.status.length > 0
        ? plaintext.status
        : 'confirmed',
    source:
      typeof plaintext.source === 'string' && plaintext.source.length > 0
        ? plaintext.source
        : 'manual',
    createdAtMs: Math.max(0, Math.round(finiteOr(plaintext.createdAtMs, 0))),
    lastUsedAtMs: Math.max(0, Math.round(finiteOr(plaintext.lastUsedAtMs, 0))),
    useCount: Math.max(0, Math.round(finiteOr(plaintext.useCount, 0)))
  }
}

function analyticsFromJson(syncId: string, plaintext: Record<string, unknown>): AnalyticsEvent {
  const createdAtMs = finiteOptional(plaintext.createdAtMs)
  if (createdAtMs === null || createdAtMs <= 0) {
    throw new Error('Analytics record missing valid createdAtMs')
  }
  return {
    syncId,
    createdAtMs,
    zoneId:
      typeof plaintext.zoneId === 'string' && plaintext.zoneId.length > 0
        ? plaintext.zoneId
        : 'UTC',
    audioDurationMs: Math.max(0, Math.round(finiteOr(plaintext.audioDurationMs, 0))),
    wordCount: Math.max(0, Math.round(finiteOr(plaintext.wordCount, 0))),
    processingMs: Math.max(0, Math.round(finiteOr(plaintext.processingMs, 0))),
    polished: Boolean(plaintext.polished),
    corrected: Boolean(plaintext.corrected),
    asrNeurons: Math.max(0, finiteOr(plaintext.asrNeurons, 0)),
    polishNeurons: Math.max(0, finiteOr(plaintext.polishNeurons, 0)),
    estimatedCostUsd: Math.max(0, finiteOr(plaintext.estimatedCostUsd, 0))
  }
}

function keychainSecretFiles(dir: string): SecretFiles {
  return {
    load(name): Buffer | null {
      try {
        const sealed = readFileSync(join(dir, name))
        if (sealed.length === 0 || !safeStorage.isEncryptionAvailable()) return null
        return Buffer.from(safeStorage.decryptString(sealed), 'base64')
      } catch {
        return null
      }
    },
    remove(name): void {
      try {
        unlinkSync(join(dir, name))
      } catch {
        // absent — nothing to remove
      }
    }
  }
}
