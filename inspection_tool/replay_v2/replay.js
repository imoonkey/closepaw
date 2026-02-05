import { renderDetailPanel } from "./detail.js";

const folderInput = document.getElementById("folderInput");
const loadBtn = document.getElementById("loadBtn");
const treePanel = document.getElementById("treePanel");
const detailPanel = document.getElementById("detailPanel");
const meta = document.getElementById("meta");
const stepCounter = document.getElementById("stepCounter");
const prevBtn = document.getElementById("prevBtn");
const nextBtn = document.getElementById("nextBtn");
const filterInput = document.getElementById("filterInput");
const clearFilterBtn = document.getElementById("clearFilterBtn");

/** @type {Map<string, File>} */
let fileMap = new Map();
let rootPrefix = "";
let sessions = [];
let sessionById = new Map();
let stepById = new Map();
let steps = [];
let filteredSteps = [];
let selectedStepIndex = -1;
let selectedStepId = null;
let filterQuery = "";
let treeError = null;

loadBtn.addEventListener("click", () => folderInput.click());
folderInput.addEventListener("change", () => loadTrace(folderInput.files));
prevBtn.addEventListener("click", () => moveSelection(-1));
nextBtn.addEventListener("click", () => moveSelection(1));
filterInput.addEventListener("input", () => {
  filterQuery = filterInput.value.trim().toLowerCase();
  applyFilters();
});
clearFilterBtn.addEventListener("click", () => {
  filterQuery = "";
  filterInput.value = "";
  applyFilters();
});

