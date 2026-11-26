import { randomUUID } from 'node:crypto'
import { DatabaseSync } from 'node:sqlite'

/**
 * Local dictation database on Node's built-in SQLite. The schema mirrors the
 * phone's Room entities (same table and column names) so the sync layer can
 * map records 1:1. Soft deletes leave tombstone-ready rows for sync.
 */

export interface DictationRecord {
  requestId: string
  finalText: string
  createdAtMs: number
  zoneId: string
  wordCount: number
  audioDurationMs: number
  asrModel: string
  polished: boolean
  asrMs: number
  polishMs: number
  totalMs: number
  deleted: boolean
  pricingVersion?: string | null
  inputTokens?: number | null
  outputTokens?: number | null
  asrNeurons?: number | null
  polishNeurons?: number | null
  totalNeurons?: number | null
  estimatedCostUsd?: number | null
}

export interface DictionaryEntry {
  id: number
  term: string
  normalizedTerm: string
  status: string
  source: string
  createdAtMs: number
  lastUsedAtMs: number
  useCount: number
}

export interface AnalyticsEvent {
  syncId: string
  createdAtMs: number
  zoneId: string
  audioDurationMs: number
  wordCount: number
  processingMs: number
  polished: boolean
  corrected: boolean
  asrNeurons: number
  polishNeurons: number
  estimatedCostUsd: number
}

export interface HomeStats {
  dictations: number
  audioDurationMs: number
  words: number
  recent: Array<
    Pick<
      DictationRecord,
      'requestId' | 'finalText' | 'createdAtMs' | 'wordCount' | 'audioDurationMs'
    >
  >
}

export function openDatabase(path: string): WoVoiceDb {
  const db = new DatabaseSync(path)
  db.exec('PRAGMA busy_timeout = 5000')
  db.exec('PRAGMA journal_mode = WAL')
  db.exec('PRAGMA foreign_keys = ON')
  db.exec(SCHEMA)
  migrate(db)
  return new WoVoiceDb(db)
}

/**
 * Brings tables created by earlier builds up to the current shape. SCHEMA only
 * runs CREATE TABLE IF NOT EXISTS, which never alters an existing table.
 */
function migrate(db: DatabaseSync): void {
  const columns = (table: string): Set<string> =>
    new Set(
      (db.prepare(`PRAGMA table_info(${table})`).all() as Array<{ name: string }>).map(
        (column) => column.name
      )
    )
  db.exec('BEGIN')
  try {
    const records = columns('dictation_records')
    if (!records.has('syncState')) {
      db.exec("ALTER TABLE dictation_records ADD COLUMN syncState TEXT NOT NULL DEFAULT 'local'")
    }
    if (!records.has('syncVersion')) {
      db.exec('ALTER TABLE dictation_records ADD COLUMN syncVersion INTEGER NOT NULL DEFAULT 0')
    }
    if (!records.has('localRevision')) {
      db.exec('ALTER TABLE dictation_records ADD COLUMN localRevision INTEGER NOT NULL DEFAULT 0')
    }
    if (!records.has('pricingVersion')) {
      db.exec('ALTER TABLE dictation_records ADD COLUMN pricingVersion TEXT')
    }
    if (!records.has('inputTokens')) {
      db.exec('ALTER TABLE dictation_records ADD COLUMN inputTokens INTEGER')
    }
    if (!records.has('outputTokens')) {
      db.exec('ALTER TABLE dictation_records ADD COLUMN outputTokens INTEGER')
    }
    if (!records.has('asrNeurons')) {
      db.exec('ALTER TABLE dictation_records ADD COLUMN asrNeurons REAL')
    }
    if (!records.has('polishNeurons')) {
      db.exec('ALTER TABLE dictation_records ADD COLUMN polishNeurons REAL')
    }
    if (!records.has('totalNeurons')) {
      db.exec('ALTER TABLE dictation_records ADD COLUMN totalNeurons REAL')
    }
    if (!records.has('estimatedCostUsd')) {
      db.exec('ALTER TABLE dictation_records ADD COLUMN estimatedCostUsd REAL')
    }

    const dictionary = columns('dictionary_entries')
    if (!dictionary.has('syncState')) {
      db.exec("ALTER TABLE dictionary_entries ADD COLUMN syncState TEXT NOT NULL DEFAULT 'local'")
    }
    if (!dictionary.has('syncVersion')) {
      db.exec('ALTER TABLE dictionary_entries ADD COLUMN syncVersion INTEGER NOT NULL DEFAULT 0')
    }
    if (!dictionary.has('localRevision')) {
      db.exec('ALTER TABLE dictionary_entries ADD COLUMN localRevision INTEGER NOT NULL DEFAULT 0')
    }
    if (!dictionary.has('syncId')) {
      // ALTER TABLE cannot add a NOT NULL UNIQUE column: add it, give every
      // existing term an id, then enforce uniqueness with an index.
      db.exec('ALTER TABLE dictionary_entries ADD COLUMN syncId TEXT')
      const assign = db.prepare('UPDATE dictionary_entries SET syncId = ? WHERE id = ?')
      for (const row of db.prepare('SELECT id FROM dictionary_entries').all() as Array<{
        id: number
      }>) {
        assign.run(randomUUID(), row.id)
      }
      db.exec('CREATE UNIQUE INDEX idx_dictionary_entries_sync_id ON dictionary_entries(syncId)')
    }

    const tombstones = columns('pending_tombstones')
    if (!tombstones.has('holdUntilMs')) {
      db.exec('ALTER TABLE pending_tombstones ADD COLUMN holdUntilMs INTEGER NOT NULL DEFAULT 0')
    }

    const analytics = columns('analytics_events')
    if (!analytics.has('localRevision')) {
      db.exec('ALTER TABLE analytics_events ADD COLUMN localRevision INTEGER NOT NULL DEFAULT 0')
    }

    db.exec('COMMIT')
  } catch (error) {
    db.exec('ROLLBACK')
    throw error
  }
}

