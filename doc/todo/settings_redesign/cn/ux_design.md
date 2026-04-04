status: draft

# UX 设计：Settings 页面重构

日期：2026-04-03
参考：当前 `ui/settings/SettingsSheet.kt`（362 行，flat layout）

---

## 1. 问题分析

### 谁会遇到这个问题
- **OAuth 用户**完成 onboarding 后，完全看不到自己的认证状态，无法切换到手动 API key，无法断开/重新登录，token 出问题时没有任何诊断手段。
- **所有用户**——随着功能增加，flat layout 会越来越难用。

### 什么时候/哪里发生
- Onboarding 完成后，用户从 navigation drawer 打开 Settings bottom sheet 时。
- OAuth token 静默过期或 refresh 失败时——用户只看到 "session failed"，但没有任何 Settings UI 可以排查或修复。

### 为什么重要
- OAuth 用户被困住：出问题后除了重装没有恢复路径。
- 不能在 OAuth ↔ 手动 key 之间切换，除非重走 onboarding。
- Flat layout 已经有 8 个 section、26 个参数——再加一个功能就会变成无尽滚动。

### 成功标准
- OAuth 用户可以在 Settings 中查看认证状态、断开连接、重新认证。
- 用户可以在 OAuth ↔ API key 之间切换，无需重走 onboarding。
- Settings 按逻辑分组，结构可扩展。

---

## 2. 设计方案：两级 Settings 导航

保留 ModalBottomSheet（移动端体验好）。在内部增加两级导航模式。

### Level 1：Settings 主页（纯导航页）

主页面是纯导航 hub，所有 section 变成可点击的行，每行一句摘要副标题。

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

**副标题逻辑**：

| 行 | 副标题 |
|---|---|
| LLM & Authentication (OAuth) | `"{model} · OpenAI OAuth"` |
| LLM & Authentication (API Key) | `"{model} · API key"` |
| LLM & Authentication (Local) | `"{localModel}"` |
| Agent Behavior | `"{mode} · {maxTurns} turns · {perception}"` |
| Permissions & Advanced | `"{permStatus} · Debug {on/off}"` |

### Level 2：LLM & Authentication（三个顶级 Tab）

先选认证方式，再选 provider。三个 tab 对应三种接入方式。

```
┌─────────────────────────────────────────┐
│  ‹ LLM & Authentication            ✕   │
├─────────────────────────────────────────┤
│                                         │
│  ┌───────────┬───────────┬───────────┐  │
│  │  Sign In  │  API Key  │   Local   │  │  ← 3 个顶级 tab
│  └───────────┴───────────┴───────────┘  │
│                                         │
│  (下方内容根据选中的 tab 动态切换)        │
│                                         │
└─────────────────────────────────────────┘
```

**Tab 1: Sign In**（OpenAI OAuth）

```
│  MODEL                                  │
│  Cloud Model    [ gpt-5.4         ▾ ]   │
│  Executor Model [ (Same as Main)  ▾ ]   │  ← 仅 PRO mode
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
│  (未登录时显示 [Sign in with OpenAI] 按钮) │
```

**Tab 2: API Key**（手动 key 输入）

```
│  PROVIDER                               │
│  ┌────────┬────────────┬────────┐       │
│  │ OpenAI │ OpenRouter │ Novita │       │  ← provider sub-selector
│  └────────┴────────────┴────────┘       │
│                                         │
│  MODEL                                  │
│  Cloud Model    [ gpt-5.4         ▾ ]   │  ← model 列表跟 provider 联动
│  Executor Model [ (Same as Main)  ▾ ]   │  ← 仅 PRO mode
│                                         │
│  ─────────────────────────────────────  │
│                                         │
│  API KEY                                │
│  OpenAI Key  [ sk-***...          👁 ]  │  ← 跟选中 provider 联动
```

**Tab 3: Local**（本地推理）

```
│  LOCAL MODEL                            │
│  [ LFM 1.2B Instruct (Recommended) ▾ ] │
│  ████████████████░░░░  72% downloading  │
```

**设计要点**：
1. 先认证方式再 provider——符合用户决策顺序
2. OAuth 完全独立——Sign In tab 只有 account card，不跟 key 输入框混
3. API Key tab 内 provider 并列——OpenAI / OpenRouter / Novita 是同一类操作，以后加 Anthropic 等也在这里
4. Model selector 跟来源联动——不同 tab/provider 的 model catalog 不同

### Level 2：Agent Behavior

简单控件页面，无需更多层级。

- Max Turns dropdown
- Agent Mode dropdown (Basic / Pro)
- Perception Mode selector (A11y / Hybrid / Screenshot)

### Level 2：Permissions & Advanced

