import { useCallback, useEffect, useRef, useState } from 'react'
import type {
  AuthStateSnapshot,
  HistoryRowSnapshot,
  HomeStatsSnapshot,
  PermissionsSnapshot,
  ProfileSnapshot,
  SessionInfo,
  SettingsKey,
  SettingsSnapshot,
  SyncStatusSnapshot,
  TermRowSnapshot
} from '../../preload/index'

type Tab = 'home' | 'history' | 'dictionary' | 'account' | 'settings'
type Period = 'today' | '7d' | '30d' | 'all'

const MAIN_TABS: Array<[Tab, string]> = [
  ['home', 'Home'],
  ['history', 'History'],
  ['dictionary', 'Dictionary'],
  ['account', 'Account'],
  ['settings', 'Settings']
]

const TRIGGER_KEY_OPTIONS: Array<{ id: string; label: string }> = [
  { id: 'option', label: '⌥ Left Option (default)' },
  { id: 'option-right', label: '⌥ Right Option' },
  { id: 'command', label: '⌘ Left Command' },
  { id: 'command-right', label: '⌘ Right Command' },
  { id: 'caps-lock', label: '⇪ Caps Lock' },
  { id: 'fn', label: '🌐 Fn / Globe' }
]

const LOCALE_OPTIONS: Array<{ id: string; label: string }> = [
  { id: 'en-IN', label: 'English (India — default)' },
  { id: 'auto', label: 'Auto-detect language' },
  { id: 'en-US', label: 'English (US)' },
  { id: 'en-GB', label: 'English (UK)' },
  { id: 'hi-IN', label: 'Hindi (India)' },
  { id: 'bn-BD', label: 'Bengali' },
  { id: 'es-ES', label: 'Spanish' },
  { id: 'fr-FR', label: 'French' },
  { id: 'de-DE', label: 'German' },
  { id: 'pt-BR', label: 'Portuguese (Brazil)' },
  { id: 'ja-JP', label: 'Japanese' },
  { id: 'zh-CN', label: 'Chinese (Simplified)' },
  { id: 'ar-SA', label: 'Arabic' }
]

const POLISH_OPTIONS: Array<{ id: 'off' | 'light' | 'strong'; label: string }> = [
  { id: 'off', label: 'Off (raw transcript)' },
  { id: 'light', label: 'Light (default)' },
  { id: 'strong', label: 'Strong (full cleanup)' }
]

const PERIODS: Array<[Period, string]> = [
  ['today', 'Today'],
  ['7d', '7 days'],
  ['30d', '30 days'],
  ['all', 'All time']
]

const RETENTION_OPTIONS: Array<[number | null, string]> = [
  [null, 'Never'],
  [30, 'After 30 days'],
  [90, 'After 90 days'],
  [365, 'After 1 year']
]

function formatDuration(ms: number): string {
  if (ms < 60_000) return `${Math.round(ms / 1000)} sec`
  return `${Math.floor(ms / 60_000)}m ${Math.round((ms % 60_000) / 1000)}s`
}

function formatWhen(ms: number): string {
  return new Date(ms).toLocaleString([], {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit'
  })
}

function formatWords(count: number): string {
  return `${count} ${count === 1 ? 'word' : 'words'}`
}

/** "⌥ Left Option (default)" → "⌥ Left Option", for sentences about the chosen key. */
function triggerLabel(triggerKey: string): string {
  const option = TRIGGER_KEY_OPTIONS.find((candidate) => candidate.id === triggerKey)
  return (option?.label ?? '⌥ Left Option').replace(' (default)', '')
}

function ToggleSwitch({
  id,
  labelledBy,
  checked,
  disabled,
  onToggle
}: {
  id?: string
  labelledBy: string
  checked: boolean
  disabled?: boolean
  onToggle: () => void
}): React.JSX.Element {
  return (
    <button
      id={id}
      type="button"
      role="switch"
      aria-checked={checked}
      aria-labelledby={labelledBy}
      className="switch"
      disabled={disabled}
      onClick={onToggle}
    >
      <span className="switch-knob" aria-hidden="true" />
      <span>{checked ? 'On' : 'Off'}</span>
    </button>
  )
}