const SCHEMA = `
CREATE TABLE IF NOT EXISTS dictation_records (
  requestId TEXT PRIMARY KEY,
  finalText TEXT NOT NULL,
  createdAtMs INTEGER NOT NULL,
  zoneId TEXT NOT NULL,
  wordCount INTEGER NOT NULL,
  audioDurationMs INTEGER NOT NULL,
  asrModel TEXT NOT NULL,
  polished INTEGER NOT NULL,
  asrMs INTEGER NOT NULL DEFAULT 0,
  polishMs INTEGER NOT NULL DEFAULT 0,
  totalMs INTEGER NOT NULL DEFAULT 0,
  pricingVersion TEXT,
  inputTokens INTEGER,
  outputTokens INTEGER,
  asrNeurons REAL,
  polishNeurons REAL,
  totalNeurons REAL,
  estimatedCostUsd REAL,
  deleted INTEGER NOT NULL DEFAULT 0,
  syncState TEXT NOT NULL DEFAULT 'local',
  syncVersion INTEGER NOT NULL DEFAULT 0,
  localRevision INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_records_created
  ON dictation_records(createdAtMs DESC);
CREATE TABLE IF NOT EXISTS dictionary_entries (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  term TEXT NOT NULL,
  normalizedTerm TEXT NOT NULL UNIQUE,
  status TEXT NOT NULL DEFAULT 'confirmed',
  source TEXT NOT NULL DEFAULT 'manual',
  createdAtMs INTEGER NOT NULL,
  lastUsedAtMs INTEGER NOT NULL,
  useCount INTEGER NOT NULL DEFAULT 0,
  syncId TEXT NOT NULL UNIQUE,
  syncState TEXT NOT NULL DEFAULT 'local',
  syncVersion INTEGER NOT NULL DEFAULT 0,
  localRevision INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS analytics_events (
  syncId TEXT PRIMARY KEY,
  createdAtMs INTEGER NOT NULL,
  zoneId TEXT NOT NULL,
  audioDurationMs INTEGER NOT NULL,
  wordCount INTEGER NOT NULL,
  processingMs INTEGER NOT NULL,
  polished INTEGER NOT NULL DEFAULT 0,
  corrected INTEGER NOT NULL DEFAULT 0,
  asrNeurons REAL NOT NULL DEFAULT 0,
  polishNeurons REAL NOT NULL DEFAULT 0,
  estimatedCostUsd REAL NOT NULL DEFAULT 0,
  syncState TEXT NOT NULL DEFAULT 'local',
  syncVersion INTEGER NOT NULL DEFAULT 0,
  localRevision INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_analytics_created
  ON analytics_events(createdAtMs DESC);
CREATE TABLE IF NOT EXISTS pending_tombstones (
  recordType TEXT NOT NULL,
  recordId TEXT NOT NULL,
  baseVersion INTEGER NOT NULL,
  createdAtMs INTEGER NOT NULL,
  holdUntilMs INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (recordType, recordId)
);
`

export class WoVoiceDb {
  constructor(private readonly db: DatabaseSync) {}

  close(): void {
    this.db.close()
  }

  private transaction<T>(fn: () => T): T {
    this.db.exec('BEGIN')
    try {
      const value = fn()
      this.db.exec('COMMIT')
      return value
    } catch (error) {
      this.db.exec('ROLLBACK')
      throw error
    }
  }

  insertRecord(record: DictationRecord): void {
    this.db
      .prepare(
        `INSERT OR IGNORE INTO dictation_records
           (requestId, finalText, createdAtMs, zoneId, wordCount, audioDurationMs,
            asrModel, polished, asrMs, polishMs, totalMs,
            pricingVersion, inputTokens, outputTokens, asrNeurons, polishNeurons, totalNeurons, estimatedCostUsd)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
      )
      .run(
        record.requestId,
        record.finalText,
        record.createdAtMs,
        record.zoneId,
        record.wordCount,
        record.audioDurationMs,
        record.asrModel,
        record.polished ? 1 : 0,
        record.asrMs,
        record.polishMs,
        record.totalMs,
        record.pricingVersion ?? null,
        record.inputTokens ?? null,
        record.outputTokens ?? null,
        record.asrNeurons ?? null,
        record.polishNeurons ?? null,
        record.totalNeurons ?? null,
        record.estimatedCostUsd ?? null
      )
  }

  insertAnalyticsEvent(event: AnalyticsEvent): void {
    this.db
      .prepare(
        `INSERT OR IGNORE INTO analytics_events
           (syncId, createdAtMs, zoneId, audioDurationMs, wordCount, processingMs,
            polished, corrected, asrNeurons, polishNeurons, estimatedCostUsd)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
      )
      .run(
        event.syncId,
        event.createdAtMs,
        event.zoneId,
        event.audioDurationMs,
        event.wordCount,
        event.processingMs,
        event.polished ? 1 : 0,
        event.corrected ? 1 : 0,
        event.asrNeurons,
        event.polishNeurons,
        event.estimatedCostUsd
      )
  }

