# Shizuku Virtual Display 设计

## 0. 先说结论

我们只做一条主线：**Shizuku + Virtual Display + ImageReader + InputManager 注入**。

不要 ADB 分支，不要 ROM 分支，不要“先凑合再重构”。

- 架构上对齐现有 `AndroidPlatform` 抽象，不绕开 `AgentSession`。
- UI 上满足你的 3 个要求：
  1. 执行时有灵动岛入口（简版 capsule）。
  2. 完整 Smart Capsule 在 Virtual Display 页面内显示，不在真实主屏显示。
  3. Virtual Display 页面底部上滑退出，只退出页面，不停止任务。
- 不考虑 backward compatibility。旧方案能删就删，保留最少可读代码。

---

## 1. 设计原则（Linus 风格）

1. **KISS**：一件事一个模块，拒绝多层包装器套娃。
2. **单一真相**：Session 只认识 `AndroidPlatform`，不认识 Shizuku 细节。
3. **失败要硬**：Shizuku 不可用就明确报错，不偷偷降级到别的实现。
4. **代码可读第一**：短路径、短函数、少状态。
5. **新实现优先**：旧历史路径直接 deprecate，不做兼容分叉。

---

## 2. 与当前代码对齐（已阅读）

当前关键事实：

- 平台抽象：`app/src/main/kotlin/com/moonkey/androidagent/platform/AndroidPlatform.kt`
- 现有实现：`AccessibilityPlatform`，由 `AgentSession.create()` 硬编码创建。
- Action 执行：`tool/action/*Executor` 在上层做策略，平台只做原子动作。
- 感知模型：`ScreenSnapshot(elements, image)`；`elements` 可为空，代码已支持无 a11y 元素路径。
- Overlay：`ServiceOverlayController + SmartCapsuleManager` 当前是真实屏幕 accessibility overlay。

这意味着：

- 我们不需要推翻 Agent/tool 体系。
- 我们只要新增一个 `VirtualDisplayPlatform` 并把 session 创建改为工厂选择。
- `screenshot-only` 路径已经存在，适合 Virtual Display V1。

---

## 3. API 可行性核验（必须落地，不靠猜）

### 3.1 Shizuku 基础

- Shizuku 官方 API 仓库确认了权限请求、binder 监听、provider 依赖用法。  
  来源：<https://github.com/RikkaApps/Shizuku-API>

### 3.2 Virtual Display 创建

- AOSP `IDisplayManager.aidl` 确认存在：
  `createVirtualDisplay(VirtualDisplayConfig, IVirtualDisplayCallback, IMediaProjection, String)`  
  来源：<https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/hardware/display/IDisplayManager.aidl>

### 3.3 输入注入

- AOSP `IInputManager.aidl` 确认存在：
  `injectInputEvent(InputEvent ev, int mode)`  
  来源：<https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/hardware/input/IInputManager.aidl>

- 注入模式常量来源：`InputManager` (`INJECT_INPUT_EVENT_MODE_*`)  
  来源：<https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/hardware/input/InputManager.java>

### 3.4 shell 权限现实情况

- AOSP `com.android.shell` manifest 包含 `INJECT_EVENTS`、`CAPTURE_VIDEO_OUTPUT` 等权限。  
  来源：<https://android.googlesource.com/platform/frameworks/base/+/master/packages/Shell/AndroidManifest.xml>

- `DisplayManagerService` 对 `TRUSTED`/系统装饰 flags 有额外权限门槛（不是 shell 就能全开）。  
  来源：<https://android.googlesource.com/platform/frameworks/base/+/master/services/core/java/com/android/server/display/DisplayManagerService.java>

**结论**：

- V1 不碰 `TRUSTED` 等高权限 flags。
- 只用最小 flags，避免 ROM 差异坑。

### 3.5 启动到指定显示

- 官方 API `ActivityOptions.setLaunchDisplayId()` 可用。  
  来源：<https://developer.android.com/reference/android/app/ActivityOptions#setLaunchDisplayId(int)>

---

## 4. V1 范围（做什么 / 不做什么）

### 做什么（必须）

- 在 Shizuku 授权后创建 virtual display。
- Agent 在该 display 上执行点击/长按/滑动/系统键/输入。
- 感知使用该 display 的截图（ImageReader）。
- 提供 Virtual Display 观看页，内含完整 capsule。
- 真实屏幕仅显示“入口 capsule”（点击进入观看页）。

### 不做什么（明确砍掉）

- 不做 OEM 私有接口。
- 不做 root 方案。
- 不做任务跨 display 自动迁移黑科技。
- 不做 secure layer 绕过。

---

## 5. 总体架构（最小闭环）

```text
MainActivity / AgentService
  -> AgentSession.create()
     -> PlatformFactory.create(config, service)
        -> AccessibilityPlatform   (旧模式)
        -> VirtualDisplayPlatform  (新模式)

VirtualDisplayPlatform
  - ShizukuSession        // binder + permission + death handling
  - VirtualDisplayHost    // create/release display + ImageReader surface
  - DisplayInputInjector  // inject tap/swipe/key on displayId
  - DisplayAppLauncher    // launch app with setLaunchDisplayId
```

