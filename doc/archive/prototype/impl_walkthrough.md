# Android Agent MVP - Developer Walkthrough

Welcome! Since you're an experienced dev new to Android, this guide connects the dots between the code we wrote and how Android works.

## 🏗 High-Level Architecture

The core of this app is an **Android Accessibility Service**. Unlike a standard app that runs in the foreground, an Accessibility Service runs in the background and can inspect the UI of *other* apps, screen-read content, and inject gestures (taps/swipes).

**The Mental Model:**
1.  **Perception (`Sanitizer.kt`)**: We snapshot the current screen's accessibility tree, filter out noise (layouts, invisible items), and produce a lean JSON representation.
2.  **Reasoning (`LLMClient.kt`)**: We send this JSON + User Goal to GPT-4o. The prompt (DSL) asks for a JSON action (e.g., `{"action": "tap", "target": {"by": "index", "value": 5}}`).
3.  **Action (`AgentService.kt`)**: We parse the LLM's response and execute it using Android's `dispatchGesture` or `NodeInfo.performAction`.

## 📂 Project Structure Tour

Here are the key files you need to care about in `app/src/main/kotlin/com/moonkey/androidagent/`:

### 1. `AgentService.kt` (The Kernel)
This is the "main loop".
-   **Extends**: `AccessibilityService` (Android framework class).
-   **Lifecycle**: It's started by the system when the user toggles it ON in Settings. It runs independently of the `MainActivity`.
-   **Key Methods**:
    -   `runAgent(goal)`: Starts a coroutine loop.
    -   `rootInActiveWindow`: Android API that gives us the root of the current UI tree.
    -   `dispatchGesture()`: How we programmatically tap/swipe.
    -   `performGlobalAction()`: How we press Back/Home buttons.

### 2. `Sanitizer.kt` (The Eyes)
Translates the raw, verbose Android `AccessibilityNodeInfo` tree into something an LLM can digest.
-   **Logic**: DFS traversal of the tree.
-   **Heuristics**: We only keep nodes that are "interesting" (clickable, editable, or have text).
-   **Output**: A list of `Element` objects, converted to JSON.

### 3. `LLMClient.kt` (The Brain)
A simple wrapper around the official OpenAI Java SDK.
-   **Dependencies**: Uses `com.openai:openai-java`.
-   **System Prompt**: Defined here. This is where we tell the LLM available actions and JSON format constraints.

### 4. `MainActivity.kt` (The UI)
Standard Android Activity (screen).
-   **Purpose**: Just a form to enter the API Key/Goal and the "Start" button.
-   **Mechanism**: It talks to `AgentService` via a static instance (`AgentService.instance`). *Note: This is a hacky MVP pattern; in a production app, you'd use `bindService` or an EventBus.*

---

## 🤖 Android Concepts for Newcomers

### The Manifest (`AndroidManifest.xml`)
The "registry" of your app.
-   We declared `<service android:name=".AgentService" ...>` here so the system knows an Accessibility Service exists.
-   `android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"` ensures only the system can start this service (security).

### Capabilities Config (`res/xml/agent_accessibility_config.xml`)
Configuration file linked in the Manifest.
-   `canRetrieveWindowContent="true"`: Allows us to see the view tree (essential).
-   `canPerformGestures="true"`: Allows us to tap/swipe.

### Gradle (`build.gradle.kts`)
The build system (like Maven/Cargo/npm).
-   **`app/build.gradle.kts`**: Defines dependencies (OpenAI SDK) and Android version targeting.
-   We added a `packaging { resources { excludes ... } }` block to fix a conflict with the OpenAI SDK's transitive dependencies.

---

## 🛠 Development Workflow

### 1. Building & Installing
You don't need Android Studio GUI to build. You can use the terminal:

```bash
# Build the APK (Debug variant)
export GRADLE_HOME="$HOME/gradle-8.5"
~/gradle-8.5/bin/gradle clean assembleDebug

# Install to Emulator (must be running)
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 2. Permissions (The Annoying Part)
Every time you reinstall the app (if the signature changes or you uninstalled it), Android might disable the Accessibility Service.
-   **Fast Enable**:
    ```bash
    adb shell settings put secure enabled_accessibility_services com.moonkey.androidagent/.AgentService
    adb shell settings put secure accessibility_enabled 1
    ```

### 3. viewing Logs
Android logging is done via `Logcat`.
-   **Command**: `adb logcat -s AgentService LLMClient`
-   This filters logs to only show our tags.

## 🚀 Next Steps (from `future_features.md`)

-   **Voice Input**: You'd implement `SpeechRecognizer` in `MainActivity` or the Service.
-   **Overlay/Glow**: You can draw over other apps using a `WindowManager` overlay (requires `SYSTEM_ALERT_WINDOW` permission) to show "AI is thinking" visuals on top of the target app.
-   **Architecture**: Move `AgentService.instance` singleton access to a proper bounded service connection or use a Repository pattern for shared state.
