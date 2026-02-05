export function renderDetailPanel({ container, step, getFile, escapeHtml, onJumpToStepId, onJumpToSessionId }) {
  container.innerHTML = "";

  container.appendChild(renderSummary(step, escapeHtml));
  container.appendChild(renderWorldPanel(step, getFile, escapeHtml));
  container.appendChild(renderMindPanel(step, getFile, escapeHtml));
  container.appendChild(renderToolPanel(step, escapeHtml));
  container.appendChild(renderLinksPanel(step, escapeHtml, onJumpToStepId, onJumpToSessionId));
}

function renderSummary(step, escapeHtml) {
  const summary = document.createElement("div");
  summary.className = "section";
  summary.innerHTML = `
    <div class="section-title">Step Summary</div>
    <div class="code">step_id: ${escapeHtml(step.step_id || "")}</div>
    <div class="code">session: ${escapeHtml(step.session_id || "")}</div>
    <div class="code">turn: ${escapeHtml(String(step.turn_number ?? ""))}</div>
    <div class="code">role: ${escapeHtml(step.agent_role || "")}</div>
  `;
  return summary;
}

function renderWorldPanel(step, getFile, escapeHtml) {
  const world = document.createElement("div");
  world.className = "section";
  world.innerHTML = `<div class="section-title">World</div>`;

  const views = buildWorldViews(step);
  if (!views.length) {
    const empty = document.createElement("div");
    empty.className = "preview";
    empty.innerHTML = "<pre>No world artifacts for this step.</pre>";
    world.appendChild(empty);
    return world;
  }

  let activeIndex = 0;
  let overlayEnabled = true;

  const controls = document.createElement("div");
  controls.className = "world-controls";

  const viewButtons = views.map((view, index) => {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "toggle" + (index === activeIndex ? " active" : "");
    button.textContent = view.label;
    button.addEventListener("click", () => {
      activeIndex = index;
      updateControls();
      renderWorldStage();
    });
    controls.appendChild(button);
    return button;
  });

  const overlayToggle = document.createElement("button");
  overlayToggle.type = "button";
  overlayToggle.className = "toggle active";
  overlayToggle.textContent = "Overlay";
  overlayToggle.addEventListener("click", () => {
    overlayEnabled = !overlayEnabled;
    updateControls();
    renderWorldStage();
  });
  controls.appendChild(overlayToggle);

  const legend = document.createElement("div");
  legend.className = "legend";
  legend.innerHTML = `
    <div class="legend-item"><span class="legend-swatch overlay-clickable"></span>clickable</div>
    <div class="legend-item"><span class="legend-swatch overlay-editable"></span>editable</div>
    <div class="legend-item"><span class="legend-swatch overlay-scrollable"></span>scrollable</div>
    <div class="legend-item"><span class="legend-swatch overlay-text"></span>has text</div>
  `;

  const stageWrapper = document.createElement("div");
  stageWrapper.className = "preview";

  world.appendChild(controls);
  world.appendChild(legend);
  world.appendChild(stageWrapper);

  function updateControls() {
    viewButtons.forEach((button, index) => {
      button.classList.toggle("active", index === activeIndex);
    });
    overlayToggle.classList.toggle("active", overlayEnabled);
  }

  async function renderWorldStage() {
    stageWrapper.innerHTML = "<pre>Loading world view...</pre>";
    const view = views[activeIndex];
    if (!view?.screenshot?.path) {
      stageWrapper.innerHTML = "<pre>Missing screenshot artifact.</pre>";
      return;
    }

    const imageFile = getFile(view.screenshot.path);
    if (!imageFile) {
      stageWrapper.innerHTML = `<pre>Missing file: ${escapeHtml(view.screenshot.path)}</pre>`;
      return;
    }

    const imgUrl = URL.createObjectURL(imageFile);
    stageWrapper.innerHTML = "";

    const stage = document.createElement("div");
    stage.className = "world-stage";

    const img = document.createElement("img");
    img.src = imgUrl;
    img.alt = "screenshot";

    const overlay = document.createElement("div");
    overlay.className = "world-overlay";
    overlay.style.display = overlayEnabled ? "block" : "none";

    stage.appendChild(img);
    stage.appendChild(overlay);
    stageWrapper.appendChild(stage);

    img.addEventListener("load", async () => {
      URL.revokeObjectURL(imgUrl);
      if (!overlayEnabled) return;
      const treeArtifact = view.sanitized || view.raw;
      if (!treeArtifact?.path) {
        overlay.innerHTML = "";
        return;
      }
      const treeFile = getFile(treeArtifact.path);
      if (!treeFile) {
        overlay.innerHTML = "";
        return;
      }
      const treeText = await treeFile.text();
      let nodes = [];
      try {
        const parsed = JSON.parse(treeText);
        if (Array.isArray(parsed)) nodes = parsed;
      } catch {
        nodes = [];
      }
      renderOverlayBoxes({ overlay, nodes, img });
    });
  }

  renderWorldStage();
  return world;
}

