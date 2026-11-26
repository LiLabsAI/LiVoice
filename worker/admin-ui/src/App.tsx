import { FormEvent, ReactNode, useCallback, useEffect, useRef, useState } from "react";
import { AdminApiError, adminApi, safeReturnTo, signInRedirect } from "./api";
import { AiSettings } from "./AiSettings";
import { Dialog } from "./Dialog";

declare global {
  interface Window {
    turnstile?: {
      render(container: HTMLElement, options: Record<string, unknown>): string;
      remove(widgetId: string): void;
      reset(widgetId: string): void;
    };
  }
}

type View = "overview" | "users" | "detail" | "audit" | "ai";
type AccountState = "active" | "suspended" | "banned";

interface AdminSession {
  admin: { id: string; email: string; role: "admin" };
  session: { absoluteExpiresAt: number };
}

interface AccountStatus {
  state: AccountState;
  suspendedUntil: number | null;
  publicMessage: string | null;
}

interface UserSummary {
  id: string;
  email: string;
  role: "user" | "admin";
  status: AccountStatus;
  createdAt: number;
  lastActivityAt: number | null;
  sessionCount: number;
  todayAudioSeconds: number;
  quotaLimitAudioSeconds: number;
  usage90d: { audioSeconds: number; requests: number };
}

interface DeviceSession {
  id: string;
  deviceName: string;
  createdAt: number;
  lastSeenAt: number;
  revokedAt: number | null;
  absoluteExpiresAt: number;
}

interface UserDetail extends UserSummary {
  verifiedAt: number;
  termsVersion: string;
  quota: {
    limitAudioSeconds: number;
    todayUsedAudioSeconds: number;
    overrideExpiresAt: number | null;
  };
  usage90d: { audioSeconds: number; requests: number; neurons: number };
  syncMetadata: Array<{ type: string; count: number; storedBytes: number }>;
  sessions: DeviceSession[];
  notifications: Array<{
    id: string;
    action: string;
    status: string;
    attempts: number;
    lastError: string | null;
    createdAt: number;
    sentAt: number | null;
  }>;
}

interface Activity {
  id: string;
  type: string;
  requestId: string | null;
  statusCode: number | null;
  outcomeCode: string | null;
  model: string | null;
  audioSeconds: number | null;
  estimatedNeurons: number | null;
  estimatedCostUsd: number | null;
  latencyMs: number | null;
  itemCount: number | null;
  deviceName: string | null;
  createdAt: number;
}

interface AuditEvent {
  id: string;
  actorUserId: string | null;
  targetUserId: string | null;
  action: string;
  internalReason: string;
  beforeState: unknown;
  afterState: unknown;
  requestId: string;
  createdAt: number;
}

export function App() {
  return location.pathname.startsWith("/login") ? <LoginPage /> : <AdminPortal />;
}

