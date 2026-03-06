# 1. Legal - 开源协议与第三方归集

## 现状

- 无 LICENSE 文件 - 法律上代码默认 all rights reserved，别人不能用
- 无第三方依赖 license 归集
- 无 NOTICE 文件
- 无 CLA/DCO（贡献者协议）

## 任务

### 1.1 选择开源协议

**推荐**: Apache 2.0

理由：
- Android 生态主流（Jetpack 全家桶都是 Apache 2.0）
- 允许商业使用，要求保留版权声明
- 有专利保护条款
- Shizuku、HiddenApiBypass 等依赖也是 Apache 2.0，兼容

备选：
- MIT - 更宽松，但无专利保护
- GPL-3.0 - 强 copyleft，会限制商业整合

**操作**: 在根目录创建 `LICENSE` 文件，内容为 Apache 2.0 全文，顶部填入年份和版权人。

### 1.2 第三方依赖 License 归集

当前依赖及其协议：

| 依赖 | 协议 | 归集要求 |
|------|------|----------|
| androidx.* | Apache 2.0 | 保留 NOTICE |
| kotlinx-coroutines | Apache 2.0 | 保留 NOTICE |
| kotlinx-serialization | Apache 2.0 | 保留 NOTICE |
| Jetpack Compose | Apache 2.0 | 保留 NOTICE |
| openai-java (4.14.0) | Apache 2.0 | 保留 NOTICE |
| Shizuku (13.1.5) | Apache 2.0 | 保留 NOTICE |
| HiddenApiBypass (6.1) | Apache 2.0 | 保留 NOTICE |
| Leap SDK (0.9.2) | **需确认** | 可能是 proprietary |

**操作**:
1. 确认 `ai.liquid.leap:leap-sdk` 的 license（如果是 proprietary，开源时需要说明或替换）
2. 创建 `NOTICE` 文件，列出所有第三方依赖及其 license
3. 可选：使用 Gradle 插件 (`com.jaredsburrows:gradle-license-plugin`) 自动生成

### 1.3 贡献者协议（开源发布时）

小项目初期不需要 CLA，在 CONTRIBUTING.md 中声明：
> By submitting a PR, you agree that your contribution is licensed under Apache 2.0.

## 风险点

- **Leap SDK** 如果是闭源 SDK，开源发布时需要：要么替换为开源替代，要么声明为可选依赖
- 如果未来要从 Apache 2.0 切换到其他协议，所有历史贡献者都需要同意

## 验收标准

- [ ] 根目录有 `LICENSE` 文件
- [ ] 根目录有 `NOTICE` 文件，列出所有第三方依赖
- [ ] 所有依赖的 license 已确认兼容
- [ ] Leap SDK 的 license 状态已明确
