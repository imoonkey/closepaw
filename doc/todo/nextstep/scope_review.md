# Scope Review: What Should Android Agent Do Next?

*Date: 2026-03-24. Post-R58 (autotune round 58).*

## Current State

| Dimension | State |
|-----------|-------|
| **Agent quality** | ~81% estimated full AndroidWorld set (33/51 hard subset, ~61/65 easy set). 58 autotune rounds. |
| **Architecture** | Mature. ReAct loop, multi-agent delegation, 11 tools, streaming LLM, hybrid perception, memory V2. |
| **App skills** | 17 apps — all AndroidWorld eval-specific (Markor, Tasks.org, Broccoli, OpenTracks, etc.) |
| **Security** | NOT production-ready. Cleartext traffic enabled, API keys in plain SharedPreferences, insecure SSL config, allowBackup=true. |
| **Distribution** | Zero. No one can install or use this product. |
| **Onboarding** | None. Complex manual setup: accessibility service → overlay permission → battery optimization → API key entry. |
| **Design docs** | Extensive: 10 OpenClaw alignment topics, Task API design, proactive UX design, publish gap analysis, app skill framework. |
| **Proactive features** | None. No scheduled tasks, no notification handling. Product is purely reactive (user speaks → agent acts). |

## The Core Problem

The product has spent 58 rounds perfecting performance on a benchmark that no real user sees.

**Design-to-ship ratio is too high:**
- 10 OpenClaw alignment docs → 0 features shipped from them
- Task API fully designed → 0 lines built
- Proactive agent UX designed → 0 lines built
- Publish gap analyzed (2026-03-05) → 0 items fixed
- Memory V1 designed → V2 shipped (the one that landed)

**The eval trap:** Every hour on R59 yields ~0.2% improvement. The remaining 18 failures are:
- 6 chronic (≤1/15 pass rate) — structural, won't fix with prompting
- 7 stochastic — fluctuate regardless of changes
- 2 parked infra — platform/a11y limitations
- 3 chronic cognitive — budget-constrained

Further eval tuning has diminishing returns. The eval should become a **regression CI gate**, not an optimization target.

**The coverage gap:** 17 app skills cover only AndroidWorld eval apps (Markor, Broccoli, OpenTracks, SimpleCalendar, etc.). Real users have WeChat, WhatsApp, Gmail, YouTube, Maps, Spotify, Instagram, Uber, Alipay, 美团... The agent literally cannot help with the apps people use daily.

## Challenging priority.md

| priority.md Item | Assessment |
|-----------------|------------|
| 0. Prompt generalization | **Stop**. 58 rounds. Law of diminishing returns. Use eval as regression CI only. |
| 1. Memory V2 | **Done**. Ship it and move on. |
| 2. Session management | **Defer**. Sessions work (hot idle, checkpoint, resume). Making them user-browsable is polish, not value creation. |
| 3. Security/permissions | **Do now**. Blocks ALL distribution paths. |
| 4. Auth (OpenClaw-style) | **Defer**. No external callers exist yet. Build when Task API lands. |
| 5. Release readiness | **Do now**. The path to users. |
| 6. 60-app coverage | **High value**. Scope to top 30 popular apps first. This is what makes the product useful. |
| 7. Scheduled tasks | **High value**. Transforms one-shot tool into daily assistant with retention. |
| 8. Notification handling | **High value**. Pairs with scheduled tasks for proactive capability. |

**Note on the OpenClaw P1-P9 roadmap** (dynamic tool exposure, policy externalization, prompt assets, etc.): This is internal engineering quality work. Important for long-term code health, but zero direct user value. Interleave as you touch those areas, don't dedicate a phase to it.

## Three Paths

### Path A: Ship First, Iterate Later

Open source on GitHub with minimal cleanup. Let real usage guide what to build next.

```
Week 1:  Security hardening (EncryptedSharedPrefs, disable cleartext/insecure SSL)
         README + LICENSE + CONTRIBUTING
Week 2:  Onboarding wizard (a11y → overlay → battery → API key → demo)
         Minimal CI (build + test + lint)
         GitHub release v0.1-alpha
Week 3+: Iterate based on real user feedback
```

| Pros | Cons |
|------|------|
| Fastest to real users | First impression with only 17 niche eval apps |
| Feedback-driven iteration | Security fixes under time pressure |
| Forces confronting real problems | May underwhelm without app coverage |

### Path B: Coverage + Ship (Recommended)

Expand to 30 popular apps, fix security, add onboarding, then ship. Users get real utility on day one.