### 核心决策

- **只增加一个工厂层 `PlatformFactory`**，别把判断散落到 `AgentSession`、`MainActivity`、`Service` 各处。
- `VirtualDisplayPlatform` 直接实现 `AndroidPlatform`，上层执行器不改策略。
- `captureScreen()` 返回 `elements = emptyList()` + `image`（V1）。

---

## 6. 配置模型改造（直接改，不兼容旧字段）

在 `SessionConfig` 增加：

```kotlin
enum class PlatformMode {
    ACCESSIBILITY,
    VIRTUAL_DISPLAY_SHIZUKU
}

data class VirtualDisplayRuntimeConfig(
    val width: Int,
    val height: Int,
    val densityDpi: Int,
    val name: String = "moonkey_agent_display"
)
```

并在 `SessionConfig` 中加入：

- `platformMode: PlatformMode`
- `virtualDisplayConfig: VirtualDisplayRuntimeConfig?`

约束：

- `platformMode == VIRTUAL_DISPLAY_SHIZUKU` 时，`virtualDisplayConfig` 必填。
- 同时强制 `PerceptionConfig` 为 screenshot-capable（`ScreenshotOnly` 或 `Hybrid`）。

---

## 7. AndroidPlatform 生命周期（补齐缺口）

当前 `SessionServices.cleanup()` 不会释放平台资源。Virtual display 必须显式释放。

直接改 `AndroidPlatform`：

```kotlin
interface AndroidPlatform {
    suspend fun start()
    suspend fun stop()
    suspend fun captureScreen(): ScreenSnapshot
    suspend fun performAction(action: UIAction): ActionResult
    ...
}
```

- `AccessibilityPlatform.start/stop` 实现为 no-op。
- `VirtualDisplayPlatform.start()` 创建 display + injector + image pipeline。
- `VirtualDisplayPlatform.stop()` 释放所有资源。
- `AgentSession` 启动 session 时调用 `platform.start()`。
- `SessionServices.cleanup()` 或 `AgentSession.handleShutdown()` 调用 `platform.stop()`。

这比引入新接口更直接、更可读。

---

## 8. Action 语义映射（保持上层不动）

| UIAction | VirtualDisplayPlatform 实现 |
|---|---|
| `TapAt` | 注入 DOWN/UP 到 `displayId` |
| `Swipe` | 注入 DOWN/MOVE/UP 序列到 `displayId` |
| `LongPressAt` | DOWN -> delay -> UP |
| `SystemButton` | 注入 `KeyEvent`（BACK/HOME/RECENTS/ENTER） |
| `ClickNodeAt` | **直接等价为 `TapAt`**（V1 没 node） |
| `LongClickNodeAt` | **直接等价为 `LongPressAt`** |
| `SetTextOnNodeAt` | 先 `TapAt(x,y)` 聚焦，再按字符注入 key events |
| `SetTextOnFocused` | 直接按字符注入 key events |
| `Wait` | 复用现有 delay |

说明：

- 这是 KISS 方案。上层执行器逻辑完全不用重写。
- 文本输入 V1 先保证英文/数字稳定；复杂输入法字符标记为 best-effort。

---

## 9. 感知路径（V1）

`VirtualDisplayPlatform.captureScreen()`：

1. 从 `ImageReader.acquireLatestImage()` 取最新帧。
2. 转为 JPEG（沿用当前压缩参数）。
3. 构造：
   - `elements = emptyList()`
   - `image = ScreenImage(source = VIRTUAL_DISPLAY)`（新增 enum 值）

`Perceptor` 不参与 virtual display 截图路径。它仍只负责 accessibility tree 转换。

---

## 10. UI 设计（按你的要求）

### 10.1 真实屏幕：只保留入口灵动岛

新增 `MiniIslandManager`（极简）：

- 状态文本（Running/Paused/Error）
- 一个点击事件：打开 `VirtualDisplayActivity`
- 不放 pause/stop 按钮，避免在真实屏幕误触

### 10.2 Virtual Display 页面：完整 capsule

新增 `VirtualDisplayActivity`：

- `TextureView/SurfaceView` 显示 virtual display 画面
- 页面内叠加 `SmartCapsule`（pause/resume/stop/open-app 状态）
- 与 `AgentService` 通过 sessionId 或 service binder 同步状态

### 10.3 底部上滑退出（任务继续）

在 `VirtualDisplayActivity`：

- 检测底部区域上滑手势，触发 `finish()`。
- **禁止调用 `Op.Shutdown`**。
- 仅退出观看页，agent 在 service 继续跑。

---

## 11. 代码落地清单（文件级）

### 新增