function Dashboard(): React.JSX.Element {
  const [tab, setTab] = useState<Tab>('home')
  const [auth, setAuth] = useState<AuthStateSnapshot>({ signedIn: false })
  const [profile, setProfile] = useState<ProfileSnapshot | null>(null)
  const [permissions, setPermissions] = useState<PermissionsSnapshot | null>(null)
  const [settings, setSettings] = useState<SettingsSnapshot | null>(null)
  const [contextConsent, setContextConsent] = useState<{
    level: 'app' | 'nearby'
    owner: string
  } | null>(null)
  const [version, setVersion] = useState('')
  const [loginItem, setLoginItem] = useState(false)
  const [busy, setBusy] = useState(false)
  const [confirmSignOut, setConfirmSignOut] = useState(false)
  const [signOutWarning, setSignOutWarning] = useState<string | null>(null)
  const [policyNotice, setPolicyNotice] = useState(false)
  const [historyRevision, setHistoryRevision] = useState(0)
  const [toast, setToast] = useState<string | null>(null)
  const [micDevices, setMicDevices] = useState<Array<{ deviceId: string; label: string }>>([])
  const [micNote, setMicNote] = useState<string | null>(null)
  const toastTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  const showToast = useCallback((message: string): void => {
    setToast(message)
    if (toastTimerRef.current !== null) clearTimeout(toastTimerRef.current)
    toastTimerRef.current = setTimeout(() => {
      toastTimerRef.current = null
      setToast(null)
    }, 2400)
  }, [])

  useEffect(() => {
    return () => {
      if (toastTimerRef.current !== null) clearTimeout(toastTimerRef.current)
    }
  }, [])

  const refreshPermissions = useCallback((): void => {
    window.api
      .permissionsCheck()
      .then(setPermissions)
      .catch(() => setPermissions(null))
  }, [])

  const refreshMicDevices = useCallback((): void => {
    if (!navigator.mediaDevices?.enumerateDevices) return
    navigator.mediaDevices
      .enumerateDevices()
      .then((devices) => {
        const inputs = devices
          .filter((device) => device.kind === 'audioinput' && device.deviceId !== 'default')
          .map((device, index) => ({
            deviceId: device.deviceId,
            label: device.label.trim() || `Microphone ${index + 1}`
          }))
        setMicDevices(inputs)
      })
      .catch(() => setMicDevices([]))
  }, [])

  useEffect(() => {
    window.api
      .policyNotice()
      .then(setPolicyNotice)
      .catch(() => setPolicyNotice(false))
  }, [])

  useEffect(() => {
    const loadProfile = (): void => {
      window.api
        .profile()
        .then(setProfile)
        .catch(() => setProfile(null))
    }
    window.api.authState().then((state) => {
      setAuth(state)
      if (state.signedIn) loadProfile()
    })
    window.api
      .settingsGet()
      .then(setSettings)
      .catch(() => setSettings(null))
    window.api.getVersion().then(setVersion)
    window.api.getLoginItem().then(setLoginItem)
    refreshPermissions()
    refreshMicDevices()

    const unsubAuth = window.api.onAuthState((state) => {
      setContextConsent(null)
      setConfirmSignOut(false)
      void window.api
        .settingsGet()
        .then(setSettings)
        .catch(() => setSettings(null))
      setAuth(state)
      if (state.signedIn) loadProfile()
      else setProfile(null)
    })
    const unsubHistory = window.api.onHistoryChanged(() => {
      setHistoryRevision((value) => value + 1)
    })
    const unsubPerms = window.api.onPermissionsChanged((perms) => {
      setPermissions(perms)
      if (perms.mic === 'granted') refreshMicDevices()
    })
    const onWindowFocus = (): void => {
      refreshPermissions()
      refreshMicDevices()
    }
    window.addEventListener('focus', onWindowFocus)

    return () => {
      unsubAuth()
      unsubHistory()
      unsubPerms()
      window.removeEventListener('focus', onWindowFocus)
    }
  }, [refreshPermissions, refreshMicDevices])

  const signIn = (): void => {
    setSignOutWarning(null)
    setBusy(true)
    window.api.signIn().finally(() => setBusy(false))
  }

  const cancelSignIn = (): void => {
    void window.api.cancelSignIn()
  }

  const performSignOut = (): void => {
    setBusy(true)
    setConfirmSignOut(false)
    window.api
      .signOut()
      .then((result) => {
        setSignOutWarning(result.warning ?? null)
      })
      .finally(() => setBusy(false))
  }

  const updateSetting = (
    key: SettingsKey,
    value: boolean | string,
    contextOwner?: string
  ): void => {
    if (settings === null) return
    setSettings({ ...settings, [key]: value })
    void window.api.settingsSet(key, value, contextOwner)
  }

  const enableMicrophone = (): void => {
    setMicNote(null)
    void window.api
      .enableMicrophone()
      .then((res) => {
        if (res.openedSettings) {
          setMicNote(
            'Microphone access was previously denied. Enable LiVoice in System Settings → Privacy & Security → Microphone.'
          )
        }
        refreshPermissions()
        refreshMicDevices()
      })
      .catch(() => refreshPermissions())
  }

  const enableAccessibility = (): void => {
    void window.api
      .enableAccessibility()
      .then(() => refreshPermissions())
      .catch(() => refreshPermissions())
  }

  const handleMainTabKeyDown = (
    event: React.KeyboardEvent<HTMLButtonElement>,
    index: number
  ): void => {
    let nextIndex: number | null = null
    if (event.key === 'ArrowRight') nextIndex = (index + 1) % MAIN_TABS.length
    else if (event.key === 'ArrowLeft')
      nextIndex = (index - 1 + MAIN_TABS.length) % MAIN_TABS.length
    else if (event.key === 'Home') nextIndex = 0
    else if (event.key === 'End') nextIndex = MAIN_TABS.length - 1
    if (nextIndex === null) return
    event.preventDefault()
    const [nextId] = MAIN_TABS[nextIndex]
    setTab(nextId)
    document.getElementById(`tab-${nextId}`)?.focus()
  }

  const missingPermissions =
    permissions !== null && (!permissions.accessibility || permissions.mic !== 'granted')

  const effectiveAccountState = profile?.user.accountStatus.state ?? auth.accountState ?? 'active'
  const effectivePublicMessage =
    profile?.user.accountStatus.publicMessage ?? auth.accountPublicMessage ?? null
  const effectiveSuspendedUntil =
    profile?.user.accountStatus.suspendedUntil ?? auth.accountSuspendedUntil ?? null
  const effectiveSupportEmail =
    profile?.user.accountStatus.supportEmail ?? auth.accountSupportEmail ?? 'support@aliahad.com'

  return (
    <div className="shell">
      <header className="topbar">
        <div className="brand">
          <svg viewBox="0 0 108 108" width="24" height="24" aria-hidden="true">
            <rect
              width="108"
              height="108"
              rx="24"
              fill="#1A1B22"
              stroke="#2D2F3A"
              strokeWidth="2"
            />
            <path
              d="M 33.5,39.5 V 65.5 A 7.5,7.5 0 0 0 41,73 H 46"
              fill="none"
              stroke="#7DE4B6"
              strokeWidth="8.4"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
            <circle cx="58.5" cy="41.5" r="4.6" fill="#AFA4FF" />
            <path
              d="M 58.5,53.5 V 73"
              fill="none"
              stroke="#7DE4B6"
              strokeWidth="8.2"
              strokeLinecap="round"
            />
            <path
              d="M 60.79,30.74 A 11,11 0 0 1 69.47,42.27"
              fill="none"
              stroke="#AFA4FF"
              strokeWidth="4.8"
              strokeLinecap="round"
            />
            <path
              d="M 65.06,25.27 A 17.5,17.5 0 0 1 75.99,42.11"
              fill="none"
              stroke="#7DE4B6"
              strokeOpacity="0.92"
              strokeWidth="4.3"
              strokeLinecap="round"
            />
          </svg>
          LiVoice
        </div>
        <nav className="tabs" role="tablist" aria-label="Main navigation">
          {MAIN_TABS.map(([id, label], index) => (
            <button
              key={id}
              id={`tab-${id}`}
              type="button"
              role="tab"
              aria-selected={tab === id}
              aria-controls={`panel-${id}`}
              tabIndex={tab === id ? 0 : -1}
              className={`tab ${tab === id ? 'active' : ''}`}
              onClick={() => setTab(id)}
              onKeyDown={(event) => handleMainTabKeyDown(event, index)}
            >
              {label}
            </button>
          ))}
        </nav>
        <div className="account-chip">{auth.signedIn ? auth.email : 'Signed out'}</div>
      </header>

      <main className="content">
        {policyNotice && (
          <div className="card undo" role="region" aria-label="Privacy policy update">
            <span>
              Sync no longer needs a recovery key: your history and dictionary are stored encrypted
              in your LiVoice account.
            </span>
            <button
              type="button"
              className="action secondary"
              onClick={() => {
                void window.api.dismissPolicyNotice()
                setPolicyNotice(false)
              }}
            >
              Got it
            </button>
          </div>
        )}

        {tab === 'home' && (
          <section role="tabpanel" id="panel-home" aria-labelledby="tab-home">
            {missingPermissions && permissions && (
              <div className="banner warn" role="region" aria-label="Required macOS permissions">
                <div className="banner-title">Finish macOS permissions to dictate everywhere</div>
                <div className="hint" style={{ marginBottom: 0 }}>
                  {!permissions.accessibility && permissions.mic !== 'granted'
                    ? 'Microphone and Accessibility access are required for global voice dictation.'
                    : !permissions.accessibility
                      ? 'Accessibility access is required to detect your trigger key and insert text.'
                      : 'Microphone access is required to hear your voice during dictation.'}
                </div>
                <div className="banner-actions">
                  {permissions.mic !== 'granted' && (
                    <button type="button" className="action compact" onClick={enableMicrophone}>
                      Enable microphone
                    </button>
                  )}
                  {!permissions.accessibility && (
                    <button type="button" className="action compact" onClick={enableAccessibility}>
                      Enable Accessibility
                    </button>
                  )}
                  <button
                    type="button"
                    className="action secondary compact"
                    onClick={() => setTab('settings')}
                  >
                    Open permission settings
                  </button>
                </div>
              </div>
            )}
            <HomeTab
              signedIn={auth.signedIn}
              triggerLabel={triggerLabel(settings?.triggerKey ?? 'option')}
              middleClick={settings?.middleClickEnabled === true}
              revision={historyRevision}
              onCopied={showToast}
            />
          </section>
        )}

        {tab === 'history' && (
          <section role="tabpanel" id="panel-history" aria-labelledby="tab-history">
            <HistoryTab revision={historyRevision} onToast={showToast} />
          </section>
        )}

        {tab === 'dictionary' && (
          <section role="tabpanel" id="panel-dictionary" aria-labelledby="tab-dictionary">
            <DictionaryTab revision={historyRevision} onToast={showToast} />
          </section>
        )}

        {tab === 'account' && (
          <section role="tabpanel" id="panel-account" aria-labelledby="tab-account">
            <div className="hero">
              <h1>Account</h1>
              <p>Your LiVoice session lives in this Mac&apos;s Keychain.</p>
            </div>

            {auth.signedIn && effectiveAccountState !== 'active' && (
              <div className="banner danger" role="alert">
                <div className="banner-title">
                  {effectiveAccountState === 'banned'
                    ? 'Account restricted'
                    : 'Account temporarily suspended'}
                </div>
                <p style={{ marginBottom: 6 }}>
                  {effectivePublicMessage ??
                    (effectiveAccountState === 'banned'
                      ? 'Voice dictation and cloud sync are disabled for this account.'
                      : 'Voice dictation and cloud sync are temporarily paused for this account.')}
                </p>
                {effectiveSuspendedUntil !== null && (
                  <p className="hint" style={{ marginBottom: 6 }}>
                    Suspended until {formatWhen(effectiveSuspendedUntil)}.
                  </p>
                )}
                <p className="hint" style={{ marginBottom: 0 }}>
                  Questions? Contact{' '}
                  <a href={`mailto:${effectiveSupportEmail}`}>{effectiveSupportEmail}</a>.
                </p>
              </div>
            )}

            <div className="card">
              {auth.signedIn ? (
                <>
                  <div className="stat-row">
                    <span className="label">Email</span>
                    <span className="value">{auth.email ?? profile?.user.email}</span>
                  </div>
                  {profile?.quota && (
                    <>
                      <div className="stat-row">
                        <span className="label">Today&apos;s quota</span>
                        <span className="value">
                          {Math.round(profile.quota.remainingAudioSeconds)} of{' '}
                          {Math.round(profile.quota.limitAudioSeconds)} seconds left
                        </span>
                      </div>
                      {profile.quota.resetAt !== undefined && (
                        <div className="stat-row">
                          <span className="label">Quota resets</span>
                          <span className="value">
                            {new Date(profile.quota.resetAt).toLocaleTimeString([], {
                              hour: '2-digit',
                              minute: '2-digit'
                            })}
                          </span>
                        </div>
                      )}
                    </>
                  )}
                  <div className="banner-actions" style={{ marginTop: 14 }}>
                    {confirmSignOut ? (
                      <>
                        <button
                          type="button"
                          className="action danger"
                          onClick={performSignOut}
                          disabled={busy}
                        >
                          Confirm sign out
                        </button>
                        <button
                          type="button"
                          className="action secondary"
                          onClick={() => setConfirmSignOut(false)}
                          disabled={busy}
                        >
                          Cancel
                        </button>
                      </>
                    ) : (
                      <button
                        type="button"
                        className="action danger"
                        onClick={() => setConfirmSignOut(true)}
                        disabled={busy}
                      >
                        Sign out
                      </button>
                    )}
                  </div>
                  {auth.error && (
                    <p className="error" role="alert">
                      {auth.error}
                    </p>
                  )}
                </>
              ) : (
                <>
                  <p>
                    Sign in to use voice dictation. Your refresh token is sealed in the Keychain.
                  </p>
                  {auth.pendingIntent === 'login' ? (
                    <div className="banner-actions" role="status" aria-live="polite">
                      <button type="button" className="action" disabled>
                        Waiting for browser…
                      </button>
                      <button type="button" className="action secondary" onClick={cancelSignIn}>
                        Cancel
                      </button>
                    </div>
                  ) : (
                    <button type="button" className="action" onClick={signIn} disabled={busy}>
                      Sign in or create account
                    </button>
                  )}
                  {signOutWarning && (
                    <p className="hint" role="status" aria-live="polite" style={{ marginTop: 10 }}>
                      {signOutWarning}
                    </p>
                  )}
                  {auth.error && (
                    <p className="error" role="alert">
                      {auth.error}
                    </p>
                  )}
                </>
              )}
            </div>

            {auth.signedIn && <SessionsCard onToast={showToast} />}
            <SyncCard signedIn={auth.signedIn} />
            {auth.signedIn && (
              <DeleteAccountCard
                pendingDelete={auth.pendingIntent === 'delete'}
                onCancel={cancelSignIn}
                onToast={showToast}
              />
            )}
          </section>
        )}

        {tab === 'settings' && (
          <section role="tabpanel" id="panel-settings" aria-labelledby="tab-settings">
            <div className="hero">
              <h1>Settings</h1>
              <p>Language, audio, triggers, permissions, and startup for system-wide dictation.</p>
            </div>

            <div className="card">
              <h2>Dictation &amp; audio</h2>
              <div className="stat-row">
                <label htmlFor="setting-locale">Language</label>
                <select
                  id="setting-locale"
                  className="select"
                  value={settings?.locale ?? 'en-IN'}
                  onChange={(event) => updateSetting('locale', event.target.value)}
                >
                  {LOCALE_OPTIONS.map((option) => (
                    <option key={option.id} value={option.id}>
                      {option.label}
                    </option>
                  ))}
                </select>
              </div>
              <div className="stat-row">
                <label htmlFor="setting-polish">Transcript polish</label>
                <select
                  id="setting-polish"
                  className="select"
                  value={settings?.polish ?? 'light'}
                  onChange={(event) => updateSetting('polish', event.target.value)}
                >
                  {POLISH_OPTIONS.map((option) => (
                    <option key={option.id} value={option.id}>
                      {option.label}
                    </option>
                  ))}
                </select>
              </div>
              <div className="stat-row">
                <label htmlFor="setting-mic">Microphone input</label>
                <div className="row-actions">
                  <select
                    id="setting-mic"
                    className="select"
                    value={settings?.micDeviceId ?? ''}
                    onChange={(event) => updateSetting('micDeviceId', event.target.value)}
                  >
                    <option value="">System default microphone</option>
                    {micDevices.map((device) => (
                      <option key={device.deviceId} value={device.deviceId}>
                        {device.label}
                      </option>
                    ))}
                  </select>
                  <button
                    type="button"
                    className="action secondary compact"
                    onClick={refreshMicDevices}
                    aria-label="Refresh microphone list"
                  >
                    Refresh
                  </button>
                </div>
              </div>
              <div className="stat-row">
                <span id="label-sounds" className="label">
                  Dictation sound effects
                </span>
                <ToggleSwitch
                  labelledBy="label-sounds"
                  checked={settings?.soundsEnabled ?? true}
                  disabled={settings === null}
                  onToggle={() =>
                    updateSetting('soundsEnabled', !(settings?.soundsEnabled ?? true))
                  }
                />
              </div>
            </div>

            <div className="card">
              <h2>Automatic context</h2>
              <p className="hint">
                LiVoice detects the writing situation when you start speaking. You do not need to
                select chat, email, or a recipient each time. Unknown situations keep your natural
                tone.
              </p>
              <div className="stat-row">
                <label htmlFor="context-level">Context access</label>
                <select
                  id="context-level"
                  className="select"
                  value={settings?.contextLevel ?? 'off'}
                  onChange={async (event) => {
                    if (!settings) return
                    const next = event.target.value as SettingsSnapshot['contextLevel']
                    setContextConsent(null)
                    if (next !== 'off') {
                      setContextConsent({ level: next, owner: settings.contextOwner })
                    } else if (
                      await window.api.settingsSet('contextLevel', next, settings.contextOwner)
                    ) {
                      setSettings((current) =>
                        current?.contextOwner === settings.contextOwner
                          ? { ...current, contextLevel: next }
                          : current
                      )
                    }
                  }}
                >
                  <option value="off">Off</option>
                  <option value="app">App and field only</option>
                  <option value="nearby">App, field, and nearby text</option>
                </select>
              </div>
              {contextConsent && (
                <div className="card" role="region" aria-label="Context access consent">
                  <h3>
                    {contextConsent.level === 'nearby'
                      ? 'Allow nearby text?'
                      : 'Allow app and field context?'}
                  </h3>
                  <p className="hint">
                    {contextConsent.level === 'nearby'
                      ? 'When you start dictation, up to 512 characters before and 128 after the selection may be sent to LiVoice’s Cloudflare service with your recording to guide grammar and tone. These snippets are used for that request only and are not saved in history, sync, or logs. Full conversations and contacts are not read.'
                      : 'When you start dictation, LiVoice detects the app and field locally and sends only a writing-task category, such as chat or email. App names, field labels, and existing text are not sent.'}{' '}
                    You can turn this off at any time.
                  </p>
                  <div className="history-actions">
                    <button
                      type="button"
                      className="action secondary"
                      onClick={() => setContextConsent(null)}
                    >
                      Cancel
                    </button>
                    <button
                      type="button"
                      className="action"
                      onClick={async () => {
                        const pending = contextConsent
                        if (
                          await window.api.settingsSet('contextLevel', pending.level, pending.owner)
                        ) {
                          setSettings((current) =>
                            current?.contextOwner === pending.owner
                              ? { ...current, contextLevel: pending.level }
                              : current
                          )
                        }
                        setContextConsent(null)
                      }}
                    >
                      Allow context
                    </button>
                  </div>
                </div>
              )}
              <div className="stat-row">
                <label htmlFor="context-style">Style preference</label>
                <select
                  id="context-style"
                  className="select"
                  value={settings?.contextStyle ?? 'auto'}
                  onChange={async (event) => {
                    if (!settings) return
                    const next = event.target.value as SettingsSnapshot['contextStyle']
                    if (await window.api.settingsSet('contextStyle', next, settings.contextOwner)) {
                      setSettings((current) =>
                        current?.contextOwner === settings.contextOwner
                          ? { ...current, contextStyle: next }
                          : current
                      )
                    }
                  }}
                >
                  <option value="auto">Automatic (recommended)</option>
                  <option value="natural">Natural</option>
                  <option value="polite">Polite</option>
                  <option value="professional">Professional</option>
                  <option value="concise">Concise</option>
                </select>
              </div>
              <p className="hint">
                Nearby text uses only the focused field. Explicit clues such as “Dear Professor” can
                guide politeness; an app alone does not identify a relationship. Password fields are
                always excluded.
              </p>
            </div>

            <div className="card">
              <h2>Dictation triggers</h2>
              <div className="stat-row">
                <label htmlFor="trigger-key">Hold-to-talk key</label>
                <select
                  id="trigger-key"
                  className="select"
                  value={settings?.triggerKey ?? 'option'}
                  onChange={(event) => updateSetting('triggerKey', event.target.value)}
                >
                  {TRIGGER_KEY_OPTIONS.map((option) => (
                    <option key={option.id} value={option.id}>
                      {option.label}
                    </option>
                  ))}
                </select>
              </div>
              <p className="hint">
                Hold the key and speak; release to insert. A quick tap latches — tap again to stop.
                Normal shortcuts (like ⌥← or ⌘C) cancel automatically.
              </p>
              <div className="stat-row">
                <span id="label-kbd-trigger" className="label">
                  Keyboard trigger enabled
                </span>
                <ToggleSwitch
                  labelledBy="label-kbd-trigger"
                  checked={settings?.keyboardShortcutEnabled ?? true}
                  disabled={settings === null}
                  onToggle={() =>
                    updateSetting(
                      'keyboardShortcutEnabled',
                      !(settings?.keyboardShortcutEnabled ?? true)
                    )
                  }
                />
              </div>
              <div className="stat-row">
                <span id="label-mouse-trigger" className="label">
                  Hold middle-click to dictate
                </span>
                <ToggleSwitch
                  labelledBy="label-mouse-trigger"
                  checked={settings?.middleClickEnabled ?? false}
                  disabled={settings === null}
                  onToggle={() =>
                    updateSetting('middleClickEnabled', !(settings?.middleClickEnabled ?? false))
                  }
                />
              </div>
            </div>

            <div className="card">
              <h2>Permissions &amp; access</h2>
              <p className="hint">
                Microphone access records your voice when you start dictation. Accessibility
                supports the trigger key, inserts text natively, and reads optional focused-editor
                context when enabled above. LiVoice does not capture screen images, files, or
                browsing history.
              </p>
              <div className="stat-row">
                <span className="label">
                  <strong>1 · Microphone</strong> — to hear you while you dictate
                </span>
                <span className="value">
                  {permissions === null ? 'checking…' : permissions.mic}
                </span>
              </div>
              <p className="hint">
                Audio is captured only while recording, sent to your LiVoice service for
                transcription, then discarded. It is never stored on the server.
              </p>
              <p>
                <button type="button" className="action" onClick={enableMicrophone}>
                  {permissions?.mic === 'denied'
                    ? 'Open Microphone Privacy settings'
                    : 'Enable microphone'}
                </button>
              </p>
              {micNote && (
                <p className="hint" role="status" aria-live="polite">
                  {micNote}
                </p>
              )}
              <div className="stat-row">
                <span className="label">
                  <strong>2 · Accessibility</strong> — for the trigger key and native pasting
                </span>
                <span className="value">
                  {permissions === null
                    ? 'checking…'
                    : permissions.accessibility
                      ? 'granted'
                      : 'not granted'}
                </span>
              </div>
              <p className="hint">
                System Settings → Privacy &amp; Security → Accessibility. Grants LiVoice permission
                to watch your chosen trigger key in any app and paste finished text directly at your
                cursor. Permissions update automatically when you return to this window.
              </p>
              <p>
                <button type="button" className="action secondary" onClick={enableAccessibility}>
                  Enable Accessibility
                </button>
              </p>
              <div className="stat-row">
                <span className="label">Automation (System Events fallback)</span>
                <button
                  type="button"
                  className="action secondary compact"
                  onClick={() => void window.api.openAutomationPane()}
                >
                  Open Automation settings
                </button>
              </div>
              <p className="hint">
                LiVoice pastes natively via Accessibility; System Events Automation is only used as
                a fallback if native paste is unavailable. Input Monitoring is not needed.
              </p>
            </div>

            <div className="card">
              <h2>Startup &amp; menu bar</h2>
              <div className="stat-row">
                <span id="label-login-item" className="label">
                  Launch LiVoice at login
                </span>
                <ToggleSwitch
                  labelledBy="label-login-item"
                  checked={loginItem}
                  onToggle={() => {
                    const next = !loginItem
                    void window.api.setLoginItem(next).then((applied) => setLoginItem(applied))
                  }}
                />
              </div>
              <div className="stat-row">
                <span id="label-start-hidden" className="label">
                  Start hidden in menu bar
                </span>
                <ToggleSwitch
                  labelledBy="label-start-hidden"
                  checked={settings?.startHidden ?? false}
                  disabled={settings === null}
                  onToggle={() => updateSetting('startHidden', !(settings?.startHidden ?? false))}
                />
              </div>
            </div>

            <div className="card">
              <h2>About</h2>
              <div className="stat-row">
                <span className="label">LiVoice</span>
                <span className="value">{version}</span>
              </div>
              <div className="stat-row">
                <span className="label">Service</span>
                <span className="value mono">
                  {settings?.workerUrl ?? 'https://wovoice.aliahad.com'}
                </span>
              </div>
            </div>
          </section>
        )}
      </main>

      {toast !== null && (
        <div className="toast" role="status" aria-live="polite">
          {toast}
        </div>
      )}
    </div>
  )
}

