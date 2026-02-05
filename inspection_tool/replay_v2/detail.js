export function renderDetailPanel({ container, step, getFile, escapeHtml, onJumpToStepId, onJumpToSessionId }) {
  container.innerHTML = "";

  container.appendChild(renderSummary(step, escapeHtml));

  const grid = document.createElement("div");
  grid.className = "detail-grid";

  const worldColumn = document.createElement("div");
  worldColumn.className = "detail-column";
  worldColumn.appendChild(renderWorldPanel(step, getFile, escapeHtml));

  const mindColumn = document.createElement("div");
  mindColumn.className = "detail-column";
  mindColumn.appendChild(renderMindPanel(step, getFile, escapeHtml));
  mindColumn.appendChild(renderLinksPanel(step, escapeHtml, onJumpToStepId, onJumpToSessionId));

  grid.appendChild(worldColumn);
  grid.appendChild(mindColumn);
  container.appendChild(grid);
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
  stageWrapper.className = "world-preview";

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
  const toolCalls = step.tool?.calls || [];
  const toolResults = step.tool?.results || [];

  return [
    { id: "llm_request", label: "LLM Request", kind: "llm_request", reqArtifacts },
    { id: "llm_response", label: "LLM Response", kind: "llm_response", respArtifacts, step },
    { id: "tool_execution", label: "Tool Execution", kind: "tool_execution", toolCalls, toolResults },
  ];
}

async function renderMindTab({ item, content, getFile, escapeHtml }) {
  content.innerHTML = "<pre>Loading...</pre>";

  if (item.kind === "llm_request") {
    const systemPrompt = findArtifact(item.reqArtifacts, "llm_system_prompt");
    const userContext = findArtifact(item.reqArtifacts, "llm_user_context");
    const inputItems = findArtifact(item.reqArtifacts, "llm_input_items");
    const history = findArtifact(item.reqArtifacts, "llm_history");

    const blocks = [];
    if (systemPrompt) blocks.push({ title: "=== SYSTEM PROMPT ===", artifact: systemPrompt });
    if (userContext) blocks.push({ title: "=== USER CONTEXT ===", artifact: userContext });
    if (inputItems) blocks.push({ title: "=== INPUT ITEMS ===", artifact: inputItems, format: true });
    if (history) blocks.push({ title: "=== CHAT HISTORY ===", artifact: history, format: true, collapsible: true });

    if (!blocks.length) {
      content.innerHTML = "<pre>No LLM request artifacts available.</pre>";
      return;
    }
    content.innerHTML = "";
    for (const block of blocks) {
      let text = await readArtifactText(block.artifact, getFile);
      if (block.format) text = tryPretty(text);
      appendSectionBlock(content, block.title, text, escapeHtml, block.collapsible);
    }
    return;
  }

  if (item.kind === "llm_response") {
    const responseText = findArtifact(item.respArtifacts, "llm_response_text");
    const toolCalls = findArtifact(item.respArtifacts, "llm_tool_calls");

    const blocks = [];
    if (responseText) blocks.push({ title: "=== TEXT OUTPUT ===", artifact: responseText });
    if (toolCalls) blocks.push({ title: "=== TOOL CALLS ===", artifact: toolCalls, format: true });

    if (!blocks.length) {
      // Fallback to data object if no artifacts
      const data = item.step?.mind?.llm_response?.data;
      if (data) {
        content.innerHTML = `<pre>${escapeHtml(tryPretty(JSON.stringify(data)))}</pre>`;
        return;
      }
      content.innerHTML = "<pre>No LLM response artifacts available.</pre>";
      return;
    }
    content.innerHTML = "";
    for (const block of blocks) {
      let text = await readArtifactText(block.artifact, getFile);
      if (block.format) text = tryPretty(text);
      appendSectionBlock(content, block.title, text, escapeHtml);
    }
    return;
  }

  if (item.kind === "tool_execution") {
    const toolCalls = item.toolCalls || [];
    const toolResults = item.toolResults || [];

    if (!toolCalls.length && !toolResults.length) {
      content.innerHTML = "<pre>No tool executions in this turn.</pre>";
      return;
    }

    content.innerHTML = "";

    // Build a map of call_id -> result for matching
    const resultsByCallId = new Map();
    for (const result of toolResults) {
      const callId = result.data?.id;
      if (callId) resultsByCallId.set(callId, result);
    }

    // Render each tool call with its matched result
    for (const [index, call] of toolCalls.entries()) {
      const callId = call.data?.id;
      const toolName = call.data?.name || "unknown";
      const result = callId ? resultsByCallId.get(callId) : toolResults[index];
      const success = result?.data?.success;

      const card = document.createElement("div");
      card.className = "tool-card";

      // Build status indicator
      let statusClass = "tool-status-pending";
      let statusText = "pending";
      if (result) {
        statusClass = success ? "tool-status-success" : "tool-status-error";
        statusText = success ? "✓ success" : "✗ failed";
      }

      // Header
      const header = document.createElement("div");
      header.className = "tool-card-header";
      header.innerHTML = `
        <span class="tool-card-name">[${index + 1}] ${escapeHtml(toolName)}</span>
        <span class="tool-status ${statusClass}">${statusText}</span>
      `;
      card.appendChild(header);

      // Input section
      const inputArtifact = findArtifact(call.artifacts || [], "tool_call_args");
      if (inputArtifact) {
        const inputText = await readArtifactText(inputArtifact, getFile);
        const inputSection = document.createElement("div");
        inputSection.className = "tool-section";
        inputSection.innerHTML = `
          <div class="tool-section-title">Input:</div>
          <pre>${escapeHtml(tryPretty(inputText))}</pre>
        `;
        card.appendChild(inputSection);
      }

      // Output section
      if (result) {
        const outputArtifact = findArtifact(result.artifacts || [], "tool_result");
        if (outputArtifact) {
          const outputText = await readArtifactText(outputArtifact, getFile);
          const outputSection = document.createElement("div");
          outputSection.className = "tool-section";
          outputSection.innerHTML = `
            <div class="tool-section-title">Output:</div>
            <pre>${escapeHtml(outputText)}</pre>
          `;
          card.appendChild(outputSection);
        }

        // Observation section (screen state or text)
        const screenObs = findArtifact(result.artifacts || [], "tool_observation_screen");
        const textObs = findArtifact(result.artifacts || [], "tool_observation_text");
        const obsArtifact = screenObs || textObs;
        if (obsArtifact) {
          const obsText = await readArtifactText(obsArtifact, getFile);
          const obsSection = document.createElement("div");
          obsSection.className = "tool-section tool-observation";
          obsSection.innerHTML = `
            <div class="tool-section-title">Observation:</div>
            <pre>${escapeHtml(tryPretty(obsText))}</pre>
          `;
          card.appendChild(obsSection);
        }
      }

      content.appendChild(card);
    }
    return;
  }

  content.innerHTML = "<pre>No data.</pre>";
}

function appendSectionBlock(container, title, text, escapeHtml, collapsible = false) {
  const block = document.createElement("div");
  block.className = "llm-section-block";

  const titleEl = document.createElement("div");
  titleEl.className = "llm-section-title";
  titleEl.textContent = title;

  const pre = document.createElement("pre");
  pre.textContent = text;

  if (collapsible) {
    const details = document.createElement("details");
    const summary = document.createElement("summary");
    summary.textContent = title;
    details.appendChild(summary);
    details.appendChild(pre);
    block.appendChild(details);
  } else {
    block.appendChild(titleEl);
    block.appendChild(pre);
  }

  container.appendChild(block);
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