function LoginPage() {
  const [siteKey, setSiteKey] = useState("");
  const [token, setToken] = useState("");
  const [email, setEmail] = useState("");
  const [challengeId, setChallengeId] = useState("");
  const [code, setCode] = useState("");
  const [resendSeconds, setResendSeconds] = useState(0);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("Loading secure sign-in…");
  const [error, setError] = useState("");

  const widget = useRef<HTMLDivElement>(null);
  const widgetId = useRef<string | null>(null);
  const returnTo = safeReturnTo(new URLSearchParams(location.search).get("returnTo"));

  useEffect(() => {
    const controller = new AbortController();
    adminApi<{ turnstileSiteKey: string }>("/auth/config", { signal: controller.signal })
      .then((value) => {
        setSiteKey(value.turnstileSiteKey);
        setMessage("Enter your administrator email to receive a one-time code.");
      })
      .catch((reason: unknown) => {
        if (isAbortError(reason)) return;
        setError(reason instanceof Error ? reason.message : "Sign-in is unavailable.");
      });
    return () => controller.abort();
  }, []);

  useEffect(() => {
    if (resendSeconds <= 0) return;
    const timer = setInterval(() => {
      setResendSeconds((current) => (current <= 1 ? 0 : current - 1));
    }, 1_000);
    return () => clearInterval(timer);
  }, [resendSeconds > 0]);

  useEffect(() => {
    if (!siteKey || challengeId || !widget.current) return;
    let cancelled = false;
    const mount = () => {
      if (cancelled || !widget.current || !window.turnstile) return;
      widgetId.current = window.turnstile.render(widget.current, {
        sitekey: siteKey,
        theme: "dark",
        size: "flexible",
        action: "admin_login",
        callback: (value: string) => { setToken(value); setError(""); },
        "expired-callback": () => { setToken(""); setError("The security check expired. Complete it again."); },
        "error-callback": () => { setToken(""); setError("The security check could not load. Check your connection."); },
      });
    };
    if (window.turnstile) mount();
    else {
      const existing = document.querySelector<HTMLScriptElement>("script[data-wovoice-turnstile]");
      const script = existing ?? document.createElement("script");
      if (!existing) {
        script.src = "https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit";
        script.async = true;
        script.defer = true;
        script.dataset.wovoiceTurnstile = "true";
        document.head.append(script);
      }
      script.addEventListener("load", mount, { once: true });
    }
    return () => {
      cancelled = true;
      if (widgetId.current && window.turnstile) {
        window.turnstile.remove(widgetId.current);
        widgetId.current = null;
      }
    };
  }, [siteKey, challengeId]);

  async function sendCode(event: FormEvent) {
    event.preventDefault();
    if (!token) { setError("Complete the security check first."); return; }
    setBusy(true); setError(""); setMessage("Sending a secure code…");
    try {
      const value = await adminApi<{ challengeId: string; resendAfter?: number }>("/auth/start", {
        method: "POST",
        csrf: false,
        body: { email, turnstileToken: token },
      });
      setChallengeId(value.challengeId);
      setCode("");
      setResendSeconds(typeof value.resendAfter === "number" ? value.resendAfter : 60);
      setMessage("If this address is an active administrator, a six-digit code has been sent.");
    } catch (reason) {
      setError(errorMessage(reason));
      if (widgetId.current && window.turnstile) window.turnstile.reset(widgetId.current);
      setToken("");
    } finally { setBusy(false); }
  }

  async function verify(event: FormEvent) {
    event.preventDefault();
    setBusy(true); setError(""); setMessage("Verifying your administrator session…");
    try {
      await adminApi<AdminSession>("/auth/verify", {
        method: "POST",
        csrf: false,
        body: { challengeId, code },
      });
      location.replace(returnTo);
    } catch (reason) {
      setError(errorMessage(reason));
      setMessage("Enter the latest code from your email.");
    } finally { setBusy(false); }
  }

  function requestFreshCode() {
    if (resendSeconds > 0) return;
    setChallengeId("");
    setCode("");
    setToken("");
    setError("");
    setMessage("Complete the security check to send a fresh one-time code.");
  }

  return (
    <main className="login-page">
      <section className="login-card" aria-labelledby="login-title">
        <Brand />
        <p className="eyebrow">PRIVATE ADMINISTRATION</p>
        <h1 id="login-title">Sign in to LiVoice</h1>
        <p className="muted">{message}</p>
        {!challengeId ? (
          <form onSubmit={sendCode} className="login-form">
            <label>Email address<input type="email" autoComplete="email" value={email} onChange={(event) => setEmail(event.target.value)} required /></label>
            <div ref={widget} className="turnstile" aria-label="Security check" />
            {error && <Banner>{error}</Banner>}
            <button className="primary-button" disabled={busy || !token}>{busy ? "Sending…" : "Send one-time code"}</button>
          </form>
        ) : (
          <form onSubmit={verify} className="login-form">
            <label>Six-digit code<input className="code-input" inputMode="numeric" autoComplete="one-time-code" pattern="[0-9]{6}" maxLength={6} value={code} onChange={(event) => setCode(event.target.value.replace(/\D/gu, ""))} required autoFocus /></label>
            {error && <Banner>{error}</Banner>}
            <button className="primary-button" disabled={busy || code.length !== 6}>{busy ? "Verifying…" : "Open admin console"}</button>
            <div className="login-actions">
              <button type="button" className="text-button" disabled={busy || resendSeconds > 0} onClick={requestFreshCode}>
                {resendSeconds > 0 ? `Resend code (${resendSeconds}s)` : "Resend code"}
              </button>
              <button type="button" className="text-button" onClick={() => location.reload()}>Start again</button>
            </div>
          </form>
        )}
        <p className="privacy-note">Protected by email verification, Turnstile, short-lived cookies, and the administrator role stored in LiVoice.</p>
      </section>
    </main>
  );
}

