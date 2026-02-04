export function renderDetailPanel({ container, step, getFile, escapeHtml }) {
  container.innerHTML = "";

  const summary = document.createElement("div");
  summary.className = "section";
  summary.innerHTML = `
    <div class="section-title">Step Summary</div>
    <div class="code">step_id: ${escapeHtml(step.step_id || "")}</div>
    <div class="code">session: ${escapeHtml(step.session_id || "")}</div>
    <div class="code">turn: ${escapeHtml(String(step.turn_number ?? ""))}</div>
  `;
  container.appendChild(summary);

  const world = document.createElement("div");
  world.className = "section";
  world.innerHTML = `<div class="section-title">World</div>`;
  const worldChips = document.createElement("div");
  worldChips.className = "chips";
  const worldPreview = document.createElement("div");
  worldPreview.className = "preview";
  worldPreview.innerHTML = "<pre>Pick an artifact.</pre>";

  const worldArtifacts = collectWorldArtifacts(step);
  if (!worldArtifacts.length) {
    worldPreview.innerHTML = "<pre>No world artifacts for this step.</pre>";
  } else {
    worldArtifacts.forEach((artifact) => {
      worldChips.appendChild(makeArtifactChip({ artifact, previewContainer: worldPreview, getFile, escapeHtml }));
    });
  }
  world.appendChild(worldChips);
  world.appendChild(worldPreview);
  container.appendChild(world);

  const mind = document.createElement("div");
  mind.className = "section";
  mind.innerHTML = `<div class="section-title">Mind</div>`;

  const mindMeta = document.createElement("div");
  mindMeta.className = "preview";
  mindMeta.innerHTML = `<pre>${escapeHtml(pretty(step.mind))}</pre>`;

  const mindChips = document.createElement("div");
  mindChips.className = "chips";
  const mindPreview = document.createElement("div");
  mindPreview.className = "preview";
  mindPreview.innerHTML = "<pre>Pick a prompt/response artifact.</pre>";

  const mindArtifacts = collectMindArtifacts(step);
  if (!mindArtifacts.length) {
    mindPreview.innerHTML = "<pre>No mind artifacts for this step.</pre>";
  } else {
    mindArtifacts.forEach((artifact) => {
      mindChips.appendChild(makeArtifactChip({ artifact, previewContainer: mindPreview, getFile, escapeHtml }));
    });
  }

  mind.appendChild(mindMeta);
  mind.appendChild(mindChips);
  mind.appendChild(mindPreview);
  container.appendChild(mind);

  const tool = document.createElement("div");
  tool.className = "section";
  tool.innerHTML = `
    <div class="section-title">Tool</div>
    <div class="preview"><pre>${escapeHtml(pretty(step.tool))}</pre></div>
  `;
  container.appendChild(tool);

  const links = document.createElement("div");
  links.className = "section";
  links.innerHTML = `
    <div class="section-title">Links</div>
    <div class="code">parent_step_id: ${escapeHtml(step.links?.parent_step_id || "-")}</div>
    <div class="code">child_session_ids: ${escapeHtml((step.links?.child_session_ids || []).join(", ") || "-")}</div>
  `;
  container.appendChild(links);
}

function collectWorldArtifacts(step) {
  const artifacts = [];
  const pre = step.world?.pre;
  const post = step.world?.post;

  if (pre?.screenshot) artifacts.push({ label: "pre screenshot", ...pre.screenshot });
  if (pre?.raw_a11y_tree) artifacts.push({ label: "pre raw a11y", ...pre.raw_a11y_tree });
  if (pre?.sanitized_a11y_tree) artifacts.push({ label: "pre sanitized", ...pre.sanitized_a11y_tree });

  if (post?.screenshot) artifacts.push({ label: "post screenshot", ...post.screenshot });
  if (post?.raw_a11y_tree) artifacts.push({ label: "post raw a11y", ...post.raw_a11y_tree });
  if (post?.sanitized_a11y_tree) artifacts.push({ label: "post sanitized", ...post.sanitized_a11y_tree });

  return artifacts;
}

function collectMindArtifacts(step) {
  const artifacts = [];
  const requestArtifacts = step.mind?.llm_request?.artifacts || [];
  const responseArtifacts = step.mind?.llm_response?.artifacts || [];
  const toolCallArtifacts = (step.tool?.calls || []).flatMap((item) => item.artifacts || []);
  const toolResultArtifacts = (step.tool?.results || []).flatMap((item) => item.artifacts || []);

  requestArtifacts.forEach((artifact) => artifacts.push({ label: `req:${artifact.kind || "artifact"}`, ...artifact }));
  responseArtifacts.forEach((artifact) => artifacts.push({ label: `resp:${artifact.kind || "artifact"}`, ...artifact }));
  toolCallArtifacts.forEach((artifact) => artifacts.push({ label: `tool_call:${artifact.kind || "artifact"}`, ...artifact }));
  toolResultArtifacts.forEach((artifact) => artifacts.push({ label: `tool_result:${artifact.kind || "artifact"}`, ...artifact }));
  return artifacts;
}

function makeArtifactChip({ artifact, previewContainer, getFile, escapeHtml }) {
  const chip = document.createElement("button");
  chip.type = "button";
  chip.className = "chip";
  chip.textContent = artifact.label || `${artifact.kind || "artifact"}`;
  chip.title = artifact.path || "";
  chip.addEventListener("click", () => previewArtifact({ artifact, previewContainer, getFile, escapeHtml }));
  return chip;
}

async function previewArtifact({ artifact, previewContainer, getFile, escapeHtml }) {
  if (!artifact.path) {
    previewContainer.innerHTML = "<pre>Artifact has no path.</pre>";
    return;
  }

  const file = getFile(artifact.path);
  if (!file) {
    previewContainer.innerHTML = `<pre>Missing file: ${escapeHtml(artifact.path)}</pre>`;
    return;
  }

  const mime = artifact.mimeType || file.type || "";
  if (mime.startsWith("image/")) {
    const url = URL.createObjectURL(file);
    previewContainer.innerHTML = "";
    const img = document.createElement("img");
    img.src = url;
    previewContainer.appendChild(img);
    return;
  }

  const text = await file.text();
  const prettyText = tryPretty(text);
  previewContainer.innerHTML = `<pre>${escapeHtml(prettyText)}</pre>`;
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