  historySearch(query: string, limit = 200): DictationRecord[] {
    const trimmed = query.trim()
    const like = `%${escapeLike(trimmed)}%`
    const rows = this.db
      .prepare(
        `SELECT requestId, finalText, createdAtMs, zoneId, wordCount, audioDurationMs,
                asrModel, polished, asrMs, polishMs, totalMs,
                pricingVersion, inputTokens, outputTokens, asrNeurons, polishNeurons, totalNeurons, estimatedCostUsd
         FROM dictation_records
         WHERE deleted = 0 AND (:q = '' OR finalText LIKE :like ESCAPE '\\')
         ORDER BY createdAtMs DESC
         LIMIT :limit`
      )
      .all({ q: trimmed, like, limit }) as Array<Record<string, unknown>>
    return rows.map(rowToRecord)
  }

  latestDictationText(): string | null {
    const row = this.db
      .prepare(
        `SELECT finalText FROM dictation_records
         WHERE deleted = 0
         ORDER BY createdAtMs DESC LIMIT 1`
      )
      .get() as { finalText: string } | undefined
    return row ? row.finalText : null
  }

  exportHistory(format: 'txt' | 'json'): string {
    const rows = this.db
      .prepare(
        `SELECT requestId, finalText, createdAtMs, zoneId, wordCount, audioDurationMs,
                asrModel, polished, asrMs, polishMs, totalMs,
                pricingVersion, inputTokens, outputTokens, asrNeurons, polishNeurons, totalNeurons, estimatedCostUsd
         FROM dictation_records
         WHERE deleted = 0
         ORDER BY createdAtMs DESC`
      )
      .all() as Array<Record<string, unknown>>
    const records = rows.map(rowToRecord)
    if (format === 'json') {
      return JSON.stringify(records, null, 2)
    }
    return records
      .map(
        (record) =>
          `[${new Date(record.createdAtMs).toISOString()}] (${record.wordCount} ${record.wordCount === 1 ? 'word' : 'words'})\n${record.finalText}`
      )
      .join('\n\n')
  }

  deleteRecord(requestId: string, holdMs = 0): void {
    this.transaction(() => {
      const row = this.db
        .prepare('SELECT syncVersion, syncState FROM dictation_records WHERE requestId = ?')
        .get(requestId) as { syncVersion: number; syncState: string } | undefined
      if (row === undefined) return
      this.db
        .prepare(
          'UPDATE dictation_records SET deleted = 1, localRevision = localRevision + 1 WHERE requestId = ?'
        )
        .run(requestId)
      if (Number(row.syncVersion) > 0 || row.syncState === 'queued') {
        const holdUntil = holdMs > 0 ? Date.now() + holdMs : 0
        this.queueTombstone('history', requestId, Number(row.syncVersion), holdUntil)
      }
    })
  }

  /**
   * Undo of a delete. Also withdraws the pending tombstone — leaving it queued
   * made the next sync delete the "restored" record everywhere, then locally.
   * Returns false once the deletion has already synced and the row is gone.
   */
  restoreRecord(requestId: string): boolean {
    return this.transaction(() => {
      const result = this.db
        .prepare(
          'UPDATE dictation_records SET deleted = 0, localRevision = localRevision + 1 WHERE requestId = ? AND deleted = 1'
        )
        .run(requestId)
      this.clearTombstone('history', requestId)
      return Number(result.changes) === 1
    })
  }

  /** Re-bases a local write onto the server's version so the next push applies. */
  rebaseHistory(requestId: string, version: number): void {
    this.db
      .prepare(
        "UPDATE dictation_records SET syncVersion = ?, syncState = 'local', localRevision = localRevision + 1 WHERE requestId = ?"
      )
      .run(version, requestId)
  }

  rebaseDictionary(syncId: string, version: number): void {
    this.db
      .prepare(
        "UPDATE dictionary_entries SET syncVersion = ?, syncState = 'local', localRevision = localRevision + 1 WHERE syncId = ?"
      )
      .run(version, syncId)
  }

  rebaseAnalytics(syncId: string, version: number): void {
    this.db
      .prepare(
        "UPDATE analytics_events SET syncVersion = ?, syncState = 'local', localRevision = localRevision + 1 WHERE syncId = ?"
      )
      .run(version, syncId)
  }

  hasPendingTombstone(recordType: string, recordId: string): boolean {
    return (
      this.db
        .prepare('SELECT 1 FROM pending_tombstones WHERE recordType = ? AND recordId = ?')
        .get(recordType, recordId) !== undefined
    )
  }

  /** The server version this Mac already holds for a record, or null when it has none. */
  knownSyncVersion(recordType: string, recordId: string): number | null {
    const sql =
      recordType === 'history'
        ? 'SELECT syncVersion FROM dictation_records WHERE requestId = ?'
        : recordType === 'dictionary'
          ? 'SELECT syncVersion FROM dictionary_entries WHERE syncId = ?'
          : recordType === 'analytics'
            ? 'SELECT syncVersion FROM analytics_events WHERE syncId = ?'
            : null
    if (sql === null) return null
    const row = this.db.prepare(sql).get(recordId) as { syncVersion: number } | undefined
    return row === undefined ? null : Number(row.syncVersion)
  }

  syncStateOf(recordType: string, recordId: string): string | null {
    const sql =
      recordType === 'history'
        ? 'SELECT syncState FROM dictation_records WHERE requestId = ?'
        : recordType === 'dictionary'
          ? 'SELECT syncState FROM dictionary_entries WHERE syncId = ?'
          : recordType === 'analytics'
            ? 'SELECT syncState FROM analytics_events WHERE syncId = ?'
            : null
    if (sql === null) return null
    const row = this.db.prepare(sql).get(recordId) as { syncState: string } | undefined
    return row === undefined ? null : String(row.syncState)
  }

  isHistoryDeleted(requestId: string): boolean | null {
    const row = this.db
      .prepare('SELECT deleted FROM dictation_records WHERE requestId = ?')
      .get(requestId) as { deleted: number } | undefined
    return row === undefined ? null : Number(row.deleted) === 1
  }