function AdminPortal() {
  const route = currentRoute();
  const [session, setSession] = useState<AdminSession | null>(null);
  const [view, setView] = useState<View>(route.view);
  const [selectedUser, setSelectedUser] = useState<string | null>(route.userId);
  const [error, setError] = useState("");
  const [pendingNavigation, setPendingNavigation] = useState<{ view: View; userId?: string; path: string; replace?: boolean } | null>(null);
  const aiDirty = useRef(false);
  const lastPath = useRef(`${location.pathname}${location.search}`);
  const onAiDirtyChange = useCallback((dirty: boolean) => { aiDirty.current = dirty; }, []);

  useEffect(() => {
    const restoreRoute = () => {
      const restored = currentRoute();
      const targetPath = `${location.pathname}${location.search}`;
      if (aiDirty.current) {
        history.pushState({}, "", lastPath.current);
        setPendingNavigation({ view: restored.view, userId: restored.userId ?? undefined, path: targetPath, replace: true });
        return;
      }
      lastPath.current = targetPath;
      setView(restored.view);
      setSelectedUser(restored.userId);
      setError("");
    };
    addEventListener("popstate", restoreRoute);
    return () => removeEventListener("popstate", restoreRoute);
  }, []);

  const loadSession = useCallback(async () => {
    try { setSession(await adminApi<AdminSession>("/session")); }
    catch (reason) {
      if (reason instanceof AdminApiError && reason.status === 401) signInRedirect();
      else setError(errorMessage(reason));
    }
  }, []);
  useEffect(() => { void loadSession(); }, [loadSession]);

  function navigate(next: View, userId?: string) {
    const path = next === "overview" ? "/admin/" : next === "users" ? "/admin/users" : next === "audit" ? "/admin/audit" : next === "ai" ? "/admin/ai" : `/admin/users/${encodeURIComponent(userId ?? "")}`;
    if (path === lastPath.current) return;
    if (aiDirty.current) { setPendingNavigation({ view: next, userId, path }); return; }
    move(next, userId, path);
  }
  function move(next: View, userId: string | undefined, path: string, replace = false) {
    if (replace) history.replaceState({}, "", path); else history.pushState({}, "", path);
    lastPath.current = path;
    setView(next); setSelectedUser(userId ?? null); setError("");
  }

  async function logout() {
    try { await adminApi<void>("/logout", { method: "POST" }); }
    finally { location.replace("/login"); }
  }

  if (!session && !error) return <FullPageStatus title="Opening LiVoice Admin" detail="Verifying your secure browser session…" />;
  if (!session) return <FullPageStatus title="Admin console unavailable" detail={error} action={<button className="primary-button" onClick={signInRedirect}>Return to sign in</button>} />;

  const titles: Record<View, [string, string]> = {
    overview: ["SERVICE OPERATIONS", "Overview"],
    users: ["ACCOUNT MANAGEMENT", "Users"],
    detail: ["ACCOUNT DETAIL", "User"],
    audit: ["ACCOUNTABILITY", "Audit log"],
    ai: ["DICTATION INTELLIGENCE", "AI workspace"],
  };
  return (
    <div className="shell">
      <aside className="sidebar">
        <Brand onNavigate={() => navigate("overview")} />
        <p className="eyebrow">ADMIN CONSOLE</p>
        <nav className="nav" aria-label="Admin sections">
          <NavButton active={view === "overview"} onClick={() => navigate("overview")} icon="⌁">Overview</NavButton>
          <NavButton active={view === "users" || view === "detail"} onClick={() => navigate("users")} icon="◎">Users</NavButton>
          <NavButton active={view === "ai"} onClick={() => navigate("ai")} icon="✦">AI workspace</NavButton>
          <NavButton active={view === "audit"} onClick={() => navigate("audit")} icon="✓">Audit log</NavButton>
        </nav>
        <div className="sidebar-footer">
          <div className="identity"><span className="status-dot" aria-hidden="true" /><div><strong>{session.admin.email}</strong><small>Email-verified session</small></div></div>
          <button className="logout-button" type="button" onClick={() => void logout()}>Sign out</button>
        </div>
      </aside>
      <main className="main">
        <header className="topbar"><div><p className="eyebrow">{titles[view][0]}</p><h1>{titles[view][1]}</h1></div><span className="privacy-chip">{view === "ai" ? "Draft → test → publish" : "Operational metadata only"}</span></header>
        {error && <Banner>{error}</Banner>}
        {view === "overview" && <Overview onError={setError} />}
        {view === "users" && <Users onOpen={(id) => navigate("detail", id)} onError={setError} />}
        {view === "detail" && selectedUser && <UserDetailView userId={selectedUser} onBack={() => navigate("users")} onError={setError} />}
        {view === "audit" && <Audit onError={setError} />}
        {view === "ai" && <AiSettings onDirtyChange={onAiDirtyChange} />}
      </main>
      {pendingNavigation && <Dialog title="Leave unsaved AI settings?" onClose={() => setPendingNavigation(null)}><p className="muted">Your unsaved edits will be discarded. Saved drafts and live settings are unchanged.</p><div className="modal-actions"><button className="secondary-button" onClick={() => setPendingNavigation(null)}>Keep editing</button><button className="danger-button" onClick={() => { const next = pendingNavigation; aiDirty.current = false; setPendingNavigation(null); move(next.view, next.userId, next.path, next.replace); }}>Discard edits</button></div></Dialog>}
    </div>
  );
}