function HomeTab({
  signedIn,
  triggerLabel: keyLabel,
  middleClick,
  revision,
  onCopied
}: {
  signedIn: boolean
  triggerLabel: string
  middleClick: boolean
  revision: number
  onCopied: (message: string) => void
}): React.JSX.Element {
  const [period, setPeriod] = useState<Period>('today')
  const [stats, setStats] = useState<HomeStatsSnapshot | null>(null)

  useEffect(() => {
    let cancelled = false
    window.api
      .homeStats(period)
      .then((value) => {
        if (!cancelled) setStats(value)
      })
      .catch(() => {
        if (!cancelled) setStats(null)
      })
    return () => {
      cancelled = true
    }
  }, [period, revision])

  const wpm =
    stats !== null && stats.audioDurationMs > 0
      ? Math.round((stats.words * 60_000) / stats.audioDurationMs)
      : 0

  const handlePeriodKeyDown = (
    event: React.KeyboardEvent<HTMLButtonElement>,
    index: number
  ): void => {
    let nextIndex: number | null = null
    if (event.key === 'ArrowRight') nextIndex = (index + 1) % PERIODS.length
    else if (event.key === 'ArrowLeft') nextIndex = (index - 1 + PERIODS.length) % PERIODS.length
    if (nextIndex === null) return
    event.preventDefault()
    const [nextId] = PERIODS[nextIndex]
    setPeriod(nextId)
    document.getElementById(`period-${nextId}`)?.focus()
  }

  return (
    <>
      <div className="hero">
        <h1>Speak naturally. Get ready-to-use text.</h1>
        <p>
          Hold {keyLabel}
          {middleClick ? ' (or middle-click)' : ''} anywhere on your Mac to dictate.
        </p>
      </div>

      <div className="card">
        <h2>Your dictations</h2>
        <div
          className="tabs"
          role="tablist"
          aria-label="Statistics timeframe"
          style={{ marginBottom: 10 }}
        >
          {PERIODS.map(([id, label], index) => (
            <button
              key={id}
              id={`period-${id}`}
              type="button"
              role="tab"
              aria-selected={period === id}
              tabIndex={period === id ? 0 : -1}
              className={`tab ${period === id ? 'active' : ''}`}
              onClick={() => setPeriod(id)}
              onKeyDown={(event) => handlePeriodKeyDown(event, index)}
            >
              {label}
            </button>
          ))}
        </div>
        <div className="stat-row">
          <span className="label">Dictations</span>
          <span className="value">{stats === null ? '—' : stats.dictations}</span>
        </div>
        <div className="stat-row">
          <span className="label">Dictation time</span>
          <span className="value">
            {stats === null ? '—' : formatDuration(stats.audioDurationMs)}
          </span>
        </div>
        <div className="stat-row">
          <span className="label">Words</span>
          <span className="value">{stats === null ? '—' : stats.words}</span>
        </div>
        <div className="stat-row">
          <span className="label">Speaking pace</span>
          <span className="value">{stats === null ? '—' : `${wpm} wpm`}</span>
        </div>
      </div>

      <div className="card">
        <div className="toolbar" style={{ marginBottom: 8 }}>
          <h2 style={{ margin: 0 }}>Recent dictations</h2>
          {stats !== null && stats.recent.length > 0 && (
            <button
              type="button"
              className="action secondary compact"
              onClick={() => {
                void window.api.copyLastDictation().then((ok) => {
                  if (ok) onCopied('Copied latest dictation')
                })
              }}
            >
              Copy last dictation
            </button>
          )}
        </div>
        {stats === null || stats.recent.length === 0 ? (
          <p className="muted">
            {signedIn
              ? `Nothing yet — hold ${keyLabel} anywhere and speak.`
              : `Sign in, then hold ${keyLabel} anywhere and speak.`}
          </p>
        ) : (
          stats.recent.map((record) => (
            <div key={record.requestId} className="stat-row">
              <span className="label clamp" title={record.finalText}>
                {record.finalText}
              </span>
              <div className="row-actions">
                <span className="value">{formatWords(record.wordCount)}</span>
                <button
                  type="button"
                  className="action secondary compact"
                  aria-label={`Copy dictation from ${formatWhen(record.createdAtMs)}`}
                  onClick={() => {
                    void window.api.historyCopy(record.finalText).then(() => onCopied('Copied!'))
                  }}
                >
                  Copy
                </button>
              </div>
            </div>
          ))
        )}
      </div>
    </>
  )
}