  queueTombstone(
    recordType: 'history' | 'dictionary' | 'analytics',
    recordId: string,
    baseVersion: number,
    holdUntilMs = 0
  ): void {
    this.db
      .prepare(
        `INSERT OR REPLACE INTO pending_tombstones
           (recordType, recordId, baseVersion, createdAtMs, holdUntilMs)
         VALUES (?, ?, ?, ?, ?)`
      )
      .run(recordType, recordId, baseVersion, Date.now(), holdUntilMs)
  }

  /**
   * Marks every local record as never uploaded and drops pending deletions. Used
   * when a new vault is created or the vault is reset: the server then holds
   * nothing, and rows marked synced to the old vault would never upload again.
   */
  resetSyncState(): void {
    this.transaction(() => {
      this.db.exec(
        "UPDATE dictation_records SET syncState = 'local', syncVersion = 0;" +
          "UPDATE dictionary_entries SET syncState = 'local', syncVersion = 0;" +
          "UPDATE analytics_events SET syncState = 'local', syncVersion = 0;" +
          'DELETE FROM dictation_records WHERE deleted = 1;' +
          'DELETE FROM pending_tombstones;'
      )
    })
  }

  /** History sync was turned off (its cloud copy deleted): re-upload it all if it comes back on. */
  markHistoryUnsynced(): void {
    this.transaction(() => {
      this.db.exec(
        "UPDATE dictation_records SET syncState = 'local', syncVersion = 0;" +
          "DELETE FROM pending_tombstones WHERE recordType = 'history';"
      )
    })
  }

  /** Local side of history retention; the server deletes its own expired copies. */
  deleteHistoryBefore(cutoffMs: number): void {
    this.transaction(() => {
      this.db.prepare('DELETE FROM dictation_records WHERE createdAtMs < ?').run(cutoffMs)
      this.db
        .prepare(
          "DELETE FROM pending_tombstones WHERE recordType = 'history' AND recordId NOT IN (SELECT requestId FROM dictation_records)"
        )
        .run()
    })
  }

  /** Removes every local record — used when a different account signs in or account is deleted. */
  clearAllData(): void {
    this.transaction(() => {
      this.db.exec(
        'DELETE FROM dictation_records; DELETE FROM dictionary_entries; DELETE FROM analytics_events; DELETE FROM pending_tombstones;'
      )
    })
  }

  localDataCounts(): { history: number; dictionary: number; unsynced: number } {
    const count = (sql: string): number => Number((this.db.prepare(sql).get() as { n: number }).n)
    return {
      history: count('SELECT COUNT(*) AS n FROM dictation_records WHERE deleted = 0'),
      dictionary: count("SELECT COUNT(*) AS n FROM dictionary_entries WHERE status = 'confirmed'"),
      unsynced:
        count(
          "SELECT COUNT(*) AS n FROM dictation_records WHERE deleted = 0 AND syncState IN ('local', 'queued')"
        ) +
        count(
          "SELECT COUNT(*) AS n FROM dictionary_entries WHERE syncState IN ('local', 'queued')"
        ) +
        count("SELECT COUNT(*) AS n FROM analytics_events WHERE syncState IN ('local', 'queued')") +
        count('SELECT COUNT(*) AS n FROM pending_tombstones')
    }
  }

  /**
   * Clears local dictation history and queues cloud tombstones ONLY for records
   * that were previously synced (or currently uploading). Preserves analytics_events
   * so Home stats survive history clears.
   */
  clearHistory(): void {
    this.transaction(() => {
      const synced = this.db
        .prepare(
          "SELECT requestId, syncVersion FROM dictation_records WHERE syncVersion > 0 OR syncState = 'queued'"
        )
        .all() as Array<{ requestId: string; syncVersion: number }>
      const now = Date.now()
      const queue = this.db.prepare(
        `INSERT OR REPLACE INTO pending_tombstones
           (recordType, recordId, baseVersion, createdAtMs, holdUntilMs)
         VALUES ('history', ?, ?, ?, 0)`
      )
      for (const row of synced) {
        queue.run(row.requestId, Number(row.syncVersion), now)
      }
      this.db.prepare('DELETE FROM dictation_records').run()
    })
  }

  stats(sinceMs: number): HomeStats {
    const analyticsAgg = this.db
      .prepare(
        `SELECT COUNT(*) AS dictations,
                COALESCE(SUM(audioDurationMs), 0) AS audioDurationMs,
                COALESCE(SUM(wordCount), 0) AS words
         FROM analytics_events
         WHERE createdAtMs >= ?`
      )
      .get(sinceMs) as { dictations: number; audioDurationMs: number; words: number }
    const fallbackAgg = this.db
      .prepare(
        `SELECT COUNT(*) AS dictations,
                COALESCE(SUM(audioDurationMs), 0) AS audioDurationMs,
                COALESCE(SUM(wordCount), 0) AS words
         FROM dictation_records
         WHERE deleted = 0 AND createdAtMs >= ?
           AND requestId NOT IN (SELECT syncId FROM analytics_events)`
      )
      .get(sinceMs) as { dictations: number; audioDurationMs: number; words: number }
    const recent = this.db
      .prepare(
        `SELECT requestId, finalText, createdAtMs, wordCount, audioDurationMs
         FROM dictation_records
         WHERE deleted = 0
         ORDER BY createdAtMs DESC LIMIT 3`
      )
      .all() as HomeStats['recent']
    return {
      dictations: Number(analyticsAgg.dictations) + Number(fallbackAgg.dictations),
      audioDurationMs: Number(analyticsAgg.audioDurationMs) + Number(fallbackAgg.audioDurationMs),
      words: Number(analyticsAgg.words) + Number(fallbackAgg.words),
      recent
    }
  }

