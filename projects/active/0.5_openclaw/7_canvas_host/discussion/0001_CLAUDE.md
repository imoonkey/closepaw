Aligned draft created in `final/design.md`.

Changes made:
- merged both designs around one shared `CanvasSpec` schema
- pulled display-only summary/result cards into Step 1
- kept the capsule intentionally small: inline only `TextInput` and `ActionRequired`; redirect dense choice/confirmation/summary interactions to the app
- chose one canonical rich-interaction tool, `show_canvas`, instead of keeping two blocking channels
- kept process-death recovery for in-flight requests out of scope

Open question resolved:
- tool split vs. unification: one tool wins, but it should reuse the existing suspension machinery internally rather than invent a second runtime path

Vote: CHANGES
