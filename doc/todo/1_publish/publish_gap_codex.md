# Android Agent 发布差距评估（Codex）

更新时间：2026-03-05（America/Los_Angeles）  
评估范围：当前仓库代码与构建产物，目标为：
- Google Play Store 上架
- 开源仓库发布（GitHub/GitLab）

---

## 0. 当前基线（已验证）

### 工程验证
- `./gradlew assembleDebug lint test`：通过（0 errors, 48 warnings）
- `./gradlew assembleRelease`：通过
- `./gradlew bundleRelease`：通过

### 产物与签名状态
- 存在产物：`app/build/outputs/bundle/release/app-release.aab`
- APK 为 unsigned：`app/build/outputs/apk/release/app-release-unsigned.apk`
- `signingReport` 显示 release `Config: null`
- `jarsigner -verify` 显示 AAB 未签名

结论：**当前是“可构建可运行”，但不是“可上架可分发”状态。**

---

## 1. 距离 Google Play 上架还有多远

结论（先说）：**中到远距离（约 2-6 周）**，且存在 1 个高不确定性策略风险（Accessibility 合规）。

### P0（上架阻塞，必须先解决）

1. 发布签名与发行流程未完成
- release 未配置签名，当前 AAB/APK 不可直接用于正式分发。
- 缺少 keystore 管理、签名流程文档、版本发布流程（versionCode/versionName 策略）。

2. 隐私政策与 Data safety 未落地
- 代码侧未看到可对外发布的 privacy policy URL 与应用内入口。
- Play 要求所有应用在 Play Console 提供隐私政策链接，且应用内也要有隐私政策文本或链接；Data safety 必须准确填写。

3. AccessibilityService 合规材料与产品形态未完成
- 当前使用 AccessibilityService，且能力覆盖读取界面、手势、截图（`canTakeScreenshot=true`）。
- 服务 metadata 里未声明 `isAccessibilityTool`。
- 根据 Play Accessibility 政策，若非无障碍工具，必须做单独显著披露+明确同意+Console 声明；且“自主发起/规划/执行动作或决策”的自动化存在高审核风险。
- 这项是**最大不确定性**：你的产品定位如果是“通用自动化代理”，可能需要产品形态收敛才能通过。

4. 存储与数据处理策略不合规风险
- Manifest 仍含 `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE`（lint: ScopedStorage warning）。
- `AppSettingsStore` 仍尝试从外部存储读取 `api_key.txt`，不符合现代 Android 存储与最小权限原则。

### P1（强烈建议在提审前完成）

1. 安全基线强化
- API Key 目前以明文 SharedPreferences 存储，建议迁移到 `EncryptedSharedPreferences`/Keystore。
- 会话历史与可能敏感内容（屏幕文本、用户输入）需补充数据最小化、保留周期、删除机制与文档说明。

2. 发布质量与审核体验
- `release` 仍 `isMinifyEnabled = false`，建议开启 R8 并建立 keep rules。
- 增加 `dataExtractionRules` / `fullBackupContent`（lint: DataExtractionRules warning）。
- Launcher monochrome icon 缺失（lint warning，影响质量感知）。

3. 合规文档与审核材料
- 需要准备：权限用途说明、无障碍声明视频、测试账号/审核说明、隐私政策、数据删除说明（若涉及账号）。

### P2（上线后很快会追着你补）

- 依赖版本更新与技术债清理（GradleDependency/ObsoleteSdkInt 等）。
- 多个核心文件 > 400 行（可维护性债务，不是上架阻塞但会拖慢迭代）。
- 缺少崩溃监控、运行遥测、灰度发布流程。

---

## 2. 距离开源仓库发布还有多远

结论（先说）：**近距离（约 1-4 天）**，主要缺“开源门面与协作基础设施”。

### P0（开源发布阻塞）

1. 根目录缺少标准开源入口文件
- 缺 `README.md`（仓库首页说明）
- 缺 `LICENSE`

2. 缺少贡献与安全协作文档
- 缺 `CONTRIBUTING.md`
- 缺 `SECURITY.md`（漏洞提交流程）

### P1（建议同步补齐）

1. 缺 CI
- 当前没有 `.github/workflows/*`，建议至少加：
  - `build+test`（PR）
  - `lint`（PR）

2. 缺发布信息
- 建议补 `CHANGELOG.md` 或 release note 机制。
- 建议提供 `.env.example`（不含真实密钥）和最小可运行指引。

3. 第三方依赖与合规说明
- 建议补充第三方依赖/许可证汇总（NOTICE 或 docs 小节）。

---

## 3. 关键政策校验（2026-03-05 视角）

1. Target API 要求
- Play 当前公开要求（截至 2025-08-31）：新应用/更新需 target Android 15（API 35）或更高。
- 你当前 `targetSdk = 36`，**满足**该项门槛。

2. User Data / 隐私政策
- Play 要求所有应用提供可公开访问的隐私政策 URL，并在应用内提供隐私政策链接或文本。
- Data safety 必须准确、持续更新，且与实际数据处理一致。

3. AccessibilityService
- 若不是声明为 accessibility tool（`isAccessibilityTool=true`），需完成 Play Console 声明，并在应用内做单独显著披露与明确同意。
- 自动化用途限制严格；通用自主代理形态存在显著审核风险。

参考：
- https://support.google.com/googleplay/android-developer/answer/11926878
- https://support.google.com/googleplay/android-developer/answer/10144311
- https://support.google.com/googleplay/android-developer/answer/10964491

---

## 4. 推荐落地顺序（最短路径）

### 路径 A：先开源（低风险、快）
1. 补齐 `README + LICENSE + CONTRIBUTING + SECURITY`
2. 加最小 CI（build/test/lint）
3. 发布 v0.x 开源版本并收集外部反馈

预计：**1-4 天**

### 路径 B：再冲 Play（高风险、需产品策略）
1. 先处理 P0：签名、隐私政策、Data safety、Accessibility 声明与披露、存储权限整改
2. 内测轨道验证（Internal testing）+ 预审核材料
3. 首次提审，按反馈迭代

预计：**2-6 周**（受 Accessibility 审核策略影响较大）

---

## 5. 一句话结论

你现在已经有“强工程底座”，但离“可上架产品”主要卡在**合规与发布基础设施**；  
离“可开源发布”只差一套标准仓库门面与 CI，优先走开源更快更稳。