  // ---- Sync bookkeeping ----

  /** Returns any 'queued' rows from an interrupted upload back to 'local'. */
  returnQueuedToLocal(_accountId?: string): void {
    void _accountId
    this.transaction(() => {
      this.db.exec(
        "UPDATE dictation_records SET syncState = 'local' WHERE syncState = 'queued';" +
          "UPDATE dictionary_entries SET syncState = 'local' WHERE syncState = 'queued';" +
          "UPDATE analytics_events SET syncState = 'local' WHERE syncState = 'queued';"
      )
    })
  }

  markQueued(
    items: Array<{ type: 'history' | 'dictionary' | 'analytics'; id: string; revision: number }>
  ): void {
    if (items.length === 0) return
    this.transaction(() => {
      const historyStmt = this.db.prepare(
        "UPDATE dictation_records SET syncState = 'queued' WHERE requestId = ? AND syncState = 'local' AND localRevision = ?"
      )
      const dictStmt = this.db.prepare(
        "UPDATE dictionary_entries SET syncState = 'queued' WHERE syncId = ? AND syncState = 'local' AND localRevision = ?"
      )
      const analyticsStmt = this.db.prepare(
        "UPDATE analytics_events SET syncState = 'queued' WHERE syncId = ? AND syncState = 'local' AND localRevision = ?"
      )
      for (const item of items) {
        if (item.type === 'history') historyStmt.run(item.id, item.revision)
        else if (item.type === 'dictionary') dictStmt.run(item.id, item.revision)
        else analyticsStmt.run(item.id, item.revision)
      }
    })
  }

  historyNeedingSync(): Array<DictationRecord & { syncVersion: number; localRevision: number }> {
    const rows = this.db
      .prepare(
        `SELECT requestId, finalText, createdAtMs, zoneId, wordCount, audioDurationMs,
                asrModel, polished, asrMs, polishMs, totalMs,
                pricingVersion, inputTokens, outputTokens, asrNeurons, polishNeurons, totalNeurons, estimatedCostUsd,
                syncVersion, localRevision
         FROM dictation_records WHERE deleted = 0 AND syncState = 'local'
         ORDER BY createdAtMs DESC LIMIT 100`
      )
      .all() as Array<Record<string, unknown>>
    return rows.map((row) => ({
      ...rowToRecord(row),
      syncVersion: Number(row.syncVersion),
      localRevision: Number(row.localRevision ?? 0)
    }))
  }

  tombstones(
    nowMs = Date.now()
  ): Array<{ recordType: string; recordId: string; baseVersion: number }> {
    return this.db
      .prepare(
        'SELECT recordType, recordId, baseVersion FROM pending_tombstones WHERE holdUntilMs <= ? LIMIT 100'
      )
      .all(nowMs) as Array<{ recordType: string; recordId: string; baseVersion: number }>
  }

  markHistorySynced(requestId: string, version: number, expectedRevision?: number): void {
    this.transaction(() => {
      if (expectedRevision !== undefined) {
        this.db
          .prepare('UPDATE dictation_records SET syncVersion = ? WHERE requestId = ?')
          .run(version, requestId)
        this.db
          .prepare(
            "UPDATE dictation_records SET syncState = 'synced' WHERE requestId = ? AND syncState = 'queued' AND localRevision = ?"
          )
          .run(requestId, expectedRevision)
      } else {
        this.db
          .prepare(
            "UPDATE dictation_records SET syncState = 'synced', syncVersion = ? WHERE requestId = ?"
          )
          .run(version, requestId)
      }
      this.rebaseTombstone('history', requestId, version)
    })
  }

  /** A delete made while the record's own push was in flight must build on the version it produced. */
  private rebaseTombstone(recordType: string, recordId: string, version: number): void {
    this.db
      .prepare(
        'UPDATE pending_tombstones SET baseVersion = ? WHERE recordType = ? AND recordId = ? AND baseVersion < ?'
      )
      .run(version, recordType, recordId, version)
  }

  hardDeleteHistory(requestId: string): void {
    this.db.prepare('DELETE FROM dictation_records WHERE requestId = ?').run(requestId)
  }

  historyByRequestId(requestId: string): DictationRecord | null {
    const row = this.db
      .prepare(
        `SELECT requestId, finalText, createdAtMs, zoneId, wordCount, audioDurationMs,
                asrModel, polished, asrMs, polishMs, totalMs,
                pricingVersion, inputTokens, outputTokens, asrNeurons, polishNeurons, totalNeurons, estimatedCostUsd
         FROM dictation_records WHERE requestId = ?`
      )
      .get(requestId)
    return row === undefined ? null : rowToRecord(row as Record<string, unknown>)
  }

