# 3. Privacy - 隐私政策与无障碍服务说明

## 现状

- 无隐私政策文档
- 无数据处理说明
- 无障碍服务描述过于简单（仅 "AI agent that can navigate Android UI to accomplish goals"）
- 无用户数据收集/传输的透明说明
- API key 存储在 SharedPreferences（未加密）

## 为什么这是 Critical

Google Play 对使用 Accessibility Service 的 app 有**极严格审核**：
- 2024+ 政策要求：必须在应用内和 Play Store 列表中详细说明无障碍服务的用途
- 必须提交视频演示
- 不符合要求的 app 会被直接 reject 或下架
- 这是 Play Store 发布的**最大风险点**

## 任务

### 3.1 隐私政策文档

创建 `PRIVACY_POLICY.md`（根目录），涵盖：

**数据收集**:
- 屏幕内容：通过无障碍服务读取屏幕元素（文本、结构），仅在用户主动发起任务时
- 截图：可选功能，用于视觉感知
- 用户输入：任务指令文本

**数据传输**:
- 屏幕信息通过 API 发送到 LLM 提供商（OpenAI/OpenRouter/Novita）
- 用户可选择本地推理（Leap SDK）避免数据外传
- 无服务器端存储，无遥测，无分析

**数据存储**:
- API key 存储在本地 SharedPreferences
- 会话历史存储在本地设备
- 无云同步

**用户控制**:
- 用户可随时关闭无障碍服务
- 用户可清除本地数据
- 用户选择 LLM 提供商（控制数据流向）

### 3.2 无障碍服务说明增强

当前 `strings.xml` 中的描述太简单。需要增强为详细说明，让用户和 Google 审核人员理解用途。

更新 `agent_accessibility_config.xml` 的 description：
```
Android Agent uses the accessibility service to:
1. Read screen content (text, buttons, menus) to understand the current app state
2. Perform taps, swipes, and text input to complete user-requested tasks
3. Navigate between apps and screens as directed by the user

This service is ONLY active when you explicitly start a task. It does NOT run in the background, collect personal data, or monitor your activity outside of active task execution.

You remain in full control: you can stop any task at any time, and disable the service through Android Settings > Accessibility.
```

### 3.3 App 内隐私声明

在首次启用无障碍服务时，显示一个 consent dialog：
- 说明 app 将读取屏幕内容
- 说明数据会发送到哪个 LLM 提供商
- 提供隐私政策链接
- 用户必须明确同意

### 3.4 API Key 存储安全加固（可选但推荐）

当前 API key 存 SharedPreferences 明文。改进方案：
- 使用 `EncryptedSharedPreferences`（AndroidX Security 库）
- 或使用 Android Keystore 加密后存储

```kotlin
// 替换普通 SharedPreferences
val masterKey = MasterKey.Builder(context)
    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
    .build()

val prefs = EncryptedSharedPreferences.create(
    context, "secure_prefs", masterKey,
    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
)
```

### 3.5 权限最小化审查

当前 Manifest 权限：
- `INTERNET` - 必需（LLM API 调用）
- `SYSTEM_ALERT_WINDOW` - 必需（Smart Capsule overlay）
- `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE` - **审查是否仍需要**
  - 如果只用于读 API key 文件，可改为更精确的方式
  - Android 13+ 这两个权限已被更细粒度的 media permissions 替代
- `moe.shizuku.manager.permission.API_V23` - 需要（virtual display），但要在隐私政策中说明

## 验收标准

- [ ] 根目录有 `PRIVACY_POLICY.md`
- [ ] 无障碍服务描述已更新为详细版
- [ ] App 内有首次使用 consent 流程
- [ ] 权限声明已审查，移除不必要的权限
- [ ] 隐私政策有英文版（Play Store 要求）
