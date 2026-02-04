import { renderDetailPanel } from "./detail.js";

const folderInput = document.getElementById("folderInput");
const loadBtn = document.getElementById("loadBtn");
const treePanel = document.getElementById("treePanel");
const timeline = document.getElementById("timeline");
const detailPanel = document.getElementById("detailPanel");
const meta = document.getElementById("meta");
const stepCounter = document.getElementById("stepCounter");
const prevBtn = document.getElementById("prevBtn");
const nextBtn = document.getElementById("nextBtn");

/** @type {Map<string, File>} */
let fileMap = new Map();
let rootPrefix = "";
let allEvents = [];
let sessions = [];
let sessionById = new Map();
let steps = [];
let filteredSteps = [];
let selectedSessionId = null;
let selectedStepIndex = -1;

loadBtn.addEventListener("click", () => folderInput.click());
folderInput.addEventListener("change", () => loadTrace(folderInput.files));
prevBtn.addEventListener("click", () => selectStep(selectedStepIndex - 1));
nextBtn.addEventListener("click", () => selectStep(selectedStepIndex + 1));

document.addEventListener("keydown", (event) => {
  if (event.key === "ArrowRight") {
    selectStep(selectedStepIndex + 1);
  }
  if (event.key === "ArrowLeft") {
    selectStep(selectedStepIndex - 1);
  }
});

function buildFileMap(files) {
  fileMap = new Map();
  for (const file of files) {
    const rel = file.webkitRelativePath || file.name;
    fileMap.set(rel, file);
  }
}

function detectRootPrefix() {
  for (const path of fileMap.keys()) {
    if (path.endsWith("/trace.jsonl")) {
      return path.slice(0, -"trace.jsonl".length);
    }
    if (path === "trace.jsonl") {
      return "";
    }
  }
  return "";
}

function getFile(path) {
  const withPrefix = rootPrefix ? rootPrefix + path : path;
  return fileMap.get(withPrefix) || fileMap.get(path) || null;
}

async function readText(path) {
  const file = getFile(path);
  if (!file) return null;
  return file.text();
}

async function loadTrace(files) {
  if (!files || files.length === 0) return;

  buildFileMap(files);
  rootPrefix = detectRootPrefix();

  const metaText = await readText("meta.json");
  const metaJson = parseJson(metaText);
  if (metaJson) {
    meta.textContent = `${metaJson.runId || "run"} • ${metaJson.appId || "app"} • sdk ${metaJson.deviceSdkInt || "?"}`;
  } else {
    meta.textContent = "Trace loaded";
  }

  const traceText = await readText("trace.jsonl");
  if (!traceText) {
    meta.textContent = "Missing trace.jsonl";
    return;
  }

  allEvents = traceText
    .split("\n")
    .map((line) => line.trim())
    .filter((line) => line.length > 0)
    .map(parseJson)
    .filter(Boolean)
    .sort((a, b) => (a.seq || 0) - (b.seq || 0));

  const derivedTree = parseJson(await readText("derived/agent_tree.json"));
  const derivedStepsText = await readText("derived/steps.jsonl");

  sessions = derivedTree?.sessions || buildSessions(allEvents);
  sessionById = new Map(sessions.map((node) => [node.session_id, node]));

  steps = derivedStepsText ? parseJsonLines(derivedStepsText) : buildStepsFallback(allEvents);
  filteredSteps = steps;

  selectedSessionId = null;
  selectedStepIndex = -1;

  renderTree();
  renderTimeline();
  detailPanel.textContent = "Select a step.";
}

