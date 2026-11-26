import {
  app,
  shell,
  clipboard,
  dialog,
  globalShortcut,
  BrowserWindow,
  Menu,
  Tray,
  ipcMain,
  systemPreferences,
  nativeImage
} from 'electron'
import { join } from 'path'
import { unlinkSync, writeFileSync } from 'fs'
import { electronApp, optimizer, is } from '@electron-toolkit/utils'
import trayTemplate from '../../resources/trayTemplate.png?asset'
import { SettingsStore, isValidLocale, isValidPolish } from './store'
import { SessionStore } from './session'
import { WorkerClient, WorkerError, type WorkerTokens } from './worker'
import { DesktopAuth } from './auth'
import { openDatabase } from './db'
import { SyncService } from './sync'
import { DictationService } from './dictation'
import { TriggerEngine, TRIGGER_KEYS, releaseHint } from './triggers'
import { migrateLegacyUserData } from './migrate-userdata'

// Claim the single instance before ANY module-level work: a second launch must
// exit immediately without touching migration, settings.json, Keychain, or SQLite.
const primaryInstance = app.requestSingleInstanceLock()
if (!primaryInstance) {
  app.quit()
} else {
  bootstrapPrimaryInstance()
}

function bootstrapPrimaryInstance(): void {
  // Migrate ~/Library/Application Support/WoVoice -> .../LiVoice BEFORE any
  // store, session, or database opens files in app.getPath('userData').
  const userDataDir = app.getPath('userData')
  migrateLegacyUserData(userDataDir)

  let dashboard: BrowserWindow | null = null
  let tray: Tray | null = null
  let desktopAuth: DesktopAuth | null = null
  let lastForegroundRefreshAt = 0

  // Shown once in the dashboard when the Privacy Policy changes in a way users should see.
  const POLICY_VERSION = '2026-09-26-cloud-sync'
  const SIGN_IN_TIMEOUT_MS = 5 * 60_000
  const HISTORY_UNDO_WINDOW_MS = 6_000
  const PERIODIC_SYNC_INTERVAL_MS = 10 * 60_000
  const FOREGROUND_SYNC_THROTTLE_MS = 30_000
  const SIGNOUT_NETWORK_TIMEOUT_MS = 4_000
  const DAY_MS = 86_400_000

  let deferredSync: ReturnType<typeof setTimeout> | null = null
  let periodicSyncTimer: ReturnType<typeof setInterval> | null = null
  let pendingDeepLink: string | null = null

  const settings = new SettingsStore(userDataDir)
  // Builds before lastAccountId existed only knew the signed-in account.
  if (settings.get<string | null>('lastAccountId', null) === null) {
    const current = settings.get<string | null>('accountId', null)
    if (current !== null) settings.set('lastAccountId', current)
  }

  const worker = new WorkerClient(settings.workerUrl)
  // Desktop-owned database file: keep exact filename for upgrades.
  const db = openDatabase(join(userDataDir, 'wovoice-desktop.db'))

  // Apply cached history retention immediately on startup (even when offline).
  if (settings.historyRetentionDays !== null && settings.historyRetentionDays > 0) {
    db.deleteHistoryBefore(Date.now() - settings.historyRetentionDays * DAY_MS)
  }

  const broadcastHistoryChanged = (): void => {
    if (dashboard !== null && !dashboard.isDestroyed()) {
      dashboard.webContents.send('history:changed')
    }
    rebuildTrayMenu()
  }

  const currentSyncStatus = (): {
    lastSyncAt: number
    historySyncEnabled: boolean
    historyRetentionDays: number | null
    syncWarning: string | null
  } => ({
    lastSyncAt: settings.lastSyncAt,
    historySyncEnabled: settings.historySyncEnabled,
    historyRetentionDays: settings.historyRetentionDays,
    syncWarning: settings.syncWarning
  })

  const broadcastSyncChanged = (): void => {
    if (dashboard !== null && !dashboard.isDestroyed()) {
      dashboard.webContents.send('sync:changed', currentSyncStatus())
    }
  }

  const broadcastPermissionsChanged = async (): Promise<void> => {
    if (dashboard === null || dashboard.isDestroyed()) return
    dashboard.webContents.send('permissions:changed', {
      accessibility: await accessibilityTrusted(),
      mic: systemPreferences.getMediaAccessStatus('microphone')
    })
  }

  const handleAccountRestriction = (state: 'suspended' | 'banned', message: string): void => {
    settings.setAccountRestriction(state, message)
    broadcastAuthState()
  }

  const session = new SessionStore(
    (refreshToken) =>
      worker.refresh(refreshToken).then((tokens) => {
        settings.applyAccountUser(tokens.user)
        return {
          accessToken: tokens.accessToken,
          accessExpiresInSeconds: tokens.accessExpiresIn,
          refreshToken: tokens.refreshToken
        }
      }),
    () => {
      settings.clearAccount()
      removeVaultFiles()
      broadcastSyncChanged()
      broadcastAuthState({ signedIn: false, email: null })
    },
    userDataDir
  )

  const sync = new SyncService({
    worker,
    settings: {
      workerUrl: settings.workerUrl,
      getAccountId: () => settings.get<string | null>('accountId', null),
      getCursor: () => settings.syncCursor,
      setCursor: (value) => {
        settings.syncCursor = value
      },
      getMigratedAccount: () => settings.cloudSyncMigratedAccount,
      setMigratedAccount: (value) => {
        settings.cloudSyncMigratedAccount = value
      },
      getHistorySyncEnabled: () => settings.historySyncEnabled,
      setHistorySyncEnabled: (value) => {
        settings.historySyncEnabled = value
      },
      getRetentionDays: () => settings.historyRetentionDays,
      setRetentionDays: (value) => {
        settings.historyRetentionDays = value
      },
      setLastSyncAt: (value) => {
        settings.lastSyncAt = value
      },
      setSyncWarning: (warning) => {
        settings.syncWarning = warning
      }
    },
    db,
    getToken: async () => session.accessToken(),
    invalidateToken: (token) => session.invalidateAccessToken(token),
    onAccountRestricted: handleAccountRestriction,
    onSyncCompleted: (outcome) => {
      broadcastSyncChanged()
      if (outcome.kind === 'ok' && outcome.downloaded > 0) {
        broadcastHistoryChanged()
      }
    },
    vaultDir: userDataDir
  })

  async function withAuthRetry<T>(fn: (token: string) => Promise<T>): Promise<T> {
    const token = await session.accessToken()
    try {
      return await fn(token)
    } catch (error) {
      if (error instanceof WorkerError) {
        if (error.code === 'ACCOUNT_SUSPENDED') {
          handleAccountRestriction('suspended', error.message)
          throw error
        }
        if (error.code === 'ACCOUNT_BANNED') {
          handleAccountRestriction('banned', error.message)
          throw error
        }
        if (error.code === 'TOKEN_EXPIRED' || error.code === 'AUTH_REQUIRED') {
          session.invalidateAccessToken(token)
          const refreshed = await session.accessToken()
          return await fn(refreshed)
        }
      }
      throw error
    }
  }

  const dictation = new DictationService({
    settings,
    isAccountAllowed: () => {
      if (!session.hasRefreshToken) {
        return { allowed: false, message: 'Sign in to use voice dictation.' }
      }
      if (!settings.isAccountActive()) {
        return { allowed: false, message: settings.restrictionMessage() }
      }
      return { allowed: true }
    },
    onAccountRestricted: handleAccountRestriction,
    getToken: async () => session.accessToken(),
    invalidateToken: (token) => session.invalidateAccessToken(token),
    getGlossary: async () => db.bestGlossary(100),
    transcribe: (token, wav, glossary, context, sentenceStart, options) =>
      worker.transcribe(token, wav, glossary, context, sentenceStart, options),
    onSessionAborted: () => triggers.reset(),
    recordHistory: (entry) => {
      const now = new Date()
      const id = entry.requestId !== '' ? entry.requestId : crypto.randomUUID()
      const zoneId = Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'
      const wordCount = countWords(entry.text)
      const usage = entry.usage
      db.insertRecord({
        requestId: id,
        finalText: entry.text,
        createdAtMs: now.getTime(),
        zoneId,
        wordCount,
        audioDurationMs: entry.durationMs,
        asrModel: entry.asrModel,
        polished: entry.polished,
        asrMs: entry.timingsMs.asr,
        polishMs: entry.timingsMs.polish,
        totalMs: entry.timingsMs.total,
        deleted: false,
        pricingVersion: usage?.pricingVersion ?? null,
        inputTokens: usage?.inputTokens ?? null,
        outputTokens: usage?.outputTokens ?? null,
        asrNeurons: usage?.asrNeurons ?? null,
        polishNeurons: usage?.polishNeurons ?? null,
        totalNeurons: usage?.totalNeurons ?? null,
        estimatedCostUsd: usage?.estimatedCostUsd ?? null
      })
      db.insertAnalyticsEvent({
        syncId: id,
        createdAtMs: now.getTime(),
        zoneId,
        audioDurationMs: entry.durationMs,
        wordCount,
        processingMs: entry.timingsMs.total,
        polished: entry.polished,
        corrected: false,
        asrNeurons: usage?.asrNeurons ?? 0,
        polishNeurons: usage?.polishNeurons ?? 0,
        estimatedCostUsd: usage?.estimatedCostUsd ?? 0
      })
      db.recordUsage(db.bestGlossary(100), entry.text)
      broadcastHistoryChanged()
      broadcastAuthState({ lastDictation: entry.text })
      void sync.syncNow()
    }
  })

  const triggers = new TriggerEngine(
    {
      get keyboardShortcutEnabled() {
        return settings.keyboardShortcutEnabled
      },
      get middleClickEnabled() {
        return settings.middleClickEnabled
      },
      get triggerKey() {
        return settings.get<string>('triggerKey', 'option')
      }
    },
    (trigger): void =>
      dictation.begin(releaseHint(trigger, settings.get<string>('triggerKey', 'option'))),
    (): void => dictation.end(),
    {
      onCancel: () => dictation.cancelSilent(),
      onLatch: (hint) => dictation.notifyLatched(hint),
      isBusy: () => dictation.isBusy()
    }
  )

  function currentAuthState(extra: Record<string, unknown> = {}): Record<string, unknown> {
    const status = settings.accountStatus
    return {
      signedIn: session.hasRefreshToken,
      email: settings.get<string | null>('accountEmail', null),
      pendingIntent: desktopAuth?.intent ?? null,
      accountState: status.state,
      accountPublicMessage: status.publicMessage,
      accountSuspendedUntil: status.suspendedUntil,
      accountSupportEmail: status.supportEmail,
      ...extra
    }
  }

  function broadcastAuthState(extra: Record<string, unknown> = {}): void {
    if (app.isReady()) dictation.onAccountChanged()
    if (dashboard !== null && !dashboard.isDestroyed()) {
      dashboard.webContents.send('auth:state', currentAuthState(extra))
    }
    rebuildTrayMenu()
  }

  function scheduleSync(delayMs: number): void {
    if (deferredSync !== null) clearTimeout(deferredSync)
    deferredSync = setTimeout(() => {
      deferredSync = null
      if (session.hasRefreshToken) void sync.syncNow()
    }, delayMs)
  }

  function removeVaultFiles(): void {
    for (const name of ['vault-key.bin', 'recovery-secret.bin']) {
      try {
        unlinkSync(join(userDataDir, name))
      } catch {
        // absent — nothing to clean
      }
    }
  }

  async function adoptAccount(tokens: WorkerTokens): Promise<boolean> {
    const previous = settings.get<string | null>('lastAccountId', null)
    if (previous === null) {
      const counts = db.localDataCounts()
      if (counts.history + counts.dictionary > 0) {
        const { response } = await dialog.showMessageBox({
          type: 'question',
          buttons: ['Keep with this account', 'Remove from this Mac', 'Cancel sign-in'],
          defaultId: 0,
          cancelId: 2,
          message: `Is the data on this Mac yours, ${tokens.user.email}?`,
          detail:
            `This Mac holds ${counts.history} dictation${counts.history === 1 ? '' : 's'} and ` +
            `${counts.dictionary} dictionary term${counts.dictionary === 1 ? '' : 's'} from an earlier ` +
            'sign-in. Keep them only if they belong to this account — kept items sync into your account.'
        })
        if (response === 2) return false
        if (response === 1) {
          db.clearAllData()
          removeVaultFiles()
          settings.syncCursor = 0
        }
      }
    } else if (previous !== tokens.user.id) {
      const counts = db.localDataCounts()
      if (counts.history + counts.dictionary > 0) {
        const lost =
          counts.unsynced > 0
            ? ` ${counts.unsynced} change${counts.unsynced === 1 ? '' : 's'} that never synced will be lost.`
            : ''
        const { response } = await dialog.showMessageBox({
          type: 'warning',
          buttons: ['Remove and continue', 'Cancel sign-in'],
          defaultId: 1,
          cancelId: 1,
          message: `Sign in as ${tokens.user.email}?`,
          detail:
            'This Mac holds history and dictionary items from a different LiVoice account. ' +
            'They will be removed from this Mac so they never mix with or sync into this account. ' +
            `Anything already synced stays in the other account.${lost}`
        })
        if (response !== 0) return false
      }
      db.clearAllData()
      removeVaultFiles()
      settings.syncCursor = 0
    }
    settings.set('lastAccountId', tokens.user.id)
    return true
  }

  function cancelPendingAuth(): void {
    if (desktopAuth === null) return
    desktopAuth.dispose()
    desktopAuth = null
    broadcastAuthState({ pendingIntent: null })
  }

  async function startSignIn(): Promise<{ ok: boolean; message?: string }> {
    // Never lock the user out for 5 minutes if they closed their browser tab:
    // replace any stale login attempt cleanly.
    if (desktopAuth !== null) {
      desktopAuth.dispose()
      desktopAuth = null
    }

    const auth = new DesktopAuth(
      settings.workerUrl,
      (authorizationCode) => {
        const finish = (): void => {
          auth.dispose()
          if (desktopAuth === auth) desktopAuth = null
        }
        try {
          const request = auth.buildTokenRequest(authorizationCode)
          worker
            .exchangeAuthorizationCode(request)
            .then(async (tokens) => {
              if (!(await adoptAccount(tokens))) {
                await worker
                  .logout(tokens.accessToken, SIGNOUT_NETWORK_TIMEOUT_MS)
                  .catch(() => undefined)
                finish()
                broadcastAuthState({ signedIn: false, error: 'Sign-in cancelled.' })
                return
              }
              session.storeTokens(tokens.accessToken, tokens.accessExpiresIn, tokens.refreshToken)
              settings.applyAccountUser(tokens.user)
              finish()
              broadcastAuthState({ signedIn: true, email: tokens.user.email })
              broadcastHistoryChanged()
              showDashboard()
              void sync.syncNow()
            })
            .catch((error: Error) => {
              finish()
              broadcastAuthState({ signedIn: false, error: error.message })
            })
        } catch (error) {
          finish()
          broadcastAuthState({
            signedIn: false,
            error: error instanceof Error ? error.message : 'Sign-in failed.'
          })
        }
      },
      (message) => {
        auth.dispose()
        if (desktopAuth === auth) desktopAuth = null
        broadcastAuthState({ signedIn: false, error: message })
      },
      'login'
    )

    desktopAuth = auth
    broadcastAuthState({ pendingIntent: 'login' })

    const release = (): void => {
      auth.dispose()
      if (desktopAuth === auth) {
        desktopAuth = null
        broadcastAuthState({ pendingIntent: null })
      }
    }

    setTimeout(() => {
      if (desktopAuth === auth) release()
    }, SIGN_IN_TIMEOUT_MS).unref()

    try {
      const result = await auth.start()
      if (!result.ok) {
        release()
        broadcastAuthState({ signedIn: false, error: result.message })
      }
      return result
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Could not open browser for sign-in.'
      release()
      broadcastAuthState({ signedIn: false, error: message })
      return { ok: false, message }
    }
  }

  async function startDeleteAccount(): Promise<{ ok: boolean; message?: string }> {
    if (!session.hasRefreshToken) {
      return { ok: false, message: 'Sign in first before deleting your account.' }
    }
    const email = settings.get<string | null>('accountEmail', null) ?? 'your account'
    const { response } = await dialog.showMessageBox({
      type: 'warning',
      buttons: ['Continue to browser verification', 'Cancel'],
      defaultId: 1,
      cancelId: 1,
      message: `Delete LiVoice account (${email})?`,
      detail:
        'This permanently deletes your LiVoice account, synced history, dictionary, and analytics from LiVoice servers and clears this Mac. You will verify your email in the browser to confirm.'
    })
    if (response !== 0) return { ok: false, message: 'Account deletion cancelled.' }

    if (desktopAuth !== null) {
      desktopAuth.dispose()
      desktopAuth = null
    }

    const auth = new DesktopAuth(
      settings.workerUrl,
      (reauthToken) => {
        const finish = (): void => {
          auth.dispose()
          if (desktopAuth === auth) desktopAuth = null
        }
        try {
          const proof = auth.buildDeleteRequest(reauthToken)
          void withAuthRetry((token) =>
            worker.deleteAccount(token, proof.reauthToken, proof.codeVerifier)
          )
            .then(() => {
              finish()
              session.clear(false)
              settings.clearAccount()
              settings.set('lastAccountId', undefined)
              db.clearAllData()
              removeVaultFiles()
              broadcastHistoryChanged()
              broadcastSyncChanged()
              broadcastAuthState({ signedIn: false, email: null })
              showDashboard()
            })
            .catch((error: Error) => {
              finish()
              broadcastAuthState({ error: error.message || 'Account deletion failed.' })
            })
        } catch (error) {
          finish()
          broadcastAuthState({
            error: error instanceof Error ? error.message : 'Account deletion failed.'
          })
        }
      },
      (message) => {
        auth.dispose()
        if (desktopAuth === auth) desktopAuth = null
        broadcastAuthState({ error: message })
      },
      'delete'
    )

    desktopAuth = auth
    broadcastAuthState({ pendingIntent: 'delete' })

    const release = (): void => {
      auth.dispose()
      if (desktopAuth === auth) {
        desktopAuth = null
        broadcastAuthState({ pendingIntent: null })
      }
    }

    setTimeout(() => {
      if (desktopAuth === auth) release()
    }, SIGN_IN_TIMEOUT_MS).unref()

    try {
      const result = await auth.start()
      if (!result.ok) {
        release()
      }
      return result
    } catch (error) {
      const message =
        error instanceof Error ? error.message : 'Could not open browser for verification.'
      release()
      return { ok: false, message }
    }
  }

  async function signOut(): Promise<{ ok: boolean; warning?: string }> {
    cancelPendingAuth()
    let warning: string | undefined
    let token = session.validAccessToken
    if (token === null) {
      const refreshToken = session.peekRefreshToken()
      if (refreshToken !== null) {
        try {
          const rotated = await worker.refresh(refreshToken, SIGNOUT_NETWORK_TIMEOUT_MS)
          token = rotated.accessToken
        } catch {
          warning =
            'Signed out on this Mac, but could not reach LiVoice to revoke this session remotely.'
        }
      }
    }
    if (token !== null) {
      try {
        await worker.logout(token, SIGNOUT_NETWORK_TIMEOUT_MS)
      } catch {
        warning =
          'Signed out on this Mac, but could not reach LiVoice to revoke this session remotely.'
      }
    }
    // Clear session silently first, then clear account metadata, then broadcast ONCE.
    session.clear(false)
    settings.clearAccount()
    removeVaultFiles()
    broadcastSyncChanged()
    broadcastAuthState({ signedIn: false, email: null })
    return warning ? { ok: true, warning } : { ok: true }
  }

  function installApplicationMenu(): void {
    const template: Electron.MenuItemConstructorOptions[] = [
      {
        label: app.name,
        submenu: [
          { role: 'about' },
          { type: 'separator' },
          { role: 'services' },
          { type: 'separator' },
          { role: 'hide' },
          { role: 'hideOthers' },
          { role: 'unhide' },
          { type: 'separator' },
          { role: 'quit' }
        ]
      },
      {
        label: 'Edit',
        submenu: [
          { role: 'undo' },
          { role: 'redo' },
          { type: 'separator' },
          { role: 'cut' },
          { role: 'copy' },
          { role: 'paste' },
          { role: 'delete' },
          { role: 'selectAll' }
        ]
      },
      {
        label: 'Window',
        submenu: [
          { role: 'minimize' },
          { role: 'zoom' },
          { type: 'separator' },
          { role: 'front' },
          { role: 'close' }
        ]
      }
    ]
    Menu.setApplicationMenu(Menu.buildFromTemplate(template))
  }

  function onAppForeground(): void {
    void accessibilityTrusted().then((trusted) => {
      if (trusted) triggers.registerIfMissing()
      void broadcastPermissionsChanged()
    })
    if (!session.hasRefreshToken) return
    const now = Date.now()
    if (now - lastForegroundRefreshAt < FOREGROUND_SYNC_THROTTLE_MS) return
    lastForegroundRefreshAt = now
    void withAuthRetry((token) => worker.profile(token))
      .then((profile) => {
        settings.applyAccountUser(profile.user)
        broadcastAuthState()
      })
      .catch(() => undefined)
    void sync.syncNow()
  }

  function isSafeExternalUrl(raw: string): boolean {
    try {
      const parsed = new URL(raw)
      return (
        parsed.protocol === 'https:' || parsed.protocol === 'http:' || parsed.protocol === 'mailto:'
      )
    } catch {
      return false
    }
  }

  function createDashboard(): void {
    dashboard = new BrowserWindow({
      width: 980,
      height: 720,
      minWidth: 760,
      minHeight: 560,
      backgroundColor: '#17161b',
      show: false,
      autoHideMenuBar: true,
      title: 'LiVoice',
      webPreferences: {
        preload: join(__dirname, '../preload/index.js'),
        sandbox: true,
        contextIsolation: true,
        nodeIntegration: false
      }
    })

    dashboard.on('ready-to-show', () => {
      dashboard?.show()
    })

    dashboard.on('focus', () => {
      onAppForeground()
    })

    dashboard.on('closed', () => {
      dashboard = null
    })

    dashboard.webContents.setWindowOpenHandler((details) => {
      if (isSafeExternalUrl(details.url)) {
        void shell.openExternal(details.url)
      }
      return { action: 'deny' }
    })

    const allowedDevOrigin =
      is.dev && process.env['ELECTRON_RENDERER_URL']
        ? new URL(process.env['ELECTRON_RENDERER_URL']).origin
        : null
    dashboard.webContents.on('will-navigate', (event, targetUrl) => {
      if (allowedDevOrigin !== null) {
        try {
          if (new URL(targetUrl).origin === allowedDevOrigin) return
        } catch {
          // block malformed URL
        }
      }
      event.preventDefault()
    })

    if (is.dev && process.env['ELECTRON_RENDERER_URL']) {
      void dashboard.loadURL(process.env['ELECTRON_RENDERER_URL'])
    } else {
      void dashboard.loadFile(join(__dirname, '../renderer/index.html'))
    }
  }

  function showDashboard(): void {
    if (dashboard === null || dashboard.isDestroyed()) {
      createDashboard()
    } else {
      if (dashboard.isMinimized()) dashboard.restore()
      dashboard.show()
      dashboard.focus()
    }
    app.focus({ steal: true })
  }

  function rebuildTrayMenu(): void {
    if (tray === null || tray.isDestroyed()) return
    const email = settings.get<string | null>('accountEmail', null)
    const signedIn = session.hasRefreshToken
    const paused = triggers.isPaused()
    const hasLast = db.latestDictationText() !== null
    const statusLabel = !signedIn
      ? 'Not signed in'
      : !settings.isAccountActive()
        ? `Account ${settings.accountState}`
        : paused
          ? `Paused · ${email ?? 'Signed in'}`
          : (email ?? 'Signed in')

    tray.setContextMenu(
      Menu.buildFromTemplate([
        { label: statusLabel, enabled: false },
        { type: 'separator' },
        { label: 'Open LiVoice', click: showDashboard },
        {
          label: 'Copy last dictation',
          enabled: hasLast,
          click: (): void => {
            const latest = db.latestDictationText()
            if (latest !== null) clipboard.writeText(latest)
          }
        },
        {
          label: 'Sync now',
          enabled: signedIn,
          click: (): void => {
            void sync.syncNow()
          }
        },
        {
          label: paused ? 'Resume dictation' : 'Pause dictation',
          click: (): void => {
            triggers.setPaused(!triggers.isPaused())
            rebuildTrayMenu()
          }
        },
        { type: 'separator' },
        signedIn
          ? {
              label: 'Sign out',
              click: (): void => {
                void signOut()
              }
            }
          : {
              label: 'Sign in…',
              click: (): void => {
                void startSignIn()
              }
            },
        { type: 'separator' },
        {
          label: 'Quit LiVoice',
          click: (): void => {
            triggers.unregister()
            app.quit()
          }
        }
      ])
    )
  }

  function createTray(): void {
    // Load the 1x trayTemplate.png so Electron automatically attaches trayTemplate@2x.png
    // on Retina displays without downscaling to blurry 1x.
    const trayImage = nativeImage.createFromPath(trayTemplate)
    trayImage.setTemplateImage(true)
    tray = new Tray(trayImage)
    tray.setToolTip('LiVoice')
    rebuildTrayMenu()
  }

  function routeDeepLink(value: string): void {
    let url: URL
    try {
      url = new URL(value)
    } catch {
      return
    }
    if (url.protocol !== 'wovoice:' && url.protocol !== 'livoice:') return
    if (url.hostname !== 'callback' && url.pathname !== '/callback') return
    const state = url.searchParams.get('state') ?? ''
    const paramName = desktopAuth?.intent === 'delete' ? 'reauth_token' : 'code'
    const token = url.searchParams.get(paramName) ?? ''
    desktopAuth?.handleExternalCallback(token, state)
    showDashboard()
  }

  // Deep links can arrive before the app is ready (cold launch from browser hand-off).
  app.on('open-url', (event, url) => {
    event.preventDefault()
    if (app.isReady()) routeDeepLink(url)
    else pendingDeepLink = url
  })

  app.on('second-instance', (_event, argv) => {
    const url = argv.find(
      (value) => value.startsWith('wovoice://') || value.startsWith('livoice://')
    )
    if (!app.isReady()) {
      if (url !== undefined) pendingDeepLink = url
      return
    }
    if (url !== undefined) routeDeepLink(url)
    else showDashboard()
  })

  app.whenReady().then(() => {
    installApplicationMenu()

    app.setAsDefaultProtocolClient('wovoice')
    app.setAsDefaultProtocolClient('livoice')
    electronApp.setAppUserModelId('com.aliahad.wovoice.desktop')

    app.on('browser-window-created', (_, window) => {
      optimizer.watchWindowShortcuts(window)
    })

    // ---- IPC handlers ----
    ipcMain.handle('app:version', () => app.getVersion())
    ipcMain.handle('auth:start', () => startSignIn())
    ipcMain.handle('auth:cancel', () => cancelPendingAuth())
    ipcMain.handle('auth:signOut', () => signOut())
    ipcMain.handle('auth:state', () => currentAuthState())
    ipcMain.handle('auth:profile', async () => {
      const profile = await withAuthRetry((token) => worker.profile(token))
      settings.applyAccountUser(profile.user)
      broadcastAuthState()
      return profile
    })
    ipcMain.handle('auth:sessions', async () =>
      withAuthRetry((token) => worker.listSessions(token))
    )
    ipcMain.handle('auth:revokeSession', async (_event, sessionId: unknown) => {
      if (typeof sessionId !== 'string' || sessionId.trim().length === 0) {
        return { ok: false, message: 'Invalid session ID.' }
      }
      try {
        await withAuthRetry((token) => worker.revokeSession(token, sessionId.trim()))
        // Check if we revoked our own active session by probing profile or checking response.
        try {
          await withAuthRetry((token) => worker.listSessions(token))
          return { ok: true, signedOut: false }
        } catch (err) {
          if (err instanceof WorkerError && err.code === 'AUTH_REQUIRED') {
            session.clear(false)
            settings.clearAccount()
            removeVaultFiles()
            broadcastAuthState({ signedIn: false, email: null })
            return { ok: true, signedOut: true }
          }
          return { ok: true, signedOut: false }
        }
      } catch (error) {
        return {
          ok: false,
          message: error instanceof Error ? error.message : 'Could not revoke session.'
        }
      }
    })
    ipcMain.handle('auth:deleteAccount', () => startDeleteAccount())

    ipcMain.handle('permissions:check', async () => ({
      accessibility: await accessibilityTrusted(),
      mic: systemPreferences.getMediaAccessStatus('microphone')
    }))
    ipcMain.handle('permissions:enableMicrophone', async () => {
      const status = systemPreferences.getMediaAccessStatus('microphone')
      if (status === 'granted') return { granted: true }
      if (status === 'denied' || status === 'restricted') {
        void shell.openExternal(
          'x-apple.systempreferences:com.apple.preference.security?Privacy_Microphone'
        )
        return { granted: false, openedSettings: true }
      }
      const granted = await systemPreferences.askForMediaAccess('microphone')
      return { granted }
    })
    ipcMain.handle('permissions:enableAccessibility', async () => {
      if (process.platform === 'darwin' && systemPreferences.isTrustedAccessibilityClient(true)) {
        triggers.registerIfMissing()
        return true
      }
      void shell.openExternal(
        'x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility'
      )
      return false
    })
    ipcMain.handle('permissions:openAccessibilityPane', () => {
      if (process.platform === 'darwin') {
        systemPreferences.isTrustedAccessibilityClient(true)
      }
      void shell.openExternal(
        'x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility'
      )
    })
    ipcMain.handle('permissions:openAutomationPane', () => {
      void shell.openExternal(
        'x-apple.systempreferences:com.apple.preference.security?Privacy_Automation'
      )
    })

    ipcMain.handle('settings:get', () => ({
      keyboardShortcutEnabled: settings.keyboardShortcutEnabled,
      middleClickEnabled: settings.middleClickEnabled,
      triggerKey: settings.get<string>('triggerKey', 'option'),
      workerUrl: settings.workerUrl,
      contextLevel: settings.contextLevel,
      contextStyle: settings.contextStyle,
      contextOwner: settings.contextOwner,
      locale: settings.locale,
      polish: settings.polish,
      soundsEnabled: settings.soundsEnabled,
      startHidden: settings.startHidden,
      micDeviceId: settings.micDeviceId
    }))
    ipcMain.handle(
      'settings:set',
      (_event, key: unknown, value: unknown, contextOwner?: unknown) => {
        if (typeof key !== 'string') return false
        if (
          (key === 'contextLevel' || key === 'contextStyle') &&
          contextOwner !== settings.contextOwner
        ) {
          return false
        }
        if (key === 'keyboardShortcutEnabled' || key === 'middleClickEnabled') {
          if (typeof value !== 'boolean') return false
          settings.set(key, value)
          triggers.syncRegistration()
          return true
        }
        if (key === 'contextLevel' && (value === 'off' || value === 'app' || value === 'nearby')) {
          settings.contextLevel = value
          return true
        }
        if (
          key === 'contextStyle' &&
          typeof value === 'string' &&
          ['auto', 'natural', 'polite', 'professional', 'concise'].includes(value)
        ) {
          settings.contextStyle = value as typeof settings.contextStyle
          return true
        }
        if (key === 'triggerKey') {
          if (typeof value !== 'string' || !TRIGGER_KEYS.includes(value)) return false
          settings.set(key, value)
          triggers.restart()
          return true
        }
        if (key === 'locale') {
          if (!isValidLocale(value)) return false
          settings.locale = value
          return true
        }
        if (key === 'polish') {
          if (!isValidPolish(value)) return false
          settings.polish = value
          return true
        }
        if (key === 'soundsEnabled') {
          if (typeof value !== 'boolean') return false
          settings.soundsEnabled = value
          return true
        }
        if (key === 'startHidden') {
          if (typeof value !== 'boolean') return false
          settings.startHidden = value
          return true
        }
        if (key === 'micDeviceId') {
          if (typeof value !== 'string' || value.length > 256) return false
          settings.micDeviceId = value
          return true
        }
        return false
      }
    )

    ipcMain.handle('stats:home', (_event, period: unknown) => {
      const daysBack = period === 'today' ? 0 : period === '7d' ? 6 : period === '30d' ? 29 : null
      const today = new Date()
      const since =
        daysBack === null
          ? 0
          : new Date(today.getFullYear(), today.getMonth(), today.getDate() - daysBack).getTime()
      return db.stats(since)
    })
    ipcMain.handle('history:list', (_event, query: unknown) =>
      db.historySearch(typeof query === 'string' ? query : '')
    )
    ipcMain.handle('history:delete', (_event, requestId: unknown) => {
      if (typeof requestId !== 'string' || requestId.length === 0) return
      db.deleteRecord(requestId, HISTORY_UNDO_WINDOW_MS)
      broadcastHistoryChanged()
      scheduleSync(HISTORY_UNDO_WINDOW_MS + 250)
    })
    ipcMain.handle('history:restore', (_event, requestId: unknown) => {
      if (typeof requestId !== 'string' || requestId.length === 0) return false
      const restored = db.restoreRecord(requestId)
      if (restored) {
        broadcastHistoryChanged()
        if (session.hasRefreshToken) void sync.syncNow()
      }
      return restored
    })
    ipcMain.handle('history:copy', (_event, text: unknown) => {
      if (typeof text === 'string') clipboard.writeText(text)
    })
    ipcMain.handle('history:copyLast', () => {
      const latest = db.latestDictationText()
      if (latest === null) return false
      clipboard.writeText(latest)
      return true
    })
    ipcMain.handle('history:clear', async () => {
      const { response } = await dialog.showMessageBox({
        type: 'warning',
        buttons: ['Clear history', 'Cancel'],
        defaultId: 1,
        cancelId: 1,
        message: 'Clear dictation history?',
        detail:
          'This removes all dictated text from this Mac (and from your synced account if history sync is on). Your usage totals and dictionary are kept.'
      })
      if (response !== 0) return { ok: false, cancelled: true }
      db.clearHistory()
      broadcastHistoryChanged()
      if (session.hasRefreshToken) void sync.syncNow()
      return { ok: true }
    })
    ipcMain.handle('history:export', async (_event, format: unknown) => {
      const safeFormat: 'txt' | 'json' = format === 'json' ? 'json' : 'txt'
      const ext = safeFormat === 'json' ? 'json' : 'txt'
      const dateSlug = new Date().toISOString().slice(0, 10)
      const { canceled, filePath } = await dialog.showSaveDialog({
        title: 'Export dictation history',
        defaultPath: `livoice-history-${dateSlug}.${ext}`,
        filters: [
          safeFormat === 'json'
            ? { name: 'JSON', extensions: ['json'] }
            : { name: 'Plain Text', extensions: ['txt'] }
        ]
      })
      if (canceled || !filePath) return { ok: false, cancelled: true }
      try {
        writeFileSync(filePath, db.exportHistory(safeFormat), 'utf-8')
        return { ok: true, filePath }
      } catch (error) {
        return {
          ok: false,
          message: error instanceof Error ? error.message : 'Could not write export file.'
        }
      }
    })

    ipcMain.handle('dictionary:list', (_event, query: unknown) =>
      db.listTerms(typeof query === 'string' ? query : '')
    )
    ipcMain.handle('dictionary:add', (_event, term: unknown) => {
      if (typeof term !== 'string') return false
      const added = db.addTerm(term)
      if (added && session.hasRefreshToken) void sync.syncNow()
      return added
    })
    ipcMain.handle('dictionary:edit', (_event, id: unknown, term: unknown) => {
      if (typeof id !== 'number' || !Number.isSafeInteger(id) || typeof term !== 'string') {
        return false
      }
      const updated = db.editTerm(id, term)
      if (updated && session.hasRefreshToken) void sync.syncNow()
      return updated
    })
    ipcMain.handle('dictionary:delete', (_event, id: unknown) => {
      if (typeof id !== 'number' || !Number.isSafeInteger(id)) return
      db.deleteTerm(id)
      if (session.hasRefreshToken) void sync.syncNow()
    })

    ipcMain.handle('sync:now', () => sync.syncNow())
    ipcMain.handle('sync:status', () => currentSyncStatus())
    ipcMain.handle(
      'sync:updateSettings',
      async (_event, changes: { historySyncEnabled?: unknown; historyRetentionDays?: unknown }) => {
        const update: { historySyncEnabled?: boolean; historyRetentionDays?: number | null } = {}
        if (typeof changes?.historySyncEnabled === 'boolean') {
          if (!changes.historySyncEnabled) {
            const { response } = await dialog.showMessageBox({
              type: 'warning',
              buttons: ['Stop and delete', 'Keep syncing'],
              defaultId: 1,
              cancelId: 1,
              message: 'Stop syncing history?',
              detail:
                'LiVoice will delete the dictated text stored in your account. History already on this ' +
                'Mac and your other devices stays there; new dictations stay on the device that made them.'
            })
            if (response !== 0) return { ok: false, cancelled: true }
          }
          update.historySyncEnabled = changes.historySyncEnabled
        }
        if (
          changes?.historyRetentionDays === null ||
          [30, 90, 365].includes(Number(changes?.historyRetentionDays))
        ) {
          update.historyRetentionDays =
            changes.historyRetentionDays === null ? null : Number(changes.historyRetentionDays)
        }
        try {
          const updated = await sync.updateSettings(update)
          broadcastSyncChanged()
          broadcastHistoryChanged()
          if (update.historySyncEnabled === true) void sync.syncNow()
          return { ok: true, settings: updated }
        } catch (error) {
          return {
            ok: false,
            message: error instanceof Error ? error.message : 'The setting could not be saved.'
          }
        }
      }
    )

    ipcMain.handle(
      'app:policyNotice',
      () => settings.get<string | null>('acknowledgedPolicyVersion', null) !== POLICY_VERSION
    )
    ipcMain.handle('app:dismissPolicyNotice', () => {
      settings.set('acknowledgedPolicyVersion', POLICY_VERSION)
    })
    ipcMain.handle('app:setLoginItem', (_event, openAtLogin: unknown) => {
      if (typeof openAtLogin !== 'boolean') return app.getLoginItemSettings().openAtLogin
      app.setLoginItemSettings({ openAtLogin })
      return app.getLoginItemSettings().openAtLogin
    })
    ipcMain.handle('app:getLoginItem', () => app.getLoginItemSettings().openAtLogin)

    // ---- Overlay IPC (strict sender verification) ----
    const isFromOverlay = (senderId: number): boolean => {
      const overlayId = dictation.getOverlayWebContentsId()
      return overlayId !== null && senderId === overlayId
    }

    ipcMain.on(
      'overlay:done',
      (
        event,
        payload: {
          sessionId?: unknown
          wav?: unknown
          durationMs?: unknown
          containsSpeech?: unknown
        }
      ) => {
        if (!isFromOverlay(event.sender.id)) return
        if (
          typeof payload?.sessionId !== 'number' ||
          typeof payload?.durationMs !== 'number' ||
          typeof payload?.containsSpeech !== 'boolean' ||
          !(payload.wav instanceof ArrayBuffer || ArrayBuffer.isView(payload.wav))
        ) {
          return
        }
        const wavBuffer =
          payload.wav instanceof ArrayBuffer
            ? payload.wav
            : payload.wav.buffer.slice(
                payload.wav.byteOffset,
                payload.wav.byteOffset + payload.wav.byteLength
              )
        console.log(
          '[overlay] done received:',
          payload.durationMs,
          'ms, speech =',
          payload.containsSpeech
        )
        void dictation.handleCapture({
          sessionId: payload.sessionId,
          wav: wavBuffer as ArrayBuffer,
          durationMs: payload.durationMs,
          containsSpeech: payload.containsSpeech
        })
      }
    )
    ipcMain.on('overlay:cancelled', (event, id: unknown) => {
      if (!isFromOverlay(event.sender.id)) return
      if (typeof id === 'number' && dictation.matchesSession(id)) dictation.cancelUser()
    })
    ipcMain.on('overlay:dismiss', (event) => {
      if (!isFromOverlay(event.sender.id)) return
      dictation.dismissOverlay()
    })
    ipcMain.on('overlay:autoStop', (event, id: unknown) => {
      if (!isFromOverlay(event.sender.id)) return
      if (typeof id === 'number' && dictation.matchesSession(id)) dictation.autoStop()
    })
    ipcMain.on('overlay:fail', (event, message: unknown, id: unknown) => {
      if (!isFromOverlay(event.sender.id)) return
      if (typeof id === 'number' && typeof message === 'string' && dictation.matchesSession(id)) {
        dictation.fail(message)
      }
    })

    triggers.syncRegistration()
    if (!settings.startHidden) {
      createDashboard()
    }
    try {
      createTray()
    } catch (error) {
      // Cosmetic — a tray failure must never take down dictation setup.
      console.error('[tray] failed:', error)
    }
    dictation.prepare()

    // Automatic startup & periodic sync when signed in.
    if (session.hasRefreshToken) {
      void sync.syncNow()
    }
    periodicSyncTimer = setInterval(() => {
      if (session.hasRefreshToken) void sync.syncNow()
    }, PERIODIC_SYNC_INTERVAL_MS)
    periodicSyncTimer.unref()

    app.on('activate', () => {
      showDashboard()
      onAppForeground()
    })

    if (pendingDeepLink !== null) {
      routeDeepLink(pendingDeepLink)
      pendingDeepLink = null
    }
  })

  app.on('will-quit', () => {
    triggers.unregister()
    desktopAuth?.dispose()
    desktopAuth = null
    if (deferredSync !== null) {
      clearTimeout(deferredSync)
      deferredSync = null
    }
    if (periodicSyncTimer !== null) {
      clearInterval(periodicSyncTimer)
      periodicSyncTimer = null
    }
    dictation.dispose()
    globalShortcut.unregisterAll()
    try {
      db.close()
    } catch {
      // ignore close errors on exit
    }
  })

  app.on('window-all-closed', () => {
    // Tray keeps the app alive on macOS; quit only via the tray menu or ⌘Q.
  })
}

function countWords(text: string): number {
  const matches = text.match(/[\p{L}\p{N}]+(?:['’\u2010-\u2015-][\p{L}\p{N}]+)*/gu)
  return matches === null ? 0 : matches.length
}

async function accessibilityTrusted(): Promise<boolean> {
  return process.platform !== 'darwin' || systemPreferences.isTrustedAccessibilityClient(false)
}