- Accessibility Service 状态 + 点击跳转系统设置
- Overlay Permission 状态 + 点击跳转系统设置
- Shizuku（未来）
- Debug Mode toggle

---

## 3. State Machine

### Settings 导航状态

```
States: Home | LlmAuth | AgentBehavior | PermissionsAdvanced
Initial: Home

Transitions:
  Home → LlmAuth            : 用户点击 "LLM & Authentication" 行
  Home → AgentBehavior       : 用户点击 "Agent Behavior" 行
  Home → PermissionsAdvanced : 用户点击 "Permissions & Advanced" 行
  LlmAuth → Home            : 用户点击返回箭头 (‹)
  AgentBehavior → Home       : 用户点击返回箭头 (‹)
  PermissionsAdvanced → Home : 用户点击返回箭头 (‹)
  Any → Dismissed            : 用户点击 ✕ 或下滑关闭
```

实现：`enum SettingsPage { HOME, LLM_AUTH, AGENT_BEHAVIOR, PERMISSIONS_ADVANCED }`，配合 `AnimatedContent` 做转场动画。

### LLM & Authentication Tab 状态

```
States: SignIn | ApiKey | Local
Initial: 基于当前 authMethod/backend 自动选择

Transitions:
  SignIn ↔ ApiKey ↔ Local : 用户点击对应 tab
```

Tab 切换不改变已保存的设置，只改变当前可见的配置区域。用户在某个 tab 内修改设置后才会持久化。

### OpenAI Auth Card 状态（Sign In tab 内）

```
States: OAuthActive | OAuthNotSignedIn | OAuthInProgress | OAuthError

Transitions:
  OAuthActive → OAuthNotSignedIn
    trigger: 用户点击 "Sign Out"
    side effect: 清除 OAuth creds，设置 authMethod=null

  OAuthNotSignedIn → OAuthInProgress
    trigger: 用户点击 "Sign in with OpenAI"
    side effect: 启动 OAuth flow（复用 onboarding 逻辑）

  OAuthInProgress → OAuthActive
    trigger: OAuth callback 成功
    side effect: 保存 tokens，设置 authMethod="oauth"，更新 apiKey

  OAuthInProgress → OAuthError
    trigger: OAuth callback 失败 / 超时
    side effect: 显示错误信息

  OAuthError → OAuthInProgress
    trigger: 用户点击 "Try Again"
```

注意：旧设计中的 "Switch to API Key" 转换不再需要——用户直接切换到 API Key tab 即可。

### Guards
- Executor Model 行仅在 agentMode == PRO 时可见
- API Key tab 的 provider sub-selector 和 key 输入框联动
- Sign Out 清除 OAuth tokens，但不清除已有的 API key

---

## 4. 组件规格

### 4.1 导航行（Level 1 通用）

```kotlin
SettingsNavigationRow(
    icon = Icons.Outlined.Psychology,  // 各行不同
    title = "LLM & Authentication",   // 各行不同
    subtitle = buildSubtitle(...),     // 各行不同
    onClick = { settingsPage = SettingsPage.LLM_AUTH }
)
```

- `surfaceVariant` 背景的 Surface，12.dp 圆角
- 右侧 chevron 图标 (›)
- 副标题：`bodySmall`，`onSurfaceVariant` 颜色
- 整行可点击

### 4.2 子页面 Header（Level 2 通用）

```kotlin
SettingsSubPageHeader(
    title = "LLM & Authentication",
    onBack = { settingsPage = SettingsPage.HOME },
    onClose = onDismiss
)
```

- 左侧返回箭头 (‹)
- 标题居中
- 右侧关闭按钮 (✕)

### 4.3 LLM Tab Bar

```kotlin
TabRow(selectedTabIndex) {
    Tab(text = "Sign In", ...)
    Tab(text = "API Key", ...)
    Tab(text = "Local", ...)
}
```

使用 Material 3 `TabRow`，配合 `HorizontalPager` 或 `AnimatedContent` 实现 tab 内容切换。

### 4.4 OpenAI Auth Card（Sign In tab 内，已登录）

```
Surface(surfaceVariant, 12.dp 圆角, 16.dp padding)
├── Row
│   ├── Box(8.dp 圆形, ChatSuccess 绿色)
│   ├── Spacer(8.dp)
│   └── Column
│       ├── Text("Signed in", bodyLarge)
│       └── Text(email, bodySmall, onSurfaceVariant)
├── Spacer(16.dp)
└── OutlinedButton("Sign Out", colors = error outline)
```

### 4.5 OpenAI Auth Card（Sign In tab 内，未登录）

