# MiniWindowGuard / 小窗守护

MiniWindowGuard 是一个仅作用于 `system / system_server` 的 LSPosed 模块。

## 4.1.0

4.1.0 将小窗窗口层完全重写。公开项目 YAMF / YAMF² / FreeformShell 只作为架构思路参考，当前窗口引擎不复制它们的实现源码。

### 小窗核心

- system_server 创建独立 VirtualDisplay。
- 使用独立 SurfaceView 作为稳定显示 Surface。
- **先等 Surface 创建成功，再把目标 Task 移入 VirtualDisplay**，避免 Task 已迁移但显示 Surface 尚未准备好的黑屏。
- VirtualDisplay 启用 Android 16 的：
  - `SUPPORTS_TOUCH`
  - `TRUSTED`
  - `OWN_FOCUS`
  - `STEAL_TOP_FOCUS_DISABLED`
- 目标 display 可以维护自己的输入焦点，同时不抢走主屏 display 0 的顶层焦点。
- 触摸事件根据 SurfaceView 本地坐标重新构造，并写入目标 `displayId` 后由 system_server 的 InputManager 注入。
- 窗口移动只调用 `updateViewLayout`，不会 remove/add 承载 Surface 的根 View。
- resize 使用约 16ms 的节流，并在手势结束时强制提交最终 VirtualDisplay 尺寸。

### 窗口 UI

只保留最小控制：

- 拖动标题栏
- 调整窗口大小
- 返回
- 关闭

已删除旧版：

- 三点菜单
- 图标态
- 隐藏态
- 放大/小窗状态菜单
- 早期 Overlay 状态切换代码

关闭窗口时，目标 Task 会移回原 display。

### 始终前台

保留 MiniWindowGuard 自己的 system_server 前台保护逻辑：

- 容器 App 进程状态保持前台级别
- `hasResumedActivity(uid)` 保持为 true
- 阻止容器顶层 Activity 因 display 0 焦点变化进入 pause/stop
- 保持 Activity 逻辑 Visible
- 防止普通最近任务/OEM 清理链路把容器 App 强杀
- 放回原 display 后解除容器保护

VirtualDisplay 负责“窗口怎么显示和操作”，前台保护负责“App 是否继续真正运行”。

## 诊断

保留完整诊断 ZIP，重点记录：

- `VD_COMMAND_PENDING`
- `VD_TASK_CAPTURED`
- `VD_WINDOW_CREATED`
- `VD_SURFACE_READY`
- `VD_TASK_MOVED`
- `VD_FOCUS`
- `VD_INPUT_DOWN`
- `VD_INPUT_ERROR`
- `VD_RESIZE`
- `VD_SURFACE_LOST`
- Activity Resumed / Visible 生命周期保护
- Display / InputDispatcher / SurfaceFlinger / Audio / MediaSession / Window / Task 快照
- 最近 30000 行 logcat

诊断 ZIP 保存到：

`Download/MiniWindowGuard/`

## 使用

1. 安装 APK。
2. 在 LSPosed 启用模块，作用域只选 `system`。
3. 重启手机。
4. 确认 System 引擎显示当前 code。
5. 进入“选择应用并打开小窗”。
6. 选择目标 App。

## 公开架构参考

设计过程中研究过：

- YAMF² / YAMFsquared
- YAMF
- FreeformShell
- Android AOSP DisplayManager / ActivityTaskManager / InputManager

这些项目和 AOSP 用于理解公开架构与系统行为。MiniWindowGuard 4.1 的窗口引擎为本项目重新实现，不直接使用上述项目的窗口实现源码。

本仓库继续使用 GPLv3。详见 `LICENSE` 和 `THIRD_PARTY_NOTICES.md`。
