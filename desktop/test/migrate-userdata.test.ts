import { strict as assert } from 'node:assert'
import { describe, test } from 'node:test'
import { existsSync, mkdirSync, mkdtempSync, readFileSync, statSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { migrateLegacyUserData } from '../src/main/migrate-userdata'

describe('migrateLegacyUserData', () => {
  test('fresh install with no legacy WoVoice directory is a no-op', () => {
    const root = mkdtempSync(join(tmpdir(), 'livoice-mig-'))
    const current = join(root, 'LiVoice')
    const legacy = join(root, 'WoVoice')
    const result = migrateLegacyUserData(current, legacy)
    assert.deepEqual(result, { migrated: [], skipped: [] })
    assert.equal(existsSync(current), false)
  })

  test('copies WoVoice files into LiVoice on upgrade and tightens private file permissions', () => {
    const root = mkdtempSync(join(tmpdir(), 'livoice-mig-'))
    const current = join(root, 'LiVoice')
    const legacy = join(root, 'WoVoice')
    mkdirSync(legacy, { recursive: true })
    writeFileSync(join(legacy, 'settings.json'), JSON.stringify({ accountEmail: 'u@example.com' }))
    writeFileSync(join(legacy, 'wovoice-desktop.db'), 'sqlite-bytes')
    writeFileSync(join(legacy, 'refresh-token.bin'), 'sealed-token')
    writeFileSync(join(legacy, 'unrelated.txt'), 'ignore-me')

    const result = migrateLegacyUserData(current, legacy)
    assert.deepEqual(result.migrated.sort(), [
      'refresh-token.bin',
      'settings.json',
      'wovoice-desktop.db'
    ])
    assert.equal(result.skipped.length, 0)
    assert.equal(
      JSON.parse(readFileSync(join(current, 'settings.json'), 'utf-8')).accountEmail,
      'u@example.com'
    )
    assert.equal(readFileSync(join(current, 'wovoice-desktop.db'), 'utf-8'), 'sqlite-bytes')
    assert.equal(existsSync(join(current, 'unrelated.txt')), false)
    assert.equal(statSync(join(current, 'refresh-token.bin')).mode & 0o777, 0o600)
    assert.equal(statSync(join(current, 'settings.json')).mode & 0o777, 0o600)

    // Idempotent on second launch
    const second = migrateLegacyUserData(current, legacy)
    assert.deepEqual(second, { migrated: [], skipped: [] })
  })

  test('never overwrites an existing LiVoice directory that already has state', () => {
    const root = mkdtempSync(join(tmpdir(), 'livoice-mig-'))
    const current = join(root, 'LiVoice')
    const legacy = join(root, 'WoVoice')
    mkdirSync(legacy, { recursive: true })
    mkdirSync(current, { recursive: true })
    writeFileSync(
      join(legacy, 'settings.json'),
      JSON.stringify({ accountEmail: 'old@example.com' })
    )
    writeFileSync(join(legacy, 'refresh-token.bin'), 'old-token')
    writeFileSync(
      join(current, 'settings.json'),
      JSON.stringify({ accountEmail: 'new@example.com' })
    )

    const result = migrateLegacyUserData(current, legacy)
    assert.deepEqual(result, { migrated: [], skipped: [] })
    assert.equal(
      JSON.parse(readFileSync(join(current, 'settings.json'), 'utf-8')).accountEmail,
      'new@example.com'
    )
    assert.equal(existsSync(join(current, 'refresh-token.bin')), false)
  })
})