function Overview({ onError }: { onError(message: string): void }) {
  const [period, setPeriod] = useState("7d");
  const [data, setData] = useState<Record<string, unknown> | null>(null);
  const [loadError, setLoadError] = useState("");
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    setData(null);
    setLoadError("");
    onError("");
    adminApi<Record<string, unknown>>(`/overview?period=${period}`, { signal: controller.signal })
      .then(setData)
      .catch((reason) => {
        if (isAbortError(reason)) return;
        const message = errorMessage(reason);
        setLoadError(message);
        onError(message);
      });
    return () => controller.abort();
  }, [period, reloadKey, onError]);

  if (loadError) {
    return (
      <Panel>
        <div className="error-state">
          <p className="muted">{loadError}</p>
          <button className="secondary-button" type="button" onClick={() => setReloadKey((value) => value + 1)}>Try again</button>
        </div>
      </Panel>
    );
  }
  if (!data) return <LoadingCards />;
  const users = data.users as { registered: number; active: number; suspended: number; banned: number; newInPeriod: number };
  const transcription = data.transcription as { succeeded: number; failed: number; successRate: number | null; audioSeconds: number; quotaRejections: number; medianLatencyMs: number | null };
  const usage = data.usage as { estimatedCostUsd: number; neurons: number; todayGlobalUsedNeurons: number; todayGlobalReservedNeurons: number; todayGlobalLimitNeurons: number };
  const service = data.service as { syncOperations: number; verificationEmailsThisMonth: number; moderationEmailsThisMonth: number; monthlyEmailLimit: number };
  return <>
    <div className="period-toolbar">{["today", "7d", "30d", "90d", "12m"].map((value) => <button key={value} type="button" className={period === value ? "active" : ""} onClick={() => setPeriod(value)}>{periodLabel(value)}</button>)}</div>
    <div className="metric-grid">
      <Metric label="Registered users" value={formatNumber(users.registered)} note={`${formatNumber(users.active)} active`} />
      <Metric label="Successful dictations" value={formatNumber(transcription.succeeded)} note={transcription.successRate === null ? "No requests yet" : `${formatPercent(transcription.successRate)} success`} />
      <Metric label="Voice processed" value={formatDuration(transcription.audioSeconds)} note={`${formatNumber(transcription.quotaRejections)} quota rejections`} />
      <Metric label="Estimated AI cost" value={formatUsd(usage.estimatedCostUsd)} note={`${formatNumber(usage.neurons)} neurons`} />
    </div>
    <div className="dashboard-grid">
      <Panel title="Service activity" kicker="DICTATION"><Summary rows={[["Median processing latency", transcription.medianLatencyMs === null ? "—" : `${formatNumber(transcription.medianLatencyMs)} ms`], ["Failed dictations", formatNumber(transcription.failed)], ["New accounts", formatNumber(users.newInPeriod)], ["Suspended / banned", `${users.suspended} / ${users.banned}`], ["Sync operations", formatNumber(service.syncOperations)]]} /></Panel>
      <Panel title="Free beta budget" kicker="CAPACITY"><Summary rows={[["Global neurons today", `${formatNumber(usage.todayGlobalUsedNeurons + usage.todayGlobalReservedNeurons)} / ${formatNumber(usage.todayGlobalLimitNeurons)}`], ["Verification emails", formatNumber(service.verificationEmailsThisMonth)], ["Moderation emails", formatNumber(service.moderationEmailsThisMonth)], ["Monthly email budget", `${service.verificationEmailsThisMonth + service.moderationEmailsThisMonth} / ${service.monthlyEmailLimit}`], ["Cost reporting", "Estimated, not an invoice"]]} /></Panel>
    </div>
  </>;
}

