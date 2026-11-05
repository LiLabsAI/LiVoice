# Client/server contracts

`ios-sync-v2.json` contains invented history, vocabulary and analytics records.
The history and analytics intentionally share an ID, as they do in iOS; record
identity includes the type. Analytics contains no dictated text.

The Swift `syncPayloadsMatchTheWorkerContract` test constructs these records with
the production payload builders and wire encoder. It checks the current time
zone before normalizing that field to UTC for the shared example. The Worker
test submits the same JSON through its real authenticated handler, D1 storage
and encryption, then verifies pull, vocabulary editing and deletion. Android's
`SyncCoordinatorTest` imports the same records into an in-memory Room database,
checks history/vocabulary/analytics, and applies remote edits and tombstones.
These tests run locally without production credentials or audio.

When the sync format changes, update the fixture and both sides together. Run
`swift test --package-path ios/Core` and
`./gradlew :shared:test --tests 'com.aliahad.wovoice.sync.SyncCoordinatorTest'`
from the repository root, plus `npm test` from `worker/`. The iOS CI path filter
includes contract changes.
