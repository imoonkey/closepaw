// Input helpers for browser_script. Coordinates are CSS pixels, not screenshot pixels.
// Assumes only the browser_script prelude function: await cdp(method, params, options).

const KEY_DEFINITIONS = {
  Enter: [13, "Enter", "\r"],
  Tab: [9, "Tab", "\t"],
  Backspace: [8, "Backspace", ""],
  Escape: [27, "Escape", ""],
  Delete: [46, "Delete", ""],
  " ": [32, "Space", " "],
  ArrowLeft: [37, "ArrowLeft", ""],
  ArrowUp: [38, "ArrowUp", ""],
  ArrowRight: [39, "ArrowRight", ""],
  ArrowDown: [40, "ArrowDown", ""],
  Home: [36, "Home", ""],
  End: [35, "End", ""],
  PageUp: [33, "PageUp", ""],
  PageDown: [34, "PageDown", ""]
};

async function clickAt(x, y, { button = "left", clickCount = 1 } = {}) {
  const common = { x, y, button, clickCount };
  await cdp("Input.dispatchMouseEvent", { type: "mousePressed", ...common });
  await cdp("Input.dispatchMouseEvent", { type: "mouseReleased", ...common });
}

async function typeText(text) {
  await cdp("Input.insertText", { text: String(text) });
}

async function pressKey(key, { modifiers = 0 } = {}) {
  const fallback = key.length === 1 ? [key.charCodeAt(0), key, key] : [0, key, ""];
  const [windowsVirtualKeyCode, code, text] = KEY_DEFINITIONS[key] || fallback;
  const base = {
    key,
    code,
    modifiers,
    windowsVirtualKeyCode,
    nativeVirtualKeyCode: windowsVirtualKeyCode
  };
  await cdp("Input.dispatchKeyEvent", {
    type: "keyDown",
    ...base,
    ...(text ? { text } : {})
  });
  if (text && text.length === 1) {
    await cdp("Input.dispatchKeyEvent", {
      type: "char",
      text,
      unmodifiedText: text,
      modifiers,
      windowsVirtualKeyCode,
      nativeVirtualKeyCode: windowsVirtualKeyCode
    });
  }
  await cdp("Input.dispatchKeyEvent", { type: "keyUp", ...base });
}

async function scrollAt(x, y, { deltaY = -300, deltaX = 0 } = {}) {
  await cdp("Input.dispatchMouseEvent", {
    type: "mouseWheel",
    x,
    y,
    deltaX,
    deltaY
  });
}
