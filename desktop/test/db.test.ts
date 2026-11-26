import { strict as assert } from 'node:assert'
import { test, describe, beforeEach } from 'node:test'
import { mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { DatabaseSync } from 'node:sqlite'
import {
  openDatabase,
  cleanTerm,
  normalize,
  escapeLike,
  type DictationRecord
} from '../src/main/db'

function makeRecord(overrides: Partial<DictationRecord> = {}): DictationRecord {
  return {
    requestId: 'req-1',
    finalText: 'Hello from the desktop.',
    createdAtMs: Date.now(),
    zoneId: 'Asia/Shanghai',
    wordCount: 4,
    audioDurationMs: 2000,
    asrModel: 'whisper-large-v3-turbo',
    polished: true,
    asrMs: 500,
    polishMs: 300,
    totalMs: 800,
    deleted: false,
    ...overrides
  }
}

describe('dictation records', () => {
  let db: ReturnType<typeof openDatabase>
  beforeEach(() => {
    db = openDatabase(join(mkdtempSync(join(tmpdir(), 'wovoice-')), 'test.db'))
  })

  test('inserts and lists records newest-first', () => {
    db.insertRecord(makeRecord({ requestId: 'a', createdAtMs: 100 }))
    db.insertRecord(makeRecord({ requestId: 'b', createdAtMs: 200 }))
    const all = db.historySearch('')
    assert.equal(all.length, 2)
    assert.equal(all[0].requestId, 'b')
  })

  test('ignores duplicate requestIds (idempotent insert)', () => {
    db.insertRecord(makeRecord({ requestId: 'a', finalText: 'one' }))
    db.insertRecord(makeRecord({ requestId: 'a', finalText: 'two' }))
    assert.equal(db.historySearch('').length, 1)
    assert.equal(db.historySearch('')[0].finalText, 'one')
  })

  test('searches by substring with wildcards escaped', () => {
    db.insertRecord(makeRecord({ requestId: 'a', finalText: 'cost 100% today' }))
    db.insertRecord(makeRecord({ requestId: 'b', finalText: 'plain text' }))
    assert.equal(db.historySearch('100%').length, 1)
    assert.equal(db.historySearch('100').length, 1)
    assert.equal(db.historySearch('missing').length, 0)
  })

  test('soft delete hides and restore brings back', () => {
    db.insertRecord(makeRecord())
    db.deleteRecord('req-1')
    assert.equal(db.historySearch('').length, 0)
    db.restoreRecord('req-1')
    assert.equal(db.historySearch('').length, 1)
  })

  test('stats aggregate words and duration with recent list', () => {
    db.insertRecord(
      makeRecord({ requestId: 'a', wordCount: 10, audioDurationMs: 20_000, createdAtMs: 1_000 })
    )
    db.insertRecord(
      makeRecord({ requestId: 'b', wordCount: 30, audioDurationMs: 40_000, createdAtMs: 2_000 })
    )
    const stats = db.stats(0)
    assert.equal(stats.dictations, 2)
    assert.equal(stats.words, 40)
    assert.equal(stats.audioDurationMs, 60_000)
    assert.equal(stats.recent.length, 2)
    assert.equal(stats.recent[0].requestId, 'b')
  })

  test('stats exclude deleted records', () => {
    db.insertRecord(makeRecord({ requestId: 'a', wordCount: 10 }))
    db.deleteRecord('a')
    const stats = db.stats(0)
    assert.equal(stats.dictations, 0)
  })
})

describe('dictionary', () => {
  let db: ReturnType<typeof openDatabase>
  beforeEach(() => {
    db = openDatabase(':memory:')
  })

  test('adds, dedupes case-insensitively, and validates length', () => {
    assert.equal(db.addTerm('Alia Had'), true)
    assert.equal(db.addTerm('alia had'), false)
    assert.equal(db.addTerm('x'), false)
    assert.equal(db.addTerm('a'.repeat(81)), false)
    assert.equal(db.listTerms('').length, 1)
  })

  test('glossary ranks manual over learned, then usage', () => {
    db.addTerm('Zebra')
    db.addTerm('Alia Had')
    db.recordUsage(['Alia Had', 'Alia Had', 'Alia Had'], 'Hello Alia Had')
    const glossary = db.bestGlossary(10)
    // manual rank is equal; higher useCount first
    assert.equal(glossary[0], 'Alia Had')
  })

  test('deletes terms', () => {
    db.addTerm('WoVoice')
    const [entry] = db.listTerms('')
    db.deleteTerm(entry.id)
    assert.equal(db.listTerms('').length, 0)
  })

  test('recordUsage only bumps terms present as whole words (AI does not match said/again)', () => {
    db.addTerm('Alpha')
    db.addTerm('Beta')
    db.addTerm('AI')
    db.recordUsage(['Alpha', 'Beta', 'AI'], 'alpha said again here')
    let entries = db.listTerms('')
    assert.equal(entries.find((e) => e.term === 'Alpha')?.useCount, 1)
    assert.equal(entries.find((e) => e.term === 'Beta')?.useCount, 0)
    assert.equal(entries.find((e) => e.term === 'AI')?.useCount, 0)

    db.recordUsage(['AI'], 'Using AI tools today.')
    entries = db.listTerms('')
    assert.equal(entries.find((e) => e.term === 'AI')?.useCount, 1)
  })

  test('addTerm promotes a suggested entry synced from mobile to confirmed', () => {
    db.upsertRemoteDictionary(
      {
        term: 'Koramangala',
        normalizedTerm: 'koramangala',
        status: 'suggested',
        source: 'learned',
        createdAtMs: 1,
        lastUsedAtMs: 1,
        useCount: 0
      },
      'sync-sugg-1',
      2
    )
    assert.equal(db.listTerms('').length, 0)
    assert.equal(db.addTerm('Koramangala'), true)
    const terms = db.listTerms('')
    assert.equal(terms.length, 1)
    assert.equal(terms[0].status, 'confirmed')
    assert.equal(db.dictionaryNeedingSync().length, 1)
  })

  test('editTerm renames, rejects duplicates, and marks local with incremented revision', () => {
    db.addTerm('K8s')
    db.addTerm('Docker')
    const [k8s] = db.listTerms('K8s')
    db.markDictionarySynced(db.dictionaryNeedingSync()[0].syncId, 1)

    assert.equal(db.editTerm(k8s.id, 'docker'), false)
    assert.equal(db.editTerm(k8s.id, 'Kubernetes'), true)
    assert.equal(db.listTerms('Kubernetes').length, 1)
    const pending = db.dictionaryNeedingSync()
    assert.equal(
      pending.some((entry) => entry.term === 'Kubernetes'),
      true
    )
  })

  test('upsertRemoteHistory resets deleted = 0 and updates metadata', () => {
    db.insertRecord(makeRecord({ requestId: 'r-del', finalText: 'old', wordCount: 1 }))
    db.markHistorySynced('r-del', 1)
    db.deleteRecord('r-del')
    assert.equal(db.historySearch('').length, 0)

    db.upsertRemoteHistory(
      makeRecord({ requestId: 'r-del', finalText: 'restored remote text', wordCount: 3 }),
      2
    )
    const rows = db.historySearch('')
    assert.equal(rows.length, 1)
    assert.equal(rows[0].finalText, 'restored remote text')
    assert.equal(rows[0].wordCount, 3)
  })

  test('clearHistory skips tombstones for unsynced rows, queues for synced rows, and preserves analytics stats', () => {
    db.insertRecord(makeRecord({ requestId: 'unsynced', wordCount: 5, audioDurationMs: 3_000 }))
    db.insertRecord(makeRecord({ requestId: 'synced', wordCount: 7, audioDurationMs: 4_000 }))
    db.insertAnalyticsEvent({
      syncId: 'synced',
      createdAtMs: Date.now(),
      zoneId: 'UTC',
      audioDurationMs: 4_000,
      wordCount: 7,
      processingMs: 500,
      polished: true,
      corrected: false,
      asrNeurons: 1,
      polishNeurons: 0.2,
      estimatedCostUsd: 0.0001
    })
    db.markHistorySynced('synced', 1)

    db.clearHistory()
    assert.equal(db.historySearch('').length, 0)
    const tombstones = db.tombstones()
    assert.deepEqual(
      tombstones.map((t) => t.recordId),
      ['synced']
    )
    // Analytics stats survive clearHistory!
    const stats = db.stats(0)
    assert.equal(stats.dictations, 1)
    assert.equal(stats.words, 7)
    assert.equal(stats.audioDurationMs, 4_000)
  })

  test('deleteRecord holdUntilMs delays tombstone eligibility during Undo window', () => {
    db.insertRecord(makeRecord({ requestId: 'r-hold' }))
    db.markHistorySynced('r-hold', 1)
    const now = Date.now()
    db.deleteRecord('r-hold', 6_000)
    assert.equal(db.tombstones(now).length, 0)
    assert.equal(db.tombstones(now + 7_000).length, 1)
  })

  test('returnQueuedToLocal recovers stuck queued rows and exportHistory formats txt & json', () => {
    db.insertRecord(makeRecord({ requestId: 'q1', finalText: 'Queued text', wordCount: 2 }))
    const [pending] = db.historyNeedingSync()
    db.markQueued([{ type: 'history', id: pending.requestId, revision: pending.localRevision }])
    assert.equal(db.historyNeedingSync().length, 0)

    db.returnQueuedToLocal('acct-1')
    assert.equal(db.historyNeedingSync().length, 1)

    const txt = db.exportHistory('txt')
    assert.ok(txt.includes('Queued text'))
    const parsed = JSON.parse(db.exportHistory('json')) as Array<{ requestId: string }>
    assert.equal(parsed[0].requestId, 'q1')
  })

  test('revision-checked markDictionarySynced preserves local state if edited during upload', () => {
    db.addTerm('OldName')
    const [pending] = db.dictionaryNeedingSync()
    db.markQueued([{ type: 'dictionary', id: pending.syncId, revision: pending.localRevision }])
    assert.equal(db.editTerm(pending.id, 'NewName'), true)
    // Stale upload settles with old expectedRevision: syncVersion advances to 1,
    // but syncState stays 'local' so NewName will still be uploaded!
    db.markDictionarySynced(pending.syncId, 1, pending.localRevision)
    const remaining = db.dictionaryNeedingSync()
    assert.equal(remaining.length, 1)
    assert.equal(remaining[0].term, 'NewName')
    assert.equal(remaining[0].syncVersion, 1)
  })
})

describe('schema migration', () => {
  test('upgrades a database created before sync existed', () => {
    const path = join(mkdtempSync(join(tmpdir(), 'wovoice-')), 'legacy.db')
    const legacy = new DatabaseSync(path)
    // The pre-sync (E5) shape: dictionary_entries had no syncId.
    legacy.exec(`
      CREATE TABLE dictation_records (
        requestId TEXT PRIMARY KEY, finalText TEXT NOT NULL, createdAtMs INTEGER NOT NULL,
        zoneId TEXT NOT NULL, wordCount INTEGER NOT NULL, audioDurationMs INTEGER NOT NULL,
        asrModel TEXT NOT NULL, polished INTEGER NOT NULL, asrMs INTEGER NOT NULL DEFAULT 0,
        polishMs INTEGER NOT NULL DEFAULT 0, totalMs INTEGER NOT NULL DEFAULT 0,
        deleted INTEGER NOT NULL DEFAULT 0, syncState TEXT NOT NULL DEFAULT 'local',
        syncVersion INTEGER NOT NULL DEFAULT 0
      );
      CREATE TABLE dictionary_entries (
        id INTEGER PRIMARY KEY AUTOINCREMENT, term TEXT NOT NULL,
        normalizedTerm TEXT NOT NULL UNIQUE, status TEXT NOT NULL DEFAULT 'confirmed',
        source TEXT NOT NULL DEFAULT 'manual', createdAtMs INTEGER NOT NULL,
        lastUsedAtMs INTEGER NOT NULL, useCount INTEGER NOT NULL DEFAULT 0,
        syncState TEXT NOT NULL DEFAULT 'local', syncVersion INTEGER NOT NULL DEFAULT 0
      );
      INSERT INTO dictionary_entries (term, normalizedTerm, createdAtMs, lastUsedAtMs)
        VALUES ('Rahim', 'rahim', 1, 1), ('Dhaka', 'dhaka', 1, 1);
    `)
    legacy.close()

    const db = openDatabase(path)
    const pending = db.dictionaryNeedingSync()
    assert.equal(pending.length, 2)
    assert.equal(new Set(pending.map((entry) => entry.syncId)).size, 2)
    assert.equal(db.addTerm('Chattogram'), true)
    assert.equal(db.dictionaryNeedingSync().length, 3)
    // Reopening an upgraded database is a no-op.
    assert.equal(openDatabase(path).listTerms('').length, 3)
  })
})

describe('term helpers', () => {
  test('cleanTerm mirrors the phone rules', () => {
    assert.equal(cleanTerm('  Hello   World  '), 'Hello World')
    assert.equal(cleanTerm('a'), null)
    // The phone collapses whitespace before its newline check, so embedded
    // newlines become spaces rather than a rejection — mirror that exactly.
    assert.equal(cleanTerm('line\nbreak'), 'line break')
    assert.equal(cleanTerm('a'.repeat(81)), null)
  })

  test('normalize is NFKC + lowercase', () => {
    assert.equal(normalize('Ｃａｆé'), 'café')
    assert.equal(normalize('  ABC  '), 'abc')
  })

  test('escapeLike escapes wildcard characters', () => {
    assert.equal(escapeLike('100%_done'), '100\\%\\_done')
  })
})
