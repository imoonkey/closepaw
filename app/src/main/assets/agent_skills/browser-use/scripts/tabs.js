// Tab and target helpers for browser_script. Inline the functions you need.
// Assumes only the browser_script prelude function: await cdp(method, params, options).

const INTERNAL_TARGET_PREFIXES = [
  "chrome://",
  "chrome-untrusted://",
  "devtools://",
  "chrome-extension://",
  "about:"
];

function isInternalTargetUrl(url) {
  return INTERNAL_TARGET_PREFIXES.some(prefix => String(url || "").startsWith(prefix));
}

function targetIdOf(target) {
  if (typeof target === "string") return target;
  if (target && typeof target.targetId === "string") return target.targetId;
  throw new Error("Expected a targetId string or target object");
}

async function listTabs({ includeInternal = false } = {}) {
  const result = await cdp("Target.getTargets");
  const targets = result.targetInfos || [];
  return targets
    .filter(target => target.type === "page")
    .filter(target => includeInternal || !isInternalTargetUrl(target.url))
    .map(target => ({
      targetId: target.targetId,
      title: target.title || "",
      url: target.url || "",
      attached: !!target.attached
    }));
}

async function currentTab() {
  const result = await cdp("Target.getTargetInfo");
  const target = result.targetInfo || {};
  return {
    targetId: target.targetId || "",
    title: target.title || "",
    url: target.url || "",
    attached: !!target.attached
  };
}

async function switchTab(target) {
  const targetId = targetIdOf(target);
  await cdp("Target.activateTarget", { targetId });
  // Passing targetId through options makes the Kotlin CDP client attach and set
  // this target as the default page session for later page-domain calls.
  await cdp("Page.enable", {}, { targetId });
  await cdp("Runtime.enable");
  await cdp("DOM.enable");
  return { targetId };
}

async function newTab(url = "about:blank") {
  // Create blank first, then navigate after attach. Creating with the final URL
  // can race load polling because about:blank is already complete.
  const created = await cdp("Target.createTarget", { url: "about:blank" });
  const targetId = created.targetId;
  await switchTab(targetId);
  if (url !== "about:blank") {
    await cdp("Page.navigate", { url });
  }
  return targetId;
}

async function ensureRealTab() {
  const tabs = await listTabs();
  if (tabs.length === 0) {
    return { targetId: await newTab("about:blank") };
  }
  const current = await currentTab();
  if (current.url && !isInternalTargetUrl(current.url)) {
    return current;
  }
  await switchTab(tabs[0]);
  return tabs[0];
}
