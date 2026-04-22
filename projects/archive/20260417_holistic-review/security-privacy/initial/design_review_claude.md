# Cross-Review of Codex's Security & Privacy Design

**Reviewer:** CLAUDE
**Date:** 2026-04-08
**Reviewed:** `design_codex.md` and `improvement_plan_codex.md`

## Overall Assessment

Codex's review is architecturally stronger than mine. It correctly identifies the problem as **boundary placement and privilege composition** rather than a collection of individual bugs. The trust-boundary framing (control plane, privileged automation, perception/prompting, persistence, network) gives the review structural clarity that my finding-by-finding approach lacks.

## What Codex Got Right That I Missed

### 1. Blocked-App Privacy Gating Happens After Capture (Finding 2)

This is the most important finding I missed entirely. Codex correctly identifies that `captureScreen()` writes raw trees, sanitized trees, and screenshots to trace artifacts **before** any blocked-app masking occurs. Post-action observations in `OpenAppTool` and `UIActionInvocation` also bypass masking. This is a systemic architectural flaw, not a single-point bug. The recommendation to move gating inside the capture pipeline is correct.

### 2. Accessibility Data Not Sanitized for Privacy (Finding 3)

I identified the trace redactor's coverage but failed to notice the deeper issue: the capture model itself has no sensitivity metadata. `PerceptionElement` doesn't carry `isPassword` or equivalent, so password fields, OTP codes, typed messages, and financial data flow into prompts, history, and traces as regular text. Codex's recommendation to add field-level sensitivity metadata and split serializers by audience is architecturally sound and addresses a systemic gap.

### 3. Shell as Capability Model Mismatch (Finding 5)

I identified shell command injection (metacharacter bypass), which is a real bug. But Codex's framing is better: the fundamental problem is that the foreground-app approval model is the wrong abstraction for a file/system tool. Even with perfect input validation, `shell` can access app-private session files, memory, and preferences regardless of the foreground package. Codex correctly notes this needs a separate data-access policy, not just input sanitization.

## What Codex Got Right and I Also Covered

- **Exported intent control plane** (Codex Finding 1 = my Finding 6): Both identified this. Codex's framing as "unauthenticated control plane" is more precise. Codex also noted the goal auto-start and mode-switching risks that I underemphasized.
- **Secret storage fails open** (Codex Finding 4 = my Finding 1): Both identified. Codex's recommendation to fail closed is cleaner; I recommended a degraded-mode banner which is the right UX but doesn't go far enough on refusing to persist refresh tokens.
- **InsecureSsl** (Codex Finding 6 = my Finding 4): Both identified. Both recommend compile-time debug-only. No disagreement.
- **Logging PII** (Codex Finding 7 = my Finding 2): Both identified id_token claims logging and LlmLogger exposure. No disagreement.

## What I Found That Codex Missed or Underweighted

### 1. API Key from /sdcard (my Finding 3)

I specifically called out `loadApiKeyFromFile()` reading from `/sdcard/api_key.txt`, which is world-readable. Codex mentions the "shared-storage API-key bootstrap path" in passing but doesn't give it finding-level treatment. This is HIGH severity because it enables both key theft and poisoned key injection.

### 2. OAuth Callback Localhost Race (my Finding 7)

I identified the specific race condition where a co-installed app can connect to `localhost:1455` to intercept or spoof the OAuth callback. Codex briefly mentions this in P2.1 but classifies it as follow-up architecture work. I'd keep it at MEDIUM priority.

### 3. AppClassifier Fails Open (my Finding 8)

If `app_tiers.json` fails to load, the classifier returns empty, and all apps become `CAUTIOUS` instead of `BLOCKED`. With `AUTO_APPROVE` mode, this means banking apps get auto-approved. Codex didn't cover this. It deserves MEDIUM severity.

### 4. Positive Findings

I identified 6 positive findings (network config, TOCTOU guard, trace redactor coverage, password masking in UI, AppClassifier only-tightens, PKCE/CSRF). These are valuable for understanding what works and should be preserved. Codex's "Existing Strengths" section covers some of these less specifically.

## Assessment of Codex's Improvement Plan

The plan is well-structured with clear acceptance criteria, which my plan lacks. The delivery sequence (Milestone A/B/C) is practical. The "if only five things" summary is actionable.

One concern: P0.3 (accessibility sanitization at source) is listed as P0 but is architecturally complex. It touches the perception model, prompt serialization, history, traces, and observation building. This is more of a P1 effort in practice, even if its security impact is P0-level. The plan acknowledges this indirectly by placing it in Milestone B.

Missing from Codex's plan: AppClassifier fail-closed behavior and OAuth callback hardening.

## Gaps in Both Reviews

Neither review addressed:
- **Memory store privacy**: `MemoryStore` persists markdown files in internal storage. If the agent writes sensitive data to memory during a task, it persists indefinitely with no redaction.
- **Session history retention**: No time-based expiry or user-accessible wipe for session data.

Codex's plan partially addresses the second point in P1.4 (retention controls).

## Better Base for Aligned Draft

**CODEX** is the better base.

Reasons:
1. Architectural framing (trust boundaries, boundary placement) provides a stronger foundation for the final document
2. Findings 2 (capture-before-masking) and 3 (accessibility field sensitivity) are major gaps in my review
3. The improvement plan has acceptance criteria and delivery milestones
4. The systemic perspective will produce a more durable security posture than patching individual bugs

The aligned draft should use Codex's structure and add:
- My granular findings (#3 sdcard key, #7 OAuth race, #8 AppClassifier fail-open)
- Positive findings as a "What Works" section
- Specific line-number references from my review where they complement Codex's architectural descriptions