function HistoryTab({
  revision,
  onToast
}: {
  revision: number
  onToast: (message: string) => void
}): React.JSX.Element {
  const [query, setQuery] = useState('')
  const [debouncedQuery, setDebouncedQuery] = useState('')
  const [rows, setRows] = useState<HistoryRowSnapshot[]>([])
  const [undo, setUndo] = useState<HistoryRowSnapshot | null>(null)
  const [undoError, setUndoError] = useState<string | null>(null)
  const [localRefresh, setLocalRefresh] = useState(0)

  useEffect(() => {
    const timer = setTimeout(() => setDebouncedQuery(query), 180)
    return () => clearTimeout(timer)
  }, [query])

  useEffect(() => {
    let cancelled = false
    window.api
      .historyList(debouncedQuery)
      .then((value) => {
        if (!cancelled) setRows(value)
      })
      .catch(() => {
        if (!cancelled) setRows([])
      })
    return () => {
      cancelled = true
    }
  }, [debouncedQuery, revision, localRefresh])

  useEffect(() => {
    if (undo === null) return
    const timer = setTimeout(() => setUndo(null), 5_000)
    return () => clearTimeout(timer)
  }, [undo])

  const remove = (row: HistoryRowSnapshot): void => {
    void window.api.historyDelete(row.requestId).then(() => {
      setUndoError(null)
      setUndo(row)
      setLocalRefresh((v) => v + 1)
    })
  }

  const restore = (): void => {
    if (undo === null) return
    void window.api.historyRestore(undo.requestId).then((restored) => {
      setLocalRefresh((v) => v + 1)
      setUndo(null)
      setUndoError(restored ? null : 'Too late to undo — the deletion already synced.')
    })
  }

  const handleClearHistory = (): void => {
    void window.api.clearHistory().then((res) => {
      if (res.ok) {
        setUndo(null)
        setLocalRefresh((v) => v + 1)
        onToast('History cleared')
      }
    })
  }

  const handleExport = (format: 'txt' | 'json'): void => {
    void window.api.exportHistory(format).then((res) => {
      if (res.ok) onToast(`Exported history (${format.toUpperCase()})`)
      else if (!res.cancelled && res.message) onToast(res.message)
    })
  }

  const hasQuery = debouncedQuery.trim().length > 0

  return (
    <>
      <div className="hero">
        <h1>History</h1>
        <p>Dictations inserted on this Mac. Search, copy, export, or delete with undo.</p>
      </div>
      {undoError !== null && undo === null && (
        <p className="error" role="alert">
          {undoError}
        </p>
      )}
      {undo !== null && (
        <div className="card undo" role="status" aria-live="polite">
          <span>
            Deleted “{undo.finalText.slice(0, 40)}
            {undo.finalText.length > 40 ? '…' : ''}”
          </span>
          <button type="button" className="action secondary" onClick={restore}>
            Undo
          </button>
        </div>
      )}
      <div className="toolbar">
        <label htmlFor="history-search" className="sr-only">
          Search dictation history
        </label>
        <input
          id="history-search"
          type="search"
          className="search toolbar-search"
          placeholder="Search generated text"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
        />
        <div className="toolbar-actions">
          <button
            type="button"
            className="action secondary compact"
            disabled={rows.length === 0 && !hasQuery}
            onClick={() => handleExport('txt')}
          >
            Export TXT
          </button>
          <button
            type="button"
            className="action secondary compact"
            disabled={rows.length === 0 && !hasQuery}
            onClick={() => handleExport('json')}
          >
            Export JSON
          </button>
          <button
            type="button"
            className="action danger compact"
            disabled={rows.length === 0 && !hasQuery}
            onClick={handleClearHistory}
          >
            Clear history
          </button>
        </div>
      </div>
      <div className="card">
        {rows.length === 0 ? (
          <p className="muted">
            {hasQuery ? `No dictations match “${debouncedQuery.trim()}”.` : 'No dictations yet.'}
          </p>
        ) : (
          rows.map((row) => (
            <div key={row.requestId} className="history-item">
              <div className="history-text">{row.finalText}</div>
              <div className="history-meta">
                <span>{formatWhen(row.createdAtMs)}</span>
                <span>• {formatWords(row.wordCount)}</span>
                <span>• {formatDuration(row.audioDurationMs)}</span>
                {row.polished && <span className="badge mint">Polished</span>}
                {row.asrModel && <span className="badge">{row.asrModel}</span>}
                {row.totalMs > 0 && <span>• {(row.totalMs / 1000).toFixed(1)}s latency</span>}
              </div>
              <div className="history-actions">
                <button
                  type="button"
                  className="action secondary compact"
                  aria-label={`Copy dictation from ${formatWhen(row.createdAtMs)}`}
                  onClick={() =>
                    void window.api.historyCopy(row.finalText).then(() => onToast('Copied!'))
                  }
                >
                  Copy
                </button>
                <button
                  type="button"
                  className="action danger compact"
                  aria-label={`Delete dictation from ${formatWhen(row.createdAtMs)}`}
                  onClick={() => remove(row)}
                >
                  Delete
                </button>
              </div>
            </div>
          ))
        )}
      </div>
    </>
  )
}

