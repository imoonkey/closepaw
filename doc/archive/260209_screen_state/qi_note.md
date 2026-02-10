**Status: Implemented (2026-02-09)**

我的agent现在是主要用Accessibility Tree的information convert成JSON format,同时做了sanitization来表示的。但是这有个问题,会因为有些时候有的app对Accessibility不是特别友好,这时候我就没有办法操作这个屏幕。 所以我想在我的code层面做一个改变,就是screen state不在在code的层面不再把accessibility information作为一等公民,然后screenshot image作为二等公民,而是把它们两个做成并列,然后要求每次screen state至少包含一个东西。这样我可以比较直接的切换,我是想要accessibility tree,还是想要screen state,还是想要两个一起,这样方便我在debug-run的时候很容易的config这三种模式。

**Implementation summary:**
- `PerceptionConfig` sealed class (`AccessibilityOnly`, `ScreenshotOnly`, `Hybrid`)
- `ScreenSnapshot.elements` nullable; `hasAccessibility` / `hasScreenshot` convenience properties
- `SessionConfig.perceptionConfig` replaces `enableScreenshotInput`, `screenshotMaxDimension`, `screenshotJpegQuality`
- `AccessibilityPlatform.captureScreen()` conditionally captures based on config
- Settings UI: 3-option perception mode selector
- `AppSettingsStore`: `perception_mode` string with migration from `screenshot_input`