- `app/src/main/kotlin/com/moonkey/androidagent/platform/PlatformFactory.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/platform/virtualdisplay/ShizukuSession.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/platform/virtualdisplay/VirtualDisplayHost.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/platform/virtualdisplay/DisplayInputInjector.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/platform/virtualdisplay/DisplayAppLauncher.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/platform/virtualdisplay/VirtualDisplayPlatform.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/ui/virtualdisplay/VirtualDisplayActivity.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/MiniIslandManager.kt`

### 修改

- `app/build.gradle.kts`（Shizuku + hidden stub 依赖）
- `app/src/main/AndroidManifest.xml`（Shizuku 权限 + VirtualDisplayActivity）
- `app/src/main/kotlin/com/moonkey/androidagent/protocol/Op.kt`（`SessionConfig` 新字段）
- `app/src/main/kotlin/com/moonkey/androidagent/session/AgentSession.kt`（平台工厂 + 生命周期）
- `app/src/main/kotlin/com/moonkey/androidagent/session/SessionServices.kt`（cleanup 调用 platform stop）
- `app/src/main/kotlin/com/moonkey/androidagent/model/Models.kt`（`ScreenImageSource` 新值）
- `app/src/main/kotlin/com/moonkey/androidagent/app/ServiceOverlayController.kt`（VD 模式分流）
- `app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt`（平台模式设置）
- `app/src/main/kotlin/com/moonkey/androidagent/app/AppSettingsStore.kt` / `AppSettingsState.kt` / `ui/settings/*`（平台模式配置）

---

## 12. 依赖与 Manifest（最小集）

`app/build.gradle.kts`：

```kotlin
dependencies {
    implementation("dev.rikka.shizuku:api:<latest>")
    implementation("dev.rikka.shizuku:provider:<latest>")
    compileOnly("dev.rikka.hidden:stub:6.1.0")
}
```

`AndroidManifest.xml`：

- 增加 `uses-permission`：`moe.shizuku.manager.permission.API_V23`
- 注册 `VirtualDisplayActivity`
- Shizuku provider 使用库自带 provider（按官方方式），不要手写奇怪 permission/exported 组合。

---

## 13. 弃用与删除（不搞兼容包袱）

以下逻辑在 `VIRTUAL_DISPLAY_SHIZUKU` 模式下直接废弃：

- `ServiceOverlayController` 的完整 real-screen capsule 控制路径。
- 任何“Shizuku 失败自动回退 AccessibilityPlatform”的隐式行为。
- Virtual display 相关旧草稿代码中的 `WindowManager.captureDisplay` 路径（V1 不需要）。

标记方式：

- 直接删除 dead path，或者 `@Deprecated("Replaced by VirtualDisplayPlatform")` 后立即迁移调用点。
- 不保留双实现并行超过一个迭代。

---

## 14. 实施顺序（按周可交付）

### Phase 1: 平台最小闭环

- 接入依赖 + Shizuku permission/request
- `VirtualDisplayHost` 创建/释放 display
- `DisplayInputInjector` 支持 tap/swipe/back/home
- `VirtualDisplayPlatform` 跑通 `captureScreen + performAction`

**验收**：`Open Settings` 在 virtual display 可执行，截图能返回。

### Phase 2: Session 接线

- `PlatformFactory` + `SessionConfig.platformMode`
- `AgentSession` 生命周期接入 `start/stop`
- MainActivity 设置项接入

**验收**：可在设置中切换平台并稳定启动。

### Phase 3: UI 完整体验

- `MiniIslandManager`
- `VirtualDisplayActivity` + 页面内 capsule
- 上滑退出仅关闭页面

**验收**：符合你的 3 条交互要求。

### Phase 4: 清理

- 删除废弃路径
- 文档更新
- 全量验证

---

## 15. 测试与验证

### 单元测试

- `DisplayInputInjector` 事件构造（坐标、displayId、action 序列）
- `PlatformFactory` 模式选择
- `SessionConfig` 约束校验

### 设备集成测试（必须真机）

1. Shizuku 启动/授权后可创建 display 并获取有效 `displayId`。
2. `TapAt/Swipe/SystemButton` 可在该 display 生效。
3. 截图持续可用，`ScreenSnapshot.image != null`。
4. 退出 `VirtualDisplayActivity` 后任务持续运行。

### 验证命令

- `./gradlew assembleDebug`
- `./gradlew test`
- `./gradlew lint`
- `./scripts/debug-run.sh "Open Settings"`

---

## 16. 风险与处理

1. **Shizuku 中途死亡**  
处理：binder dead listener 触发 session error，停止平台并给出明确 UI 状态。

2. **部分 ROM 对 display/input 行为有限制**  
处理：启动时做 capability probe；失败直接报错，不 silently fallback。

3. **文本输入在复杂输入法场景不稳定**  
处理：V1 明确 best-effort；V2 再补更强输入策略。

---

## 17. 这份设计与现有代码的一句话关系

它不是重写你的 agent。它只是在你现有 `AndroidPlatform` 架构下，新增一个干净的 `VirtualDisplayPlatform`，并把 UI 入口/观看页逻辑摆正。路径短、改动集中、可直接开工。
