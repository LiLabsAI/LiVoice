import { strict as assert } from 'node:assert'
import { describe, test } from 'node:test'
import {
  boundGlossary,
  MAX_GLOSSARY_CHARS,
  MAX_GLOSSARY_ITEMS,
  WorkerClient,
  WorkerError
} from '../src/main/worker'

describe('boundGlossary', () => {
  test('enforces both 100-item and 4,000 joined-character caps', () => {
    const longTerms = Array.from(
      { length: 80 },
      (_, index) => `Term${String(index).padStart(2, '0')}-${'x'.repeat(68)}`
    )
    const bounded = boundGlossary(longTerms)
    assert.ok(bounded.length < 80)
    assert.ok(bounded.length <= MAX_GLOSSARY_ITEMS)
    assert.ok(bounded.join('\n').length <= MAX_GLOSSARY_CHARS)
  })

  test('trims, skips empty or >80-char terms, and deduplicates case-insensitively', () => {
    const bounded = boundGlossary([
      '  LiVoice  ',
      'livoice',
      '',
      '   ',
      'a'.repeat(81),
      'Kubernetes'
    ])
    assert.deepEqual(bounded, ['LiVoice', 'Kubernetes'])
  })

  test('caps at 100 items even when terms are short', () => {
    const terms = Array.from({ length: 150 }, (_, index) => `w${index}`)
    const bounded = boundGlossary(terms)
    assert.equal(bounded.length, MAX_GLOSSARY_ITEMS)
  })
})

describe('WorkerClient', () => {
  test('throws INVALID_RESPONSE on non-JSON HTTP 200', async () => {
    const originalFetch = globalThis.fetch
    globalThis.fetch = (async () =>
      new Response('<html>gateway page</html>', {
        status: 200,
        headers: { 'Content-Type': 'text/html' }
      })) as typeof fetch
    try {
      const client = new WorkerClient('https://wovoice.test')
      await assert.rejects(
        () => client.profile('token'),
        (error: unknown) => error instanceof WorkerError && error.code === 'INVALID_RESPONSE'
      )
      await assert.rejects(
        () => client.transcribe('token', Buffer.from('RIFF'), []),
        (error: unknown) => error instanceof WorkerError && error.code === 'INVALID_RESPONSE'
      )
    } finally {
      globalThis.fetch = originalFetch
    }
  })

  test('distinguishes timeout errors from offline NETWORK_UNAVAILABLE', async () => {
    const originalFetch = globalThis.fetch
    try {
      globalThis.fetch = (async () => {
        const err = new Error('timed out')
        err.name = 'TimeoutError'
        throw err
      }) as typeof fetch
      const client = new WorkerClient('https://wovoice.test')
      await assert.rejects(
        () => client.profile('token'),
        (error: unknown) => error instanceof WorkerError && error.code === 'REQUEST_TIMEOUT'
      )

      globalThis.fetch = (async () => {
        throw new TypeError('fetch failed')
      }) as typeof fetch
      await assert.rejects(
        () => client.profile('token'),
        (error: unknown) => error instanceof WorkerError && error.code === 'NETWORK_UNAVAILABLE'
      )
    } finally {
      globalThis.fetch = originalFetch
    }
  })

  test('transcribe sends configurable locale, polish, and bounded glossary', async () => {
    const originalFetch = globalThis.fetch
    let capturedOptions: Record<string, unknown> | null = null
    globalThis.fetch = (async (_url: string | URL | Request, init?: RequestInit) => {
      const form = init?.body as FormData
      capturedOptions = JSON.parse(String(form.get('options'))) as Record<string, unknown>
      return new Response(
        JSON.stringify({
          requestId: 'req-42',
          text: 'Hello world.',
          rawText: 'hello world',
          polished: true,
          asrModel: 'whisper-large-v3-turbo',
          timingsMs: { asr: 120, polish: 80, total: 200 },
          usage: {
            pricingVersion: '2026-11-02',
            inputTokens: 40,
            outputTokens: 12,
            asrNeurons: 1.5,
            polishNeurons: 0.4,
            totalNeurons: 1.9,
            estimatedCostUsd: 0.0001
          }
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } }
      )
    }) as typeof fetch

    try {
      const client = new WorkerClient('https://wovoice.test')
      const longGlossary = Array.from({ length: 80 }, (_, i) => `G${i}-${'z'.repeat(70)}`)
      const result = await client.transcribe(
        'tok',
        Buffer.from('RIFF'),
        longGlossary,
        undefined,
        true,
        { locale: 'auto', polish: 'strong' }
      )
      assert.equal(capturedOptions?.['locale'], 'auto')
      assert.equal(capturedOptions?.['polish'], 'strong')
      const sentGlossary = capturedOptions?.['glossary'] as string[]
      assert.ok(sentGlossary.join('\n').length <= MAX_GLOSSARY_CHARS)
      assert.equal(result.usage?.pricingVersion, '2026-11-02')
      assert.equal(result.usage?.asrNeurons, 1.5)
    } finally {
      globalThis.fetch = originalFetch
    }
  })

  test('deleteAccount sends reauthToken and codeVerifier to DELETE /v1/me', async () => {
    const originalFetch = globalThis.fetch
    let capturedMethod = ''
    let capturedUrl = ''
    let capturedBody: Record<string, unknown> | null = null
    globalThis.fetch = (async (url: string | URL | Request, init?: RequestInit) => {
      capturedUrl = String(url)
      capturedMethod = init?.method ?? ''
      capturedBody = JSON.parse(String(init?.body ?? '{}')) as Record<string, unknown>
      return new Response(null, { status: 204 })
    }) as typeof fetch

    try {
      const client = new WorkerClient('https://wovoice.test')
      await client.deleteAccount('access-tok', 'reauth-123', 'verifier-xyz')
      assert.equal(capturedMethod, 'DELETE')
      assert.equal(capturedUrl, 'https://wovoice.test/v1/me')
      assert.deepEqual(capturedBody, { reauthToken: 'reauth-123', codeVerifier: 'verifier-xyz' })
    } finally {
      globalThis.fetch = originalFetch
    }
  })
})
