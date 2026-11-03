import { afterEach, describe, expect, it, vi } from "vitest";
import authScript from "../../public/auth.js?raw";

afterEach(() => vi.restoreAllMocks());

describe("native authorization callbacks", () => {
  async function complete(platform: string, intent = "login", extra = "") {
    const listeners = new Map<string, (event: { preventDefault(): void }) => Promise<void>>();
    const node = (selector: string) => ({
      value: selector === "#code" ? "123456" : "person@example.test",
      disabled: false, required: true, textContent: "", clientWidth: 400,
      classList: { add() {}, remove() {}, toggle() {} }, focus() {},
      addEventListener(event: string, callback: (event: { preventDefault(): void }) => Promise<void>) { listeners.set(`${selector}:${event}`, callback); },
    });
    const nodes = new Map<string, ReturnType<typeof node>>();
    const document = { querySelector(selector: string) { if (!nodes.has(selector)) nodes.set(selector, node(selector)); return nodes.get(selector); } };
    const replace = vi.fn();
    const location = { search: `?platform=${platform}&intent=${intent}&code_challenge=${"a".repeat(43)}&state=${"s".repeat(32)}${extra}`, origin: "https://wovoice.aliahad.com", replace, reload() {} };
    const fetch = vi.fn(async (path: string) => ({ ok: true, json: async () => path === "/v1/auth/config"
      ? { turnstileSiteKey: "test-site" } : { authorizationCode: "authorization-code-fixture", reauthToken: "reauth-token-fixture" } }));
    const window = { onTurnstileLoad: () => {}, turnstile: { render: () => "test-widget" } };
    new Function("document", "location", "window", "fetch", authScript)(document, location, window, fetch);
    window.onTurnstileLoad();
    await listeners.get("#code-form:submit")!({ preventDefault() {} });
    return new URL(replace.mock.calls[0][0] as string);
  }
  it("routes iOS sign-in to its fixed scheme with code and state", async () => {
    const target = await complete("ios", "login", "&redirect_uri=https://attacker.test");
    expect(target.protocol).toBe("wovoice-ios:"); expect(target.host).toBe("callback");
    expect(target.searchParams.get("code")).toBe("authorization-code-fixture");
    expect(target.searchParams.get("state")).toBe("s".repeat(32));
    expect(target.searchParams.has("reauth_token")).toBe(false);
  });
  it("uses the same isolated callback for fresh deletion verification", async () => {
    const target = await complete("ios", "delete");
    expect(target.searchParams.get("reauth_token")).toBe("reauth-token-fixture");
    expect(target.searchParams.has("code")).toBe(false);
  });
  it("keeps Android and desktop callbacks compatible", async () => {
    expect((await complete("android")).href).toContain("https://wovoice.aliahad.com/app/callback?");
    expect((await complete("desktop", "login", "&redirect_port=54321")).origin).toBe("http://127.0.0.1:54321");
    expect((await complete("desktop", "login", "&redirect_port=evil")).origin).toBe("https://wovoice.aliahad.com");
  });
});