function DictionaryTab({
  revision,
  onToast
}: {
  revision: number
  onToast: (message: string) => void
}): React.JSX.Element {
  const [query, setQuery] = useState('')
  const [debouncedQuery, setDebouncedQuery] = useState('')
  const [rows, setRows] = useState<TermRowSnapshot[]>([])
  const [draft, setDraft] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [editingId, setEditingId] = useState<number | null>(null)
  const [editDraft, setEditDraft] = useState('')
  const [localRefresh, setLocalRefresh] = useState(0)

  useEffect(() => {
    const timer = setTimeout(() => setDebouncedQuery(query), 180)
    return () => clearTimeout(timer)
  }, [query])

  useEffect(() => {
    let cancelled = false
    window.api
      .dictionaryList(debouncedQuery)
      .then((value) => {
        if (!cancelled) setRows(value)
      })
      .catch(() => {
        if (!cancelled) setRows([])
      })
    return () => {
      cancelled = true
    }
  }, [debouncedQuery, revision, localRefresh])

  const add = (): void => {
    void window.api.dictionaryAdd(draft).then((added) => {
      if (!added) {
        setError('That term is invalid (2–80 characters) or already exists.')
        return
      }
      setError(null)
      setDraft('')
      setLocalRefresh((v) => v + 1)
      onToast('Added to dictionary')
    })
  }

  const saveEdit = (id: number): void => {
    void window.api.editTerm(id, editDraft).then((updated) => {
      if (!updated) {
        setError('Could not update term — check length (2–80 chars) and duplicates.')
        return
      }
      setError(null)
      setEditingId(null)
      setEditDraft('')
      setLocalRefresh((v) => v + 1)
      onToast('Term updated')
    })
  }

  const hasQuery = debouncedQuery.trim().length > 0

  return (
    <>
      <div className="hero">
        <h1>Dictionary</h1>
        <p>Names and specialist terms LiVoice should recognize in dictation.</p>
      </div>
      <div className="card">
        <div className="add-row">
          <label htmlFor="dict-add" className="sr-only">
            Add name or specialist term
          </label>
          <input
            id="dict-add"
            className="search"
            placeholder="Name or specialist term"
            value={draft}
            onChange={(event) => setDraft(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter') add()
            }}
          />
          <button type="button" className="action" onClick={add}>
            Add
          </button>
        </div>
        {error !== null && (
          <p className="error" role="alert">
            {error}
          </p>
        )}
      </div>
      <div className="card">
        <div className="add-row" style={{ marginBottom: 8 }}>
          <label htmlFor="dict-search" className="sr-only">
            Search dictionary
          </label>
          <input
            id="dict-search"
            type="search"
            className="search"
            placeholder="Search dictionary"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
          />
        </div>
        {rows.length === 0 ? (
          <p className="muted">
            {hasQuery
              ? `No terms match “${debouncedQuery.trim()}”.`
              : 'No terms yet — add an important name above.'}
          </p>
        ) : (
          rows.map((row) => (
            <div key={row.id} className="stat-row">
              {editingId === row.id ? (
                <div className="add-row" style={{ width: '100%' }}>
                  <label htmlFor={`dict-edit-${row.id}`} className="sr-only">
                    Rename {row.term}
                  </label>
                  <input
                    id={`dict-edit-${row.id}`}
                    className="search"
                    value={editDraft}
                    autoFocus
                    onChange={(event) => setEditDraft(event.target.value)}
                    onKeyDown={(event) => {
                      if (event.key === 'Enter') saveEdit(row.id)
                      else if (event.key === 'Escape') {
                        setEditingId(null)
                        setEditDraft('')
                      }
                    }}
                  />
                  <button type="button" className="action compact" onClick={() => saveEdit(row.id)}>
                    Save
                  </button>
                  <button
                    type="button"
                    className="action secondary compact"
                    onClick={() => {
                      setEditingId(null)
                      setEditDraft('')
                    }}
                  >
                    Cancel
                  </button>
                </div>
              ) : (
                <>
                  <span className="label">
                    {row.term}
                    <span className="mono">
                      {' '}
                      · {row.source} · {row.useCount} {row.useCount === 1 ? 'use' : 'uses'}
                    </span>
                  </span>
                  <div className="row-actions">
                    <button
                      type="button"
                      className="action secondary compact"
                      aria-label={`Edit term ${row.term}`}
                      onClick={() => {
                        setError(null)
                        setEditingId(row.id)
                        setEditDraft(row.term)
                      }}
                    >
                      Edit
                    </button>
                    <button
                      type="button"
                      className="action danger compact"
                      aria-label={`Delete term ${row.term}`}
                      onClick={() => {
                        void window.api.dictionaryDelete(row.id).then(() => {
                          setLocalRefresh((v) => v + 1)
                          onToast('Removed term')
                        })
                      }}
                    >
                      Delete
                    </button>
                  </div>
                </>
              )}
            </div>
          ))
        )}
      </div>
    </>
  )
}