  upsertRemoteHistory(record: Omit<DictationRecord, 'deleted'>, version: number): void {
    this.db
      .prepare(
        `INSERT INTO dictation_records
           (requestId, finalText, createdAtMs, zoneId, wordCount, audioDurationMs,
            asrModel, polished, asrMs, polishMs, totalMs,
            pricingVersion, inputTokens, outputTokens, asrNeurons, polishNeurons, totalNeurons, estimatedCostUsd,
            deleted, syncState, syncVersion)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 'synced', ?)
         ON CONFLICT(requestId) DO UPDATE SET
           finalText = excluded.finalText,
           createdAtMs = excluded.createdAtMs,
           zoneId = excluded.zoneId,
           wordCount = excluded.wordCount,
           audioDurationMs = excluded.audioDurationMs,
           asrModel = excluded.asrModel,
           polished = excluded.polished,
           asrMs = excluded.asrMs,
           polishMs = excluded.polishMs,
           totalMs = excluded.totalMs,
           pricingVersion = excluded.pricingVersion,
           inputTokens = excluded.inputTokens,
           outputTokens = excluded.outputTokens,
           asrNeurons = excluded.asrNeurons,
           polishNeurons = excluded.polishNeurons,
           totalNeurons = excluded.totalNeurons,
           estimatedCostUsd = excluded.estimatedCostUsd,
           deleted = 0,
           syncVersion = excluded.syncVersion,
           syncState = 'synced',
           localRevision = localRevision + 1`
      )
      .run(
        record.requestId,
        record.finalText,
        record.createdAtMs,
        record.zoneId,
        record.wordCount,
        record.audioDurationMs,
        record.asrModel,
        record.polished ? 1 : 0,
        record.asrMs,
        record.polishMs,
        record.totalMs,
        record.pricingVersion ?? null,
        record.inputTokens ?? null,
        record.outputTokens ?? null,
        record.asrNeurons ?? null,
        record.polishNeurons ?? null,
        record.totalNeurons ?? null,
        record.estimatedCostUsd ?? null,
        version
      )
  }

  dictionaryNeedingSync(): Array<
    DictionaryEntry & { syncId: string; syncVersion: number; localRevision: number }
  > {
    const rows = this.db
      .prepare(
        `SELECT id, term, normalizedTerm, status, source, createdAtMs, lastUsedAtMs, useCount,
                syncId, syncVersion, localRevision
         FROM dictionary_entries WHERE syncState = 'local' LIMIT 100`
      )
      .all() as Array<Record<string, unknown>>
    return rows.map((row) => ({
      ...rowToEntry(row),
      syncId: String(row.syncId),
      syncVersion: Number(row.syncVersion),
      localRevision: Number(row.localRevision ?? 0)
    }))
  }

  markDictionarySynced(syncId: string, version: number, expectedRevision?: number): void {
    this.transaction(() => {
      if (expectedRevision !== undefined) {
        this.db
          .prepare('UPDATE dictionary_entries SET syncVersion = ? WHERE syncId = ?')
          .run(version, syncId)
        this.db
          .prepare(
            "UPDATE dictionary_entries SET syncState = 'synced' WHERE syncId = ? AND syncState = 'queued' AND localRevision = ?"
          )
          .run(syncId, expectedRevision)
      } else {
        this.db
          .prepare(
            "UPDATE dictionary_entries SET syncState = 'synced', syncVersion = ? WHERE syncId = ?"
          )
          .run(version, syncId)
      }
      this.rebaseTombstone('dictionary', syncId, version)
    })
  }

  dictionaryBySyncId(syncId: string): (DictionaryEntry & { syncId: string }) | null {
    const row = this.db
      .prepare(
        `SELECT id, term, normalizedTerm, status, source, createdAtMs, lastUsedAtMs, useCount, syncId
         FROM dictionary_entries WHERE syncId = ?`
      )
      .get(syncId) as Record<string, unknown> | undefined
    return row === undefined ? null : { ...rowToEntry(row), syncId: String(row.syncId) }
  }

  upsertRemoteDictionary(
    entry: {
      term: string
      normalizedTerm: string
      status: string
      source: string
      createdAtMs: number
      lastUsedAtMs: number
      useCount: number
    },
    syncId: string,
    version: number
  ): void {
    this.transaction(() => {
      // Match on the record's sync id first. Upserting on normalizedTerm alone
      // broke on a term renamed on the phone: the new spelling matched no row, the
      // insert hit the UNIQUE syncId, and the thrown error wedged every later pull.
      // A different local row holding the same term yields to the synced copy.
      this.db
        .prepare('DELETE FROM dictionary_entries WHERE normalizedTerm = ? AND syncId != ?')
        .run(entry.normalizedTerm, syncId)
      const updated = this.db
        .prepare(
          `UPDATE dictionary_entries SET
             term = ?, normalizedTerm = ?, status = ?, source = ?, createdAtMs = ?,
             lastUsedAtMs = ?, useCount = ?, syncState = 'synced', syncVersion = ?,
             localRevision = localRevision + 1
           WHERE syncId = ?`
        )
        .run(
          entry.term,
          entry.normalizedTerm,
          entry.status,
          entry.source,
          entry.createdAtMs,
          entry.lastUsedAtMs,
          entry.useCount,
          version,
          syncId
        )
      if (Number(updated.changes) > 0) return
      this.db
        .prepare(
          `INSERT INTO dictionary_entries
             (term, normalizedTerm, status, source, createdAtMs, lastUsedAtMs, useCount, syncId, syncState, syncVersion)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'synced', ?)`
        )
        .run(
          entry.term,
          entry.normalizedTerm,
          entry.status,
          entry.source,
          entry.createdAtMs,
          entry.lastUsedAtMs,
          entry.useCount,
          syncId,
          version
        )
    })
  }

  deleteDictionaryBySyncId(syncId: string): void {
    this.db.prepare('DELETE FROM dictionary_entries WHERE syncId = ?').run(syncId)
  }

  analyticsNeedingSync(): Array<AnalyticsEvent & { syncVersion: number; localRevision: number }> {
    const rows = this.db
      .prepare(
        `SELECT syncId, createdAtMs, zoneId, audioDurationMs, wordCount, processingMs,
                polished, corrected, asrNeurons, polishNeurons, estimatedCostUsd,
                syncVersion, localRevision
         FROM analytics_events WHERE syncState = 'local'
         ORDER BY createdAtMs DESC LIMIT 100`
      )
      .all() as Array<Record<string, unknown>>
    return rows.map((row) => ({
      ...rowToAnalytics(row),
      syncVersion: Number(row.syncVersion),
      localRevision: Number(row.localRevision ?? 0)
    }))
  }

