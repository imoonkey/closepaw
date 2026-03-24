# 4. Open Source Readiness - README、社区、CI/CD

## 现状

- 无根目录 README.md（CLAUDE.md 是给 AI agent 的，不是给人看的）
- 无 CONTRIBUTING.md
- 无 GitHub Actions CI/CD
- 无 issue/PR templates
- 无 CODEOWNERS
- `.gitignore` 比较完善

## 任务

### 4.1 README.md

根目录创建 `README.md`，结构：

```markdown
# Android Agent

AI-powered Android automation — give natural language instructions,
the agent operates your phone.

[screenshot or demo gif here]

## What it does
- Natural language task execution on Android
- Screen perception via accessibility service
- Multiple LLM backends (OpenAI, OpenRouter, local via Leap)
- Smart Capsule overlay for seamless interaction

## Quick Start
### Prerequisites
- Android device/emulator (API 31+)
- One of: OpenAI API key / OpenRouter key / Novita key

### Build & Install
git clone ...
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk

### Setup
1. Open app → Settings → enter API key
2. Enable Accessibility Service in Android Settings
3. Grant overlay permission
4. Type a task and go!

## Architecture
[brief diagram or link to doc/main/]

## Contributing
See [CONTRIBUTING.md](CONTRIBUTING.md)

## License
Apache 2.0 — see [LICENSE](LICENSE)
```

**关键**: 放一个 demo GIF 或截图，这是开源项目吸引力的决定因素。

### 4.2 CONTRIBUTING.md

```markdown
# Contributing

## Development Setup
1. Clone repo
2. Open in Android Studio
3. Sync Gradle
4. Connect device/emulator (API 31+)
5. Run app

## Code Style
- Kotlin idioms, sealed classes, data classes
- val over var, null safety (no !!)
- Max 400 lines per file
- Conventional commits: feat:, fix:, refactor:, docs:, test:

## Pull Request Process
1. Fork & branch from main
2. Make changes with tests
3. Run ./gradlew clean assembleDebug lint test
4. Open PR with description of what & why

## Reporting Issues
Use GitHub Issues. Include:
- Device/emulator info
- Android version
- Steps to reproduce
- Logs (./scripts/logs.sh)

By submitting a PR, you agree your contribution is licensed under Apache 2.0.
```

### 4.3 GitHub Actions CI

创建 `.github/workflows/ci.yml`：

```yaml
name: CI

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 17
      - uses: gradle/actions/setup-gradle@v4
      - run: ./gradlew assembleDebug
      - run: ./gradlew test
      - run: ./gradlew lint
```

注意：需要确认 Leap SDK 的 maven repo 在 CI 环境是否可访问（可能需要 token）。
如果 Leap SDK 需要认证，考虑：
- CI 中用 secret 配置 maven credentials
- 或者创建一个不含 Leap SDK 的 oss flavor

### 4.4 Issue & PR Templates

`.github/ISSUE_TEMPLATE/bug_report.md`:
```markdown
---
name: Bug Report
about: Report a bug
---

**Device**:
**Android Version**:
**App Version**:

**Steps to Reproduce**:
1.

**Expected**:
**Actual**:

**Logs** (if available):
```

`.github/pull_request_template.md`:
```markdown
## What
<!-- Brief description -->

## Why
<!-- Motivation -->

## Testing
<!-- How was this tested? -->
```

### 4.5 其他文件

- `SECURITY.md` — 说明如何报告安全漏洞（不要用 public issue）
- `.github/CODEOWNERS` — 指定 code review owner
- `.env.example` — 列出需要的环境变量（不含实际值）

```env
# .env.example
OPENAI_API_KEY=sk-your-key-here
OPENROUTER_API_KEY=sk-or-your-key-here
# Optional
NOVITA_API_KEY=
```

## 验收标准

- [ ] 根目录有可读的 README.md（含截图/GIF）
- [ ] 有 CONTRIBUTING.md
- [ ] GitHub Actions CI 跑通（build + test + lint）
- [ ] 有 issue template 和 PR template
- [ ] 有 .env.example
- [ ] 有 SECURITY.md
