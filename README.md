# MiniWindowGuard / 小窗守护

MiniWindowGuard 是一个仅作用于 `system / system_server` 的 LSPosed 模块。

从 **4.0.0** 开始，小窗核心不再使用 display 0 上的 Freeform / MULTI_WINDOW / WCT bounds，而是改成与 **YAMF² / YAMF** 同类的 **VirtualDisplay 容器架构**。

## 4.0.0

### VirtualDisplay 小窗

- system_server 创建独立 VirtualDisplay。
- 用户从应用列表点击“打开小窗”后，目标 App 先正常启动。
- Activity 进入 RESUMED 时，MiniWindowGuard 捕获对应 Task。
- 通过 `moveRootTaskToDisplay(taskId, displayId)` 把 Task 从主屏 display 0 移入独立 VirtualDisplay。
- VirtualDisplay 画面通过 system_server 创建的 `TextureView + Surface` Overlay 显示。
- 触摸/鼠标事件会带目标 `displayId` 注入回 VirtualDisplay。
- 支持拖动、调整大小、返回键、三点控制菜单。
- 菜单支持：
  - 放大（把 Task 移回原 display）
  - 小窗
  - 图标
  - 隐藏
- 图标/隐藏只改变显示层；VirtualDisplay 与 Task 保持存在，不再通过把 display 0 Task 设 alpha=0 来模拟隐藏。

### 始终前台

MiniWindowGuard 保留自己的 system_server 前台保护逻辑：

- 对容器 App 返回前台级进程状态。
- `hasResumedActivity(uid)` 可保持为 true。
- 保护容器顶层 Activity 的 Resumed/Visible 状态。
- 阻止 remove-task / OEM 清理链路对容器 App 的强杀。
- 明确的强制停止、安装/更新流程不会被当成普通清理拦截。

VirtualDisplay 解决“窗口如何显示”，前台保护解决“目标 App 是否继续运行”，两者彼此独立。

## 诊断

保留原 MiniWindowGuard 的完整诊断导出。

重点事件包括：

- `VD_COMMAND_PENDING`
- `VD_TASK_CAPTURED`
- `VD_WINDOW_CREATED`
- `VD_TASK_MOVED`
- `VD_SURFACE_READY`
- `VD_RESIZE`
- `VD_SURFACE_HIDDEN`
- `VD_INPUT_ERROR`
- Activity pause / visible / resumed 拦截
- 进程、窗口、Task、Audio、MediaSession 与 logcat 快照

诊断 ZIP 保存到：

`Download/MiniWindowGuard/`

## 使用

1. 安装 APK。
2. 在 LSPosed 启用模块，作用域保持 `system`。
3. 重启手机，使新版 system_server 模块生效。
4. 打开 MiniWindowGuard，确认 System 引擎显示当前版本。
5. 进入“选择应用并打开小窗”。
6. 选择目标 App。
7. Task 会被移动到独立 VirtualDisplay，并显示为真正的悬浮容器。

## 架构来源与许可证

VirtualDisplay 小窗架构参考并适配自：

- YAMF² / YAMFsquared — https://github.com/kaii-lb/YAMFsquared
- YAMF — https://github.com/duzhaokun123/YAMF

这些项目使用 GPLv3。

MiniWindowGuard 4.x 同样以 **GNU GPL v3** 发布。详见：

- `LICENSE`
- `THIRD_PARTY_NOTICES.md`

MiniWindowGuard 保留自己的前台保护、诊断系统以及与当前项目结构相适配的实现。
