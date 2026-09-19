# MiniWindowGuard / 小窗守护

## 5.1.1 — 锁屏继续播放修复

5.1.1 保持 5.1.0 的 OPlus 小窗白名单架构不变，只修正锁屏保活时序。

### 修复原因

5.1.0 的诊断显示：

```
PowerKey
→ FlexibleWindowManagerService mInteractive=false
→ ActivityTaskManager Create SleepToken
→ OPlus onScreenLockedChanged
→ Activity setVisibility(false)
→ pause / stop
→ 后续才建立 lockKeepAlive
```

因此播放器已经先收到 `onPause/onStop`。

### 5.1.1

锁屏改为：

```
FlexibleTaskController.onScreenLockedChanged
→ 进入方法之前
→ 对“始终前台白名单 + 当前真实 OPlus 小窗”预先建立 lockKeepAlive
→ 再执行 OPlus 原生锁屏流程
```

并只在 **OPlus 自己的 onScreenLockedChanged 调用栈内部**：

- 阻止白名单小窗的 `ActivityRecord.setVisibility(false)`
- 阻止白名单小窗的 `ActivityRecord.makeInvisible()`
- `TaskFragment.sleepIfPossible()` 保持活跃
- 继续阻止该 UID 的 OPlus Hans freeze
- 继续阻止 AOSP freezer
- 继续阻止锁屏 stopUid

这些 Hook 不作用于：

- 普通全屏
- 普通 Home / Recents / Back
- 普通应用切后台
- 非白名单应用
- 非 OPlus FlexibleWindow Task

因此不会恢复旧版全局 `pause/invisible` 强拦截。

### 贴边 / 最小化

继续使用 5.1.0 的 OPlus 原生路径：

- `TaskExtImpl.moveTaskToBackForPanorama`
- `FloatHandleController.isInFloatingList(taskId)`
- 保留一加贴边动画和图标
- 跳过最终 moveTaskToBack
- 焦点交给下面的正常窗口
- 隐藏贴边 Task Surface
- App 继续运行/播放

### 诊断

新增/重点观察：

- `OPLUS_LOCK_PREARM`
- `OPLUS_LOCK_VISIBILITY_BLOCK`
- `OPLUS_LOCK_SLEEP_BLOCK`
- `OPLUS_HANS_FREEZE_BLOCK`
- `OPLUS_AOSP_FREEZE_BLOCK`
- `OPLUS_STOP_UID_BLOCK`

> 5.1.1 增加了新的 system_server Bootstrap Hook。安装后需要完整重启手机一次。

## 5.1.0 — OPlus 小窗始终前台

5.1.0 不再负责启动 App，也不再主动调用 `toggleFlexibleWindow`。

所有进入/退出小窗、贴边、恢复、关闭都完全使用 OxygenOS / ColorOS 自己的方式。

MiniWindowGuard 现在只做：

1. 管理“始终前台应用”名单；
2. 管理“强制允许一加小窗”名单；
3. 被动监听 OPlus FlexibleWindow 状态；
4. 只对白名单中的真实一加小窗做前台/后台播放保护。

---

## 启动方式

目标 App 的启动完全由系统决定：

- 桌面
- 最近任务
- 侧边栏
- 通知
- 一加系统小窗入口
- 其他正常系统入口

MiniWindowGuard 不再：

- 启动目标 App；
- 创建 VirtualDisplay；
- 创建 Overlay；
- 主动切换 FlexibleWindow；
- 改 Task bounds；
- 自己管理 Surface/输入。

---

## 始终前台应用

设置页新增：

`始终前台应用`

支持多选。

只有同时满足：

```
包名 ∈ 始终前台名单
AND
Task 当前真实属于 OPlus 小窗
```

才启用保护。

普通全屏状态完全不干预。

### 正常一加小窗

真实 FlexibleWindow 时：

- `getPackageProcessState → TOP`
- `getUidProcessState → TOP`
- `isAppForeground → true`
- `hasResumedActivity → true`
- 防 removed-task / Athena / OPlus 清理链路误杀

---

## 贴边 / 最小化继续播放

OPlus 将小窗吸附到屏幕边缘时：