document.addEventListener("keydown", (event) => {
  if (event.key === "ArrowRight") {
    moveSelection(1);
  }
  if (event.key === "ArrowLeft") {
    moveSelection(-1);
  }
  if (event.key === "ArrowUp") {
    moveSelection(-1);
  }
  if (event.key === "ArrowDown") {
    moveSelection(1);
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
  treeError = null;

  const metaText = await readText("meta.json");
  const metaJson = parseJson(metaText);
  if (metaJson) {
    meta.textContent = `${metaJson.runId || "run"} | ${metaJson.appId || "app"} | sdk ${metaJson.deviceSdkInt || "?"}`;
  } else {
    meta.textContent = "Trace loaded";
  }

  const derivedTreeText = await readText("derived/agent_tree.json");
  const derivedStepsText = await readText("derived/steps.jsonl");
  if (!derivedTreeText || !derivedStepsText) {
    meta.textContent = "Missing derived replay data";
    treePanel.textContent = "Missing derived/agent_tree.json or derived/steps.jsonl.";
    detailPanel.textContent = "Select a step.";
    return;
  }

  const derivedTree = parseJson(derivedTreeText);
  if (!derivedTree || !Array.isArray(derivedTree.sessions)) {
    sessions = [];
    treeError = "Invalid agent_tree.json.";
  } else {
    sessions = derivedTree.sessions;
    treeError = null;
  }
  sessionById = new Map(sessions.map((node) => [node.session_id, node]));

  steps = parseJsonLines(derivedStepsText);
  stepById = new Map(steps.map((step) => [step.step_id, step]));
  filteredSteps = steps.slice();

  selectedStepIndex = -1;
  selectedStepId = null;
  filterQuery = "";
  filterInput.value = "";

  applyFilters();
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

function renderTree() {
  if (treeError) {
    treePanel.textContent = treeError;
    return;
  }
  if (!sessions.length) {
    treePanel.textContent = "No session metadata.";
    return;
  }

  const stepsBySession = buildStepsBySession(steps);
  let roots = sessions.filter((node) => !node.parent_session_id || !sessionById.has(node.parent_session_id));
  if (!roots.length) {
    roots = sessions.slice();
  }
  treePanel.innerHTML = "";

  const list = document.createElement("div");
  list.className = "tree-list";
  let rendered = false;
  roots.forEach((root) => {
    if (appendSessionNode(root.session_id, list, 0, stepsBySession)) {
      rendered = true;
    }
  });
  if (!rendered) {
    treePanel.textContent = filterQuery ? "No steps match the filter." : "No steps found.";
    return;
  }
  treePanel.appendChild(list);
}

function buildStepsBySession(allSteps) {
  const map = new Map();
  allSteps.forEach((step) => {
    if (!step.session_id) return;
    if (!map.has(step.session_id)) map.set(step.session_id, []);
    map.get(step.session_id).push(step);
  });
  for (const list of map.values()) {
    list.sort((a, b) => {
      const aTs = Number(a.ts_start_ms ?? 0);
      const bTs = Number(b.ts_start_ms ?? 0);
      if (aTs !== bTs) return aTs - bTs;
      return Number(a.turn_number ?? 0) - Number(b.turn_number ?? 0);
    });
  }
  return map;
}

function appendSessionNode(sessionId, parent, depth, stepsBySession) {
  const session = sessionById.get(sessionId);
  const sessionLabel = session
    ? `${roleLabel(session.agent_role)} ${session.session_id}`
    : `[?] ${sessionId}`;

  if (filterQuery && !sessionHasVisibleSteps(sessionId, stepsBySession)) {
    return false;
  }

  const sessionRow = document.createElement("div");
  sessionRow.className = "tree-session";
  sessionRow.style.marginLeft = `${depth * 14}px`;
  sessionRow.innerHTML = `
    <div class="tree-title">${escapeHtml(sessionLabel)}</div>
    <div class="tree-meta">${escapeHtml(session?.status || "running")}</div>
  `;
  parent.appendChild(sessionRow);

  const sessionSteps = stepsBySession.get(sessionId) || [];
  sessionSteps.forEach((step) => {
    if (!stepIsVisible(step, stepsBySession)) return;
    parent.appendChild(createStepNode(step, depth + 1, stepsBySession));
  });
  return true;
}

function createStepNode(step, depth, stepsBySession) {
  const hasChildren = Array.isArray(step.links?.child_session_ids) && step.links.child_session_ids.length > 0;
  const isMatch = stepMatchesFilter(step);
  const isContext = filterQuery && !isMatch;
  const isActive = step.step_id === selectedStepId;
  const stepContent = buildStepContent(step, depth, isActive, isContext);

  if (!hasChildren) {
    stepContent.addEventListener("click", () => selectStepById(step.step_id));
    return stepContent;
  }

  const details = document.createElement("details");
  details.className = "tree-branch";
  details.open = step.step_id === selectedStepId || stepHasSelectedDescendant(step, stepsBySession);

  const summary = document.createElement("summary");
  summary.appendChild(stepContent);
  summary.addEventListener("click", () => selectStepById(step.step_id));
  details.appendChild(summary);

  const childContainer = document.createElement("div");
  const childSessions = step.links?.child_session_ids || [];
  childSessions.forEach((childSessionId) => {
    appendSessionNode(childSessionId, childContainer, depth + 1, stepsBySession);
  });
  details.appendChild(childContainer);
  return details;
}

function buildStepContent(step, depth, isActive, isContext) {
  const item = document.createElement("div");
  item.className = "tree-step";
  if (isActive) item.classList.add("active");
  if (isContext) item.classList.add("context");
  item.style.marginLeft = `${depth * 14}px`;

  const types = Array.isArray(step.event_types) ? step.event_types.join(" -> ") : "";
  const turn = step.turn_number != null ? `turn ${step.turn_number}` : "turn ?";
  item.innerHTML = `
    <div class="tree-head">
      <span>${escapeHtml(turn)}</span>
      <span class="code">#${escapeHtml(String(step.seq_start ?? "?"))}</span>
    </div>
    <div class="tree-meta-line">${escapeHtml(types)}</div>
  `;
  return item;
}

function roleLabel(role) {
  const normalized = String(role || "unknown").toLowerCase();
  if (normalized.includes("planner")) return "[P]";
  if (normalized.includes("executor")) return "[E]";
  return "[?]";
}

function applyFilters() {
  filteredSteps = filterQuery ? steps.filter((step) => stepMatchesFilter(step)) : steps.slice();
  selectedStepIndex = filteredSteps.findIndex((step) => step.step_id === selectedStepId);
  renderTree();
  updateCounter();
}

function updateCounter() {
  if (!filteredSteps.length) {
    stepCounter.textContent = "0 / 0";
    return;
  }
  if (selectedStepIndex >= 0) {
    stepCounter.textContent = `${selectedStepIndex + 1} / ${filteredSteps.length}`;
  } else {
    stepCounter.textContent = `0 / ${filteredSteps.length}`;
  }
}

function stepMatchesFilter(step) {
  if (!filterQuery) return true;
  const toolNames = (step.tool?.calls || [])
    .map((call) => call?.data?.name)
    .filter(Boolean)
    .join(" ");
  const parts = [
    step.step_id,
    step.session_id,
    step.agent_role,
    step.turn_number != null ? String(step.turn_number) : "",
    Array.isArray(step.event_types) ? step.event_types.join(" ") : "",
    toolNames,
  ];
  return parts.join(" ").toLowerCase().includes(filterQuery);
}

function stepIsVisible(step, stepsBySession) {
  if (!filterQuery) return true;
  if (stepMatchesFilter(step)) return true;
  return stepHasVisibleDescendant(step, stepsBySession, new Map(), new Set());
}

function sessionHasVisibleSteps(sessionId, stepsBySession, memo = new Map(), visiting = new Set()) {
  if (memo.has(sessionId)) return memo.get(sessionId);
  if (visiting.has(sessionId)) return false;
  visiting.add(sessionId);
  const sessionSteps = stepsBySession.get(sessionId) || [];
  for (const step of sessionSteps) {
    if (stepMatchesFilter(step)) {
      memo.set(sessionId, true);
      visiting.delete(sessionId);
      return true;
    }
    if (stepHasVisibleDescendant(step, stepsBySession, memo, visiting)) {
      memo.set(sessionId, true);
      visiting.delete(sessionId);
      return true;
    }
  }
  memo.set(sessionId, false);
  visiting.delete(sessionId);
  return false;
}

function stepHasVisibleDescendant(step, stepsBySession, memo, visiting) {
  const childSessions = step.links?.child_session_ids || [];
  for (const childSessionId of childSessions) {
    if (sessionHasVisibleSteps(childSessionId, stepsBySession, memo, visiting)) {
      return true;
    }
  }
  return false;
}

function stepHasSelectedDescendant(step, stepsBySession) {
  if (!selectedStepId) return false;
  const childSessions = step.links?.child_session_ids || [];
  for (const childSessionId of childSessions) {
    const steps = stepsBySession.get(childSessionId) || [];
    for (const childStep of steps) {
      if (childStep.step_id === selectedStepId) return true;
      if (stepHasSelectedDescendant(childStep, stepsBySession)) return true;
    }
  }
  return false;
}

function moveSelection(delta) {
  if (!filteredSteps.length) return;
  if (selectedStepIndex < 0) {
    const index = delta > 0 ? 0 : filteredSteps.length - 1;
    selectStep(index);
    return;
  }
  const nextIndex = Math.min(
    Math.max(selectedStepIndex + delta, 0),
    filteredSteps.length - 1
  );
  selectStep(nextIndex);
}

function selectStep(index) {
  if (!filteredSteps.length) return;
  if (index < 0 || index >= filteredSteps.length) return;

  const step = filteredSteps[index];
  selectedStepIndex = index;
  selectedStepId = step.step_id;
  renderTree();
  updateCounter();
  renderDetailPanel({
    container: detailPanel,
    step,
    getFile,
    escapeHtml,
    onJumpToStepId: (stepId) => jumpToStep(stepId),
    onJumpToSessionId: (sessionId) => jumpToSession(sessionId),
  });
}

function selectStepById(stepId) {
  if (!stepId) return;
  if (!stepById.has(stepId)) return;
  const index = filteredSteps.findIndex((step) => step.step_id === stepId);
  if (index >= 0) {
    selectStep(index);
    return;
  }
  selectedStepId = stepId;
  selectedStepIndex = -1;
  renderTree();
  updateCounter();
  renderDetailPanel({
    container: detailPanel,
    step: stepById.get(stepId),
    getFile,
    escapeHtml,
    onJumpToStepId: (nextStepId) => jumpToStep(nextStepId),
    onJumpToSessionId: (sessionId) => jumpToSession(sessionId),
  });
}

function jumpToStep(stepId) {
  if (!stepId || !stepById.has(stepId)) return;
  clearFilter();
  applyFilters();
  selectStepById(stepId);
}

function jumpToSession(sessionId) {
  if (!sessionId) return;
  clearFilter();
  applyFilters();
  const sessionSteps = steps
    .filter((step) => step.session_id === sessionId)
    .sort((a, b) => Number(a.ts_start_ms ?? 0) - Number(b.ts_start_ms ?? 0));
  if (sessionSteps.length > 0) {
    selectStepById(sessionSteps[0].step_id);
  }
}

function clearFilter() {
  filterQuery = "";
  filterInput.value = "";
}

function escapeHtml(text) {
  return String(text)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}
