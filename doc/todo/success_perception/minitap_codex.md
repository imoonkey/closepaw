# Minitap (mobile-use) - a11y/UI hierarchy handling notes

## Sources (local)
- `.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/clients/ui_automator_client.py`
- `.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/utils/ui_hierarchy.py`
- `.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/tools/mobile/tap.py`
- `.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/tools/utils.py`

## 1) How it captures + represents the hierarchy
- Capture: UIAutomator2 `dump_hierarchy()` provides an XML hierarchy.
- Parse: `_parse_hierarchy_xml_to_elements()` flattens XML into a **flat list of dicts**.
- Key attributes preserved in the element dict:
  - `resource-id`, `text`, `content-desc` (+ `accessibilityText` alias), `bounds`, `class`, `package`
  - common flags: `clickable`, `enabled`, `focusable`, `focused`, `scrollable`, `long-clickable`, etc.

This is “wide” (many attributes), not just minimal fields.

## 2) Selector strategy (relevant to `resource_id`)
- Tool layer uses a `Target` object (resource_id, bounds, text + optional indices).
- They have a defensive mismatch pattern:
  - When both `resource_id` and `text` exist, they may **ignore the ID** if it resolves to an element whose text does not match (prevents misleading IDs).
  - See `focus_element_if_needed()` in `.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/tools/utils.py`.

## 3) Fallback order (important nuance)
Their `tap` tool tries:
1) bounds/coordinates
2) resource_id (+ index)
3) text (+ index)

This matches a “visual first” bias (if bounds exist), but they still preserve `resource_id` as a key locator and support `resource_id_index` / `text_index` semantics.

## 4) Practical takeaways for us
- Minitap keeps **resource-id + many flags**, even if text/desc is empty. That helps when IDs exist but labels don’t.
- They explicitly model `resource_id_index` / `text_index`, which makes “nth match” stable and tool-friendly.
- They actively defend against “ID points to the wrong element” when text is also provided.

