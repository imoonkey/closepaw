// Page-domain helpers for browser_script. Inline the functions you need.
// Assumes only the browser_script prelude function: await cdp(method, params, options).

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

function jsSnippet(expression, limit = 160) {
  const snippet = String(expression).trim().replace(/\n/g, "\\n");
  return snippet.length > limit ? snippet.slice(0, limit - 3) + "..." : snippet;
}

function jsExceptionDescription(result, details) {
  let description = result && result.description;
  const exception = details && details.exception;
  if (!description && exception) {
    description = exception.description;
    if (description == null && Object.prototype.hasOwnProperty.call(exception, "value")) {
      description = String(exception.value);
    }
    if (description == null) {
      description = exception.className;
    }
  }
  if (!description && details) {
    description = details.text;
  }
  return description || "JavaScript evaluation failed";
}

function decodeUnserializableValue(value) {
  if (value === "NaN") return "NaN";
  if (value === "Infinity") return "Infinity";
  if (value === "-Infinity") return "-Infinity";
  if (value === "-0") return "-0";
  return value;
}

function runtimeValue(response, expression) {
  const result = response.result || {};
  const details = response.exceptionDetails;
  if (details || result.subtype === "error") {
    const description = jsExceptionDescription(result, details);
    const hasLocation = details && details.lineNumber != null && details.columnNumber != null;
    const location = hasLocation ? " at line " + details.lineNumber + ", column " + details.columnNumber : "";
    throw new Error(
      "JavaScript evaluation failed" + location + ": " + description + "; expression: " + jsSnippet(expression)
    );
  }
  if (Object.prototype.hasOwnProperty.call(result, "value")) {
    return result.value;
  }
  if (Object.prototype.hasOwnProperty.call(result, "unserializableValue")) {
    return decodeUnserializableValue(result.unserializableValue);
  }
  return null;
}

function hasTopLevelReturn(expression) {
  let state = "code";
  let quote = "";
  for (let i = 0; i < expression.length;) {
    const ch = expression[i];
    const next = expression[i + 1] || "";
    if (state === "code") {
      if (ch === "'" || ch === '"' || ch === "`") {
        state = "string";
        quote = ch;
        i += 1;
        continue;
      }
      if (ch === "/" && next === "/") {
        state = "lineComment";
        i += 2;
        continue;
      }
      if (ch === "/" && next === "*") {
        state = "blockComment";
        i += 2;
        continue;
      }
      if (expression.startsWith("return", i)) {
        const before = i > 0 ? expression[i - 1] : "";
        const after = i + 6 < expression.length ? expression[i + 6] : "";
        if (!/[A-Za-z0-9_]/.test(before) && !/[A-Za-z0-9_]/.test(after)) {
          return true;
        }
      }
      i += 1;
      continue;
    }
    if (state === "lineComment") {
      if (ch === "\n") state = "code";
      i += 1;
      continue;
    }
    if (state === "blockComment") {
      if (ch === "*" && next === "/") {
        state = "code";
        i += 2;
        continue;
      }
      i += 1;
      continue;
    }
    if (state === "string") {
      if (ch === "\\") {
        i += 2;
        continue;
      }
      if (ch === quote) {
        state = "code";
        quote = "";
      }
      i += 1;
    }
  }
  return false;
}

async function pageJs(expression, options = {}) {
  let source = String(expression);
  if (hasTopLevelReturn(source) && !source.trim().startsWith("(")) {
    source = "(function(){" + source + "})()";
  }
  const response = await cdp("Runtime.evaluate", {
    expression: source,
    returnByValue: true,
    awaitPromise: true
  }, options);
  return runtimeValue(response, source);
}

async function pageInfo() {
  return await pageJs(`
    return {
      url: location.href,
      title: document.title,
      viewportWidth: innerWidth,
      viewportHeight: innerHeight,
      scrollX: scrollX,
      scrollY: scrollY,
      pageWidth: document.documentElement.scrollWidth,
      pageHeight: document.documentElement.scrollHeight,
      devicePixelRatio: devicePixelRatio || 1,
      readyState: document.readyState
    };
  `);
}

async function waitForLoad({ timeoutMs = 15000, pollMs = 300 } = {}) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (await pageJs("document.readyState") === "complete") {
      return true;
    }
    await sleep(pollMs);
  }
  return false;
}

async function navigate(url, { wait = true, timeoutMs = 15000, pollMs = 300 } = {}) {
  const result = await cdp("Page.navigate", { url });
  if (wait) {
    const loaded = await waitForLoad({ timeoutMs, pollMs });
    if (!loaded) {
      throw new Error("navigate: load timeout after " + timeoutMs + "ms for " + url);
    }
  }
  return result;
}

async function screenshot({ full = false, maxDim = 1800, format = "png", quality } = {}) {
  const info = await pageInfo();
  const widthCss = full ? info.pageWidth : info.viewportWidth;
  const heightCss = full ? info.pageHeight : info.viewportHeight;
  const devicePixelRatio = info.devicePixelRatio || 1;
  const maxPixels = Math.max(widthCss * devicePixelRatio, heightCss * devicePixelRatio);
  const scale = maxDim && maxPixels > maxDim ? maxDim / maxPixels : 1;
  const params = {
    format,
    captureBeyondViewport: full,
    fromSurface: true
  };
  if (full || scale < 1) {
    params.clip = {
      x: full ? 0 : info.scrollX,
      y: full ? 0 : info.scrollY,
      width: widthCss,
      height: heightCss,
      scale
    };
  }
  if (format === "jpeg" && quality != null) {
    params.quality = quality;
  }
  const result = await cdp("Page.captureScreenshot", params);
  const fileName = `screenshot-${Date.now()}.${format}`;
  const path = storeArtifact("browser-script", fileName, result.data, `image/${format}`);
  return {
    path,
    format,
    widthCss,
    heightCss,
    devicePixelRatio,
    scale,
    estimatedMaxPixels: Math.ceil(maxPixels * scale),
    estimatedBytes: Math.ceil(result.data.length * 3 / 4)
  };
}
