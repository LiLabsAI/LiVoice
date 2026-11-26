import { createHash, randomBytes, timingSafeEqual } from 'crypto'
import { createServer, type Server } from 'http'
import { hostname } from 'os'
import { shell } from 'electron'

export type AuthIntent = 'login' | 'delete'

function constantTimeEqual(a: string, b: string): boolean {
  const left = Buffer.from(a, 'utf-8')
  const right = Buffer.from(b, 'utf-8')
  if (left.length !== right.length) return false
  return timingSafeEqual(left, right)
}

/**
 * Passwordless sign-in and deletion re-authentication: PKCE (S256) + system
 * browser + a loopback HTTP server that receives the authorization code or
 * reauth token. The Worker's auth page redirects to http://127.0.0.1:<port>/callback
 * when opened with platform=desktop.
 */
export class DesktopAuth {
  private server: Server | null = null
  private port: number | null = null
  private verifier: string | null = null
  private state: string | null = null
  private settled = false

  constructor(
    private readonly workerUrl: string,
    private readonly onCompleted: (token: string) => void,
    private readonly onFailed: (message: string) => void,
    readonly intent: AuthIntent = 'login'
  ) {}

  private pkce(): { verifier: string; challenge: string; state: string } {
    const verifier = randomBytes(48).toString('base64url')
    const challenge = createHash('sha256').update(verifier).digest('base64url')
    const state = randomBytes(32).toString('base64url')
    return { verifier, challenge, state }
  }

  async start(): Promise<{ ok: boolean; message?: string }> {
    if (this.server !== null) {
      return {
        ok: false,
        message:
          this.intent === 'delete'
            ? 'Account deletion confirmation is already in progress.'
            : 'Sign-in is already in progress.'
      }
    }
    this.settled = false
    const { verifier, challenge, state } = this.pkce()
    this.verifier = verifier
    this.state = state

    const port = await this.listen()
    this.port = port

    const authUrl = new URL('/auth', this.workerUrl)
    authUrl.searchParams.set('code_challenge', challenge)
    authUrl.searchParams.set('state', state)
    authUrl.searchParams.set('platform', 'desktop')
    authUrl.searchParams.set('redirect_port', String(port))
    if (this.intent === 'delete') {
      authUrl.searchParams.set('intent', 'delete')
    }

    this.awaitCallback()

    await shell.openExternal(authUrl.toString())
    return {
      ok: true,
      message:
        this.intent === 'delete'
          ? 'Verify your email in your browser to delete your account.'
          : 'Complete sign-in in your browser.'
    }
  }

  /**
   * Completes sign-in or delete verification from a wovoice:// / livoice://
   * deep link (fallback when the loopback listener missed the response).
   */
  handleExternalCallback(token: string, returnedState: string): void {
    if (this.settled) return
    const trimmedToken = token.trim()
    if (!trimmedToken || this.state === null || !constantTimeEqual(returnedState, this.state)) {
      this.settled = true
      this.closeServer()
      this.onFailed('The verification response could not be verified. Please try again.')
      return
    }
    this.settled = true
    this.closeServer()
    this.onCompleted(trimmedToken)
  }

  /**
   * Releases the loopback listener and pending PKCE state — used when sign-in
   * is abandoned (cancel, timeout, failure) so a fresh attempt can start immediately.
   */
  dispose(): void {
    this.settled = true
    this.closeServer()
    this.verifier = null
    this.state = null
  }

  /** Waits for the loopback callback, then hands the token to onCompleted. */
  private awaitCallback(): void {
    const server = this.server
    if (server === null) return
    const expectedHost = this.port !== null ? `127.0.0.1:${this.port}` : null
    const paramName = this.intent === 'delete' ? 'reauth_token' : 'code'
    const successHtml =
      this.intent === 'delete'
        ? '<!doctype html><meta charset="utf-8"><title>LiVoice</title>' +
          '<p style="font-family:system-ui;padding:2rem">Verification complete — return to the LiVoice app.</p>'
        : '<!doctype html><meta charset="utf-8"><title>LiVoice</title>' +
          '<p style="font-family:system-ui;padding:2rem">Sign-in complete — return to the LiVoice app.</p>'

    server.on('request', (request, response) => {
      const headers = { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' }

      // Defend against DNS rebinding against the local loopback listener.
      const host = request.headers.host ?? ''
      if (expectedHost !== null && host !== expectedHost) {
        response.writeHead(403, headers)
        response.end('Forbidden')
        return
      }

      let url: URL
      try {
        url = new URL(request.url ?? '/', 'http://127.0.0.1')
      } catch {
        response.writeHead(400, headers)
        response.end('Bad request')
        return
      }

      if (url.pathname !== '/callback') {
        response.writeHead(404, headers)
        response.end('Not found')
        return
      }

      if (this.settled) {
        response.writeHead(200, headers)
        response.end(successHtml)
        return
      }

      // Claim settlement synchronously so a racing deep link or browser retry
      // cannot run completion twice.
      this.settled = true
      const token = (url.searchParams.get(paramName) ?? '').trim()
      const returnedState = url.searchParams.get('state') ?? ''

      response.writeHead(200, headers)
      response.end(successHtml)

      setImmediate(() => {
        this.closeServer()
        if (!token || this.state === null || !constantTimeEqual(returnedState, this.state)) {
          this.onFailed('The sign-in response could not be verified. Please try again.')
          return
        }
        this.onCompleted(token)
      })
    })
  }

  private closeServer(): void {
    const server = this.server
    this.server = null
    this.port = null
    if (server === null) return
    try {
      server.close()
      server.closeAllConnections?.()
    } catch {
      // ignore close errors
    }
  }

  private listen(): Promise<number> {
    return new Promise((resolve, reject) => {
      const server = createServer()
      this.server = server
      server.on('error', (error) => {
        if (this.server === server) {
          this.server = null
          this.port = null
        }
        reject(error)
      })
      server.listen(0, '127.0.0.1', () => {
        const address = server.address()
        if (address === null || typeof address === 'string') {
          this.closeServer()
          reject(new Error('Could not bind the loopback sign-in listener.'))
          return
        }
        resolve(address.port)
      })
    })
  }

  buildTokenRequest(authorizationCode: string): {
    code: string
    codeVerifier: string
    deviceName: string
  } {
    if (this.verifier === null) throw new Error('No sign-in is in progress.')
    const verifier = this.verifier
    this.verifier = null
    this.state = null
    return {
      code: authorizationCode,
      codeVerifier: verifier,
      deviceName: desktopDeviceName()
    }
  }

  buildDeleteRequest(reauthToken: string): {
    reauthToken: string
    codeVerifier: string
  } {
    if (this.verifier === null) throw new Error('No deletion verification is in progress.')
    const verifier = this.verifier
    this.verifier = null
    this.state = null
    return {
      reauthToken,
      codeVerifier: verifier
    }
  }
}

export function desktopDeviceName(): string {
  return `macOS — ${hostname().split('.')[0]}`.slice(0, 80)
}
