# AlwaysForeground / 始终前台

LSPosed Modern API 102 module. It makes selected target apps believe they are still foreground/interactive while they are backgrounded or the device is locked.

## v1.2.0 core fixes

- Fixed API 102 `Chain.getArgs()` usage (`List<Object>`, not `Object[]`).
- Configuration now uses the official libxposed service + RemotePreferences path.
- Removed exported custom `ConfigProvider`, 3-second polling and hidden `ActivityThread.currentApplication()` reflection.
- Safe default changed from strong mode to standard mode.
- AndroidX app-class hooks are installed from `onPackageReady()`.
- Added `INSTALLED`, `SKIPPED` and first-use `HIT` diagnostic logs.

## Modes

- 普通模式：屏幕交互、锁屏状态、当前进程 importance。
- 增强模式：再伪装后台限制、Doze/省电模式、UID importance、my memory state。
- 强力模式：再将 AndroidX `LifecycleRegistry.getCurrentState()` 伪装为 `RESUMED`。

The module does not suppress real Activity lifecycle callbacks and does not prevent Android/OEM process killing or freezing. Apps that directly pause in `onPause()`/`onStop()` may require app-specific hooks.

## Build

- compileSdk 37
- targetSdk 37
- Java 17
- AGP 9.2.0
- `io.github.libxposed:api:102.0.0`
- `io.github.libxposed:service:102.0.0`