```
FlexibleTaskController
→ FloatHandleController.addFloatHandle
→ TaskExtImpl.moveTaskToBackForPanorama
```

5.1.0 允许 OxygenOS 正常完成：

- 小窗动画；
- FloatHandle/贴边图标；
- Surface 隐藏；
- OPlus 自己的窗口状态转换。

但对于“始终前台”名单：

- 识别 Task 已进入 `FloatHandleController` FloatingList；
- 阻止最终的 `moveTaskToBackForPanorama`；
- 将焦点转移给小窗下面的正常窗口；
- 保持隐藏小窗 Surface 不重新占屏；
- App 继续保持运行/播放。

不会重新使用旧版全局 `pause/invisible` 拦截。

---

## 锁屏继续播放

如果白名单 App 在按电源键前确实是 OPlus 小窗/贴边小窗：

`FlexibleTaskController.notifyKeyguardStateChanged(...)`

会把该 Task 标记为锁屏保活。

锁屏期间只对这个 Task/UID 精准处理：

- `TaskFragment.sleepIfPossible(...)`
  - 阻止该 OPlus 小窗因为 display sleep 进入真正 pause/stop；
- `HansCGroup.hansFreezeLocked(...)`
  - 阻止 ColorOS/OxygenOS Hans freezer 冻结；
- `CachedAppOptimizer.freezeAppAsyncInternalLSP(...)`
  - 阻止 AOSP freezer；
- `ActivityManagerService.doStopUidLocked(...)`
  - 阻止锁屏 force-idle 停止该 UID。

解锁后有短暂 grace period，然后恢复完全由系统状态判断。

普通全屏 App 即使在名单里，也不会命中锁屏保活。

---

## 强制允许一加小窗

设置页另有：

`强制允许一加小窗应用`

这是独立名单。

仅对勾选 App 修改：

- `FlexibleWindowUtils.isSupportFlexibleWindow`
- `FlexibleTaskController.isSupportFlexibleWindow`
- FlexibleWindow 黑名单判断
- 旧 `OplusZoomWindowConfig.isSupportZoomMode`

它只负责“允许系统小窗”。

不会：

- 自动启动 App；
- 自动打开小窗；
- 自动加入始终前台名单。

两个名单互相独立。

---

## 安全边界

5.1.0 不再使用这些全局强制生命周期 Hook：

- `ActivityRecord.makeInvisible`
- `setVisible(false)`
- `setVisibleRequested(false)`
- 全局 `shouldPauseActivity=false`
- 全局 `TaskFragment.startPausing` 拦截

因此普通全屏页面、Home、返回、最近任务、导航键都继续由系统控制。

---

## 诊断日志

新增/重点关注：

- `OPLUS_TASK_TRACKED`
- `OPLUS_TASK_INFO`
- `OPLUS_STATE`
- `OPLUS_EDGE_KEEPALIVE`
- `OPLUS_EDGE_MOVE_BACK_BLOCK`
- `OPLUS_EDGE_SURFACE_HIDE`
- `OPLUS_EDGE_FOCUS_REDIRECT`
- `OPLUS_KEYGUARD_STATE`
- `OPLUS_LOCK_KEEPALIVE`
- `OPLUS_LOCK_SLEEP_BLOCK`
- `OPLUS_HANS_FREEZE_BLOCK`
- `OPLUS_AOSP_FREEZE_BLOCK`
- `OPLUS_STOP_UID_BLOCK`
- `AMS_PACKAGE_STATE`
- `AMS_UID_STATE`
- `AMS_FOREGROUND`
- `ATMS_HAS_RESUMED`

---

## 升级说明

5.1.0 新增了 system_server Bootstrap Hook：

- `TaskExtImpl.moveTaskToBackForPanorama`
- `Task.prepareSurfaces`
- `DisplayContent.setFocusedApp`
- `FlexibleTaskController.notifyKeyguardStateChanged`
- `TaskFragment.sleepIfPossible`
- Hans / CachedAppOptimizer / doStopUidLocked

因此从 5.0.0 升级到 5.1.0 后需要 **完整重启手机一次**。

后续如果只修改动态 Engine，仍可继续使用热重载。