```
Surface(surfaceVariant, 12.dp 圆角, 16.dp padding)
├── Text("Not signed in", bodyLarge)
├── Spacer(12.dp)
└── FilledButton("Sign in with OpenAI")
```

### 4.6 OpenAI Auth Card（Sign In tab 内，进行中）

```
Surface(surfaceVariant, 12.dp 圆角, 16.dp padding)
├── Row
│   ├── CircularProgressIndicator(size = 20.dp)
│   ├── Spacer(12.dp)
│   └── Text("Signing in with OpenAI...", bodyLarge)
├── Spacer(12.dp)
└── TextButton("Cancel")
```

### 4.7 API Key Provider Selector（API Key tab 内）

```kotlin
SegmentedButton(
    options = listOf("OpenAI", "OpenRouter", "Novita"),
    selected = selectedProvider,
    onSelect = { selectedProvider = it }
)
```

使用 Material 3 `SingleChoiceSegmentedButtonRow`。

---

## 5. 边界情况

| 场景 | 行为 |
|---|---|
| OAuth token 过期，refresh 失败 | Auth card 显示警告："Session expired. Sign in again."，附带 "Sign In" 按钮 |
| 用户在 session 进行中 sign out | Session 继续运行直到下一次 LLM 调用失败（401），然后显示错误。不主动中断。 |
| 用户 sign out 后想用 API Key | 切换到 API Key tab，输入 key 即可。无需额外流程。 |
| OAuth flow 被中断（用户在浏览器中取消） | OAuthError 状态："Sign-in was cancelled."，提供 "Try Again" 按钮。 |
| Tab 切换 | 不改变已保存设置。只有在 tab 内修改并确认后才持久化。 |
| Token refresh 在后台成功 | Auth card 自动更新（通过 Compose recomposition） |

---

## 6. 数据流变更

### `AppSettingsState` 需要的新 state：
- `oauthEmail: String?` — 从 `OAuthCredentialStore` 加载，用于显示
- 不需要新的持久化——`OnboardingStore.authMethod` 和 `OAuthCredentialStore` 已经处理

### `SettingsSheet` 需要的新 callbacks：
- `onSignOut: () -> Unit` — 清除 OAuth creds + apiKey + authMethod
- `onStartOAuth: () -> Unit` — 启动 OAuth flow（复用 `OnboardingViewModel` 的逻辑）
- `onOAuthCancel: () -> Unit` — 取消进行中的 OAuth

### OAuth 操作的数据流：
```
用户点击 "Sign Out"
  → SettingsSheet callback
  → MainActivity handler:
      oauthCredentialStore.clear()
      settingsState.updateApiKey("")
      settingsState.updateAuthMethod(null)
      onboardingStore.saveAuthMethod(null)
```

---

## 7. 不在范围内

- **多账号支持** — 同一时间只支持一个 OpenAI 账号
- **Settings 搜索** — 当前规模不需要
- **Settings 导入/导出** — 不需要
- **Tablet 双栏布局** — 暂时保持单栏

---

## 8. 实施策略

### Phase 1：Settings 导航骨架
- 添加 `SettingsPage` enum（HOME, LLM_AUTH, AGENT_BEHAVIOR, PERMISSIONS_ADVANCED）
- `SettingsSheet` 增加 `AnimatedContent` wrapper
- 新增 `SettingsNavigationRow` 和 `SettingsSubPageHeader` 通用组件
- 将所有现有 section 按新分组移入各子页面 composable
- 无行为变更——纯粹的布局重组

### Phase 2：LLM & Authentication 三 Tab 结构
- 新增 `LlmAuthPage` composable，内含 `TabRow` + tab 内容切换
- Tab 1 (Sign In)：新增 `OpenAiAuthCard` composable，显示 OAuth 状态
- Tab 2 (API Key)：新增 provider sub-selector + 联动的 model/key 输入
- Tab 3 (Local)：移入现有 local model 选择和下载状态 UI
- 接入 Sign Out / Sign In 操作（复用 onboarding 的 OAuth flow）

### Phase 3：State 接线
- 将 `oauthEmail`、`authMethod` 传递给 SettingsSheet
- 添加新 callbacks（onSignOut、onStartOAuth、onOAuthCancel）
- 在 MainActivity 中接线

---

## 9. 自查

对照原始目标：
- OAuth 用户可以看到状态 → Sign In tab 的 auth card 显示 email 和状态
- 可以切换认证方式 → 直接切换 tab（Sign In ↔ API Key ↔ Local）
- 可以断开连接 → Sign Out 按钮
- Settings 层级化组织 → 两级导航 + 三子页面
- 没有死胡同 → 每个状态都有明确的下一步操作
- 可扩展 → 新 provider 在 API Key tab 加一项即可，新权限在 Permissions 子页面加即可