  markAnalyticsSynced(syncId: string, version: number, expectedRevision?: number): void {
    this.transaction(() => {
      if (expectedRevision !== undefined) {
        this.db
          .prepare('UPDATE analytics_events SET syncVersion = ? WHERE syncId = ?')
          .run(version, syncId)
        this.db
          .prepare(
            "UPDATE analytics_events SET syncState = 'synced' WHERE syncId = ? AND syncState = 'queued' AND localRevision = ?"
          )
          .run(syncId, expectedRevision)
      } else {
        this.db
          .prepare(
            "UPDATE analytics_events SET syncState = 'synced', syncVersion = ? WHERE syncId = ?"
          )
          .run(version, syncId)
      }
      this.rebaseTombstone('analytics', syncId, version)
    })
  }

  analyticsBySyncId(syncId: string): AnalyticsEvent | null {
    const row = this.db
      .prepare(
        `SELECT syncId, createdAtMs, zoneId, audioDurationMs, wordCount, processingMs,
                polished, corrected, asrNeurons, polishNeurons, estimatedCostUsd
         FROM analytics_events WHERE syncId = ?`
      )
      .get(syncId) as Record<string, unknown> | undefined
    return row === undefined ? null : rowToAnalytics(row)
  }

  upsertRemoteAnalytics(event: AnalyticsEvent, version: number): void {
    this.db
      .prepare(
        `INSERT INTO analytics_events
           (syncId, createdAtMs, zoneId, audioDurationMs, wordCount, processingMs,
            polished, corrected, asrNeurons, polishNeurons, estimatedCostUsd,
            syncState, syncVersion)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'synced', ?)
         ON CONFLICT(syncId) DO UPDATE SET
           createdAtMs = excluded.createdAtMs,
           zoneId = excluded.zoneId,
           audioDurationMs = excluded.audioDurationMs,
           wordCount = excluded.wordCount,
           processingMs = excluded.processingMs,
           polished = excluded.polished,
           corrected = excluded.corrected,
           asrNeurons = excluded.asrNeurons,
           polishNeurons = excluded.polishNeurons,
           estimatedCostUsd = excluded.estimatedCostUsd,
           syncState = 'synced',
           syncVersion = excluded.syncVersion,
           localRevision = localRevision + 1`
      )
      .run(
        event.syncId,
        event.createdAtMs,
        event.zoneId,
        event.audioDurationMs,
        event.wordCount,
        event.processingMs,
        event.polished ? 1 : 0,
        event.corrected ? 1 : 0,
        event.asrNeurons,
        event.polishNeurons,
        event.estimatedCostUsd,
        version
      )
  }

  deleteAnalyticsBySyncId(syncId: string): void {
    this.db.prepare('DELETE FROM analytics_events WHERE syncId = ?').run(syncId)
  }

  applyTombstone(recordType: string, recordId: string): void {
    if (recordType === 'history') this.hardDeleteHistory(recordId)
    else if (recordType === 'dictionary') this.deleteDictionaryBySyncId(recordId)
    else if (recordType === 'analytics') this.deleteAnalyticsBySyncId(recordId)
  }

  clearTombstone(recordType: string, recordId: string): void {
    this.db
      .prepare('DELETE FROM pending_tombstones WHERE recordType = ? AND recordId = ?')
      .run(recordType, recordId)
  }

  // ---- Dictionary ----

  addTerm(rawTerm: string, source = 'manual'): boolean {
    const cleaned = cleanTerm(rawTerm)
    if (cleaned === null) return false
    const norm = normalize(cleaned)
    const now = Date.now()
    return this.transaction(() => {
      const existing = this.db
        .prepare('SELECT id, status FROM dictionary_entries WHERE normalizedTerm = ?')
        .get(norm) as { id: number; status: string } | undefined
      if (existing !== undefined) {
        if (existing.status === 'confirmed') return false
        // Promote an invisible 'suggested' entry (synced from mobile) to 'confirmed'.
        this.db
          .prepare(
            `UPDATE dictionary_entries
             SET term = ?, status = 'confirmed', source = ?, lastUsedAtMs = ?,
                 syncState = 'local', localRevision = localRevision + 1
             WHERE id = ?`
          )
          .run(cleaned, source, now, existing.id)
        return true
      }
      const result = this.db
        .prepare(
          `INSERT INTO dictionary_entries
             (term, normalizedTerm, status, source, createdAtMs, lastUsedAtMs, syncId)
           VALUES (?, ?, 'confirmed', ?, ?, ?, ?)`
        )
        .run(cleaned, norm, source, now, now, randomUUID())
      return Number(result.changes) === 1
    })
  }

  editTerm(id: number, rawTerm: string): boolean {
    const cleaned = cleanTerm(rawTerm)
    if (cleaned === null) return false
    const norm = normalize(cleaned)
    return this.transaction(() => {
      const current = this.db.prepare('SELECT id FROM dictionary_entries WHERE id = ?').get(id) as
        { id: number } | undefined
      if (current === undefined) return false
      const clash = this.db
        .prepare('SELECT id FROM dictionary_entries WHERE normalizedTerm = ? AND id != ?')
        .get(norm, id) as { id: number } | undefined
      if (clash !== undefined) return false
      const result = this.db
        .prepare(
          `UPDATE dictionary_entries
           SET term = ?, normalizedTerm = ?, status = 'confirmed',
               syncState = 'local', localRevision = localRevision + 1
           WHERE id = ?`
        )
        .run(cleaned, norm, id)
      return Number(result.changes) === 1
    })
  }

