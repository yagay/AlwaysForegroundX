# MiniWindowGuard / 小窗守护

## 5.0.0 — OPlus FlexibleWindow Hook 重构

MiniWindowGuard 5.0.0 不再实现自己的窗口。

整个项目现在建立在 OxygenOS / ColorOS 原生小窗之上：

```
选择 App
  ↓
Activity 正常启动
  ↓
MiniWindowGuard 捕获 RESUMED Task
  ↓
Hook OPlus FlexibleWindow 支持判断
  ↓
OplusActivityTaskManager.toggleFlexibleWindow(...)
  ↓
OxygenOS 原生小窗
```

### 已删除

- VirtualDisplay
- TextureView
- 自定义 Overlay 小窗
- 自定义标题栏
- 自定义拖动/缩放
- 小窗宽度/高度设置
- Task 迁移到副屏
- 输入映射
- 固定 48% 画布
- NativeBounds / 自建 Freeform
- `shouldPauseActivity` 强制拦截
- `makeInvisible` 强制拦截
- `setVisible(false)` 强制拦截
- `setVisibleRequested(false)` 强制拦截
- `TaskFragment.startPausing` 强制拦截
- `WindowToken` 可见性强制 Hook

窗口生命周期、Surface、焦点、导航键、动画全部交还 OxygenOS。

## OPlus Hook

system_server 会动态 Hook：

- `com.android.server.wm.FlexibleWindowUtils`
- `com.android.server.wm.FlexibleTaskController`
- `com.android.server.wm.FlexibleWindowManagerService`
- `com.android.server.wm.OplusZoomWindowConfig`
- `android.app.OplusActivityTaskManager`

对当前选择/跟踪的 App：

- 强制通过 `isSupportFlexibleWindow`
- 绕过 FlexibleWindow 黑名单检查
- 兼容旧 ZoomWindow 支持检查
- 监听 OPlus Task appeared / changed / vanished
- 使用系统 `toggleFlexibleWindow` 切换真实 Task

不会全局修改其他 App 的小窗支持。

## 前台保护

前台保护现在完全依赖 **一加自己的小窗状态**。

只有以下状态才认为 App 需要保护：

1. Task 当前 bounds 与 maxBounds 不同；
2. OPlus TaskInfo 报告 `isInFlexibleEmbedded=true`；
3. Task 位于一加 `FloatHandleController` 的 FloatingList（贴边/最小化）。

只有这些状态下才会：

- `getPackageProcessState → TOP`
- `getUidProcessState → TOP`
- `isAppForeground → true`
- `hasResumedActivity → true`
- 阻止 removed-task / Athena / OPlus 清理链路中的特定 SIGKILL

如果一加把 App 真正恢复成普通全屏：

- 不再伪装 TOP；
- 不再伪装 Resumed；
- 不拦 pause；
- 不拦 invisible；
- 不拦导航键；
- 不拦普通全屏生命周期。

因此不会再出现“剧集进入全屏后覆盖屏幕、Home/返回/最近任务失效”的旧问题。

## Activity 切换

同一个被跟踪 Task 内如果启动新的 Activity，例如视频/剧集页面：

- MiniWindowGuard 只观察新的 `RESUMED`；
- OPlus 支持 Hook 继续把该包视为可使用系统小窗；
- 120ms 后重新检查并请求系统 FlexibleWindow；
- 不直接修改 Activity 生命周期或 Task bounds。

## 设置

5.0.0 只保留：

- 启用小窗守护
- 自动热重载
- 强制允许所选应用使用一加小窗
- 小窗进程状态保持 TOP
- 小窗视为存在 Resumed Activity
- 阻止一加清理链路强杀小窗
- 详细诊断

## 诊断

关键日志：

- `OPLUS_ENGINE_READY`
- `OPLUS_API_READY`
- `OPLUS_COMMAND_PENDING`
- `OPLUS_TASK_TRACKED`
- `OPLUS_ACTIVITY_CHANGED`
- `OPLUS_FLEX_TOGGLE`
- `OPLUS_FLEX_VERIFY`
- `OPLUS_TASK_INFO`
- `OPLUS_TASK_VANISHED`
- `OPLUS_SUPPORT_FORCE`
- `OPLUS_RESTRICTION_BYPASS`
- `AMS_PACKAGE_STATE`
- `AMS_UID_STATE`
- `AMS_FOREGROUND`
- `ATMS_HAS_RESUMED`
- `KILL_GUARD_BLOCK`

> 5.0.0 修改了 system_server 的 Bootstrap Hook 注册集合。安装后需要完整重启手机一次。之后只有 Engine 内部变化时仍可继续热重载。
