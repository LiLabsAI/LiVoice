import Foundation
import WoVoiceCore

final class SyncService {
    private let account: AccountSession
    private let store: RecordStore
    private var running: Task<Void, any Error>?
    init(account: AccountSession, store: RecordStore) { self.account = account; self.store = store }
    func run() async throws {
        if let running { return try await running.value }
        let task = Task { try await perform() }
        running = task
        defer { running = nil }
        try await task.value
    }
    private func perform() async throws {
        guard let owner = account.user?.id, account.cloudServicesAllowed else { return }
        func checkOwner() throws { guard account.user?.id == owner else { throw CancellationError() } }
        while true {
            try checkOwner()
            let cursor = try store.metadata("cursor", owner: owner, default: 0)
            let page = try await account.call { try await account.api.pull(token: $0, cursor: cursor) }
            try checkOwner()
            try store.transaction {
                if let settings = page.settings { try store.applySettings(owner: owner, settings: settings) }
                let settings = try store.metadata("cloudSettings", owner: owner, default: CloudSettings())
                for record in page.items where record.type != .history || settings.historySyncEnabled || record.deleted {
                    try store.merge(owner: owner, remote: record)
                }
                // A feed can still contain expired history before server cleanup.
                try store.applyRetention(owner: owner, days: settings.historyRetentionDays)
                try store.setMetadata("cursor", owner: owner, value: page.nextCursor)
            }
            if !page.hasMore { break }
            guard page.nextCursor > cursor else { throw APIError(code: "SYNC_CURSOR", message: "Sync could not advance. Please try again.", retryable: true) }
        }
        var conflicts = 0
        var skipNewDictionary = false
        var warning = ""
        for _ in 0..<20 {
            try checkOwner()
            let settings = try store.metadata("cloudSettings", owner: owner, default: CloudSettings())
            let pending = try store.pending(owner: owner, historyEnabled: settings.historySyncEnabled, skipNewDictionary: skipNewDictionary)
            if pending.isEmpty { break }
            do {
                let result = try await account.call { try await account.api.push(token: $0, writes: pending.map { .init(record: $0.record) }) }
                try checkOwner()
                try store.transaction {
                    for applied in result.applied {
                        if let sent = pending.first(where: { $0.record.id == applied.id && $0.record.type == applied.type }) {
                            try store.settle(owner: owner, sent: sent, version: applied.version)
                        }
                    }
                }
            } catch let error as APIError where error.code == "SYNC_CONFLICT" && !error.conflicts.isEmpty {
                try checkOwner(); conflicts += 1
                guard conflicts <= 3 else { throw APIError(code: "SYNC_CONFLICT", message: "Changes were merged. Sync again to finish.", retryable: true) }
                try store.transaction { for record in error.conflicts { try store.merge(owner: owner, remote: record) } }
            } catch let error as APIError where error.code == "HISTORY_SYNC_DISABLED" {
                try checkOwner()
                var settings = settings; settings.historySyncEnabled = false
                try store.applySettings(owner: owner, settings: settings)
            } catch let error as APIError where error.code == "STORAGE_LIMIT_REACHED" {
                try checkOwner()
                if skipNewDictionary { throw error }
                skipNewDictionary = true
                warning = error.message
            }
        }
        try checkOwner()
        let settings = try store.metadata("cloudSettings", owner: owner, default: CloudSettings())
        if try !store.pending(owner: owner, historyEnabled: settings.historySyncEnabled, skipNewDictionary: skipNewDictionary).isEmpty {
            warning = [warning, "Some changes are still waiting. Tap Sync now to continue."].filter { !$0.isEmpty }.joined(separator: " ")
        }
        try store.transaction {
            try store.setMetadata("syncWarning", owner: owner, value: warning)
            try store.setMetadata("lastSync", owner: owner, value: Date.now)
        }
    }
}
