status: draft

# UX Design: Settings Page Restructure

Date: 2026-04-03
Ref: Current `ui/settings/SettingsSheet.kt` (362 lines, flat layout)

---

## 1. Problem

### Who
- **OAuth users** who completed onboarding — they have zero visibility into their auth state, cannot switch to manual API key, cannot disconnect/re-login, and have no way to diagnose token issues.
- **All users** — the flat settings layout will grow unwieldy as features increase.

### When / Where
- After initial onboarding, when user opens the Settings bottom sheet from the navigation drawer.
- When an OAuth token silently expires or refresh fails — user sees "session failed" but has no settings UI to diagnose or fix.

### Why It Matters
- OAuth users are trapped: if something goes wrong, there's no recovery path except reinstalling.
- Can't switch between auth methods (OAuth ↔ manual key) without re-doing onboarding.
- Flat layout already has 8 sections and 26 parameters — one more feature and it becomes scroll-heavy.

### Success Criteria
- OAuth users can see their auth status, disconnect, and re-authenticate from Settings.
- Users can switch auth methods (OAuth ↔ API key) without re-onboarding.
- Settings are organized into logical groups that scale.

---

## 2. Design: Two-Level Settings Navigation

The ModalBottomSheet stays (works well on mobile). Inside it, add a two-level navigation pattern.

### Level 1: Settings Home (Pure Navigation Page)

The main page is a pure navigation hub. All sections become clickable rows with summary subtitles.

```
┌─────────────────────────────────────────┐
│  Settings                           ✕   │
├─────────────────────────────────────────┤
│                                         │
│  🧠  LLM & Authentication           ›  │
│      gpt-5.4 · OpenAI OAuth            │
│                                         │
│  ⚡  Agent Behavior                  ›  │
│      Pro · 20 turns · Accessibility     │
│                                         │
│  ⚙  Permissions & Advanced          ›  │
│      All granted · Debug off            │
│                                         │
│  ─────────────────────────────────────  │
│                                         │
│  Android Agent v1.0 (1)                 │
│                                         │
└─────────────────────────────────────────┘
```

**Subtitle logic**:

| Row | Subtitle |
|---|---|
| LLM & Authentication (OAuth) | `"{model} · OpenAI OAuth"` |
| LLM & Authentication (API Key) | `"{model} · API key"` |
| LLM & Authentication (Local) | `"{localModel}"` |
| Agent Behavior | `"{mode} · {maxTurns} turns · {perception}"` |
| Permissions & Advanced | `"{permStatus} · Debug {on/off}"` |

### Level 2: LLM & Authentication (Three Top-Level Tabs)

Auth method first, then provider. Three tabs for three access methods.

```
┌─────────────────────────────────────────┐
│  ‹ LLM & Authentication            ✕   │
├─────────────────────────────────────────┤
│                                         │
│  ┌───────────┬───────────┬───────────┐  │
│  │  Sign In  │  API Key  │   Local   │  │  ← 3 top-level tabs
│  └───────────┴───────────┴───────────┘  │
│                                         │
│  (content below switches by tab)        │
│                                         │
└─────────────────────────────────────────┘
```

**Tab 1: Sign In** (OpenAI OAuth)

```
│  MODEL                                  │
│  Cloud Model    [ gpt-5.4         ▾ ]   │
│  Executor Model [ (Same as Main)  ▾ ]   │  ← PRO mode only
│                                         │
│  ─────────────────────────────────────  │
│                                         │
│  OPENAI ACCOUNT                         │
│  ┌───────────────────────────────────┐  │
│  │  ● Signed in                      │  │
│  │    user@email.com                │  │
│  │                                   │  │
│  │    [Sign Out]                     │  │
│  └───────────────────────────────────┘  │
│                                         │
│  (when not signed in: [Sign in with OpenAI] button) │
```

**Tab 2: API Key** (manual key entry)

```
│  PROVIDER                               │
│  ┌────────┬────────────┬────────┐       │
│  │ OpenAI │ OpenRouter │ Novita │       │  ← provider sub-selector
│  └────────┴────────────┴────────┘       │
│                                         │
│  MODEL                                  │
│  Cloud Model    [ gpt-5.4         ▾ ]   │  ← model list linked to provider
│  Executor Model [ (Same as Main)  ▾ ]   │  ← PRO mode only
│                                         │
│  ─────────────────────────────────────  │
│                                         │
│  API KEY                                │
│  OpenAI Key  [ sk-***...          👁 ]  │  ← linked to selected provider
```

**Tab 3: Local** (on-device inference)

