const statusText = document.querySelector("#service-status");
const statusBadge = document.querySelector("#status-badge");
const maintenanceNode = document.querySelector("#maintenance-message");
const databaseNode = document.querySelector("#component-database");
const transcriptionNode = document.querySelector("#component-transcription");
const quotaNode = document.querySelector("#component-quota");
const checkedNode = document.querySelector("#status-checked");
const refreshButton = document.querySelector("#status-refresh");

const COMPONENT_LABELS = {
  operational: "Operational",
  maintenance: "Maintenance",
  exhausted: "Daily limit reached",
  degraded: "Degraded",
};

function setComponent(node, state) {
  if (!node) return;
  const normalized = state || "degraded";
  node.textContent = COMPONENT_LABELS[normalized] || normalized;
  node.dataset.state = normalized;
}

function formatCheckedAt(timestamp) {
  if (!timestamp) return "Just now";
  return new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "medium" }).format(new Date(timestamp));
}

async function checkStatus() {
  if (refreshButton) {
    refreshButton.disabled = true;
    refreshButton.textContent = "Checking…";
  }
  try {
    const response = await fetch("/v1/status", { headers: { Accept: "application/json" }, cache: "no-store" });
    const value = await response.json().catch(() => null);
    if (!value || typeof value !== "object") throw new Error("offline");

    const overall = value.status === "online" || value.status === "maintenance" || value.status === "degraded"
      ? value.status
      : response.ok
        ? "online"
        : "degraded";

    if (statusBadge) {
      statusBadge.textContent = overall === "online"
        ? "Operational"
        : overall === "maintenance"
          ? "Maintenance"
          : "Degraded";
      statusBadge.dataset.state = overall;
    }

    if (statusText) {
      statusText.textContent = overall === "online"
        ? "All LiVoice systems are operational."
        : overall === "maintenance"
          ? "LiVoice dictation is currently paused for maintenance."
          : "Some LiVoice services are experiencing issues.";
    }

    if (maintenanceNode) {
      if (value.maintenanceMessage) {
        maintenanceNode.textContent = value.maintenanceMessage;
        maintenanceNode.classList.remove("hidden");
      } else {
        maintenanceNode.textContent = "";
        maintenanceNode.classList.add("hidden");
      }
    }

    const components = value.components || {};
    setComponent(databaseNode, components.database || (overall === "online" ? "operational" : "degraded"));
    setComponent(
      transcriptionNode,
      components.transcription || (overall === "maintenance" ? "maintenance" : overall === "online" ? "operational" : "degraded"),
    );
    setComponent(quotaNode, components.quota || (overall === "online" ? "operational" : "degraded"));

    if (checkedNode) {
      checkedNode.textContent = `Last checked: ${formatCheckedAt(value.checkedAt || Date.now())}`;
    }
  } catch {
    if (statusBadge) {
      statusBadge.textContent = "Unavailable";
      statusBadge.dataset.state = "degraded";
    }
    if (statusText) statusText.textContent = "LiVoice is currently unavailable.";
    if (maintenanceNode) {
      maintenanceNode.textContent = "";
      maintenanceNode.classList.add("hidden");
    }
    setComponent(databaseNode, "degraded");
    setComponent(transcriptionNode, "degraded");
    setComponent(quotaNode, "degraded");
    if (checkedNode) {
      checkedNode.textContent = `Last checked: ${formatCheckedAt(Date.now())}`;
    }
  } finally {
    if (refreshButton) {
      refreshButton.disabled = false;
      refreshButton.textContent = "Refresh";
    }
  }
}

refreshButton?.addEventListener("click", () => {
  void checkStatus();
});

void checkStatus();