function buildWorldViews(step) {
  const views = [];
  const pre = step.world?.pre;
  const post = step.world?.post;

  if (pre && pre.screenshot) {
    views.push({
      label: "Pre",
      screenshot: pre.screenshot,
      sanitized: pre.sanitized_a11y_tree,
      raw: pre.raw_a11y_tree,
    });
  }

  if (post && post.screenshot) {
    views.push({
      label: "Post",
      screenshot: post.screenshot,
      sanitized: post.sanitized_a11y_tree,
      raw: post.raw_a11y_tree,
    });
  }

  return views;
}

function renderOverlayBoxes({ overlay, nodes, img }) {
  overlay.innerHTML = "";
  if (!nodes.length) return;

  const naturalWidth = img.naturalWidth || img.clientWidth || 1;
  const naturalHeight = img.naturalHeight || img.clientHeight || 1;
  const displayWidth = img.clientWidth || naturalWidth;
  const displayHeight = img.clientHeight || naturalHeight;

  let maxX = 0;
  let maxY = 0;
  nodes.forEach((node) => {
    const bounds = Array.isArray(node.bounds) ? node.bounds : [];
    if (bounds.length !== 4) return;
    const [, , right, bottom] = bounds.map((value) => Number(value));
    if (!Number.isFinite(right) || !Number.isFinite(bottom)) return;
    maxX = Math.max(maxX, right);
    maxY = Math.max(maxY, bottom);
  });

  if (maxX <= 0 || maxY <= 0) {
    maxX = naturalWidth;
    maxY = naturalHeight;
  }

  const scaleX = displayWidth / maxX;
  const scaleY = displayHeight / maxY;

  nodes.forEach((node) => {
    const bounds = Array.isArray(node.bounds) ? node.bounds : [];
    if (bounds.length !== 4) return;
    const [left, top, right, bottom] = bounds.map((value) => Number(value));
    if (![left, top, right, bottom].every(Number.isFinite)) return;
    const width = Math.max((right - left) * scaleX, 1);
    const height = Math.max((bottom - top) * scaleY, 1);

    const box = document.createElement("div");
    box.className = "overlay-box";
    if (node.clickable) box.classList.add("overlay-clickable");
    if (node.editable) box.classList.add("overlay-editable");
    if (node.scrollable) box.classList.add("overlay-scrollable");
    if (node.text || node.desc) box.classList.add("overlay-text");

    box.style.left = `${left * scaleX}px`;
    box.style.top = `${top * scaleY}px`;
    box.style.width = `${width}px`;
    box.style.height = `${height}px`;

    const label = [node.index, node.text || node.desc || ""].filter((value) => value !== "").join(" ");
    if (label) box.title = label;

    overlay.appendChild(box);
  });
}

