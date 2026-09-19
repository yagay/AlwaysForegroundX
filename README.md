# MiniWindowGuard / 小窗守护

## 4.4.1

4.4.1 修复首次把 Task 迁移到 VirtualDisplay 后偶发没有 focused window 的竞态。

诊断中已经确认：

- VirtualDisplay 774×1330 创建成功；
- Task 已经进入副屏；
- 视频 SurfaceView 720×1280 已经创建；
- 失败点是 InputDispatcher 报 `Application does not have a focused window`；
- 第二次重新打开同尺寸时，focused window 正常建立，视频也能显示。

因此 4.4.1：

- 移除副屏任务上的 `ActivityManager.moveTaskToFront()`；
- 避免 OxygenOS 把 display 0 的 MiniWindowGuard/Launcher 再拉回 Resumed；
- Task 迁移后增加 80 / 220 / 480 / 900 / 1500 / 2300ms 多次焦点重试；
- 触摸 DOWN 仍会补一次焦点；
- 固定内部 48% 与外部自由缩放逻辑不变。

新增日志：

- `VD_FOCUS_RETRY`

MiniWindowGuard 是一个仅作用于 `system / system_server` 的 LSPosed 模块。

## 4.4.0

4.4.0 把 **目标 App 的内部 VirtualDisplay** 和 **用户看到的外部小窗**彻底分离。

这是针对视频 App 的 SurfaceView / MediaCodec 兼容问题做的结构调整：部分应用只有在某些首次 VirtualDisplay 尺寸下才能正常建立视频 Surface，但一旦建立成功，后续外部窗口怎么缩放都能继续显示。

### 默认模式：固定内部显示

默认开启：

`固定内部显示（推荐）`

开启后：

- 目标 App 运行在稳定的 VirtualDisplay 内部画布。
- 默认内部渲染比例为 **48%**。
- 当前设备上 48% 已验证可以正常建立视频 Surface。
- 外部 TextureView 可以自由改变长宽。
- 拖动 resize 时不再调用 `VirtualDisplay.resize()`。
- 目标 App 不会因为外部窗口变化反复收到 display configuration change。
- SurfaceView / MediaCodec 不需要跟着外部窗口反复重建。

### 内部与外部尺寸

4.4.0 使用两套独立尺寸：

```
目标 App
   ↓
固定 VirtualDisplay
   ↓
TextureView
   ↓
外部可见小窗
```

内部尺寸由：

`内部渲染比例`

控制。

外部尺寸由：

- 外部窗口宽度
- 外部窗口高度
- 最小外部宽度
- 最小外部高度

控制。

默认：

- 内部渲染比例：48%
- 最小外部宽度：160dp
- 最小外部高度：220dp

因此内部仍可维持视频兼容所需的安全尺寸，而外部小窗可以比以前明显更小。

### 触摸坐标映射

固定内部显示开启时，外部 TextureView 和内部 VirtualDisplay 尺寸可以不同。

MiniWindowGuard 会自动换算触摸坐标：

```
internalX = hostX × internalWidth / hostWidth
internalY = hostY × internalHeight / hostHeight
```

因此即使把外部小窗缩小，点击和滑动仍然会落到正确的 App 坐标。

### 动态模式

关闭：

`固定内部显示（推荐）`

以后恢复动态模式：

- 外部窗口 resize 时先只做预览。
- 松手时真正调用一次 `VirtualDisplay.resize()`。
- App 会收到新的显示配置。
- 适合确实希望 App 根据窗口尺寸重新排版的应用。

视频 App 如果存在 SurfaceView 首次创建或重新布局问题，建议继续使用固定内部显示。

## 设置页新增

“小窗显示与兼容性”中现在可以调整：

- 固定内部显示
- 内部渲染比例
- 外部窗口默认宽度
- 外部窗口默认高度
- 最小外部宽度
- 最小外部高度

内部渲染比例只对新建小窗生效。

最小外部宽高只限制之后的拖动缩放，不会降低内部 VirtualDisplay 的安全尺寸。

## 热重载

4.4.0 没有修改 Bootstrap Hook 注册层。

如果已经安装 4.3.x 并完成过一次 Bootstrap 重启：

- 安装 4.4.0 后无需重启手机；
- 没有活动小窗时会自动热重载；
- 或在设置页点击“立即重新加载 System Engine”。

## 诊断

新增/重点日志：

- `VD_WINDOW_CREATED`
  - 同时记录 `host=...` 和 `internal=...`
- `VD_SURFACE_READY`
  - 记录 TextureView、内部 buffer、外部 host 尺寸
- `VD_HOST_RESIZE_ONLY`
  - 固定内部显示模式下只改变外部窗口
- `VD_RESIZE_COMMIT`
  - 动态模式下真正改变 VirtualDisplay
- `VD_INPUT_DOWN`
  - 同时记录 host / internal 尺寸
- `ENGINE_RELOAD_*`

诊断摘要也记录：

- `fixed_internal_display`
- `internal_display_scale`
- `outer_min_width_dp`
- `outer_min_height_dp`

## 开源架构参考

设计过程中研究过：

- YAMF² / YAMFsquared
- YAMF
- FreeformShell
- Android AOSP DisplayManager / ActivityTaskManager / InputManager

这些项目用于理解公开架构与系统行为。MiniWindowGuard 当前实现为本项目重新实现。

本仓库使用 GPLv3。详见 `LICENSE` 和 `THIRD_PARTY_NOTICES.md`。
