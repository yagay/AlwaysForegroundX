# MiniWindowGuard / 小窗守护

## 4.5.2

4.5.2 根据 OxygenOS 16 实机诊断加入 **NativeBounds** 模式。

实机已经确认：

- 标准 `WINDOWING_MODE_FREEFORM` 会被 OxygenOS 拒绝，Task 仍保持 fullscreen；
- 但同一个 display 0 上，Task 的真实 bounds 可以成功变成小窗尺寸；
- `setAlwaysOnTop(true)` 也会生效；
- 例如红果 Task 17044 已实际从 1272×2772 改成 774×1330；
- 4.5.1 只是因为检测到 windowingMode 仍是 fullscreen，就误判失败并回退到 VirtualDisplay。

4.5.2 不再强制要求 FREEFORM。

Native 主引擎现在接受两种后端：

1. `freeform`
   - ROM 接受标准 Android FREEFORM；
2. `bounded-fullscreen`
   - ROM 保持 fullscreen mode；
   - 但 Task bounds 已真正变小；
   - Task 仍留在原 display；
   - SurfaceView / MediaCodec / WebView / GL / 输入焦点继续属于原系统 Task。

只有连真实 Task bounds 都无法修改时，才回退 VirtualDisplay。

### 原生控制层

NativeBounds 新增轻量控制层：

- 返回
- 拖动标题栏移动真实 Task
- 缩小
- 隐藏
- 关闭/恢复原 Task
- 右下角拖动改变真实 Task bounds
- 最小化/隐藏后显示恢复按钮

控制层只是 TYPE_APPLICATION_OVERLAY 按钮和手柄，不包含 App 画面。

App 内容仍然由系统 WindowManager / SurfaceFlinger 直接显示，不经过 TextureView。

### 关键日志

- `NATIVE_BOUNDS_APPLIED`
- `NATIVE_BOUNDS_CHANGED`
- `NATIVE_BOUNDS_CHANGE_FAILED`
- `NATIVE_OVERLAY_READY`
- `NATIVE_OVERLAY_FAILED`
- `NATIVE_FREEFORM_FAILED`
- `NATIVE_FALLBACK_TO_VD`

如果看到：

```
NATIVE_BOUNDS_APPLIED backend=bounded-fullscreen
NATIVE_OVERLAY_READY
```

说明已经完全走 NativeBounds，不应该再创建 `MiniWindowGuard-<taskId>` VirtualDisplay。

## 4.5.1

4.5.1 修复 4.5.0 中目标 App 启动过快时 Native Freeform 没有真正接管的问题。

根因是：

- App 页面先写入 `container_command_seq/package/state`；
- 然后立即 `startActivity()`；
- Engine 原来每 180ms 才轮询一次命令；
- 某些 App 在这 180ms 内已经进入 RESUMED；
- ActivityRecord.setState Hook 检查时 `pendingPackage` 还没更新，因此错过 capture。

现在 `wantsPackage()` 会同步读取尚未被轮询线程消费的新命令：

- command seq 不等于 Engine 已消费 seq；
- package 与当前 RESUMED Activity 一致；
- state 不是 RELEASED；

满足后直接 capture，不需要等待轮询。

这个修复同时应用于：

- Native Freeform 主引擎；
- VirtualDisplay 后备引擎。

这样既消除启动竞态，也不会让已经消费过的旧命令永久生效。

## 4.5.0

4.5.0 新增 **Native Freeform / Task Bounds 主引擎**。

这次不再把“系统小窗为什么正常、VirtualDisplay 为什么会遇到视频 Surface 问题”继续当成尺寸问题修补，而是直接改变显示架构。

### 默认：Native Freeform

默认开启：

`优先使用系统原生小窗（推荐）`

Native 模式：

- Task 保持在原来的 display；
- 不创建新的 VirtualDisplay；
- 不创建 TextureView 作为内容宿主；
- 不把视频 SurfaceView / MediaCodec Surface 跨 display 搬运；
- 直接请求 Task 使用 FREEFORM windowing mode；
- 直接调整 Task bounds；
- WindowManager / SurfaceFlinger / InputDispatcher 继续维护原来的窗口和 Surface 树；
- 视频、WebView、GL、SurfaceView 的行为更接近系统自带小窗。

AOSP 的 desktop/freeform 也是以 Task windowing mode、bounds 和 task surface 为核心，而不是把 Task 迁移到另一个 VirtualDisplay。

### 自动后备

Native 引擎会验证：

- windowing mode 是否进入 freeform / multi-window；
- Task bounds 是否真的变成请求的小窗范围。

如果 ROM 拒绝 FREEFORM，或者 bounds 没真正生效，会记录：

`NATIVE_FREEFORM_FAILED`

然后直接把同一个 ActivityRecord 交给原来的 VirtualDisplay 引擎：

`NATIVE_FALLBACK_TO_VD`

不需要用户重新打开目标 App。

### 原生模式状态

主要日志：

- `NATIVE_ENGINE_READY`
- `NATIVE_TASK_CAPTURED`
- `NATIVE_FREEFORM_APPLIED`
- `NATIVE_FREEFORM_FAILED`
- `NATIVE_FALLBACK_TO_VD`
- `NATIVE_WINDOW_READY`
- `NATIVE_FOCUS`
- `NATIVE_MOVE_BACK`
- `NATIVE_TASK_RELEASED`

### VirtualDisplay 兼容模式

关闭：

`优先使用系统原生小窗（推荐）`

然后点击：

`立即重新加载 System Engine`

即可继续使用 4.4.x 的 VirtualDisplay / TextureView 引擎。

VirtualDisplay 的固定内部画布、48% 兼容比例和外部最小尺寸设置继续保留，作为后备方案。

### 始终前台

Native Freeform 只替换显示引擎，不删除现有 system_server 前台保护。

继续保留：

- 进程状态保持 TOP；
- hasResumedActivity；
- Activity pause / invisible 拦截；
- 最近任务/OEM 清理链路保护。

因此结构变成：

```
显示：
Native Task / Freeform
        ↓
系统 WindowManager / SurfaceFlinger

前台保护：
MiniWindowGuard system_server hooks
```

### 热重载

4.5.0 没有修改 LSPosed Bootstrap Hook 注册接口。

如果 4.3.x 以后已经完成过一次 Bootstrap 重启：

- 安装 4.5.0 后不需要再次重启手机；
- 没有活动小窗时自动热重载；
- 或点击“立即重新加载 System Engine”。

### 设计目标

Native 模式的重点不是复刻某个 OEM 私有 API，而是优先使用 Android Task/freeform 体系。

这样可以避免 VirtualDisplay 路线中的：

- displayId 切换；
- 视频 Surface 首次创建尺寸敏感；
- TextureView 与内部画布尺寸不同步；
- 副屏 focused window 竞态；
- 输入 displayId 映射；
- VirtualDisplay resize 触发大量 configuration change。

VirtualDisplay 仍然保留，因为部分 ROM 可能完全拒绝标准 freeform。

## 开源架构参考

设计过程中研究过：

- Android AOSP Task / WindowContainerTransaction / Desktop Windowing
- YAMF² / YAMFsquared
- YAMF
- FreeformShell

这些项目仅用于理解公开架构和系统行为。MiniWindowGuard 当前实现为本项目重新实现。

本仓库使用 GPLv3。详见 `LICENSE` 和 `THIRD_PARTY_NOTICES.md`。