```
│  LOCAL MODEL                            │
│  [ LFM 1.2B Instruct (Recommended) ▾ ] │
│  ████████████████░░░░  72% downloading  │
```

**Design rationale**:
1. Auth method first, then provider — matches user decision flow
2. OAuth completely separate — Sign In tab has only the account card, not mixed with key inputs
3. API Key tab providers are parallel — OpenAI / OpenRouter / Novita are the same type of operation; future providers (e.g. Anthropic) add here
4. Model selector linked to source — different tab/provider shows different model catalog

### Level 2: Agent Behavior

Simple controls page, no further nesting needed.

- Max Turns dropdown
- Agent Mode dropdown (Basic / Pro)
- Perception Mode selector (A11y / Hybrid / Screenshot)

### Level 2: Permissions & Advanced

- Accessibility Service status + tap to open system settings
- Overlay Permission status + tap to open system settings
- Shizuku (future)
- Debug Mode toggle

---

## 3. State Machine

### Settings Navigation State

```
States: Home | LlmAuth | AgentBehavior | PermissionsAdvanced
Initial: Home

Transitions:
  Home → LlmAuth            : user taps "LLM & Authentication" row
  Home → AgentBehavior       : user taps "Agent Behavior" row
  Home → PermissionsAdvanced : user taps "Permissions & Advanced" row
  LlmAuth → Home            : user taps back arrow (‹)
  AgentBehavior → Home       : user taps back arrow (‹)
  PermissionsAdvanced → Home : user taps back arrow (‹)
  Any → Dismissed            : user taps ✕ or swipes down
```

Implementation: `enum SettingsPage { HOME, LLM_AUTH, AGENT_BEHAVIOR, PERMISSIONS_ADVANCED }` with `AnimatedContent` transition.

### LLM & Authentication Tab State

```
States: SignIn | ApiKey | Local
Initial: auto-selected based on current authMethod/backend

Transitions:
  SignIn ↔ ApiKey ↔ Local : user taps the corresponding tab
```

Tab switching does not change saved settings — only changes the visible configuration area. Settings persist only when the user modifies them within a tab.

### OpenAI Auth Card State (within Sign In tab)

```
States: OAuthActive | OAuthNotSignedIn | OAuthInProgress | OAuthError

Transitions:
  OAuthActive → OAuthNotSignedIn
    trigger: user taps "Sign Out"
    side effect: clear OAuth creds, set authMethod=null

  OAuthNotSignedIn → OAuthInProgress
    trigger: user taps "Sign in with OpenAI"
    side effect: start OAuth flow (reuse onboarding logic)

  OAuthInProgress → OAuthActive
    trigger: OAuth callback success
    side effect: save tokens, set authMethod="oauth", update apiKey

  OAuthInProgress → OAuthError
    trigger: OAuth callback fails / timeout
    side effect: show error message

  OAuthError → OAuthInProgress
    trigger: user taps "Try Again"
```

Note: The old "Switch to API Key" transition is no longer needed — the user simply switches to the API Key tab.

### Guards
- Executor Model row only visible when agentMode == PRO
- API Key tab's provider sub-selector and key input are linked
- Sign Out clears OAuth tokens but does NOT clear any existing API key

---

## 4. Component Specifications

### 4.1 Navigation Row (Level 1, shared)

```kotlin
SettingsNavigationRow(
    icon = Icons.Outlined.Psychology,  // varies per row
    title = "LLM & Authentication",   // varies per row
    subtitle = buildSubtitle(...),     // varies per row
    onClick = { settingsPage = SettingsPage.LLM_AUTH }
)
```

- Surface with `surfaceVariant` background, 12.dp corners
- Trailing chevron icon (›)
- Subtitle: `bodySmall`, `onSurfaceVariant` color
- Full-width clickable

### 4.2 Sub-Page Header (Level 2, shared)

```kotlin
SettingsSubPageHeader(
    title = "LLM & Authentication",
    onBack = { settingsPage = SettingsPage.HOME },
    onClose = onDismiss
)
```

- Back arrow (‹) on left
- Title centered
- Close (✕) on right

### 4.3 LLM Tab Bar

```kotlin
TabRow(selectedTabIndex) {
    Tab(text = "Sign In", ...)
    Tab(text = "API Key", ...)
    Tab(text = "Local", ...)
}
```

Material 3 `TabRow` with `HorizontalPager` or `AnimatedContent` for tab content switching.

### 4.4 OpenAI Auth Card (Sign In tab, signed in)