function Users({ onOpen, onError }: { onOpen(id: string): void; onError(message: string): void }) {
  const [query, setQuery] = useState("");
  const [status, setStatus] = useState("");
  const [users, setUsers] = useState<UserSummary[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [loadError, setLoadError] = useState("");
  const controllerRef = useRef<AbortController | null>(null);

  const load = useCallback(async (append = false) => {
    controllerRef.current?.abort();
    const controller = new AbortController();
    controllerRef.current = controller;
    setBusy(true);
    setLoadError("");
    onError("");
    const params = new URLSearchParams({ limit: "50" });
    if (query.trim()) params.set("query", query.trim());
    if (status) params.set("status", status);
    if (append && cursor) params.set("cursor", cursor);
    try {
      const value = await adminApi<{ users: UserSummary[]; nextCursor: string | null }>(`/users?${params}`, { signal: controller.signal });
      if (controller.signal.aborted) return;
      setUsers((current) => append ? [...current, ...value.users] : value.users);
      setCursor(value.nextCursor);
    } catch (reason) {
      if (isAbortError(reason)) return;
      const message = errorMessage(reason);
      setLoadError(message);
      onError(message);
    } finally {
      if (controllerRef.current === controller) setBusy(false);
    }
  }, [cursor, onError, query, status]);

  useEffect(() => {
    void load(false);
    return () => controllerRef.current?.abort();
  }, [status]);

  return <Panel>
    <form className="list-toolbar" onSubmit={(event) => { event.preventDefault(); void load(false); }}>
      <input type="search" aria-label="Search by exact email or user ID" placeholder="Exact email or user ID" value={query} onChange={(event) => setQuery(event.target.value)} />
      <select aria-label="Filter by account status" value={status} onChange={(event) => setStatus(event.target.value)}><option value="">All statuses</option><option value="active">Active</option><option value="suspended">Suspended</option><option value="banned">Banned</option></select>
      <button className="primary-button" disabled={busy}>{busy ? "Loading…" : "Search"}</button>
    </form>
    {loadError && users.length === 0 ? (
      <div className="error-state">
        <p className="muted">{loadError}</p>
        <button className="secondary-button" type="button" onClick={() => void load(false)}>Try again</button>
      </div>
    ) : (
      <>
        <div className="table-wrap"><table><thead><tr><th>User</th><th>Status</th><th>Last active</th><th>Today</th><th>90-day usage</th><th /></tr></thead><tbody>{users.map((user) => <tr key={user.id}><td><strong>{user.email}</strong><small>{user.id}</small></td><td><StatusPill state={user.status.state} /></td><td>{formatDate(user.lastActivityAt)}</td><td>{formatNumber(user.todayAudioSeconds)} sec<small>of {formatNumber(user.quotaLimitAudioSeconds)} sec</small></td><td>{formatDuration(user.usage90d.audioSeconds)}<small>{formatNumber(user.usage90d.requests)} requests</small></td><td><button className="row-button" type="button" onClick={() => onOpen(user.id)}>Open →</button></td></tr>)}</tbody></table></div>
        {!busy && !loadError && users.length === 0 && <Empty>No accounts match this filter.</Empty>}
        {cursor && <button className="secondary-button load-more" type="button" disabled={busy} onClick={() => void load(true)}>{busy ? "Loading…" : "Load more users"}</button>}
      </>
    )}
  </Panel>;
}

function UserDetailView({ userId, onBack, onError }: { userId: string; onBack(): void; onError(message: string): void }) {
  const [user, setUser] = useState<UserDetail | null>(null);
  const [activity, setActivity] = useState<Activity[]>([]);
  const [action, setAction] = useState<string | null>(null);
  const [loadError, setLoadError] = useState("");
  const controllerRef = useRef<AbortController | null>(null);

  const load = useCallback(async () => {
    controllerRef.current?.abort();
    const controller = new AbortController();
    controllerRef.current = controller;
    setLoadError("");
    onError("");
    try {
      const [detail, events] = await Promise.all([
        adminApi<{ user: UserDetail }>(`/users/${encodeURIComponent(userId)}`, { signal: controller.signal }),
        adminApi<{ activity: Activity[] }>(`/users/${encodeURIComponent(userId)}/activity?limit=50`, { signal: controller.signal }),
      ]);
      if (controller.signal.aborted) return;
      setUser(detail.user);
      setActivity(events.activity);
    } catch (reason) {
      if (isAbortError(reason)) return;
      const message = errorMessage(reason);
      setLoadError(message);
      onError(message);
    }
  }, [onError, userId]);

  useEffect(() => {
    setUser(null);
    setActivity([]);
    void load();
    return () => controllerRef.current?.abort();
  }, [load]);

  if (loadError && !user) {
    return (
      <>
        <button className="back-button" type="button" onClick={onBack}>← Back to users</button>
        <Panel>
          <div className="error-state">
            <p className="muted">{loadError}</p>
            <button className="secondary-button" type="button" onClick={() => void load()}>Try again</button>
          </div>
        </Panel>
      </>
    );
  }
  if (!user) return <Empty>Loading account…</Empty>;

  const now = Date.now();
  return <>
    <button className="back-button" type="button" onClick={onBack}>← Back to users</button>
    <Panel className="detail-head"><div><p className="eyebrow">ACCOUNT</p><h2>{user.email}</h2><StatusPill state={user.status.state} /></div>{user.role !== "admin" && <div className="action-row">
      {user.status.state !== "suspended" && <button className="secondary-button" type="button" onClick={() => setAction("suspend")}>Suspend</button>}
      {user.status.state !== "banned" && <button className="danger-button" type="button" onClick={() => setAction("ban")}>Ban</button>}
      {user.status.state !== "active" && <button className="primary-button" type="button" onClick={() => setAction("restore")}>Restore</button>}
      <button className="secondary-button" type="button" onClick={() => setAction("quota")}>Quota grant</button>
      {user.quota.overrideExpiresAt && <button className="secondary-button" type="button" onClick={() => setAction("clear-quota")}>Clear grant</button>}
      <button className="secondary-button" type="button" onClick={() => setAction("revoke")}>Revoke all sessions</button>
    </div>}</Panel>
    <div className="detail-grid">
      <Panel title="Account"><Summary rows={[["Role", user.role], ["Created", formatDate(user.createdAt)], ["Verified", formatDate(user.verifiedAt)], ["Last active", formatDate(user.lastActivityAt)], ["Terms", user.termsVersion], ["Public message", user.status.publicMessage ?? "—"]]} /></Panel>
      <Panel title="90-day usage"><Summary rows={[["Dictation time", formatDuration(user.usage90d.audioSeconds)], ["Requests", formatNumber(user.usage90d.requests)], ["Neurons", formatNumber(user.usage90d.neurons)], ["Today", `${formatNumber(user.quota.todayUsedAudioSeconds)} / ${formatNumber(user.quota.limitAudioSeconds)} sec`], ["Quota expiry", formatDate(user.quota.overrideExpiresAt)]]} /></Panel>
      <Panel title="Cloud sync records"><Summary rows={user.syncMetadata.map((item) => [capitalize(item.type), `${formatNumber(item.count)} · ${formatBytes(item.storedBytes)}`])} /></Panel>
      <Panel title="Recent activity" className="wide"><Timeline items={activity.map((item) => ({ id: item.id, title: humanize(item.type), detail: activityDetail(item), time: item.createdAt }))} /></Panel>
      <Panel title="Device sessions">
        {user.sessions.length === 0 ? <Empty>No device sessions.</Empty> : (
          <div className="session-list">
            {user.sessions.map((item) => {
              const sessionStatus = item.revokedAt !== null
                ? "revoked"
                : item.absoluteExpiresAt <= now
                  ? "expired"
                  : "active";
              return (
                <div className="session-row" key={item.id}>
                  <div className="session-meta">
                    <strong>{item.deviceName}</strong>
                    <small>
                      Last seen {formatDate(item.lastSeenAt)}
                      {item.revokedAt ? ` · Revoked ${formatDate(item.revokedAt)}` : ""}
                    </small>
                  </div>
                  <div className="session-actions">
                    <span className={`status-pill ${sessionStatus === "active" ? "active" : sessionStatus === "revoked" ? "banned" : "suspended"}`}>{sessionStatus}</span>
                    {user.role !== "admin" && sessionStatus === "active" && (
                      <button className="text-button" type="button" onClick={() => setAction(`revoke-one:${item.id}`)}>Revoke</button>
                    )}
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </Panel>
      <Panel title="Account notices">
        <div className="notice-list">
          {user.notifications.length === 0 ? <Empty>No moderation notices.</Empty> : user.notifications.map((item) => (
            <div className="notice" key={item.id}>
              <strong>{humanize(item.action)}</strong>
              <small>
                {item.status} · {item.attempts} {item.attempts === 1 ? "attempt" : "attempts"} · {formatDate(item.sentAt ?? item.createdAt)}
              </small>
              {item.lastError && <p className="notice-error">Last error: {item.lastError}</p>}
              {item.status === "failed" && <button className="text-button" type="button" onClick={() => setAction(`retry:${item.id}`)}>Retry</button>}
            </div>
          ))}
        </div>
      </Panel>
    </div>
    {action && <ActionDialog action={action} user={user} onClose={() => setAction(null)} onComplete={async () => { setAction(null); await load(); }} />}
  </>;
}

function ActionDialog({ action, user, onClose, onComplete }: { action: string; user: UserDetail; onClose(): void; onComplete(): Promise<void> }) {
  const [reason, setReason] = useState("");
  const [publicMessage, setPublicMessage] = useState("");
  const [limit, setLimit] = useState(Math.max(600, user.quota.limitAudioSeconds));
  const [expires, setExpires] = useState(toLocalInput(Date.now() + (action === "suspend" ? 86_400_000 : 7 * 86_400_000)));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  const isRevokeOne = action.startsWith("revoke-one:");
  const targetSessionId = isRevokeOne ? action.slice("revoke-one:".length) : null;
  const targetSession = targetSessionId ? user.sessions.find((item) => item.id === targetSessionId) : null;

  const title = action === "suspend"
    ? "Suspend account"
    : action === "ban"
      ? "Ban account"
      : action === "restore"
        ? "Restore account"
        : action === "quota"
          ? "Temporary quota grant"
          : action === "clear-quota"
            ? "Clear quota grant"
            : action === "revoke"
              ? "Revoke all device sessions"
              : isRevokeOne
                ? "Revoke device session"
                : "Retry account notice";

  async function submit(event: FormEvent) {
    event.preventDefault(); setBusy(true); setError("");
    try {
      if (action === "suspend") await adminApi(`/users/${encodeURIComponent(user.id)}/status`, { method: "POST", body: { status: "suspended", suspendedUntil: new Date(expires).getTime(), publicMessage, internalReason: reason } });
      else if (action === "ban") await adminApi(`/users/${encodeURIComponent(user.id)}/status`, { method: "POST", body: { status: "banned", publicMessage, internalReason: reason } });
      else if (action === "restore") await adminApi(`/users/${encodeURIComponent(user.id)}/status`, { method: "POST", body: { status: "active", internalReason: reason } });
      else if (action === "quota") await adminApi(`/users/${encodeURIComponent(user.id)}/quota-override`, { method: "PUT", body: { limitAudioSeconds: limit, expiresAt: new Date(expires).getTime(), internalReason: reason } });
      else if (action === "clear-quota") await adminApi(`/users/${encodeURIComponent(user.id)}/quota-override/clear`, { method: "POST", body: { internalReason: reason } });
      else if (action === "revoke") await adminApi(`/users/${encodeURIComponent(user.id)}/sessions/revoke`, { method: "POST", body: { scope: "all", internalReason: reason } });
      else if (isRevokeOne && targetSessionId) await adminApi(`/users/${encodeURIComponent(user.id)}/sessions/revoke`, { method: "POST", body: { scope: "one", sessionId: targetSessionId, internalReason: reason } });
      else if (action.startsWith("retry:")) await adminApi(`/notifications/${encodeURIComponent(action.slice(6))}/retry`, { method: "POST", body: { internalReason: reason } });
      await onComplete();
    } catch (reasonValue) { setError(errorMessage(reasonValue)); } finally { setBusy(false); }
  }

  return (
    <Dialog title={title} eyebrow="CONFIRM ADMIN ACTION" onClose={() => { if (!busy) onClose(); }}>
      <form onSubmit={submit}>
        {isRevokeOne && targetSession && (
          <p className="muted">Revoking session for <strong>{targetSession.deviceName}</strong> ({targetSession.id}).</p>
        )}
        {action === "suspend" && <label>Suspension ends<input type="datetime-local" value={expires} onChange={(event) => setExpires(event.target.value)} required /></label>}
        {(action === "suspend" || action === "ban") && <label>Message shown to user<textarea value={publicMessage} maxLength={240} onChange={(event) => setPublicMessage(event.target.value)} /></label>}
        {action === "quota" && <><label>Daily seconds<input type="number" min={600} max={3600} value={limit} onChange={(event) => setLimit(Number(event.target.value))} required /></label><label>Grant expires<input type="datetime-local" value={expires} onChange={(event) => setExpires(event.target.value)} required /></label></>}
        <label>Internal reason<textarea value={reason} minLength={5} maxLength={500} onChange={(event) => setReason(event.target.value)} required /></label>
        {error && <Banner>{error}</Banner>}
        <div className="modal-actions">
          <button type="button" className="secondary-button" disabled={busy} onClick={onClose}>Cancel</button>
          <button className={action === "ban" || action === "revoke" || isRevokeOne ? "danger-button" : "primary-button"} disabled={busy}>{busy ? "Working…" : title}</button>
        </div>
      </form>
    </Dialog>
  );
}

function Audit({ onError }: { onError(message: string): void }) {
  const [action, setAction] = useState("");
  const [events, setEvents] = useState<AuditEvent[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [loadError, setLoadError] = useState("");
  const controllerRef = useRef<AbortController | null>(null);

  const load = useCallback(async (append = false) => {
    controllerRef.current?.abort();
    const controller = new AbortController();
    controllerRef.current = controller;
    setBusy(true);
    setLoadError("");
    onError("");
    const params = new URLSearchParams({ limit: "50" });
    if (action) params.set("action", action);
    if (append && cursor) params.set("cursor", cursor);
    try {
      const value = await adminApi<{ audit: AuditEvent[]; nextCursor: string | null }>(`/audit?${params}`, { signal: controller.signal });
      if (controller.signal.aborted) return;
      setEvents((current) => append ? [...current, ...value.audit] : value.audit);
      setCursor(value.nextCursor);
    } catch (reason) {
      if (isAbortError(reason)) return;
      const message = errorMessage(reason);
      setLoadError(message);
      onError(message);
    } finally {
      if (controllerRef.current === controller) setBusy(false);
    }
  }, [action, cursor, onError]);

  useEffect(() => {
    void load(false);
    return () => controllerRef.current?.abort();
  }, [action]);

  return <Panel title="Administrative actions" kicker="ACCOUNTABILITY">
    <select className="audit-filter" aria-label="Filter audit events by action" value={action} onChange={(event) => setAction(event.target.value)}>
      <option value="">All actions</option>
      <option value="admin_login">Admin sign-ins</option>
      <option value="admin_logout">Admin sign-outs</option>
      <option value="user_suspended">Suspensions</option>
      <option value="user_banned">Bans</option>
      <option value="user_restored">Restorations</option>
      <option value="suspension_expired">Suspension expiries</option>
      <option value="sessions_revoked">Session revocations</option>
      <option value="quota_override_set">Quota grants</option>
      <option value="quota_override_cleared">Cleared grants</option>
      <option value="moderation_notice_retried">Moderation notice retries</option>
      <option value="ai_configuration_draft_saved">AI drafts</option>
      <option value="ai_configuration_published">AI publications</option>
    </select>
    {loadError && events.length === 0 ? (
      <div className="error-state">
        <p className="muted">{loadError}</p>
        <button className="secondary-button" type="button" onClick={() => void load(false)}>Try again</button>
      </div>
    ) : events.length ? (
      <Timeline items={events.map((item) => ({
        id: item.id,
        title: humanize(item.action),
        detail: `${item.internalReason} · Target: ${item.targetUserId ?? "—"} · Request: ${item.requestId}`,
        time: item.createdAt,
        extra: (item.beforeState !== null || item.afterState !== null) ? (
          <details className="audit-diff">
            <summary>State diff</summary>
            <div className="audit-diff-grid">
              <div><small>BEFORE</small><pre>{formatStateJson(item.beforeState)}</pre></div>
              <div><small>AFTER</small><pre>{formatStateJson(item.afterState)}</pre></div>
            </div>
          </details>
        ) : undefined,
      }))} />
    ) : !busy && <Empty>No administrative actions in this view.</Empty>}
    {cursor && <button className="secondary-button load-more" type="button" disabled={busy} onClick={() => void load(true)}>{busy ? "Loading…" : "Load more events"}</button>}
  </Panel>;
}

function Brand({ onNavigate }: { onNavigate?: () => void }) {
  return (
    <a
      className="brand"
      href="/admin/"
      aria-label="LiVoice admin home"
      onClick={(event) => {
        if (!onNavigate) return;
        event.preventDefault();
        onNavigate();
      }}
    >
      <svg viewBox="0 0 108 108" width="28" height="28" aria-hidden="true">
        <rect width="108" height="108" rx="24" fill="#1A1B22" stroke="#302C3D" strokeWidth="2" />
        <path d="M 33.5,39.5 V 65.5 A 7.5,7.5 0 0 0 41,73 H 46" fill="none" stroke="#7DE4B6" strokeWidth="8.4" strokeLinecap="round" strokeLinejoin="round" />
        <circle cx="58.5" cy="41.5" r="4.6" fill="#AFA4FF" />
        <path d="M 58.5,53.5 V 73" fill="none" stroke="#7DE4B6" strokeWidth="8.2" strokeLinecap="round" />
        <path d="M 60.79,30.74 A 11,11 0 0 1 69.47,42.27" fill="none" stroke="#AFA4FF" strokeWidth="4.8" strokeLinecap="round" />
        <path d="M 65.06,25.27 A 17.5,17.5 0 0 1 75.99,42.11" fill="none" stroke="#7DE4B6" strokeOpacity="0.92" strokeWidth="4.3" strokeLinecap="round" />
      </svg>
      <span>LiVoice</span>
    </a>
  );
}
function NavButton({ active, icon, children, onClick }: { active: boolean; icon: string; children: ReactNode; onClick(): void }) {
  return (
    <button
      type="button"
      className={`nav-item ${active ? "active" : ""}`}
      aria-current={active ? "page" : undefined}
      onClick={onClick}
    >
      <span aria-hidden="true">{icon}</span>
      {children}
    </button>
  );
}
function Panel({ title, kicker, children, className = "" }: { title?: string; kicker?: string; children: ReactNode; className?: string }) { return <section className={`panel ${className}`}>{title && <div className="panel-heading"><div>{kicker && <p className="eyebrow">{kicker}</p>}<h2>{title}</h2></div></div>}{children}</section>; }
function Metric({ label, value, note }: { label: string; value: string; note: string }) { return <article className="metric"><p>{label}</p><strong>{value}</strong><small>{note}</small></article>; }
function Summary({ rows, empty }: { rows: Array<[string, string]>; empty?: string }) { return rows.length ? <div className="summary-list">{rows.map(([label, value]) => <div className="summary-row" key={`${label}-${value}`}><span>{label}</span><strong>{value}</strong></div>)}</div> : <Empty>{empty ?? "No data yet."}</Empty>; }
function Timeline({ items }: { items: Array<{ id: string; title: string; detail: string; time: number; extra?: ReactNode }> }) { return <div className="timeline">{items.map((item) => <article className="timeline-item" key={item.id}><span className="timeline-dot" aria-hidden="true" /><div><h3>{item.title}</h3><p>{item.detail}</p>{item.extra}</div><time>{formatDate(item.time)}</time></article>)}</div>; }
function StatusPill({ state }: { state: AccountState }) { return <span className={`status-pill ${state}`}>{state}</span>; }
function Banner({ children }: { children: ReactNode }) { return <div className="banner error" role="alert">{children}</div>; }
function Empty({ children }: { children: ReactNode }) { return <div className="empty">{children}</div>; }
function FullPageStatus({ title, detail, action }: { title: string; detail: string; action?: ReactNode }) { return <main className="login-page"><section className="login-card"><Brand /><h1>{title}</h1><p className="muted">{detail}</p>{action}</section></main>; }
function LoadingCards() { return <div className="metric-grid">{Array.from({ length: 4 }, (_, index) => <div className="metric skeleton" key={index} />)}</div>; }

function currentRoute(): { view: View; userId: string | null } {
  const detail = location.pathname.match(/^\/admin\/users\/([^/]+)$/u);
  if (detail) {
    try {
      return { view: "detail", userId: decodeURIComponent(detail[1]) };
    } catch {
      return { view: "users", userId: null };
    }
  }
  if (location.pathname.startsWith("/admin/users")) return { view: "users", userId: null };
  if (location.pathname.startsWith("/admin/audit")) return { view: "audit", userId: null };
  if (location.pathname === "/admin/ai" || location.pathname === "/admin/ai/") return { view: "ai", userId: null };
  return { view: "overview", userId: null };
}
function isAbortError(value: unknown): boolean {
  return value instanceof DOMException && value.name === "AbortError";
}
function formatStateJson(value: unknown): string {
  if (value === null || value === undefined) return "—";
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return String(value);
  }
}
function errorMessage(value: unknown): string { return value instanceof Error ? value.message : "Something went wrong. Try again."; }
function periodLabel(value: string): string { return value === "today" ? "Today" : value === "12m" ? "12 months" : value.replace("d", " days"); }
function formatNumber(value: number): string { return new Intl.NumberFormat(undefined, { maximumFractionDigits: 2 }).format(Number(value) || 0); }
function formatPercent(value: number): string { return new Intl.NumberFormat(undefined, { style: "percent", maximumFractionDigits: 1 }).format(value); }
function formatUsd(value: number): string { return new Intl.NumberFormat("en-US", { style: "currency", currency: "USD", minimumFractionDigits: 4, maximumFractionDigits: 6 }).format(Number(value) || 0); }
function formatDuration(seconds: number): string { const value = Math.max(0, Number(seconds) || 0); if (value < 60) return `${Math.round(value)} sec`; const hours = Math.floor(value / 3600); const minutes = Math.round((value % 3600) / 60); return hours ? `${hours}h ${minutes}m` : `${minutes} min`; }
function formatDate(value: number | null): string { return value ? new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" }).format(new Date(value)) : "—"; }
function formatBytes(value: number): string { return new Intl.NumberFormat(undefined, { style: "unit", unit: "byte", notation: "compact", unitDisplay: "narrow" }).format(Number(value) || 0); }
function humanize(value: string): string { return value.replaceAll("_", " ").replace(/\b\w/gu, (letter) => letter.toUpperCase()); }
function capitalize(value: string): string { return value.charAt(0).toUpperCase() + value.slice(1); }
function toLocalInput(value: number): string { return new Date(value - new Date(value).getTimezoneOffset() * 60_000).toISOString().slice(0, 16); }
function activityDetail(item: Activity): string { const values = [item.outcomeCode, item.audioSeconds === null ? null : formatDuration(item.audioSeconds), item.model, item.latencyMs === null ? null : `${formatNumber(item.latencyMs)} ms`, item.itemCount === null ? null : `${item.itemCount} items`, item.deviceName].filter(Boolean); return values.join(" · ") || "Operational event"; }