function parseJson(text) {
  if (!text) return null;
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

function parseJsonLines(text) {
  return text
    .split("\n")
    .map((line) => line.trim())
    .filter((line) => line.length > 0)
    .map(parseJson)
    .filter(Boolean);
}

function eventType(event) {
  return event.type || event.event || "unknown";
}

function eventSessionId(event) {
  return event.sessionId || event.session_id || event.ctx?.session_id || null;
}

function eventTurnNumber(event) {
  return event.turnNumber ?? event.ctx?.turn_number ?? null;
}

function buildSessions(events) {
  const map = new Map();

  for (const event of events) {
    if (eventType(event) !== "session_started") continue;
    const sessionId = eventSessionId(event);
    if (!sessionId) continue;
    const data = event.data || {};
    map.set(sessionId, {
      session_id: sessionId,
      parent_session_id: data.parent_session_id || parseParentSessionId(sessionId),
      agent_role: data.agent_role || "unknown",
      goal: data.goal || "",
      status: "running",
      children: [],
    });
  }

  for (const event of events) {
    if (eventType(event) !== "session_stopped") continue;
    const sessionId = eventSessionId(event);
    if (!sessionId) continue;
    if (!map.has(sessionId)) {
      map.set(sessionId, {
        session_id: sessionId,
        parent_session_id: parseParentSessionId(sessionId),
        agent_role: "unknown",
        goal: "",
        status: "stopped",
        children: [],
      });
    }
    const reason = event.data?.reason || "stopped";
    map.get(sessionId).status = reason;
  }

  for (const session of map.values()) {
    const parent = session.parent_session_id;
    if (parent && map.has(parent)) {
      map.get(parent).children.push(session.session_id);
    }
  }

  return Array.from(map.values());
}

function parseParentSessionId(sessionId) {
  if (!sessionId || !sessionId.includes("::")) return null;
  return sessionId.split("::").slice(0, -1).join("::");
}

function buildStepsFallback(events) {
  const grouped = new Map();

  for (const event of events) {
    const sessionId = eventSessionId(event);
    const turnNumber = eventTurnNumber(event);
    if (!sessionId || turnNumber == null) continue;
    const key = `${sessionId}::${turnNumber}`;
    if (!grouped.has(key)) grouped.set(key, []);
    grouped.get(key).push(event);
  }

  const steps = [];
  for (const [key, turnEvents] of grouped.entries()) {
    turnEvents.sort((a, b) => (a.seq || 0) - (b.seq || 0));
    const [sessionId, turnRaw] = key.split("::");
    const turnNumber = Number(turnRaw);
    const stepId = `${sessionId}::turn-${turnNumber}`;

    const preEvent = turnEvents.find((event) => eventType(event) === "screen_captured");
    const reqEvent = turnEvents.find((event) => eventType(event) === "llm_request");
    const respEvent = turnEvents.find((event) => eventType(event) === "llm_response");

    const toolCalls = turnEvents.filter((event) => eventType(event) === "tool_call");
    const toolResults = turnEvents.filter((event) => eventType(event) === "tool_result");

    steps.push({
      step_id: stepId,
      session_id: sessionId,
      turn_number: turnNumber,
      event_types: turnEvents.map(eventType),
      world: {
        pre: preEvent ? {
          event: compactEvent(preEvent),
          screenshot: findArtifact(preEvent, "screenshot"),
          raw_a11y_tree: findArtifact(preEvent, "raw_a11y_tree"),
          sanitized_a11y_tree: findArtifact(preEvent, "sanitized_a11y_tree"),
        } : null,
        post: null,
      },
      mind: {
        llm_request: reqEvent ? compactEvent(reqEvent) : null,
        llm_response: respEvent ? compactEvent(respEvent) : null,
      },
      tool: {
        calls: toolCalls.map(compactEvent),
        results: toolResults.map(compactEvent),
      },
      links: {
        parent_step_id: null,
        child_session_ids: [],
      },
    });
  }

  return steps.sort((a, b) => (a.turn_number || 0) - (b.turn_number || 0));
}

function compactEvent(event) {
  return {
    seq: event.seq,
    ts_ms: event.tsMs,
    type: eventType(event),
    data: event.data,
    artifacts: Array.isArray(event.artifacts) ? event.artifacts : [],
  };
}

function findArtifact(event, kind) {
  const artifacts = Array.isArray(event.artifacts) ? event.artifacts : [];
  return artifacts.find((artifact) => artifact.kind === kind) || null;
}

function renderTree() {
  if (!sessions.length) {
    treePanel.textContent = "No session metadata.";
    return;
  }

  const roots = sessions.filter((node) => !node.parent_session_id || !sessionById.has(node.parent_session_id));
  treePanel.innerHTML = "";

  const list = document.createElement("ul");
  list.className = "tree-list";
  roots.forEach((root) => appendTreeNode(list, root, 0));
  treePanel.appendChild(list);
}

function appendTreeNode(parent, node, depth) {
  const li = document.createElement("li");
  li.className = "tree-item" + (selectedSessionId === node.session_id ? " active" : "");
  li.style.marginLeft = `${depth * 14}px`;

  const role = roleLabel(node.agent_role);
  li.innerHTML = `
    <div class="tree-title">${role} ${escapeHtml(node.session_id)}</div>
    <div class="tree-meta">${escapeHtml(node.status || "running")}</div>
  `;

  li.addEventListener("click", () => {
    selectedSessionId = node.session_id;
    filteredSteps = steps.filter((step) => step.session_id === selectedSessionId);
    selectedStepIndex = -1;
    renderTree();
    renderTimeline();
    if (filteredSteps.length > 0) {
      selectStep(0);
    }
  });

  parent.appendChild(li);

  const children = Array.isArray(node.children) ? node.children : [];
  children.forEach((childId) => {
    const child = sessionById.get(childId);
    if (child) appendTreeNode(parent, child, depth + 1);
  });
}

function roleLabel(role) {
  const normalized = String(role || "unknown").toLowerCase();
  if (normalized.includes("planner")) return "[P]";
  if (normalized.includes("executor")) return "[E]";
  return "[?]";
}

function renderTimeline() {
  timeline.innerHTML = "";
  if (!filteredSteps.length) {
    stepCounter.textContent = "0 / 0";
    const empty = document.createElement("li");
    empty.className = "hint";
    empty.textContent = selectedSessionId ? "No steps in selected agent." : "Select an agent node.";
    timeline.appendChild(empty);
    return;
  }

  filteredSteps.forEach((step, index) => {
    const li = document.createElement("li");
    li.className = "timeline-item" + (selectedStepIndex === index ? " active" : "");
    li.addEventListener("click", () => selectStep(index));

    const types = Array.isArray(step.event_types) ? step.event_types.join(" → ") : "";
    const turn = step.turn_number != null ? `turn ${step.turn_number}` : "turn ?";

    li.innerHTML = `
      <div class="timeline-head">
        <span>${escapeHtml(turn)}</span>
        <span class="code">#${escapeHtml(String(step.seq_start ?? "?"))}</span>
      </div>
      <div class="timeline-meta">${escapeHtml(types)}</div>
    `;
    timeline.appendChild(li);
  });

  stepCounter.textContent = `${Math.max(selectedStepIndex + 1, 0)} / ${filteredSteps.length}`;
}

function selectStep(index) {
  if (!filteredSteps.length) return;
  if (index < 0 || index >= filteredSteps.length) return;

  selectedStepIndex = index;
  renderTimeline();

  const step = filteredSteps[index];
  stepCounter.textContent = `${selectedStepIndex + 1} / ${filteredSteps.length}`;
  renderDetailPanel({
    container: detailPanel,
    step,
    getFile,
    escapeHtml,
  });
}

function escapeHtml(text) {
  return String(text)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}
