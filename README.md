# MiniWindowGuard / 小窗守护

MiniWindowGuard 是一个仅作用于 `system / system_server` 的 LSPosed 模块。

## 4.3.0

4.3.0 引入 **固定 Bootstrap + 可热重载 Engine**。

目标是解决开发测试阶段最麻烦的问题：以前每次更新 APK 后，system_server 里仍然运行旧模块 ClassLoader，因此必须重启手机。现在只有 Hook 注册层固定驻留，窗口、VirtualDisplay、输入、前台策略和大部分运行逻辑都放进可重新加载的 Engine。

### 第一次升级

从 4.2.x 或更早版本升级到 4.3.0：

1. 安装 4.3.0 APK。
2. 仍然需要 **最后重启一次手机**。
3. 重启后，新的 Bootstrap 会进入 system_server。
4. 设置页会显示：
   - Bootstrap code
   - Engine code
   - 热重载可用
   - Engine generation
   - 活动小窗数量
   - 上一次 reload 结果

完成这一次之后，正常 APK 更新不再需要重启手机。

### 以后更新 APK

Bootstrap 每 2 秒检查：

- 当前安装 APK 的 versionCode
- 当前运行 Engine 的 versionCode
- 手动 reload sequence
- 当前活动小窗数量

默认开启 **自动热重载**。

如果安装新版 APK 时没有活动小窗：

```
APK 更新
  ↓
Bootstrap 检测 versionCode 不一致
  ↓
停止旧 Engine
  ↓
从当前安装 APK sourceDir 创建新的 PathClassLoader
  ↓
加载 HotReloadEngine
  ↓
启动新版 VirtualDisplay / 输入 / 前台逻辑
  ↓
原子切换 current Engine
```

整个过程不重启 system_server，也不重启手机。

如果更新时仍有活动小窗，自动 reload 会暂缓，避免突然关闭正在运行的窗口。可以：

- 先关闭当前小窗，Bootstrap 会自动加载新版；
- 或在设置页点击 **“立即重新加载 System Engine”**，强制 reload。强制 reload 会关闭当前小窗并把对应 Task 恢复到原 display。

### Reload 失败保护

新版 Engine 会先完成：

- APK 路径解析
- ClassLoader 创建
- Engine 类实例化
- Bootstrap API 兼容性检查

确认候选 Engine 可以加载后，才停止旧 Engine。

如果新版启动失败：

- Bootstrap 尝试重新启动旧 Engine；
- 保留旧 Engine 引用；
- 写入 `ENGINE_RELOAD_FAILED`；
- 如果回滚也失败，会记录 `ENGINE_ROLLBACK_FAILED`。

### Bootstrap 与 Engine

Bootstrap 负责：

- LSPosed system_server Hook 注册
- Hook 回调入口
- Engine ClassLoader 生命周期
- 自动/手动热重载
- Engine 状态上报

Engine 负责：

- VirtualDisplay
- TextureView / Surface
- 输入注入
- 小窗拖动 / resize
- 图标 / 隐藏 / 恢复
- 当前受管 Task
- 前台策略数据
- Engine 级运行逻辑

Bootstrap Hook 只安装一次，不会因为 reload 重复注册。

### 什么时候仍然需要重启

普通功能更新原则上不需要重启，例如：

- 修复黑屏
- 修复输入
- 修改 VirtualDisplay
- 修改窗口 UI
- 修改 resize
- 修改图标 / 隐藏
- 修改 Engine 内部前台策略
- 修改诊断

只有以后修改了 **Bootstrap 本身**，例如：

- 新增以前没有注册过的 system_server Hook 点
- 改变 Bootstrap / Engine 接口版本
- 修改 LSPosed 初始化方式

才可能再次需要重启 system_server / 手机。

## 4.2 窗口架构

窗口层继续使用 MiniWindowGuard 自己实现的 VirtualDisplay 引擎：

- system_server 创建独立 VirtualDisplay；
- 使用稳定 `TextureView + SurfaceTexture + Surface`；
- Surface 就绪后才迁移 Task；
- 宿主窗口存活期间不 remove/add 根 View；
- VirtualDisplay 使用 `SUPPORTS_TOUCH / TRUSTED / OWN_FOCUS / STEAL_TOP_FOCUS_DISABLED`；
- 触摸事件重新构造并带目标 displayId 注入；
- resize 手势移动阶段只预览，松手时才提交一次 VirtualDisplay resize；
- 标题栏直接提供返回、缩小成图标、隐藏和关闭。

## 始终前台

MiniWindowGuard 保留自己的 system_server 前台保护：

- 进程状态保持前台级别；
- `hasResumedActivity(uid)` 保持为 true；
- 拦截真实 pause / stop；
- 保持客户端可见；
- 阻止普通最近任务 / OEM 清理链路强杀。

## 诊断

诊断 ZIP 现在额外记录：

- `bootstrapVersionCode`
- `loadedEngineVersionCode`
- `hotReloadAvailable`
- `engineGeneration`
- `engineActiveSessions`
- `engineReloadMessage`
- `engine_reload_seq`

关键日志：

- `ENGINE_RELOAD_BEGIN`
- `ENGINE_RELOAD_SUCCESS`
- `ENGINE_RELOAD_FAILED`
- `ENGINE_RELOAD_PENDING`
- `ENGINE_ROLLBACK_FAILED`
- `VD_TASK_CAPTURED`
- `VD_WINDOW_CREATED`
- `VD_SURFACE_READY`
- `VD_TASK_MOVED`
- `VD_FOCUS`
- `VD_INPUT_DOWN`
- `VD_RESIZE_COMMIT`
- `VD_MINIMIZED`
- `VD_HIDDEN`
- `VD_RESTORE`

## 开源架构参考

设计过程中研究过：

- YAMF² / YAMFsquared
- YAMF
- FreeformShell
- Android AOSP DisplayManager / ActivityTaskManager / InputManager

这些项目用于理解公开架构与系统行为。MiniWindowGuard 当前窗口与热重载 Engine 均为本项目重新实现。

本仓库使用 GPLv3。详见 `LICENSE` 和 `THIRD_PARTY_NOTICES.md`。
