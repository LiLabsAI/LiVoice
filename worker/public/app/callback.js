const params = new URLSearchParams(location.search);
const code = params.get("code") || "";
const reauthToken = params.get("reauth_token") || "";
const state = params.get("state") || "";
const token = code || reauthToken;
const valid = /^[A-Za-z0-9_-]{20,160}$/u.test(state)
  && /^[A-Za-z0-9._~-]{20,256}$/u.test(token)
  && !(code && reauthToken);

const openApp = document.querySelector("#open-app");
const desktopLink = document.querySelector("#open-desktop");
const agent = navigator.userAgent || "";
const isAndroid = /Android/i.test(agent);
const isMac = /Macintosh|Mac OS X/i.test(agent) && !/iPhone|iPad/i.test(agent);

if (valid) {
  const packageName = "com.aliahad.wovoice";
  if (openApp !== null) {
    openApp.href = `intent://${location.host}${location.pathname}${location.search}`
      + `#Intent;scheme=https;package=${packageName};end`;
  }
  // macOS/Windows desktop builds register the wovoice:// scheme; the code is
  // PKCE-bound, so a deep-link hop stays useless to anything in between.
  if (desktopLink !== null) {
    desktopLink.href = `wovoice://callback?${location.search.split("?")[1] ?? ""}`;
    if (!isAndroid) {
      desktopLink.classList.remove("hidden");
    }
  }
  if (isMac && openApp !== null) {
    openApp.classList.add("hidden");
  }
} else {
  document.querySelector("#callback-title").textContent = "Start sign-in again";
  document.querySelector("#callback-message").textContent =
    "This secure sign-in response is missing or has expired.";
  document.querySelector("#callback-help").textContent =
    "Return to LiVoice and tap Sign in to request a new verification code.";
  openApp?.classList.add("hidden");
  desktopLink?.classList.add("hidden");
}
