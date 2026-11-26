import { contextBridge, ipcRenderer } from 'electron'

export interface AuthStateSnapshot {
  signedIn: boolean
  email?: string | null
  error?: string
  pendingIntent?: 'login' | 'delete' | null
  accountState?: 'active' | 'suspended' | 'banned'
  accountPublicMessage?: string | null
  accountSuspendedUntil?: number | null
  accountSupportEmail?: string
  lastDictation?: string
}

export interface ProfileSnapshot {
  user: {
    id: string
    email: string
    role: 'user' | 'admin'
    accountStatus: {
      state: 'active' | 'suspended' | 'banned'
      suspendedUntil: number | null
      publicMessage: string | null
      supportEmail: string
    }
  }
  quota: {
    limitAudioSeconds: number
    usedAudioSeconds: number
    reservedAudioSeconds: number
    remainingAudioSeconds: number
    resetAt: number
  } | null
}

export interface SessionInfo {
  id: string
  deviceName: string
  createdAt: number
  lastSeenAt: number
  current: boolean
}

export interface PermissionsSnapshot {
  accessibility: boolean
  mic: string
}

export interface SettingsSnapshot {
  keyboardShortcutEnabled: boolean
  middleClickEnabled: boolean
  triggerKey: string
  workerUrl: string
  contextOwner: string
  contextLevel: 'off' | 'app' | 'nearby'
  contextStyle: 'auto' | 'natural' | 'polite' | 'professional' | 'concise'
  locale: string
  polish: 'off' | 'light' | 'strong'
  soundsEnabled: boolean
  startHidden: boolean
  micDeviceId: string
}

export type SettingsKey =
  | 'keyboardShortcutEnabled'
  | 'middleClickEnabled'
  | 'triggerKey'
  | 'contextLevel'
  | 'contextStyle'
  | 'locale'
  | 'polish'
  | 'soundsEnabled'
  | 'startHidden'
  | 'micDeviceId'

export interface HistoryRowSnapshot {
  requestId: string
  finalText: string
  createdAtMs: number
  wordCount: number
  audioDurationMs: number
  asrModel: string
  polished: boolean
  asrMs: number
  polishMs: number
  totalMs: number
}

export interface HomeStatsSnapshot {
  dictations: number
  audioDurationMs: number
  words: number
  recent: Array<{
    requestId: string
    finalText: string
    createdAtMs: number
    wordCount: number
    audioDurationMs: number
  }>
}

export interface TermRowSnapshot {
  id: number
  term: string
  source: string
  useCount: number
  lastUsedAtMs: number
}

export interface SyncStatusSnapshot {
  lastSyncAt: number
  historySyncEnabled: boolean
  historyRetentionDays: number | null
  syncWarning: string | null
}

export type OverlayStateEvent =
  | { state: 'transcribing'; contextLabel?: string }
  | { state: 'success'; text: string; polished: boolean }
  | { state: 'error'; message: string; copied?: boolean }
  | { state: 'cancelled' }
  | { state: 'silent-cancel' }

