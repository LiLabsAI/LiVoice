import { strict as assert } from 'node:assert'
import { describe, test } from 'node:test'
import { TriggerEngine, type Trigger } from '../src/main/triggers'

describe('TriggerEngine', () => {
  test('hold >= 300ms starts on keydown and stops on keyup without latching', () => {
    let time = 1_000
    const events: string[] = []
    const engine = new TriggerEngine(
      { keyboardShortcutEnabled: true, middleClickEnabled: false, triggerKey: 'option' },
      (trigger: Trigger) => events.push(`start:${trigger}`),
      (trigger: Trigger) => events.push(`stop:${trigger}`),
      {
        now: () => time,
        onLatch: (hint) => events.push(`latch:${hint}`),
        onCancel: () => events.push('cancel')
      }
    )

    engine.processLine('k 58 1')
    time += 450
    engine.processLine('k 58 0')
    assert.deepEqual(events, ['start:keyboard', 'stop:keyboard'])
  })

  test('quick tap (< 300ms) enters latch mode and second tap stops cleanly', () => {
    let time = 1_000
    const events: string[] = []
    const engine = new TriggerEngine(
      { keyboardShortcutEnabled: true, middleClickEnabled: false, triggerKey: 'option' },
      (trigger) => events.push(`start:${trigger}`),
      (trigger) => events.push(`stop:${trigger}`),
      {
        now: () => time,
        onLatch: (hint) => events.push(`latch:${hint}`)
      }
    )

    // Quick tap starts + latches
    engine.processLine('k 58 1')
    time += 120
    engine.processLine('k 58 0')
    assert.deepEqual(events, ['start:keyboard', 'latch:Tap ⌥ again'])

    // Second tap stops without re-latching on release
    time += 500
    engine.processLine('k 58 1')
    time += 80
    engine.processLine('k 58 0')
    assert.deepEqual(events, ['start:keyboard', 'latch:Tap ⌥ again', 'stop:keyboard'])
  })

  test('shortcut combo (e.g. Opt+Left) cancels active hold and never enters phantom latch', () => {
    let time = 1_000
    const events: string[] = []
    const engine = new TriggerEngine(
      { keyboardShortcutEnabled: true, middleClickEnabled: false, triggerKey: 'option' },
      (trigger) => events.push(`start:${trigger}`),
      (trigger) => events.push(`stop:${trigger}`),
      {
        now: () => time,
        onCancel: () => events.push('cancel'),
        onLatch: (hint) => events.push(`latch:${hint}`)
      }
    )

    engine.processLine('k 58 1')
    time += 50
    engine.processLine('c 123 1')
    time += 50
    engine.processLine('k 58 0')
    assert.deepEqual(events, ['start:keyboard', 'cancel'])

    // Next real hold still works normally
    time += 200
    engine.processLine('k 58 1')
    time += 400
    engine.processLine('k 58 0')
    assert.deepEqual(events, ['start:keyboard', 'cancel', 'start:keyboard', 'stop:keyboard'])
  })

  test('trigger press while dictation is busy (processing) is ignored and never latches', () => {
    let time = 1_000
    let busy = true
    const events: string[] = []
    const engine = new TriggerEngine(
      { keyboardShortcutEnabled: true, middleClickEnabled: false, triggerKey: 'option' },
      (trigger) => events.push(`start:${trigger}`),
      (trigger) => events.push(`stop:${trigger}`),
      {
        now: () => time,
        isBusy: () => busy,
        onLatch: (hint) => events.push(`latch:${hint}`)
      }
    )

    engine.processLine('k 58 1')
    time += 100
    engine.processLine('k 58 0')
    assert.deepEqual(events, [])

    busy = false
    time += 200
    engine.processLine('k 58 1')
    time += 400
    engine.processLine('k 58 0')
    assert.deepEqual(events, ['start:keyboard', 'stop:keyboard'])
  })

  test('middle-click release cannot stop a keyboard-initiated dictation and vice versa', () => {
    let time = 1_000
    const events: string[] = []
    const engine = new TriggerEngine(
      { keyboardShortcutEnabled: true, middleClickEnabled: true, triggerKey: 'option' },
      (trigger) => events.push(`start:${trigger}`),
      (trigger) => events.push(`stop:${trigger}`),
      { now: () => time }
    )

    engine.processLine('k 58 1')
    engine.processLine('m 2 1')
    engine.processLine('m 2 0')
    assert.deepEqual(events, ['start:keyboard'])

    time += 400
    engine.processLine('k 58 0')
    assert.deepEqual(events, ['start:keyboard', 'stop:keyboard'])

    // Now start with middle-click: keyboard keyup must not stop it
    engine.processLine('m 2 1')
    engine.processLine('k 58 1')
    time += 400
    engine.processLine('k 58 0')
    assert.deepEqual(events, ['start:keyboard', 'stop:keyboard', 'start:middle-click'])

    engine.processLine('m 2 0')
    assert.deepEqual(events, [
      'start:keyboard',
      'stop:keyboard',
      'start:middle-click',
      'stop:middle-click'
    ])
  })
})