  listTerms(query: string, limit = 1000): DictionaryEntry[] {
    const trimmed = query.trim()
    const like = `%${escapeLike(trimmed)}%`
    const rows = this.db
      .prepare(
        `SELECT id, term, normalizedTerm, status, source, createdAtMs, lastUsedAtMs, useCount
         FROM dictionary_entries
         WHERE status = 'confirmed' AND (:q = '' OR term LIKE :like ESCAPE '\\')
         ORDER BY CASE source WHEN 'manual' THEN 0 WHEN 'imported' THEN 1 ELSE 2 END,
                  useCount DESC, lastUsedAtMs DESC, term COLLATE NOCASE
         LIMIT :limit`
      )
      .all({ q: trimmed, like, limit }) as Array<Record<string, unknown>>
    return rows.map(rowToEntry)
  }

  deleteTerm(id: number): void {
    this.transaction(() => {
      const row = this.db
        .prepare('SELECT syncId, syncVersion, syncState FROM dictionary_entries WHERE id = ?')
        .get(id) as { syncId: string; syncVersion: number; syncState: string } | undefined
      if (row === undefined) return
      this.db.prepare('DELETE FROM dictionary_entries WHERE id = ?').run(id)
      if (Number(row.syncVersion) > 0 || row.syncState === 'queued') {
        this.queueTombstone('dictionary', row.syncId, Number(row.syncVersion), 0)
      }
    })
  }

  bestGlossary(limit = 100): string[] {
    const rows = this.db
      .prepare(
        `SELECT term FROM dictionary_entries
         WHERE status = 'confirmed'
         ORDER BY CASE source WHEN 'manual' THEN 0 WHEN 'imported' THEN 1 ELSE 2 END,
                  useCount DESC, lastUsedAtMs DESC
         LIMIT ?`
      )
      .all(limit) as Array<{ term: string }>
    return rows.map((row) => row.term)
  }

  /**
   * Bumps usage for every glossary term that appears as a standalone word/phrase
   * in the committed text (e.g. "AI" will not match "said" or "again").
   */
  recordUsage(terms: string[], text: string): void {
    if (terms.length === 0 || text.trim().length === 0) return
    const now = Date.now()
    const update = this.db.prepare(
      'UPDATE dictionary_entries SET useCount = useCount + 1, lastUsedAtMs = ? WHERE term = ?'
    )
    this.transaction(() => {
      for (const term of terms) {
        if (!term || !matchesWholeTerm(term, text)) continue
        update.run(now, term)
      }
    })
  }
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/gu, '\\$&')
}

export function matchesWholeTerm(term: string, text: string): boolean {
  const trimmed = term.trim()
  if (trimmed.length === 0) return false
  const pattern = new RegExp(
    `(?<![\\p{L}\\p{N}_])${escapeRegExp(trimmed)}(?![\\p{L}\\p{N}_])`,
    'iu'
  )
  return pattern.test(text)
}

function optionalNumber(value: unknown): number | null {
  if (value === null || value === undefined) return null
  const n = Number(value)
  return Number.isFinite(n) ? n : null
}

function rowToRecord(row: Record<string, unknown>): DictationRecord {
  return {
    requestId: String(row.requestId),
    finalText: String(row.finalText),
    createdAtMs: Number(row.createdAtMs),
    zoneId: String(row.zoneId),
    wordCount: Number(row.wordCount),
    audioDurationMs: Number(row.audioDurationMs),
    asrModel: String(row.asrModel),
    polished: Number(row.polished) === 1,
    asrMs: Number(row.asrMs),
    polishMs: Number(row.polishMs),
    totalMs: Number(row.totalMs),
    deleted: false,
    pricingVersion: typeof row.pricingVersion === 'string' ? row.pricingVersion : null,
    inputTokens: optionalNumber(row.inputTokens),
    outputTokens: optionalNumber(row.outputTokens),
    asrNeurons: optionalNumber(row.asrNeurons),
    polishNeurons: optionalNumber(row.polishNeurons),
    totalNeurons: optionalNumber(row.totalNeurons),
    estimatedCostUsd: optionalNumber(row.estimatedCostUsd)
  }
}

function rowToEntry(row: Record<string, unknown>): DictionaryEntry {
  return {
    id: Number(row.id),
    term: String(row.term),
    normalizedTerm: String(row.normalizedTerm),
    status: String(row.status),
    source: String(row.source),
    createdAtMs: Number(row.createdAtMs),
    lastUsedAtMs: Number(row.lastUsedAtMs),
    useCount: Number(row.useCount)
  }
}

function rowToAnalytics(row: Record<string, unknown>): AnalyticsEvent {
  return {
    syncId: String(row.syncId),
    createdAtMs: Number(row.createdAtMs),
    zoneId: String(row.zoneId),
    audioDurationMs: Number(row.audioDurationMs),
    wordCount: Number(row.wordCount),
    processingMs: Number(row.processingMs),
    polished: Number(row.polished) === 1,
    corrected: Number(row.corrected) === 1,
    asrNeurons: Number(row.asrNeurons ?? 0),
    polishNeurons: Number(row.polishNeurons ?? 0),
    estimatedCostUsd: Number(row.estimatedCostUsd ?? 0)
  }
}

/** Mirrors the phone's cleanTerm: trimmed, whitespace-collapsed, 2..80 chars, no newlines. */
export function cleanTerm(value: string): string | null {
  const cleaned = value.trim().replace(/\s+/g, ' ')
  return cleaned.length >= 2 && cleaned.length <= 80 && !cleaned.includes('\n') ? cleaned : null
}

/** Mirrors the phone's normalize: NFKC then lowercase. */
export function normalize(value: string): string {
  return value.trim().normalize('NFKC').toLowerCase()
}

export function escapeLike(value: string): string {
  return value.replace(/\\/g, '\\\\').replace(/%/g, '\\%').replace(/_/g, '\\_')
}