function SessionsCard({ onToast }: { onToast: (message: string) => void }): React.JSX.Element {
  const [sessions, setSessions] = useState<SessionInfo[] | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [reloadKey, setReloadKey] = useState(0)

  useEffect(() => {
    let cancelled = false
    window.api
      .listSessions()
      .then((list) => {
        if (!cancelled) {
          setSessions(list)
          setError(null)
        }
      })
      .catch(() => {
        if (!cancelled) setError('Could not load signed-in devices.')
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [reloadKey])

  const refresh = (): void => {
    setLoading(true)
    setError(null)
    setReloadKey((v) => v + 1)
  }

  const revoke = (session: SessionInfo): void => {
    setLoading(true)
    window.api
      .revokeSession(session.id)
      .then((res) => {
        if (!res.ok) {
          setError(res.message ?? 'Could not revoke device session.')
          setLoading(false)
          return
        }
        onToast(res.signedOut ? 'Signed out of this Mac' : `Signed out ${session.deviceName}`)
        if (res.signedOut) setLoading(false)
        else setReloadKey((v) => v + 1)
      })
      .catch(() => {
        setError('Could not revoke device session.')
        setLoading(false)
      })
  }

  return (
    <div className="card">
      <div className="toolbar" style={{ marginBottom: 8 }}>
        <h2 style={{ margin: 0 }}>Signed-in devices</h2>
        <button
          type="button"
          className="action secondary compact"
          onClick={refresh}
          disabled={loading}
        >
          Refresh
        </button>
      </div>
      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}
      {sessions === null ? (
        <p className="muted">Loading devices…</p>
      ) : sessions.length === 0 ? (
        <p className="muted">No active sessions found.</p>
      ) : (
        sessions.map((item) => (
          <div key={item.id} className="stat-row">
            <div>
              <div style={{ fontWeight: 600 }}>
                {item.deviceName} {item.current && <span className="badge mint">This Mac</span>}
              </div>
              <div className="hint" style={{ margin: '2px 0 0' }}>
                Last active {formatWhen(item.lastSeenAt)} · Signed in {formatWhen(item.createdAt)}
              </div>
            </div>
            <button
              type="button"
              className="action danger compact"
              disabled={loading}
              aria-label={`Revoke session for ${item.deviceName}`}
              onClick={() => revoke(item)}
            >
              Revoke
            </button>
          </div>
        ))
      )}
    </div>
  )
}

function DeleteAccountCard({
  pendingDelete,
  onCancel,
  onToast
}: {
  pendingDelete: boolean
  onCancel: () => void
  onToast: (message: string) => void
}): React.JSX.Element {
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<string | null>(null)

  const beginDelete = (): void => {
    setBusy(true)
    setMessage(null)
    window.api
      .deleteAccount()
      .then((res) => {
        if (res.ok) {
          setMessage(res.message ?? 'Complete verification in your browser to delete your account.')
        } else if (res.message) {
          onToast(res.message)
        }
      })
      .finally(() => setBusy(false))
  }

  return (
    <div className="card">
      <h2>Delete account</h2>
      <p className="hint">
        Permanently delete your LiVoice account, synced history, dictionary, and usage analytics
        from LiVoice servers and remove local data from this Mac. This cannot be undone.
      </p>
      {pendingDelete ? (
        <div className="banner-actions" role="status" aria-live="polite">
          <button type="button" className="action danger" disabled>
            Waiting for browser verification…
          </button>
          <button type="button" className="action secondary" onClick={onCancel}>
            Cancel
          </button>
        </div>
      ) : (
        <button type="button" className="action danger" onClick={beginDelete} disabled={busy}>
          Delete account…
        </button>
      )}
      {message && (
        <p className="hint" role="status" aria-live="polite" style={{ marginTop: 10 }}>
          {message}
        </p>
      )}
    </div>
  )
}

function SyncCard({ signedIn }: { signedIn: boolean }): React.JSX.Element {
  const [busy, setBusy] = useState(false)
  const [result, setResult] = useState<string | null>(null)
  const [status, setStatus] = useState<SyncStatusSnapshot | null>(null)

  const refreshStatus = useCallback((): void => {
    window.api
      .syncStatus()
      .then(setStatus)
      .catch(() => setStatus(null))
  }, [])

  useEffect(() => {
    if (signedIn) refreshStatus()
    return window.api.onSyncChanged((next) => setStatus(next))
  }, [signedIn, refreshStatus])

  const runSync = (): void => {
    setBusy(true)
    setResult(null)
    window.api
      .syncNow()
      .then((outcome) => {
        if (outcome.kind === 'ok') {
          setResult(
            outcome.warning ??
              `Synced — ${outcome.uploaded ?? 0} sent, ${outcome.downloaded ?? 0} received.`
          )
        } else {
          setResult(outcome.message ?? 'Sync failed.')
        }
      })
      .catch(() => setResult('Sync failed.'))
      .finally(() => {
        setBusy(false)
        refreshStatus()
      })
  }

  const update = (changes: {
    historySyncEnabled?: boolean
    historyRetentionDays?: number | null
  }): void => {
    setBusy(true)
    window.api
      .updateSyncSettings(changes)
      .then((outcome) => {
        if (!outcome.ok && !outcome.cancelled) {
          setResult(outcome.message ?? 'The setting could not be saved.')
        }
      })
      .catch(() => setResult('The setting could not be saved.'))
      .finally(() => {
        setBusy(false)
        refreshStatus()
      })
  }

  if (!signedIn) {
    return (
      <div className="card">
        <h2>Cloud sync</h2>
        <p className="muted">
          Sign in and your history, dictionary, and stats follow you to every device.
        </p>
      </div>
    )
  }

  return (
    <div className="card">
      <h2>Cloud sync</h2>
      <p>
        History, dictionary, and analytics sync through your LiVoice account, encrypted on
        LiVoice&apos;s servers. Signing in on another device is all it takes.
      </p>
      {status?.syncWarning && (
        <div className="banner warn" role="status" aria-live="polite" style={{ marginBottom: 12 }}>
          {status.syncWarning}
        </div>
      )}
      <div className="stat-row">
        <span className="label">Last synced</span>
        <span className="value">
          {status === null || status.lastSyncAt === 0 ? 'Not yet' : formatWhen(status.lastSyncAt)}
        </span>
      </div>
      <div className="stat-row">
        <span id="label-sync-history" className="label">
          Sync history
        </span>
        <ToggleSwitch
          labelledBy="label-sync-history"
          checked={status?.historySyncEnabled ?? true}
          disabled={busy || status === null}
          onToggle={() => update({ historySyncEnabled: !(status?.historySyncEnabled ?? true) })}
        />
      </div>
      <div className="stat-row">
        <label htmlFor="sync-retention">Auto-delete history</label>
        <select
          id="sync-retention"
          className="select"
          disabled={busy || status === null}
          value={String(status?.historyRetentionDays ?? 'never')}
          onChange={(event) =>
            update({
              historyRetentionDays:
                event.target.value === 'never' ? null : Number(event.target.value)
            })
          }
        >
          {RETENTION_OPTIONS.map(([days, label]) => (
            <option key={label} value={days === null ? 'never' : String(days)}>
              {label}
            </option>
          ))}
        </select>
      </div>
      <p style={{ marginTop: 12 }}>
        <button type="button" className="action" onClick={runSync} disabled={busy}>
          {busy ? 'Syncing…' : 'Sync now'}
        </button>
      </p>
      {result !== null && (
        <p className="mono" role="status" aria-live="polite">
          {result}
        </p>
      )}
    </div>
  )
}

export default Dashboard
