# MiniWindowGuard / 小窗守护

MiniWindowGuard 是一个仅作用于 `system / system_server` 的 LSPosed 模块。

## 4.2.0

4.2.0 继续重构 VirtualDisplay 窗口层，重点解决 4.1.0 的黑屏、缩放卡顿，以及缺少图标/隐藏状态的问题。

### 显示桥

- system_server 创建独立 VirtualDisplay。
- 使用稳定的 `TextureView + SurfaceTexture + Surface` 作为 VirtualDisplay 输出。
- 先等待 TextureView Surface 准备完成，再把目标 Task 移入 VirtualDisplay。
- 宿主根 View 在窗口存活期间**绝不 remove/add 重挂**，避免 SurfaceTexture 断开和 VirtualDisplay ON/OFF 抖动。
- VirtualDisplay 使用：
  - `SUPPORTS_TOUCH`
  - `TRUSTED`
  - `OWN_FOCUS`
  - `STEAL_TOP_FOCUS_DISABLED`

### 输入

- 触摸事件从 TextureView 本地坐标读取。
- 重新构造 MotionEvent，并写入目标 VirtualDisplay 的 `displayId`。
- 通过 system_server InputManager 注入。
- 点击窗口内容时主动请求目标 Task 焦点。

### 缩放性能

4.1.0 会在手指每次移动时调用 `VirtualDisplay.resize()`，在 OxygenOS 上会造成大量：

- display configuration change
- Task transition
- Activity relayout / relaunch

4.2.0 改成：

- ACTION_MOVE：只改变宿主窗口大小作为预览。
- ACTION_UP：只提交一次 `VirtualDisplay.resize()`。

因此缩放过程中不再连续触发 display 级配置变化。

### 窗口控制

不恢复旧三点菜单。

标题栏直接提供：

- 返回
- 缩小成图标
- 隐藏
- 关闭

#### 缩小成图标

- 主窗口被移到 display 0 屏幕外。
- TextureView 和 VirtualDisplay Surface 继续保持连接。
- 显示一个圆形恢复按钮。
- 点击恢复按钮回到原窗口位置。

#### 隐藏

- 同样保持 VirtualDisplay 和 Surface 存活。
- 主窗口移出屏幕，不阻挡当前前台 App。
- 只留下右侧一个很窄的恢复把手。
- 点击把手恢复小窗。

### 始终前台

MiniWindowGuard 自己的前台保护继续保留：

- 进程状态保持前台级别。
- `hasResumedActivity(uid)` 保持为 true。
- 拦截真实 pause/stop 路径。
- 保持客户端可见。
- 阻止普通最近任务/OEM 清理链路强杀。

4.2.0 删除了两个高频 visibility 查询 Hook：

- `TaskFragment.getVisibility()`
- `TaskFragment.shouldBeVisible()`
- ActivityRecord 的高频 visible 查询强制返回

这些 Hook 在 4.1.0 日志里会在 `android.display` 线程高频触发，造成额外负担。现在只保留真正影响生命周期的 pause / invisible / clientVisible 拦截。

## 诊断

重点日志：

- `VD_TASK_CAPTURED`
- `VD_WINDOW_CREATED`
- `VD_SURFACE_READY`
- `VD_TASK_MOVED`
- `VD_FOCUS`
- `VD_INPUT_DOWN`
- `VD_INPUT_ERROR`
- `VD_RESIZE_COMMIT`
- `VD_MINIMIZED`
- `VD_HIDDEN`
- `VD_RESTORE`
- `VD_SURFACE_LOST`

诊断 ZIP 仍包含：

- `dumpsys display`
- `dumpsys input`
- SurfaceFlinger surface 列表
- Activity / Window / Task
- Audio / MediaSession
- LSPosed 日志
- 最近 30000 行 logcat

## 开源架构参考

设计过程中研究过：

- YAMF² / YAMFsquared
- YAMF
- FreeformShell
- Android AOSP DisplayManager / ActivityTaskManager / InputManager

这些项目只用于理解公开架构与系统行为。MiniWindowGuard 当前窗口引擎为本项目重新实现，不直接使用上述项目的窗口实现源码。

本仓库继续使用 GPLv3。详见 `LICENSE` 和 `THIRD_PARTY_NOTICES.md`。