function renderMindPanel(step, getFile, escapeHtml) {
  const mind = document.createElement("div");
  mind.className = "section";
  mind.innerHTML = `<div class="section-title">Mind</div>`;

  const tabs = document.createElement("div");
  tabs.className = "tab-bar";
  const content = document.createElement("div");
  content.className = "tab-content";

  const config = buildMindTabs(step);
  if (!config.length) {
    content.innerHTML = "<pre>No mind artifacts for this step.</pre>";
    mind.appendChild(content);
    return mind;
  }

  let activeId = config[0].id;

  config.forEach((item) => {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "tab" + (item.id === activeId ? " active" : "");
    button.textContent = item.label;
    button.addEventListener("click", async () => {
      activeId = item.id;
      updateTabs();
      await renderMindTab({ item, content, getFile, escapeHtml });
    });
    tabs.appendChild(button);
    item.button = button;
  });

  mind.appendChild(tabs);
  mind.appendChild(content);

  updateTabs();
  renderMindTab({ item: config[0], content, getFile, escapeHtml });
  return mind;

  function updateTabs() {
    config.forEach((item) => {
      item.button.classList.toggle("active", item.id === activeId);
    });
  }
}

function buildMindTabs(step) {
  const reqArtifacts = step.mind?.llm_request?.artifacts || [];
  const respArtifacts = step.mind?.llm_response?.artifacts || [];
  const toolCallArtifacts = (step.tool?.calls || []).flatMap((item) => item.artifacts || []);
  const toolResultArtifacts = (step.tool?.results || []).flatMap((item) => item.artifacts || []);

  return [
    { id: "prompt", label: "Prompt", kind: "prompt", reqArtifacts },
    { id: "input", label: "Input Items", kind: "input", reqArtifacts },
    { id: "response", label: "Response", kind: "response", step },
    { id: "tool_call", label: "Tool Calls", kind: "tool_call", respArtifacts, toolCallArtifacts },
    { id: "tool_result", label: "Tool Results", kind: "tool_result", toolResultArtifacts },
  ];
}

async function renderMindTab({ item, content, getFile, escapeHtml }) {
  content.innerHTML = "<pre>Loading...</pre>";

  if (item.kind === "prompt") {
    const fullPrompt = findArtifact(item.reqArtifacts, "llm_full_prompt");
    const systemPrompt = findArtifact(item.reqArtifacts, "llm_system_prompt");
    const userContext = findArtifact(item.reqArtifacts, "llm_user_context");
    const history = findArtifact(item.reqArtifacts, "llm_history");

    const blocks = [];
    if (fullPrompt) {
      blocks.push({ title: "full_prompt", artifact: fullPrompt });
    } else {
      if (systemPrompt) blocks.push({ title: "system_prompt", artifact: systemPrompt });
      if (userContext) blocks.push({ title: "user_context", artifact: userContext });
      if (history) blocks.push({ title: "history", artifact: history });
    }
    if (!blocks.length) {
      content.innerHTML = "<pre>No prompt artifacts available.</pre>";
      return;
    }
    content.innerHTML = "";
    for (const block of blocks) {
      const text = await readArtifactText(block.artifact, getFile);
      appendBlock(content, block.title, text);
    }
    return;
  }

  if (item.kind === "input") {
    const inputItems = findArtifact(item.reqArtifacts, "llm_input_items");
    if (!inputItems) {
      content.innerHTML = "<pre>No input item artifacts available.</pre>";
      return;
    }
    const text = await readArtifactText(inputItems, getFile);
    content.innerHTML = `<pre>${escapeHtml(tryPretty(text))}</pre>`;
    return;
  }

  if (item.kind === "response") {
    const data = item.step?.mind?.llm_response?.data;
    const text = tryPretty(data != null ? JSON.stringify(data) : "{}");
    content.innerHTML = `<pre>${escapeHtml(text)}</pre>`;
    return;
  }

  if (item.kind === "tool_call") {
    const toolCalls = [];
    const llmToolCalls = findArtifact(item.respArtifacts, "llm_tool_calls");
    if (llmToolCalls) {
      toolCalls.push({ title: "llm_tool_calls", artifact: llmToolCalls });
    }
    item.toolCallArtifacts
      .filter((artifact) => artifact.kind === "tool_call_args")
      .forEach((artifact, index) => {
        toolCalls.push({ title: `tool_call_args_${index + 1}`, artifact });
      });

    if (!toolCalls.length) {
      content.innerHTML = "<pre>No tool call artifacts available.</pre>";
      return;
    }
    content.innerHTML = "";
    for (const block of toolCalls) {
      const text = await readArtifactText(block.artifact, getFile);
      appendBlock(content, block.title, tryPretty(text));
    }
    return;
  }

  if (item.kind === "tool_result") {
    const toolResults = item.toolResultArtifacts || [];
    if (!toolResults.length) {
      content.innerHTML = "<pre>No tool result artifacts available.</pre>";
      return;
    }
    content.innerHTML = "";
    for (const [index, artifact] of toolResults.entries()) {
      const text = await readArtifactText(artifact, getFile);
      appendBlock(content, `${artifact.kind || "tool_result"}_${index + 1}`, tryPretty(text));
    }
    return;
  }

  content.innerHTML = "<pre>No data.</pre>";
}