```
Phase 1 — Unblock Distribution (2 weeks)
├── Security hardening (P0 items from publish gap assessment)
│   ├── EncryptedSharedPreferences for API keys
│   ├── Disable cleartext traffic
│   ├── Remove/gate insecure SSL config
│   └── Set allowBackup=false with extraction rules
├── Onboarding wizard (sequential funnel, not settings page)
│   ├── Accessibility service permission
│   ├── Overlay permission
│   ├── Battery optimization exemption
│   ├── API key entry + validation
│   └── Demo task ("Open Settings and turn on Wi-Fi")
├── README + LICENSE + CONTRIBUTING + SECURITY.md
└── Minimal CI (GitHub Actions: build + test + lint on PR)

Phase 2 — Real Utility (3-4 weeks)
├── Top 20-30 popular app skills
│   ├── Messaging: WeChat, WhatsApp, Telegram, Signal
│   ├── Social: Instagram, Twitter/X, TikTok, YouTube
│   ├── Productivity: Gmail, Google Calendar, Google Maps, Notion
│   ├── Commerce: Alipay, 美团, 饿了么, Uber, Amazon
│   ├── Media: Spotify, Apple Music, Netflix
│   └── Utility: Phone, Contacts, Camera, Clock, Calculator
├── Prompt externalization (P3 Phase 1)
│   └── System prompt to assets — iterate skills without APK rebuild
├── Eval as regression CI
│   └── Run full AndroidWorld set on PRs, fail on regression, stop optimizing
└── GitHub release v0.1-alpha

Phase 3 — Daily Assistant (3-4 weeks)
├── Scheduled tasks (cron-style recurring execution)
├── Notification handling (reactive trigger → agent action)
└── v0.2 release

Phase 4 — Platform (future, after user validation)
├── Task API (HTTP endpoint for external orchestrators)
├── Voice input (push-to-talk in Smart Capsule)
├── Play Store submission (if accessibility compliance viable)
└── OpenClaw integration via Task API
```

| Pros | Cons |
|------|------|
| Real utility on day one (30 popular apps) | 5-6 weeks before first external user |
| Compelling demo and pitch | Building app skills in vacuum without user feedback |
| Security properly fixed before distribution | App skill effort may not match real usage patterns |
| Natural progression from utility → retention → platform | |

### Path C: Platform First

Build scheduled tasks + notifications + Task API. Position as "always-on personal assistant" not "one-shot tool."

```
Week 1-2: Scheduled tasks (cron-style task execution engine)
Week 2-3: Notification handling (reactive triggers from notification stream)
Week 3-4: Task API (HTTP endpoint, bearer auth, localhost-only default)
Week 4+:  Ship with "autonomous assistant" positioning
```

| Pros | Cons |
|------|------|
| Differentiates from "just another agent demo" | Most complex path |
| Daily retention from proactive behavior | Longest time to ship anything |
| Task API enables ecosystem play | May overbuild before user validation |
| Strong positioning for OpenClaw integration | Still has the 17-app coverage gap |

## Recommendation

**Path B**, because:

1. **Security + onboarding** is non-negotiable for any distribution path.
2. **App coverage** is the difference between "cool demo" and "useful product." The agent is good — it just needs to know the apps people actually use.
3. **Prompt externalization** (P3 Phase 1) unlocks fast iteration on app skills without APK rebuild — tactical but high ROI.
4. **Eval as regression CI** preserves the 58 rounds of work as a quality gate without continuing to grind.
5. **Scheduled tasks + notifications** in Phase 3 add retention and daily utility, building on the coverage foundation.

The key insight: **security + onboarding + app coverage is the minimum viable product.** Everything else — session UX, policy externalization, rich messages, voice, Task API — is either internal quality, platform play, or polish that can wait for user validation.

## What NOT to Do

- **Don't run R59.** Use eval as CI, not as an optimization loop.
- **Don't build Task API yet.** No external callers exist. Build it when someone wants to integrate.
- **Don't refactor for OpenClaw P1-P4 roadmap.** Internal quality work that no user will see. Interleave when naturally touching those areas.
- **Don't pursue Play Store yet.** AccessibilityService compliance for "autonomous agent" is high-uncertainty. Open source first, Play Store later after real-world validation.
- **Don't build voice yet.** Nice UX but doesn't expand what the agent can do. Build after core utility is validated.

## Open Questions

1. **App skill approach at scale:** Can we generate skills from app documentation/crawling, or must each be manually authored from trace analysis? At 30-60 apps, manual authoring is the bottleneck.
2. **Chinese vs English market:** App skill priorities differ dramatically. WeChat/Alipay/美团 for Chinese users vs WhatsApp/Uber/Spotify for English. Which market first?
3. **Accessibility compliance:** The publish gap assessment flags AccessibilityService as the biggest Play Store uncertainty. Is there a way to frame the product that passes review? Or is open-source sideload the primary distribution path?
4. **LLM cost model:** Currently depends on cloud APIs (OpenAI, OpenRouter). For daily-use scheduled tasks, what's the per-user monthly cost? Is on-device LLM (LFM) viable for routine tasks?
