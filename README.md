# MiniWindowGuard / 小窗守护

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
