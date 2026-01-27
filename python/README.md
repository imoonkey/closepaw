## Android Agent Python Playground

This package mirrors the Android app's core agent loop for faster iteration.
It uses ADB for screen capture and actions.

### Requirements
- Python >= 3.13
- `adb` available on PATH
- Device connected or emulator running
- `OPENAI_API_KEY` set (for OpenAI backend)

### Setup (uv)
```bash
cd python
uv venv
uv pip install -e .
```

### Run
```bash
android-agent-playground run --goal "Open Settings and enable Wi-Fi"
```

Optional flags:
```bash
android-agent-playground run \
  --goal "Open Gmail and search for test" \
  --serial emulator-5554 \
  --model gpt-5.2 \
  --max-turns 25 \
  --debug
```

### Notes
- The a11y tree comes from `uiautomator dump`.
- Actions are executed with `adb shell input ...`.
- Behavior matches Kotlin logic where possible, with documented gaps.
