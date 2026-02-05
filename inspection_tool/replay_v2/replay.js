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
let selectedSessionId = null;
let selectedStepIndex = -1;
let filterQuery = "";
let treeError = null;

loadBtn.addEventListener("click", () => folderInput.click());
folderInput.addEventListener("change", () => loadTrace(folderInput.files));
prevBtn.addEventListener("click", () => selectStep(selectedStepIndex - 1));
nextBtn.addEventListener("click", () => selectStep(selectedStepIndex + 1));
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
    selectStep(selectedStepIndex + 1);
  }
  if (event.key === "ArrowLeft") {
    selectStep(selectedStepIndex - 1);
  }
  if (event.key === "ArrowUp") {
    selectStep(selectedStepIndex - 1);
  }
  if (event.key === "ArrowDown") {
    selectStep(selectedStepIndex + 1);
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
    meta.textContent = `${metaJson.runId || "run"} • ${metaJson.appId || "app"} • sdk ${metaJson.deviceSdkInt || "?"}`;
  } else {
    meta.textContent = "Trace loaded";
  }

  const derivedTreeText = await readText("derived/agent_tree.json");
  const derivedStepsText = await readText("derived/steps.jsonl");
  if (!derivedTreeText || !derivedStepsText) {
    meta.textContent = "Missing derived replay data";
    treePanel.textContent = "Missing derived/agent_tree.json or derived/steps.jsonl.";
    timeline.innerHTML = "";
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

  selectedSessionId = null;
  selectedStepIndex = -1;
  filterQuery = "";
  filterInput.value = "";

  renderTree();
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
    selectSession(node.session_id);
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

function selectSession(sessionId) {
  selectedSessionId = sessionId;
  selectedStepIndex = -1;
  renderTree();
  applyFilters();
  if (filteredSteps.length > 0) {
    selectStep(0);
  } else {
    detailPanel.textContent = "Select a step.";
  }
}

function applyFilters() {
  filteredSteps = steps.filter((step) => {
    if (selectedSessionId && step.session_id !== selectedSessionId) return false;
    return stepMatchesFilter(step);
  });
  selectedStepIndex = -1;
  renderTimeline();
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

function renderTimeline() {
  timeline.innerHTML = "";
  if (!filteredSteps.length) {
    stepCounter.textContent = "0 / 0";
    const empty = document.createElement("li");
    empty.className = "hint";
    if (filterQuery) {
      empty.textContent = "No steps match the filter.";
    } else {
      empty.textContent = selectedSessionId ? "No steps in selected agent." : "Select an agent node.";
    }
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
    onJumpToStepId: (stepId) => jumpToStep(stepId),
    onJumpToSessionId: (sessionId) => jumpToSession(sessionId),
  });
}

function jumpToStep(stepId) {
  if (!stepId || !stepById.has(stepId)) return;
  const step = stepById.get(stepId);
  if (step?.session_id) {
    selectedSessionId = step.session_id;
    clearFilter();
    applyFilters();
    const index = filteredSteps.findIndex((item) => item.step_id === stepId);
    if (index >= 0) {
      selectStep(index);
    }
  }
}

function jumpToSession(sessionId) {
  if (!sessionId) return;
  selectedSessionId = sessionId;
  clearFilter();
  applyFilters();
  if (filteredSteps.length > 0) {
    selectStep(0);
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