```
Surface(surfaceVariant, 12.dp corners, 16.dp padding)
├── Row
│   ├── Box(8.dp circle, ChatSuccess green)
│   ├── Spacer(8.dp)
│   └── Column
│       ├── Text("Signed in", bodyLarge)
│       └── Text(email, bodySmall, onSurfaceVariant)
├── Spacer(16.dp)
└── OutlinedButton("Sign Out", colors = error outline)
```

### 4.5 OpenAI Auth Card (Sign In tab, not signed in)

```
Surface(surfaceVariant, 12.dp corners, 16.dp padding)
├── Text("Not signed in", bodyLarge)
├── Spacer(12.dp)
└── FilledButton("Sign in with OpenAI")
```

### 4.6 OpenAI Auth Card (Sign In tab, in progress)

```
Surface(surfaceVariant, 12.dp corners, 16.dp padding)
├── Row
│   ├── CircularProgressIndicator(size = 20.dp)
│   ├── Spacer(12.dp)
│   └── Text("Signing in with OpenAI...", bodyLarge)
├── Spacer(12.dp)
└── TextButton("Cancel")
```

### 4.7 API Key Provider Selector (API Key tab)

```kotlin
SegmentedButton(
    options = listOf("OpenAI", "OpenRouter", "Novita"),
    selected = selectedProvider,
    onSelect = { selectedProvider = it }
)
```

Material 3 `SingleChoiceSegmentedButtonRow`.

---

## 5. Edge Cases

| Scenario | Behavior |
|---|---|
| OAuth token expired, refresh fails | Auth card shows warning: "Session expired. Sign in again." with "Sign In" button |
| User signs out while session is active | Session continues until next LLM call fails (401), then shows error. No auto-interrupt. |
| User signs out and wants to use API Key | Switch to API Key tab, enter key. No extra steps needed. |
| OAuth flow interrupted (user cancels in browser) | OAuthError state: "Sign-in was cancelled." with "Try Again" button. |
| Tab switching | Does not change saved settings. Only persists when user modifies within a tab. |
| Token refresh succeeds in background | Auth card auto-updates (Compose recomposition) |

---

## 6. Data Flow Changes

### New state needed in `AppSettingsState`:
- `oauthEmail: String?` — loaded from `OAuthCredentialStore` for display
- No new persistence — `OnboardingStore.authMethod` and `OAuthCredentialStore` already handle this

### New callbacks needed in `SettingsSheet`:
- `onSignOut: () -> Unit` — clears OAuth creds + apiKey + authMethod
- `onStartOAuth: () -> Unit` — kicks off OAuth flow (reuses `OnboardingViewModel` logic)
- `onOAuthCancel: () -> Unit` — cancels in-progress OAuth

### Data flow for OAuth actions:
```
User taps "Sign Out"
  → SettingsSheet callback
  → MainActivity handler:
      oauthCredentialStore.clear()
      settingsState.updateApiKey("")
      settingsState.updateAuthMethod(null)
      onboardingStore.saveAuthMethod(null)
```

---

## 7. Out of Scope

- **Multi-account support** — one OpenAI account at a time
- **Settings search** — not needed at current scale
- **Settings import/export** — not needed
- **Tablet two-pane layout** — keep single-column for now

---

## 8. Implementation Strategy

### Phase 1: Settings navigation scaffold
- Add `SettingsPage` enum (HOME, LLM_AUTH, AGENT_BEHAVIOR, PERMISSIONS_ADVANCED)
- Add `AnimatedContent` wrapper to `SettingsSheet`
- Add shared `SettingsNavigationRow` and `SettingsSubPageHeader` components
- Move all existing sections into sub-page composables by group
- No behavior change — just reorganization

### Phase 2: LLM & Authentication three-tab structure
- Add `LlmAuthPage` composable with `TabRow` + tab content switching
- Tab 1 (Sign In): add `OpenAiAuthCard` composable with OAuth state display
- Tab 2 (API Key): add provider sub-selector + linked model/key inputs
- Tab 3 (Local): move existing local model selection and download status UI
- Wire Sign Out / Sign In actions (reuse OAuth flow from onboarding)

### Phase 3: State plumbing
- Pass `oauthEmail`, `authMethod` to SettingsSheet
- Add new callbacks (onSignOut, onStartOAuth, onOAuthCancel)
- Wire in MainActivity

---

## 9. Self-Review

Against original goals:
- OAuth users can see status → Sign In tab auth card shows email and status
- Can switch auth methods → switch tabs directly (Sign In ↔ API Key ↔ Local)
- Can disconnect → Sign Out button
- Settings organized hierarchically → two-level navigation + three sub-pages
- No dead ends → every state has a clear next action
- Scales → new providers add a row in the API Key tab, new permissions add a row in the Permissions sub-page
