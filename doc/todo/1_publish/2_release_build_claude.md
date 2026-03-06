# 2. Release Build - 签名、混淆、版本管理

## 现状

- `isMinifyEnabled = false` — 无代码混淆，APK 体积大且源码暴露
- 无 `signingConfigs` — 无法生成签名发布包
- `versionCode = 1, versionName = "1.0"` — 无版本管理策略
- 无 ProGuard/R8 rules 文件
- 无 `shrinkResources`

## 任务

### 2.1 Release 签名配置

**操作**:

1. 生成 release keystore：
```bash
keytool -genkeypair -v -storetype PKCS12 \
  -keystore release.keystore -alias androidagent \
  -keyalg RSA -keysize 2048 -validity 10000
```

2. 在 `app/build.gradle.kts` 添加 signingConfigs：
```kotlin
signingConfigs {
    create("release") {
        storeFile = file(System.getenv("KEYSTORE_PATH") ?: "release.keystore")
        storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
        keyAlias = System.getenv("KEY_ALIAS") ?: "androidagent"
        keyPassword = System.getenv("KEY_PASSWORD") ?: ""
    }
}

buildTypes {
    release {
        signingConfig = signingConfigs.getByName("release")
        isMinifyEnabled = true
        isShrinkResources = true
        proguardFiles(
            getDefaultProguardFile("proguard-android-optimize.txt"),
            "proguard-rules.pro"
        )
    }
}
```

3. 把 keystore 密码放环境变量，**不要提交 keystore 到 git**
4. 确认 `.gitignore` 中已包含 `*.keystore`（目前是注释状态，需取消注释）

### 2.2 ProGuard/R8 规则

创建 `app/proguard-rules.pro`，至少包含：

```proguard
# Keep kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.moonkey.androidagent.**$$serializer { *; }
-keepclassmembers class com.moonkey.androidagent.** {
    *** Companion;
}
-keepclasseswithmembers class com.moonkey.androidagent.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep OpenAI SDK (uses reflection)
-keep class com.openai.** { *; }

# Keep Shizuku AIDL
-keep class dev.rikka.shizuku.** { *; }

# Keep Leap SDK
-keep class ai.liquid.leap.** { *; }

# Keep HiddenApiBypass
-keep class org.lsposed.hiddenapibypass.** { *; }

# Keep accessibility service
-keep class com.moonkey.androidagent.platform.accessibility.** { *; }
```

**重要**: 开启 R8 后必须做完整功能测试，accessibility service + LLM 调用 + Shizuku 都可能因反射被 strip 而崩溃。

### 2.3 版本管理策略

当前 `versionCode = 1, versionName = "1.0"` 太粗糙。

推荐 SemVer：
- `versionName` = `MAJOR.MINOR.PATCH`（如 `0.1.0` 表示首个 alpha）
- `versionCode` = 自增整数，Play Store 用来判断升级

初始发布建议从 `0.1.0` 开始（表示 alpha），别用 `1.0`（暗示稳定版）。

可选：把版本提取到 `gradle.properties` 便于 CI 管理：
```properties
VERSION_NAME=0.1.0
VERSION_CODE=1
```

### 2.4 Build Variant 配置（可选）

如果需要区分开源版和完整版：
```kotlin
flavorDimensions += "distribution"
productFlavors {
    create("oss") {
        dimension = "distribution"
        applicationIdSuffix = ".oss"
        // 排除 Leap SDK 等闭源依赖
    }
    create("full") {
        dimension = "distribution"
    }
}
```

## 验收标准

- [ ] `./gradlew assembleRelease` 能生成签名 APK
- [ ] APK 经过 R8 混淆，体积明显小于 debug 版
- [ ] 版本号从 `0.1.0` 开始
- [ ] keystore 不在 git 历史中
- [ ] R8 开启后所有功能正常（accessibility、LLM、Shizuku）
