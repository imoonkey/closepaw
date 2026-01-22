# Accessibility Service Persistence

## Problem Description

When users close the app in the background (via swiping from Recent Apps, system killing the process, or Force Stop), the Accessibility Service gets disabled and requires manual re-enabling.

## Standard Android Behavior (Verified)

Based on official documentation and community research:

| Action | A11y Service Setting | A11y Service Running | Notes |
|--------|---------------------|---------------------|-------|
| **Swipe from Recent Apps** | ✅ Stays enabled | ✅ Usually continues (may restart) | Standard behavior - only kills activity/task |
| **System kills process** (low memory) | ✅ Stays enabled | ✅ System rebinds automatically | Normal lifecycle |
| **Force Stop** (Settings → Apps → Force Stop) | ❌ Disabled | ❌ Stopped | Requires manual re-enable |
| **OEM aggressive kill** | ⚠️ Varies | ⚠️ Varies | Some OEMs treat swipe as Force Stop |

**Key Point**: In standard Android, swiping from Recent Apps does NOT disable Accessibility Service. It only removes the task from recents and may kill the process, but the system will rebind to the service. Force Stop is the only standard action that disables it.

**OEM Exception**: Some manufacturers (Xiaomi, Huawei, Samsung, Nubia, etc.) have aggressive background management that may treat "swipe from recents" similarly to Force Stop.

## Root Cause

Android has special lifecycle management for AccessibilityService:

1. **System-bound service**: Unlike regular Services, it's not started via `startService()`. The system binds to it after the user enables it in Settings.
2. **Force Stop disables the service**: When Force Stop is triggered, the app enters "stopped" state, all services are disabled, and it won't respond to broadcasts until user explicitly launches it.
3. **Cannot programmatically re-enable**: For security reasons, apps cannot automatically re-enable AccessibilityService - user must do it manually.
4. **OEM aggressive background management**: Some manufacturers treat swipe from recents as Force Stop.

## Solutions

### 1. Use Foreground Service (Reduce System Kill Probability)

While Foreground Service cannot prevent Force Stop, it reduces the probability of the system automatically killing the app due to memory pressure.

```kotlin
class AgentService : AccessibilityService() {
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        startForegroundServiceCompat()
    }
    
    private fun startForegroundServiceCompat() {
        val channelId = "agent_service_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Agent Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
        
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Android Agent")
            .setContentText("Agent is running")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
            
        startForeground(1, notification)
    }
}
```

### 2. Request Battery Optimization Exemption

Guide users to add the app to the battery optimization whitelist:

```kotlin
fun requestIgnoreBatteryOptimization(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val packageName = context.packageName
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            context.startActivity(intent)
        }
    }
}

fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }
    return true
}
```

Add permission in Manifest:
```xml
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"/>
```

### 3. Detect Service Status and Prompt User

```kotlin
object AccessibilityServiceHelper {
    
    /**
     * Check if Accessibility Service is enabled
     */
    fun isAccessibilityServiceEnabled(context: Context, serviceClass: Class<*>): Boolean {
        val serviceName = "${context.packageName}/${serviceClass.canonicalName}"
        
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        
        return enabledServices.split(':').any { 
            it.equals(serviceName, ignoreCase = true) 
        }
    }
    
    /**
     * Open Accessibility Settings
     */
    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }
    
    /**
     * Check and prompt on app launch
     */
    fun checkAndPromptIfNeeded(activity: Activity, serviceClass: Class<*>) {
        if (!isAccessibilityServiceEnabled(activity, serviceClass)) {
            AlertDialog.Builder(activity)
                .setTitle("Accessibility Service Required")
                .setMessage("Please enable the accessibility service to use full functionality")
                .setPositiveButton("Open Settings") { _, _ ->
                    openAccessibilitySettings(activity)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
}
```

### 4. OEM-Specific Settings Guide

Different manufacturers have different background management policies:

