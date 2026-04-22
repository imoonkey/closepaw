# Open Source Release

- README.md（产品介绍、安装指南、quick start）
- LICENSE（MIT 或 Apache 2.0）
- CONTRIBUTING.md + SECURITY.md
- `.env.example`（不含真实 key）
- GitHub Actions CI：PR 触发 build + lint + test
- Release workflow：tag 触发 → build signed APK → GitHub Release
- Release signing keystore 管理
