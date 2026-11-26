import { app } from 'electron'
import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process'
import { createInterface } from 'node:readline'
import { join } from 'node:path'
import {
  classifyContext,
  restrictContext,
  type ContextLevel,
  type ContextStyle,
  type DictationContext
} from '../../../worker/src/context'
export { restrictContext }
export type { ContextLevel, ContextStyle, DictationContext }

export type ContextFailureReason = 'accessibility' | 'editor' | 'secure'

interface EditorSnapshot {
  reason?: ContextFailureReason
  available: boolean
  fallback?: boolean
  token?: string
  appId?: string
  hint?: string
  field?: 'text' | 'search' | 'literal' | 'code'
  sentenceStart?: boolean
  before?: string
  after?: string
}

export interface ContextTarget {
  reason?: ContextFailureReason
  token?: string
  fallback?: boolean
  sentenceStart: boolean
  context?: DictationContext
}

/** A bounded private IPC channel; raw snippets never reach logs or persistent storage. */
export class ContextReader {
  private child: ChildProcessWithoutNullStreams | null = null
  private sequence = 0
  private pending = new Map<
    number,
    { resolve: (value: Record<string, unknown>) => void; timer: ReturnType<typeof setTimeout> }
  >()

  prepare(): void {
    if (this.child) return
    const path = app.isPackaged
      ? join(process.resourcesPath, 'contextd')
      : join(__dirname, '../../native/contextd')
    const child = spawn(path, [], { stdio: ['pipe', 'pipe', 'pipe'] })
    this.child = child
    createInterface({ input: child.stdout }).on('line', (line) => {
      try {
        const value = JSON.parse(line) as { id?: unknown; result?: unknown }
        if (typeof value?.id !== 'number') return
        const pending = this.pending.get(value.id)
        if (!pending) return
        this.pending.delete(value.id)
        clearTimeout(pending.timer)
        const result =
          value.result !== null && typeof value.result === 'object' && !Array.isArray(value.result)
            ? (value.result as Record<string, unknown>)
            : {}
        pending.resolve(result)
      } catch {
        /* Ignore malformed output; the request deadline closes the helper. */
      }
    })
    child.stderr.resume()
    child.on('error', () => {
      if (this.child === child) this.dispose()
    })
    child.on('exit', () => {
      if (this.child === child) this.dispose()
    })
  }

  private request(
    command: string,
    extra: Record<string, unknown> = {}
  ): Promise<Record<string, unknown>> {
    this.prepare()
    return new Promise((resolve) => {
      const id = ++this.sequence
      const timer = setTimeout(() => this.dispose(), 2500)
      this.pending.set(id, { resolve, timer })
      if (!this.child) {
        clearTimeout(timer)
        this.pending.delete(id)
        resolve({})
        return
      }
      this.child.stdin.write(JSON.stringify({ id, command, ...extra }) + '\n', (error) => {
        if (error) this.dispose()
      })
    })
  }

  async capture(level: ContextLevel, style: ContextStyle): Promise<ContextTarget> {
    const value = (await this.request('capture', { level })) as unknown as EditorSnapshot
    if (!value.available || typeof value.token !== 'string') {
      return { sentenceStart: true, reason: value.reason }
    }
    return {
      token: value.token,
      fallback: value.fallback === true,
      sentenceStart: value.sentenceStart !== false,
      context: classifyContext({
        appId: value.appId ?? '',
        hint: value.hint,
        field: value.field,
        level,
        style,
        before: value.before,
        after: value.after
      })
    }
  }

  async validate(token?: string): Promise<boolean> {
    return !!token && (await this.request('validate', { token })).valid === true
  }

  /** Posts a native Cmd+V keystroke via CGEventPost (no osascript / System Events needed). */
  async paste(): Promise<boolean> {
    return (await this.request('paste')).ok === true
  }

  release(): void {
    if (this.child) void this.request('release')
  }

  dispose(): void {
    const child = this.child
    this.child = null
    for (const item of this.pending.values()) {
      clearTimeout(item.timer)
      item.resolve({})
    }
    this.pending.clear()
    child?.kill()
  }
}