```kotlin
object OemSettingsHelper {
    
    fun getManufacturer(): String = Build.MANUFACTURER.lowercase()
    
    fun getOemSpecificInstructions(): OemInstructions? {
        return when (getManufacturer()) {
            "xiaomi", "redmi" -> XiaomiInstructions
            "huawei", "honor" -> HuaweiInstructions
            "samsung" -> SamsungInstructions
            "oppo" -> OppoInstructions
            "vivo" -> VivoInstructions
            "oneplus" -> OnePlusInstructions
            "nubia", "zte" -> NubiaInstructions
            else -> null
        }
    }
    
    /**
     * Try to open OEM-specific auto-start settings
     */
    fun openAutoStartSettings(context: Context): Boolean {
        val intents = listOf(
            // Xiaomi
            Intent().setComponent(ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            )),
            // Huawei
            Intent().setComponent(ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            )),
            // Samsung
            Intent().setComponent(ComponentName(
                "com.samsung.android.lool",
                "com.samsung.android.sm.battery.ui.BatteryActivity"
            )),
            // OPPO
            Intent().setComponent(ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity"
            )),
            // Vivo
            Intent().setComponent(ComponentName(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
            )),
            // Nubia
            Intent().setComponent(ComponentName(
                "com.obric.securitymanager",
                "com.obric.securitymanager.MainActivity"
            ))
        )
        
        for (intent in intents) {
            try {
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
                return true
            } catch (e: Exception) {
                // Try next
            }
        }
        return false
    }
}

sealed class OemInstructions(
    val autoStartPath: String,
    val batteryOptimizationPath: String,
    val lockAppPath: String
)

object XiaomiInstructions : OemInstructions(
    autoStartPath = "Settings → Apps → Manage apps → [App] → Autostart",
    batteryOptimizationPath = "Settings → Battery → App battery saver → [App] → No restrictions",
    lockAppPath = "Long press app in Recent Apps → Lock"
)

object HuaweiInstructions : OemInstructions(
    autoStartPath = "Settings → Apps → Startup manager → [App] → Allow auto-launch",
    batteryOptimizationPath = "Settings → Battery → App launch → [App] → Manage manually",
    lockAppPath = "Pull down app card in Recent Apps to lock"
)

object SamsungInstructions : OemInstructions(
    autoStartPath = "Settings → Battery → Background usage limits → Never sleeping apps → Add app",
    batteryOptimizationPath = "Settings → Battery → Battery optimization → [App] → Don't optimize",
    lockAppPath = "N/A"
)

object OppoInstructions : OemInstructions(
    autoStartPath = "Settings → App management → App list → [App] → Allow auto-startup",
    batteryOptimizationPath = "Settings → Battery → Power saver → [App] → Allow background running",
    lockAppPath = "Pull down to lock in Recent Apps"
)

object VivoInstructions : OemInstructions(
    autoStartPath = "iManager → App manager → Permissions → Autostart → Allow",
    batteryOptimizationPath = "Settings → Battery → Background power consumption → Allow",
    lockAppPath = "Pull down to lock in Recent Apps"
)

object OnePlusInstructions : OemInstructions(
    autoStartPath = "Settings → Battery → Battery optimization → [App] → Don't optimize",
    batteryOptimizationPath = "Settings → Apps → [App] → Battery → Unrestricted",
    lockAppPath = "Long press to lock in Recent Apps"
)

object NubiaInstructions : OemInstructions(
    autoStartPath = "Security Center → App management → Auto-start → Allow",
    batteryOptimizationPath = "Settings → Battery → App power management → [App] → Unrestricted",
    lockAppPath = "Long press app in Recent Apps → Lock (if supported)"
)
```

### 5. Complete Onboarding Flow

