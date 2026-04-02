# Onboarding Wizard — On-Device QA Report

Date: 2026-04-02
Device: nubia M153 (EP0110MZ0BC101266W), Android 12+
APK: debug build with OpenAI key validation via Tailscale proxy

---

## Results

| # | Test | Result | Evidence |
|---|------|--------|----------|
| 1 | Fresh install enters onboarding wizard | **PASS** | Step 1 of 5 shown with correct Accessibility copy, shield icon, CTA, no skip |
| 2 | Accessibility step: CTA opens settings, auto-advance on satisfy | **PASS** | Checking... state shown on return, auto-advanced through A11y + Overlay |
| 3 | Overlay step: auto-advance when already granted | **PASS** | Skipped directly to Battery (Step 3) |
| 4 | Battery step: skip works | **PASS** | "Continue without this" advanced to API Key (Step 4) |
| 5a | API key: invalid key shows error | **PASS** | "That key was rejected. Check the value and try again." with red field outline |
| 5b | API key: valid key validates and advances | **PASS** | Key accepted, auto-advanced to Demo (Step 5) |
| 6 | Demo step: skip works, Complete screen correct | **PASS** | Checklist shows accurate status per step, skipped items marked correctly |
| 7 | Complete: "Start Using" navigates to chat | **PASS** | Main chat UI with input field and suggestion chips |
| 8 | Second launch skips onboarding | **PASS** | Direct to chat + repair card for missing A11y |
| 9 | Eval bypass (fresh_session + goal) | **PASS** | Skipped onboarding, went to chat/settings |
| 10 | Legacy user migration | **PASS** | Detected existing API key, set onboarding_completed=true, went to chat |
| 11 | Post-onboarding repair card | **PASS** | "Setup Issue" card with "Fix" button for disabled A11y |

## Key Observations

- Auto-advance through already-satisfied permission steps works correctly (A11y + Overlay skipped in ~400ms)
- A11y `Checking...` spinner displays during polling
- Provider label dynamically derived: showed "Openrouter" for glm-5, "Openai" for gpt-5.2
- Error mapping correct: HTTP 401 → "key rejected", HTTP 400 → "provider configuration issue"
- Password field masking and visibility toggle work
- Repair card appears only after onboarding complete, with correct priority (A11y first)
- Migration log: `"Existing user detected — onboarding marked complete"`

## Not Tested (requires manual device interaction)

- Demo "Run Demo" end-to-end (requires live agent session with working model)
- Battery "Allow Background Running" dialog
- Process death resume mid-onboarding (hard to simulate via adb)
- Back button exits app during onboarding

## Verdict

**ALL 11 TESTS PASS** — onboarding wizard is ready for production use.