function renderToolPanel(step, escapeHtml) {
  const tool = document.createElement("div");
  tool.className = "section";
  tool.innerHTML = `
    <div class="section-title">Tool</div>
    <div class="preview"><pre>${escapeHtml(pretty(step.tool))}</pre></div>
  `;
  return tool;
}

function renderLinksPanel(step, escapeHtml, onJumpToStepId, onJumpToSessionId) {
  const links = document.createElement("div");
  links.className = "section";
  links.innerHTML = `<div class="section-title">Links</div>`;

  const parent = step.links?.parent_step_id;
  const parentRow = document.createElement("div");
  parentRow.className = "code";
  parentRow.textContent = `parent_step_id: ${parent || "-"}`;
  links.appendChild(parentRow);

  if (parent && onJumpToStepId) {
    const parentButtons = document.createElement("div");
    parentButtons.className = "link-row";
    const button = document.createElement("button");
    button.type = "button";
    button.className = "btn";
    button.textContent = "Jump to parent";
    button.addEventListener("click", () => onJumpToStepId(parent));
    parentButtons.appendChild(button);
    links.appendChild(parentButtons);
  }

  const childIds = step.links?.child_session_ids || [];
  const childRow = document.createElement("div");
  childRow.className = "code";
  childRow.textContent = `child_session_ids: ${childIds.join(", ") || "-"}`;
  links.appendChild(childRow);

  if (childIds.length && onJumpToSessionId) {
    const childButtons = document.createElement("div");
    childButtons.className = "link-row";
    childIds.forEach((sessionId) => {
      const button = document.createElement("button");
      button.type = "button";
      button.className = "btn";
      button.textContent = `Jump ${sessionId}`;
      button.addEventListener("click", () => onJumpToSessionId(sessionId));
      childButtons.appendChild(button);
    });
    links.appendChild(childButtons);
  }

  return links;
}

function findArtifact(artifacts, kind) {
  return (artifacts || []).find((artifact) => artifact.kind === kind) || null;
}

async function readArtifactText(artifact, getFile) {
  if (!artifact?.path) return "Artifact missing path.";
  const file = getFile(artifact.path);
  if (!file) return `Missing file: ${artifact.path}`;
  return file.text();
}

function appendBlock(container, title, text) {
  const block = document.createElement("div");
  block.style.marginBottom = "12px";
  const titleEl = document.createElement("div");
  titleEl.className = "code";
  titleEl.textContent = title;
  const pre = document.createElement("pre");
  pre.textContent = text;
  block.appendChild(titleEl);
  block.appendChild(pre);
  container.appendChild(block);
}

function tryPretty(text) {
  const trimmed = String(text || "").trim();
  if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
    try {
      return JSON.stringify(JSON.parse(trimmed), null, 2);
    } catch {
      return text;
    }
  }
  return text;
}

function pretty(value) {
  try {
    return JSON.stringify(value ?? {}, null, 2);
  } catch {
    return String(value);
  }
}
