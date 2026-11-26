import Foundation
import CSQLite

/// Main-actor ownership serializes SQLite work. Transactions keep feed cursor and rows atomic.
@MainActor public final class RecordStore {
    private var database: OpaquePointer?
    private let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
    public struct Pending: Sendable { public let record: CloudRecord; public let revision: Int }
    public init(url: URL) throws {
        guard sqlite3_open(url.lastPathComponent == ":memory:" ? ":memory:" : url.path, &database) == SQLITE_OK else { throw failure() }
        try execute("PRAGMA journal_mode=WAL")
        try execute("PRAGMA busy_timeout=3000")
        try execute("CREATE TABLE IF NOT EXISTS records(owner TEXT NOT NULL, type TEXT NOT NULL, id TEXT NOT NULL, version INTEGER NOT NULL DEFAULT 0, deleted INTEGER NOT NULL DEFAULT 0, payload BLOB, dirty INTEGER NOT NULL DEFAULT 1, revision INTEGER NOT NULL DEFAULT 1, PRIMARY KEY(owner,type,id))")
        try execute("CREATE TABLE IF NOT EXISTS metadata(owner TEXT NOT NULL,key TEXT NOT NULL,value BLOB NOT NULL,PRIMARY KEY(owner,key))")
        let columns = try statement("PRAGMA table_info(records)")
        var hasHold = false
        while sqlite3_step(columns) == SQLITE_ROW {
            if String(cString: sqlite3_column_text(columns,1)) == "hold_until" { hasHold = true }
        }
        sqlite3_finalize(columns)
        if !hasHold { try execute("ALTER TABLE records ADD COLUMN hold_until REAL NOT NULL DEFAULT 0") }
    }
    isolated deinit { if let database { sqlite3_close(database) } }
    private func failure() -> APIError { APIError(code: "LOCAL_STORAGE", message: "Could not save your data on this device. Free some storage and try again.") }
    private func statement(_ sql: String) throws -> OpaquePointer {
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(database, sql, -1, &stmt, nil) == SQLITE_OK, let stmt else { throw failure() }
        return stmt
    }
    private func bind(_ values: [String], to stmt: OpaquePointer) {
        for (i, value) in values.enumerated() { sqlite3_bind_text(stmt, Int32(i+1), value, -1, transient) }
    }
    private func step(_ stmt: OpaquePointer) throws -> Bool {
        let status = sqlite3_step(stmt)
        guard status == SQLITE_ROW || status == SQLITE_DONE else { throw failure() }
        return status == SQLITE_ROW
    }
    private func execute(_ sql: String, strings: [String] = []) throws {
        let stmt = try statement(sql); defer { sqlite3_finalize(stmt) }; bind(strings, to: stmt)
        var status = sqlite3_step(stmt)
        while status == SQLITE_ROW { status = sqlite3_step(stmt) }
        guard status == SQLITE_DONE else { throw failure() }
    }
    public func transaction<T>(_ operation: () throws -> T) throws -> T {
        try execute("BEGIN IMMEDIATE")
        do { let value = try operation(); try execute("COMMIT"); return value }
        catch { try? execute("ROLLBACK"); throw error }
    }
    public func metadata<T: Decodable>(_ key: String, owner: String, default fallback: T) throws -> T {
        let stmt = try statement("SELECT value FROM metadata WHERE owner=? AND key=?")
        defer { sqlite3_finalize(stmt) }; bind([owner, key], to: stmt)
        guard try step(stmt) else { return fallback }
        return try JSONDecoder().decode(T.self, from: columnData(stmt, 0))
    }
    public func setMetadata<T: Encodable>(_ key: String, owner: String, value: T) throws {
        let stmt = try statement("INSERT INTO metadata(owner,key,value) VALUES(?,?,?) ON CONFLICT(owner,key) DO UPDATE SET value=excluded.value")
        defer { sqlite3_finalize(stmt) }; bind([owner,key], to: stmt)
        let data = try JSONEncoder().encode(value)
        _ = data.withUnsafeBytes { sqlite3_bind_blob(stmt, 3, $0.baseAddress, Int32($0.count), transient) }
        guard sqlite3_step(stmt) == SQLITE_DONE else { throw failure() }
    }
    public func records(owner: String, type: RecordType, includeDeleted: Bool = false) throws -> [CloudRecord] {
        let stmt = try statement("SELECT id,version,deleted,payload,revision FROM records WHERE owner=? AND type=? \(includeDeleted ? "" : "AND deleted=0") ORDER BY rowid DESC")
        defer { sqlite3_finalize(stmt) }; bind([owner, type.rawValue], to: stmt)
        var result: [CloudRecord] = []
        while try step(stmt) { result.append(try decode(stmt, type: type)) }
        return result
    }
    public func record(owner: String, type: RecordType, id: String) throws -> CloudRecord? {
        let stmt = try statement("SELECT id,version,deleted,payload,revision FROM records WHERE owner=? AND type=? AND id=?")
        defer { sqlite3_finalize(stmt) }; bind([owner,type.rawValue,id], to: stmt)
        return try step(stmt) ? decode(stmt, type: type) : nil
    }
    public func save(owner: String, record: CloudRecord, dirty: Bool = true) throws {
        let stmt = try statement("INSERT INTO records(owner,type,id,version,deleted,payload,dirty) VALUES(?,?,?,?,?,?,?) ON CONFLICT(owner,type,id) DO UPDATE SET version=excluded.version,deleted=excluded.deleted,payload=excluded.payload,dirty=excluded.dirty,revision=records.revision+1,hold_until=0")
        defer { sqlite3_finalize(stmt) }; bind([owner,record.type.rawValue,record.id], to: stmt)
        sqlite3_bind_int64(stmt, 4, Int64(record.version)); sqlite3_bind_int(stmt, 5, record.deleted ? 1 : 0)
        if let payload = record.payload {
            let data = try JSONEncoder().encode(payload)
            _ = data.withUnsafeBytes { sqlite3_bind_blob(stmt, 6, $0.baseAddress, Int32($0.count), transient) }
        } else { sqlite3_bind_null(stmt, 6) }
        sqlite3_bind_int(stmt, 7, dirty ? 1 : 0)
        guard sqlite3_step(stmt) == SQLITE_DONE else { throw failure() }
    }
    public func delete(owner: String, record: CloudRecord, holdUntil: Date? = nil) throws {
        var copy = record; copy.deleted = true; copy.payload = nil
        try save(owner: owner, record: copy)
        if let holdUntil { try hold(owner: owner, type: record.type, id: record.id, until: holdUntil) }
    }
    private func hold(owner: String, type: RecordType, id: String, until: Date) throws {
        let stmt = try statement("UPDATE records SET hold_until=? WHERE owner=? AND type=? AND id=?")
        defer { sqlite3_finalize(stmt) }
        sqlite3_bind_double(stmt,1,until.timeIntervalSince1970)
        for (index,value) in [owner,type.rawValue,id].enumerated() { sqlite3_bind_text(stmt,Int32(index+2),value,-1,transient) }
        guard sqlite3_step(stmt) == SQLITE_DONE else { throw failure() }
    }
    private func heldUntil(owner: String, type: RecordType, id: String) throws -> Date {
        let stmt = try statement("SELECT hold_until FROM records WHERE owner=? AND type=? AND id=?")
        defer { sqlite3_finalize(stmt) }; bind([owner,type.rawValue,id], to: stmt)
        return try step(stmt) ? Date(timeIntervalSince1970: sqlite3_column_double(stmt,0)) : .distantPast
    }
    public func restore(owner: String, original: CloudRecord) throws {
        let current = try record(owner: owner, type: original.type, id: original.id)
        var restored = original; restored.version = current?.version ?? original.version
        try save(owner: owner, record: restored)
    }
    public func pending(owner: String, historyEnabled: Bool, skipNewDictionary: Bool = false) throws -> [Pending] {
        let stmt = try statement("SELECT id,version,deleted,payload,revision,type FROM records WHERE owner=? AND dirty=1 AND hold_until<=? AND NOT(type='dictionary' AND deleted=0 AND json_extract(CAST(payload AS TEXT),'$.status')='suggested') \(historyEnabled ? "" : "AND type!='history'") \(skipNewDictionary ? "AND NOT(type='dictionary' AND version=0 AND deleted=0)" : "") ORDER BY deleted DESC,rowid LIMIT 100")
        defer { sqlite3_finalize(stmt) }; bind([owner], to: stmt)
        sqlite3_bind_double(stmt,2,Date.now.timeIntervalSince1970)
        var result: [Pending] = []
        while try step(stmt) {
            guard let type = RecordType(rawValue: String(cString: sqlite3_column_text(stmt, 5))) else { throw failure() }
            result.append(Pending(record: try decode(stmt, type: type), revision: Int(sqlite3_column_int64(stmt, 4))))
        }
        return result
    }
    public func settle(owner: String, sent: Pending, version: Int) throws {
        // A local edit while the upload was in flight stays dirty, rebased on the acknowledgement.
        let stmt = try statement("UPDATE records SET version=?,dirty=CASE WHEN revision=? THEN 0 ELSE 1 END WHERE owner=? AND type=? AND id=?")
        defer { sqlite3_finalize(stmt) }
        sqlite3_bind_int64(stmt, 1, Int64(version)); sqlite3_bind_int64(stmt, 2, Int64(sent.revision))
        for (i, value) in [owner, sent.record.type.rawValue, sent.record.id].enumerated() { sqlite3_bind_text(stmt, Int32(i+3), value, -1, transient) }
        guard sqlite3_step(stmt) == SQLITE_DONE else { throw failure() }
    }
    public func merge(owner: String, remote: CloudRecord) throws {
        let local = try record(owner: owner, type: remote.type, id: remote.id)
        if remote.version == 0, !remote.deleted, remote.payload == nil, var local {
            local.version = 0; try save(owner: owner, record: local); return
        }
        if let local, (local.deleted && !remote.deleted) || (!local.deleted && remote.deleted) {
            let pending = try isDirty(owner: owner, type: local.type, id: local.id)
            if pending {
                let held = try heldUntil(owner: owner, type: local.type, id: local.id)
                var rebased = local; rebased.version = remote.version
                try save(owner: owner, record: rebased)
                if held > .now { try hold(owner: owner, type: local.type, id: local.id, until: held) }
                return
            }
        }
        if local?.version == remote.version { return }
        try save(owner: owner, record: remote, dirty: false)
        if remote.type == .dictionary, !remote.deleted, let normalized = remote.payload?["normalizedTerm"]?.string {
            try coalesceDictionary(owner: owner, normalized: normalized)
        }
    }
    private func coalesceDictionary(owner: String, normalized: String) throws {
        let stmt = try statement("SELECT id,version,deleted,payload,revision FROM records WHERE owner=? AND type='dictionary' AND deleted=0 AND json_extract(CAST(payload AS TEXT),'$.normalizedTerm')=?")
        defer { sqlite3_finalize(stmt) }; bind([owner,normalized], to: stmt)
        var matches: [CloudRecord] = []
        while try step(stmt) { matches.append(try decode(stmt, type: .dictionary)) }
        guard matches.count > 1 else { return }
        matches.sort {
            if ($0.version > 0) != ($1.version > 0) { return $0.version > 0 }
            return $0.id < $1.id
        }
        var canonical = matches[0]
        let previous = canonical.payload
        if matches.contains(where: { $0.payload?["status"]?.string == "confirmed" }) { canonical.payload?["status"] = .string("confirmed") }
        for key in ["useCount", "lastUsedAtMs"] { canonical.payload?[key] = .number(matches.compactMap { $0.payload?[key]?.number }.max() ?? 0) }
        if canonical.payload != previous { try save(owner: owner, record: canonical, dirty: true) }
        for duplicate in matches.dropFirst() { try delete(owner: owner, record: duplicate) }
    }
    public func isDirty(owner: String, type: RecordType, id: String) throws -> Bool {
        let stmt = try statement("SELECT dirty FROM records WHERE owner=? AND type=? AND id=?")
        defer { sqlite3_finalize(stmt) }; bind([owner,type.rawValue,id], to: stmt)
        return try step(stmt) && sqlite3_column_int(stmt,0) == 1
    }
    public func applySettings(owner: String, settings: CloudSettings) throws {
        try setMetadata("cloudSettings", owner: owner, value: settings)
        if !settings.historySyncEnabled {
            try execute("DELETE FROM records WHERE owner=? AND type='history' AND deleted=1", strings: [owner])
            try execute("UPDATE records SET version=0,dirty=1 WHERE owner=? AND type='history' AND deleted=0", strings: [owner])
        }
        try applyRetention(owner: owner, days: settings.historyRetentionDays)
    }
    public func applyRetention(owner: String, days: Int?, now: Date = .now) throws {
        guard let days, days > 0 else { return }
        let cutoff = now.addingTimeInterval(-Double(days) * 86400).timeIntervalSince1970 * 1000
        for record in try records(owner: owner, type: .history) where (record.payload?["createdAtMs"]?.number ?? 0) < cutoff {
            // The server applies the current cloud policy. A cached offline
            // policy only expires this device's copy, not another device's data.
            try execute("DELETE FROM records WHERE owner=? AND type='history' AND id=?", strings: [owner, record.id])
        }
    }
    public func removeAll(owner: String) throws {
        try transaction { try execute("DELETE FROM records WHERE owner=?", strings: [owner]); try execute("DELETE FROM metadata WHERE owner=?", strings: [owner]) }
    }
    public func removeAllAccounts() throws {
        try transaction { try execute("DELETE FROM records"); try execute("DELETE FROM metadata") }
    }
    private func columnData(_ stmt: OpaquePointer, _ column: Int32) -> Data {
        guard let bytes = sqlite3_column_blob(stmt, column) else { return Data() }
        return Data(bytes: bytes, count: Int(sqlite3_column_bytes(stmt, column)))
    }
    private func decode(_ stmt: OpaquePointer, type: RecordType) throws -> CloudRecord {
        let payload: Payload? = sqlite3_column_type(stmt, 3) == SQLITE_NULL ? nil : try JSONDecoder().decode(Payload.self, from: columnData(stmt, 3))
        return CloudRecord(id: String(cString: sqlite3_column_text(stmt, 0)), type: type,
                           version: Int(sqlite3_column_int64(stmt,1)), deleted: sqlite3_column_int(stmt,2) == 1, payload: payload)
    }
}
