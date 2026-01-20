# Android Config + Build Review

## Summary

AndroidManifest, permissions, accessibility configuration, build setup, and resource leftovers from the pre-Compose UI.

## High-risk issues (must-fix)

### External storage API key handling is insecure and likely broken on targetSdk 35
- Why it matters: `READ/WRITE_EXTERNAL_STORAGE` is deprecated and ignored on modern Android. Loading API keys from shared storage both fails on new OS versions and exposes secrets to other apps.
- Location: `app/src/main/AndroidManifest.xml` (storage permissions) and `app/src/main/kotlin/com/moonkey/androidagent/MainActivity.kt` (`loadApiKeyFromFile()`).
- Fix: move API key storage to app-private storage or encrypted storage (e.g., EncryptedSharedPreferences/Keystore), and remove external storage permissions.

## Medium issues (should-fix)

### Accessibility service is exported without a clear justification
- Why it matters: exported services are visible to other apps. While binding is protected by `BIND_ACCESSIBILITY_SERVICE`, it’s safer to keep the service non-exported if possible.
- Location: `app/src/main/AndroidManifest.xml` — `<service android:name=".AgentService" android:exported="true">`.
- Fix: verify whether the system still binds when `exported="false"`; if yes, set it to false.

## Low-risk suggestions (nice-to-have)

### Unused XML layout remains after Compose migration
- Why it matters: dead resources can confuse maintainers and increase maintenance burden.
- Location: `app/src/main/res/layout/activity_main.xml`.
- Fix: remove the layout or keep it only if there is a planned fallback path.