// Custom APIs for renderer (sandbox-safe — uses only contextBridge & ipcRenderer)
const api = {
  getVersion: (): Promise<string> => ipcRenderer.invoke('app:version'),
  signIn: (): Promise<{ ok: boolean; message?: string }> => ipcRenderer.invoke('auth:start'),
  cancelSignIn: (): Promise<void> => ipcRenderer.invoke('auth:cancel'),
  signOut: (): Promise<{ ok: boolean; warning?: string }> => ipcRenderer.invoke('auth:signOut'),
  authState: (): Promise<AuthStateSnapshot> => ipcRenderer.invoke('auth:state'),
  profile: (): Promise<ProfileSnapshot> => ipcRenderer.invoke('auth:profile'),
  listSessions: (): Promise<SessionInfo[]> => ipcRenderer.invoke('auth:sessions'),
  revokeSession: (
    sessionId: string
  ): Promise<{ ok: boolean; signedOut?: boolean; message?: string }> =>
    ipcRenderer.invoke('auth:revokeSession', sessionId),
  deleteAccount: (): Promise<{ ok: boolean; message?: string }> =>
    ipcRenderer.invoke('auth:deleteAccount'),
  onAuthState: (listener: (state: AuthStateSnapshot) => void): (() => void) => {
    const wrapped = (_event: unknown, state: AuthStateSnapshot): void => listener(state)
    ipcRenderer.on('auth:state', wrapped)
    return () => ipcRenderer.removeListener('auth:state', wrapped)
  },
  onHistoryChanged: (listener: () => void): (() => void) => {
    const wrapped = (): void => listener()
    ipcRenderer.on('history:changed', wrapped)
    return () => ipcRenderer.removeListener('history:changed', wrapped)
  },
  onSyncChanged: (listener: (status: SyncStatusSnapshot) => void): (() => void) => {
    const wrapped = (_event: unknown, status: SyncStatusSnapshot): void => listener(status)
    ipcRenderer.on('sync:changed', wrapped)
    return () => ipcRenderer.removeListener('sync:changed', wrapped)
  },
  onPermissionsChanged: (listener: (perms: PermissionsSnapshot) => void): (() => void) => {
    const wrapped = (_event: unknown, perms: PermissionsSnapshot): void => listener(perms)
    ipcRenderer.on('permissions:changed', wrapped)
    return () => ipcRenderer.removeListener('permissions:changed', wrapped)
  },
  permissionsCheck: (): Promise<PermissionsSnapshot> => ipcRenderer.invoke('permissions:check'),
  enableMicrophone: (): Promise<{ granted: boolean; openedSettings?: boolean }> =>
    ipcRenderer.invoke('permissions:enableMicrophone'),
  enableAccessibility: (): Promise<boolean> =>
    ipcRenderer.invoke('permissions:enableAccessibility'),
  openAccessibilityPane: (): Promise<void> =>
    ipcRenderer.invoke('permissions:openAccessibilityPane'),
  openAutomationPane: (): Promise<void> => ipcRenderer.invoke('permissions:openAutomationPane'),
  settingsGet: (): Promise<SettingsSnapshot> => ipcRenderer.invoke('settings:get'),
  settingsSet: (
    key: SettingsKey,
    value: boolean | string,
    contextOwner?: string
  ): Promise<boolean> => ipcRenderer.invoke('settings:set', key, value, contextOwner),
  homeStats: (period: 'today' | '7d' | '30d' | 'all'): Promise<HomeStatsSnapshot> =>
    ipcRenderer.invoke('stats:home', period),
  historyList: (query: string): Promise<HistoryRowSnapshot[]> =>
    ipcRenderer.invoke('history:list', query),
  historyDelete: (requestId: string): Promise<void> =>
    ipcRenderer.invoke('history:delete', requestId),
  historyRestore: (requestId: string): Promise<boolean> =>
    ipcRenderer.invoke('history:restore', requestId),
  historyCopy: (text: string): Promise<void> => ipcRenderer.invoke('history:copy', text),
  copyLastDictation: (): Promise<boolean> => ipcRenderer.invoke('history:copyLast'),
  clearHistory: (): Promise<{ ok: boolean; cancelled?: boolean }> =>
    ipcRenderer.invoke('history:clear'),
  exportHistory: (
    format: 'txt' | 'json'
  ): Promise<{ ok: boolean; cancelled?: boolean; filePath?: string; message?: string }> =>
    ipcRenderer.invoke('history:export', format),
  dictionaryList: (query: string): Promise<TermRowSnapshot[]> =>
    ipcRenderer.invoke('dictionary:list', query),
  dictionaryAdd: (term: string): Promise<boolean> => ipcRenderer.invoke('dictionary:add', term),
  editTerm: (id: number, term: string): Promise<boolean> =>
    ipcRenderer.invoke('dictionary:edit', id, term),
  dictionaryDelete: (id: number): Promise<void> => ipcRenderer.invoke('dictionary:delete', id),
  setLoginItem: (openAtLogin: boolean): Promise<boolean> =>
    ipcRenderer.invoke('app:setLoginItem', openAtLogin),
  syncNow: (): Promise<{
    kind: string
    uploaded?: number
    downloaded?: number
    message?: string
    warning?: string
  }> => ipcRenderer.invoke('sync:now'),
  syncStatus: (): Promise<SyncStatusSnapshot> => ipcRenderer.invoke('sync:status'),
  updateSyncSettings: (changes: {
    historySyncEnabled?: boolean
    historyRetentionDays?: number | null
  }): Promise<{ ok: boolean; cancelled?: boolean; message?: string }> =>
    ipcRenderer.invoke('sync:updateSettings', changes),
  policyNotice: (): Promise<boolean> => ipcRenderer.invoke('app:policyNotice'),
  dismissPolicyNotice: (): Promise<void> => ipcRenderer.invoke('app:dismissPolicyNotice'),
  getLoginItem: (): Promise<boolean> => ipcRenderer.invoke('app:getLoginItem'),
  overlay: {
    done: (payload: {
      sessionId: number
      wav: ArrayBuffer
      durationMs: number
      containsSpeech: boolean
    }): void => ipcRenderer.send('overlay:done', payload),
    cancelled: (id: number): void => ipcRenderer.send('overlay:cancelled', id),
    dismiss: (): void => ipcRenderer.send('overlay:dismiss'),
    autoStop: (id: number): void => ipcRenderer.send('overlay:autoStop', id),
    fail: (message: string, id: number): void => ipcRenderer.send('overlay:fail', message, id),
    onBegin: (
      listener: (releaseHint: string, sessionId: number, micDeviceId: string) => void
    ): void => {
      ipcRenderer.on(
        'overlay:begin',
        (_event, releaseHint: unknown, sessionId: unknown, micDeviceId: unknown) =>
          listener(
            typeof releaseHint === 'string' ? releaseHint : 'Release ⌥',
            typeof sessionId === 'number' ? sessionId : 0,
            typeof micDeviceId === 'string' ? micDeviceId : ''
          )
      )
    },
    onEnd: (listener: () => void): void => {
      ipcRenderer.on('overlay:end', () => listener())
    },
    onLatch: (listener: (hint: string) => void): void => {
      ipcRenderer.on('overlay:latch', (_event, hint: unknown) =>
        listener(typeof hint === 'string' ? hint : 'Tap trigger again')
      )
    },
    onState: (listener: (state: OverlayStateEvent) => void): void => {
      ipcRenderer.on('overlay:state', (_event, state: OverlayStateEvent) => listener(state))
    }
  }
}

export type Api = typeof api

if (process.contextIsolated) {
  try {
    contextBridge.exposeInMainWorld('api', api)
  } catch (error) {
    console.error(error)
  }
} else {
  // @ts-ignore (define in dts)
  window.api = api
}
