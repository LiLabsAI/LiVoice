import { strict as assert } from 'node:assert'
import { describe, test } from 'node:test'
import { mkdtempSync, statSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { DEFAULT_WORKER_URL, sanitizeWorkerUrl, SettingsStore } from '../src/main/store'

describe('SettingsStore', () => {
  test('validates workerUrl is https:// and falls back safely', () => {
    assert.equal(sanitizeWorkerUrl('https://custom.example.com/'), 'https://custom.example.com')
    assert.equal(sanitizeWorkerUrl('http://insecure.example.com'), DEFAULT_WORKER_URL)
    assert.equal(sanitizeWorkerUrl('javascript:alert(1)'), DEFAULT_WORKER_URL)
    assert.equal(sanitizeWorkerUrl('not a url'), DEFAULT_WORKER_URL)
  })

  test('persists atomically with 0600 permissions and stores new settings & accountStatus', () => {
    const dir = mkdtempSync(join(tmpdir(), 'livoice-store-'))
    writeFileSync(join(dir, 'settings.json'), JSON.stringify({ workerUrl: 'http://evil.local' }))

    const store = new SettingsStore(dir)
    assert.equal(store.workerUrl, DEFAULT_WORKER_URL)
    assert.equal(store.locale, 'en-IN')
    assert.equal(store.polish, 'light')
    assert.equal(store.soundsEnabled, true)
    assert.equal(store.startHidden, false)

    store.locale = 'auto'
    store.polish = 'strong'
    store.soundsEnabled = false
    store.startHidden = true
    store.micDeviceId = 'mic-usb-1'
    store.syncWarning = 'More items waiting'
    store.applyAccountUser({
      id: 'u1',
      email: 'u@example.com',
      role: 'user',
      accountStatus: {
        state: 'suspended',
        suspendedUntil: 1_900_000_000_000,
        publicMessage: 'Under review',
        supportEmail: 'help@example.com'
      }
    })

    assert.equal(statSync(join(dir, 'settings.json')).mode & 0o777, 0o600)

    const reloaded = new SettingsStore(dir)
    assert.equal(reloaded.locale, 'auto')
    assert.equal(reloaded.polish, 'strong')
    assert.equal(reloaded.soundsEnabled, false)
    assert.equal(reloaded.startHidden, true)
    assert.equal(reloaded.micDeviceId, 'mic-usb-1')
    assert.equal(reloaded.syncWarning, 'More items waiting')
    assert.equal(reloaded.accountState, 'suspended')
    assert.equal(reloaded.accountPublicMessage, 'Under review')
    assert.equal(reloaded.accountSuspendedUntil, 1_900_000_000_000)
    assert.equal(reloaded.accountSupportEmail, 'help@example.com')

    reloaded.clearAccount()
    assert.equal(reloaded.accountState, 'active')
    assert.equal(reloaded.accountPublicMessage, null)
    assert.equal(reloaded.accountSuspendedUntil, null)
    assert.equal(reloaded.syncWarning, null)
  })
})
