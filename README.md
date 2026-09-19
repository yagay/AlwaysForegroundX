# MiniWindowGuard / 小窗守护

MiniWindowGuard 是一个 Android 31+ 的 Root + LSPosed Modern API 102 模块。核心作用域固定在 **System Framework / system_server（system）**，目标 App 本身不需要加入 LSPosed 作用域。

## 3.0.8

本版修复图标态和完全隐藏态会让目标 App 停止播放的问题。

- 目标 App 仍由真实 Task 承载，不使用截图或 WebView 伪装。
- 窗口态通过 `WindowContainerTransaction` 提交 MULTI_WINDOW / FREEFORM、bounds、focusable 和层级。
- 图标/隐藏态先切换内部容器状态，再把 Task 放到其他前台 App 后方。
- 图标/隐藏态通过 system_server 拦截受保护顶层 Activity 的 pause / invisible / clientVisible=false 路径，使其继续保持逻辑 Visible / Resumed。
- 真正的画面隐藏由 `SurfaceControl.Transaction.setAlpha(0)` 完成；恢复窗口或释放时恢复为 `alpha=1`。
- 对 OEM Shell 过渡增加 Surface alpha 延迟补写，避免系统重新提交 Surface 属性后画面意外出现。
- 只保护当前受管 Task 的顶层 Activity，避免干扰同一 App 内的普通页面切换。
- 保留 Root 的 Doze、待机桶、后台 AppOps、后台网络和 WakeLock 策略。
- 诊断日志新增 TaskFragment pause、ActivityRecord visibility、clientVisible 和 Surface alpha 记录。

## 容器状态

- **窗口**：真实 Task 以小窗 bounds 显示并可直接交互。
- **图标**：目标 Task 位于其他前台 App 后方，仍保持运行；真实 Surface 隐藏，只显示 MiniWindowGuard 悬浮图标。
- **完全隐藏**：与图标态相同地保持目标 App 运行，但连悬浮图标也不显示，通过常驻通知恢复。
- **释放**：恢复接管前的 bounds、windowing mode 和 Surface 显示状态。

## 作用域与权限

- **LSPosed**：只需要 `system / system_server`。
- **Root**：只授予 MiniWindowGuard，用于后台保活策略。
- **悬浮窗**：只用于 MiniWindowGuard 自己的控制栏和悬浮图标。
- 目标 App 不需要单独授予 Root 或加入 LSPosed 作用域。

## 构建

- compileSdk 37
- targetSdk 37
- minSdk 31
- Java 17
- Gradle 9.4.1
- `io.github.libxposed:api:102.0.0`
- `io.github.libxposed:service:102.0.0`

修改 system_server Hook 后，安装新版 APK 需要重启手机（或重启 system_server）才能加载新模块代码。