```kotlin
class OnboardingHelper(private val activity: Activity) {
    
    fun showSetupGuide() {
        val steps = mutableListOf<SetupStep>()
        
        // Step 1: Check accessibility service
        if (!AccessibilityServiceHelper.isAccessibilityServiceEnabled(
                activity, 
                AgentService::class.java
            )) {
            steps.add(SetupStep.ACCESSIBILITY_SERVICE)
        }
        
        // Step 2: Check battery optimization
        if (!isIgnoringBatteryOptimizations(activity)) {
            steps.add(SetupStep.BATTERY_OPTIMIZATION)
        }
        
        // Step 3: Check OEM-specific settings
        if (OemSettingsHelper.getOemSpecificInstructions() != null) {
            steps.add(SetupStep.OEM_SETTINGS)
        }
        
        if (steps.isNotEmpty()) {
            showSetupDialog(steps)
        }
    }
    
    private fun showSetupDialog(steps: List<SetupStep>) {
        // Show setup guide dialog
        // Guide user to complete all necessary settings
    }
}

enum class SetupStep {
    ACCESSIBILITY_SERVICE,
    BATTERY_OPTIMIZATION,
    OEM_SETTINGS
}
```

### 6. Run Service in Separate Process (Optional)

Running AccessibilityService in a separate process can isolate crash impact:

```xml
<service
    android:name=".service.AgentService"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"
    android:process=":accessibility"
    android:exported="false">
    <intent-filter>
        <action android:name="android.accessibilityservice.AccessibilityService"/>
    </intent-filter>
    <meta-data
        android:name="android.accessibilityservice"
        android:resource="@xml/accessibility_service_config"/>
</service>
```

### 7. Exception Handling and Service Stability

```kotlin
class AgentService : AccessibilityService() {
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            handleEvent(event)
        } catch (e: Exception) {
            // Catch all exceptions to prevent service crash
            Log.e(TAG, "Error handling accessibility event", e)
        }
    }
    
    override fun onInterrupt() {
        // Handle service interruption
    }
}
```

## Best Practices Summary

| Measure | Effect | Limitation |
|---------|--------|------------|
| Foreground Service | Reduces system auto-kill probability | Cannot prevent Force Stop |
| Battery optimization whitelist | Reduces background restrictions | Requires user action |
| OEM auto-start settings | Solves OEM-specific issues | Different UI per manufacturer |
| Separate process | Isolates crash impact | Increases complexity |
| Status detection + prompt | Quick service recovery | Still requires user action |

## Core Conclusion

**Cannot completely prevent service from being disabled after Force Stop** - this is Android's security design. What we can do:

1. **Prevention**: Use Foreground Service + battery optimization whitelist + OEM settings to reduce system/OEM auto-kill probability
2. **Quick Recovery**: Detect service status and guide user to quickly re-enable
3. **User Education**: Guide users through all necessary settings in onboarding flow

## ADB Debugging Commands

```bash
# Check if A11y is enabled
adb shell settings get secure enabled_accessibility_services

# Check if app is in battery whitelist
adb shell dumpsys deviceidle whitelist | grep <package_name>

# Check if app is in stopped state
adb shell dumpsys package <package_name> | grep "stopped="

# Check accessibility service status
adb shell dumpsys accessibility | grep -E "(Enabled services|Bound services|Crashed services)"
```

## References

- [Don't Kill My App](https://dontkillmyapp.com/) - OEM background restriction details
- [Android AccessibilityService Documentation](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)
- [Stack Overflow: Accessibility service does not restart](https://stackoverflow.com/questions/67410929/accessibility-service-does-not-restart-when-manually-re-enabled-after-app-force)

## Implementation Plan

### Phase 1: Basic Protection
- [ ] Add Foreground Service with notification
- [ ] Implement battery optimization whitelist request (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`)
- [ ] Add service status detection logic

### Phase 2: User Guidance
- [ ] Implement OEM detection and settings guide
- [ ] Create onboarding setup wizard UI to guide users through:
  - Enable accessibility service
  - Disable battery optimization
  - Enable auto-start (OEM-specific)
- [ ] Check A11y status on every app launch, prompt user if disabled

### Phase 3: Stability
- [ ] Add global exception handling
- [ ] Consider using separate process
- [ ] Add logging and error reporting
