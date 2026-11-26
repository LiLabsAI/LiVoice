import { strict as assert } from 'node:assert'
import { describe, test } from 'node:test'
import { existsSync, mkdtempSync, statSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { SessionStore, type TokenStorage } from '../src/main/session'
import { WorkerError } from '../src/main/worker'

function fakeStorage(): TokenStorage & { decryptCalls: number } {
  const storage = {
    decryptCalls: 0,
    isEncryptionAvailable: () => true,
    encryptString: (plainText: string) => Buffer.from(`sealed:${plainText}`, 'utf-8'),
    decryptString: (encrypted: Buffer) => {
      storage.decryptCalls++
      const text = encrypted.toString('utf-8')
      if (!text.startsWith('sealed:')) throw new Error('corrupt')
      return text.slice('sealed:'.length)
    }
  }
  return storage
}

describe('SessionStore', () => {
  test('in-flight refresh resolving after clear() never resurrects a signed-out session', async () => {
    const dir = mkdtempSync(join(tmpdir(), 'livoice-sess-'))
    const storage = fakeStorage()
    let resolveRefresh!: (value: {
      accessToken: string
      accessExpiresInSeconds: number
      refreshToken: string
    }) => void
    const pendingRefresh = new Promise<{
      accessToken: string
      accessExpiresInSeconds: number
      refreshToken: string
    }>((resolve) => {
      resolveRefresh = resolve
    })

    let invalidCalls = 0
    const store = new SessionStore(
      () => pendingRefresh,
      () => invalidCalls++,
      dir,
      storage
    )

    store.storeTokens('old-access', 0, 'old-refresh')
    assert.equal(statSync(join(dir, 'refresh-token.bin')).mode & 0o777, 0o600)

    const accessPromise = store.accessToken()
    store.clear()
    assert.equal(store.hasRefreshToken, false)
    assert.equal(existsSync(join(dir, 'refresh-token.bin')), false)
    assert.equal(invalidCalls, 1)

    resolveRefresh({
      accessToken: 'zombie-access',
      accessExpiresInSeconds: 900,
      refreshToken: 'zombie-refresh'
    })

    await assert.rejects(accessPromise, (error: unknown) => {
      return error instanceof WorkerError && error.code === 'AUTH_REQUIRED'
    })
    assert.equal(store.hasRefreshToken, false)
    assert.equal(store.validAccessToken, null)
    assert.equal(existsSync(join(dir, 'refresh-token.bin')), false)
  })

  test('in-flight refresh never overwrites tokens from a newer sign-in', async () => {
    const dir = mkdtempSync(join(tmpdir(), 'livoice-sess-'))
    const storage = fakeStorage()
    let resolveRefresh!: (value: {
      accessToken: string
      accessExpiresInSeconds: number
      refreshToken: string
    }) => void
    const pendingRefresh = new Promise<{
      accessToken: string
      accessExpiresInSeconds: number
      refreshToken: string
    }>((resolve) => {
      resolveRefresh = resolve
    })

    const store = new SessionStore(
      () => pendingRefresh,
      () => undefined,
      dir,
      storage
    )
    store.storeTokens('expired-access', 0, 'old-refresh')

    const inflight = store.accessToken()
    // New sign-in occurs while the old refresh is still in flight
    store.storeTokens('fresh-access', 900, 'fresh-refresh')

    resolveRefresh({
      accessToken: 'stale-access',
      accessExpiresInSeconds: 900,
      refreshToken: 'stale-refresh'
    })

    const resolved = await inflight
    assert.equal(resolved, 'fresh-access')
    assert.equal(store.validAccessToken, 'fresh-access')
    assert.equal(store.peekRefreshToken(), 'fresh-refresh')
  })

  test('caches hasRefreshToken in memory without repeated Keychain decrypts', () => {
    const dir = mkdtempSync(join(tmpdir(), 'livoice-sess-'))
    const storage = fakeStorage()
    const writer = new SessionStore(
      async () => ({ accessToken: 'a', accessExpiresInSeconds: 900, refreshToken: 'r' }),
      () => undefined,
      dir,
      storage
    )
    writer.storeTokens('a', 900, 'r')

    const reader = new SessionStore(
      async () => ({ accessToken: 'a', accessExpiresInSeconds: 900, refreshToken: 'r' }),
      () => undefined,
      dir,
      storage
    )
    assert.equal(reader.hasRefreshToken, true)
    assert.equal(reader.hasRefreshToken, true)
    assert.equal(reader.hasRefreshToken, true)
    assert.equal(storage.decryptCalls, 1)
  })
})
