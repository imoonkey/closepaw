# DroidRun tools deep dive (code + docs)

Sources used
- .reference/mobile_agent/droidrun/docs/v3/concepts/android-tools.mdx
- .reference/mobile_agent/droidrun/docs/sdk/adb-tools.mdx
- .reference/mobile_agent/droidrun/droidrun/tools/android/adb.py
- .reference/mobile_agent/droidrun/droidrun/tools/formatters/indexed_formatter.py
- .reference/mobile_agent/droidrun/droidrun/tools/android/portal_client.py

## Tool list (Android)
Core action tools (AdbTools)
- tap_by_index(index)
- tap_by_coordinates(x, y)
- tap_on_index(index) (overlap-aware tap)
- swipe(start_x, start_y, end_x, end_y, duration_ms)
- input_text(text, index=-1, clear=False)
- press_key(keycode), back()
- start_app(package, activity="")
- list_packages(include_system_apps=False)
- install_app(apk_path, reinstall=False, grant_permissions=True)
- get_state()
- take_screenshot()
- remember(info), get_memory()
- complete(success, reason)

Prompt-facing tool catalog in docs mirrors the above and emphasizes get_state -> tap_by_index caching.

## Targeting + parameters (nitty-gritty)
Index-based targeting (primary)
- get_state() builds and caches a11y_tree; indices assigned in IndexedFormatter._flatten_with_index() (1-based, pre-order traversal).
- a11y_tree elements include index, resourceId, className (short), text, bounds string, and children.
- tap_by_index() looks up the element in cached a11y_tree (recursive search in children), reads bounds string ("left,top,right,bottom"), computes center, and clicks that center.
- If no cached elements: hard error "Call get_state first".
- If index missing: error includes list of available indices (first 20).
- If bounds missing: error includes element text/type/class to debug.

Coordinate targeting (fallback)
- tap_by_coordinates(x,y) directly clicks absolute pixels.

Overlap-aware targeting (fallback)
- tap_on_index() detects overlapping elements with higher indices and finds a clear point inside target bounds (rects_overlap + find_clear_point) to avoid tapping occluders.

Text input targeting
- input_text(text, index=-1, clear=False): if index provided, it first tap_by_index(index); otherwise uses currently focused element.
- Uses PortalClient input_text (not ADB key events), so clear=True is handled at input layer.

Bounds normalization
- IndexedFormatter supports normalized bounds (0-1000) if use_normalized=true and screen size is known.
- get_state() stores screen_width/height and can choose normalized bounds in formatter.

## Execution details (retry, caching, filtering)
get_state() retry
- AdbTools.get_state() retries up to 3 times with 0.5s delay and raises after final failure.
- It validates required keys (a11y_tree, phone_state, device_context) and fails fast if missing.

Tree filtering + formatting
- raw_tree_cache is filtered via tree_filter (concise/detailed), then formatted to a11y_tree with indices.
- formatted_text includes phone state + UI list; focused_text extracted from phone_state.

Portal connection fallback
- portal_client.get_state() tries TCP, falls back to content provider on failure.

Tool execution telemetry
- tap_by_index() emits TapActionEvent with element text/class/bounds; input_text emits InputTextActionEvent; used for trajectory logging.

## Prompt / policy details that matter
- Prompts require call get_state before tap_by_index and to use remember() as explicit memory.
- Index-based selection is treated as authoritative; coordinates are for fallback (or special tools).

## What this suggests for our tools
- A hard dependency on get_state cache is enforced; errors are explicit and explain available indices.
- Index->bounds->center is fully deterministic and centralized.
- There is an explicit overlap-aware tap when bounds overlap.
- get_state has built-in retry and validation.
