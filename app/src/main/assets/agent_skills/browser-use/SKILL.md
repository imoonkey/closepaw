---
name: browser-use
description: Browser automation guidance for browser_script and reusable CDP snippets.
allowed-tools:
  - browser_script
  - shell
metadata:
  bundled: "true"
---

# Browser Use

Use `browser_script` when the task needs Chrome DevTools Protocol control over the user's real Android Chrome profile: navigation, reading page state, screenshots, input, or tab management.

`browser_script` runs JavaScript in ClosePaw's hidden script host. The only built-in browser primitive is:

```js
await cdp(method, params = {}, options = {})
```

Prefer raw CDP for simple one-off actions. Read a snippet bundle only when you need repeated page, tab, or input helpers:

- Page helpers: `{{SKILL_DIR}}/scripts/page.js`
- Tab helpers: `{{SKILL_DIR}}/scripts/tabs.js`
- Input helpers: `{{SKILL_DIR}}/scripts/input.js`

Use shell `cat` to read the bundle you need, then copy or adapt the relevant functions into one `browser_script` call:

```text
cat {{SKILL_DIR}}/scripts/page.js
cat {{SKILL_DIR}}/scripts/tabs.js
cat {{SKILL_DIR}}/scripts/input.js
```

## Raw CDP Examples

Read the current title:

```js
const response = await cdp("Runtime.evaluate", {
  expression: "document.title",
  returnByValue: true
});
return response.result.value;
```

Navigate and wait for the load event with raw polling:

```js
await cdp("Page.navigate", { url: "https://example.com" });
for (let i = 0; i < 50; i++) {
  const state = await cdp("Runtime.evaluate", {
    expression: "document.readyState",
    returnByValue: true
  });
  if (state.result.value === "complete") break;
  await new Promise(resolve => setTimeout(resolve, 300));
}
return { loaded: true };
```

## Snippet Examples

After reading `page.js`, you can inline the helpers and write:

```js
await navigate("https://example.com");
return await pageInfo();
```

After reading `tabs.js`, create a blank tab first, then navigate:

```js
const targetId = await newTab("https://example.com");
return { targetId, info: await currentTab() };
```

After reading `input.js`, use CSS pixel coordinates:

```js
await clickAt(120, 340);
await typeText("hello");
return await cdp("Runtime.evaluate", {
  expression: "document.activeElement && document.activeElement.value",
  returnByValue: true
});
```

## Operating Rules

- Keep scripts small and task-specific. Inline only the helpers you use.
- `Target.*` and `Browser.*` are browser-level CDP methods. Most page domains route to the active page session unless you pass `options.targetId` or `options.sessionId`.
- Screenshots are device pixels. Input coordinates are CSS pixels. Convert by `window.devicePixelRatio` if you measure coordinates from a screenshot.
- After visible actions, verify by rereading page state or taking another screenshot.
- Android Chrome CDP support can differ from desktop Chrome. If a method is unsupported, use another raw CDP route or visible Android automation.

## Screenshots Return Paths, Not Bytes

`screenshot()` from `page.js` writes the image bytes to a trace artifact and returns metadata plus `path` — the absolute on-device path of the saved file (or `null` when tracing is disabled). It does NOT return the raw base64 in the result. If you need to verify visual state, reference the `path` with shell tools (`adb shell run-as ai.closepaw cat <path>` from the host, `cat`/`stat` on device). Do NOT base64-decode it back and paste it into your tool output — that is exactly the cost this helper exists to avoid (a single screenshot can be 100–500 KB of base64).

If you need the raw base64 for a specific reason (e.g. uploading), call `Page.captureScreenshot` directly via `cdp(...)` instead of `screenshot()`.
