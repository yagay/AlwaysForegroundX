# MiniWindowGuard / 小窗守护

## 5.4.6 — 通知栏真正的“暂停”按钮

- 原通知里的三角形是 small icon，并不是可点击按钮；Android 的通知小图标本身不能单独接点击事件；
- 展开后台播放通知后新增明确的“双竖线 暂停” Action；
- 通知 Action 使用目标包名发送，只控制该通知对应的后台播放 App；
- Universal Playback Guard 会跟踪常见播放器最近的播放实例，优先直接调用该实例的 `pause()` / `setPlayWhenReady(false)`；
- 如果无法识别播放器实例，再在目标 App 进程内退回标准 `KEYCODE_MEDIA_PAUSE`；
- 点击整条通知仍然返回目标 App；
- 暂停后 AudioPlaybackCallback 检测到不再播放时会自动撤销后台播放通知；
- 控制广播使用 MiniWindowGuard 自定义 signature 权限，避免其他普通 App 随意发送暂停控制。

Bootstrap API 仍为 6；本版未新增 system_server 固定 Hook。已运行 Bootstrap 6 的设备不需要因为本次更新再次完整重启，但需要重新打开目标 App，让新的 App 进程播放守护加载。

## 5.4.5 — 锁屏只保播放，不保画面

修复“App 在前台播放时锁屏后仍能看到视频”的问题。此前锁屏保活同时阻止了 Activity Pause 和窗口隐藏，导致播放状态与画面可见性被错误绑定。

- 后台播放应用在 `uiSleeping/keyguard` 场景不再阻止 framework Pause；
- Android 可以正常执行 Activity/窗口隐藏和 Surface 隐藏，因此锁屏后不再显示视频画面；
- App 进程侧 Universal Playback Guard 继续只拦截生命周期触发的播放器 `pause()/stop()/setPlayWhenReady(false)`，尽量保持音频/播放不中断；
- 删除锁屏期间对 `ActivityRecord.setVisibility(false)` / `makeInvisible()` 的强制阻止；
- 一加小窗锁屏仍保留 `sleepIfPossible`、Hans/AOSP freezer、`doStopUidLocked`、进程重要性等保活保护；
- 用户主动暂停、退出、强制停止仍正常执行；
- 主页面作用域说明同步更新：system 为系统作用域，后台播放 App 按需动态加入。

Bootstrap API 升到 6。本版修改了固定驻留在 system_server 的 GuardModule/EngineBridge，安装后需要完整重启手机一次，不能只依赖 Engine 热重载。

## 5.4.4 — 修复状态栏/导航栏遮挡

- 新增统一 `SystemBarInsets` 适配层；
- 主页面、应用名单页和错误页都会读取 `systemBars + displayCutout` Insets；
- 在页面原有 padding 基础上叠加状态栏、三键导航、手势导航和刘海/挖孔安全区；
- 不再依赖固定状态栏高度，也不使用旧的 `fitsSystemWindows`；
- 适配 Android 15/16 强制 edge-to-edge 行为，顶部内容不再被通知栏覆盖，底部按钮/列表不再被导航键覆盖。

## 5.4.3 — 手动同步作用域 + 已选应用置顶

- “后台播放应用”页面新增“同步到 LSPosed 作用域”按钮；
- 手动同步会重新读取 LSPosed 当前作用域，批量请求所有“已选择但尚未加入”的应用，不受自动同步 pending 状态影响；
- 同步完成后提示“已在作用域 / 本次新增 / 未加入”数量；
- 页面计数增加“已加入 LSPosed”数量，方便直接确认抖音等应用是否真正进入作用域；
- 所有应用选择列表统一改为“已选应用置顶”，未选应用继续按名称排序；
- 勾选或取消后列表立即重新排序，搜索结果也保持已选置顶；
- 新增作用域后重新打开目标 App 即可让 App 进程侧 Universal Playback Guard 加载。

## 5.4.2 — 通用后台播放保护

5.4.1 已解决返回桌面时 system_server / WMS 卡死，但允许正常 Activity Pause 后，部分 App（例如红果这类在 onPause 中主动暂停播放器的应用）会停止播放。5.4.2 不再恢复 system_server 生命周期强拦截，而是新增通用 App 进程播放保护层。

- `system` 侧继续允许 Home、Recents、切换其他 App 的 Pause 正常完成，保留 5.4.1 的防卡死修复；
- 模块改为 `staticScope=false`，后台播放列表中的 App 会通过 libxposed Service 请求加入模块作用域；未选择 App 不注入；
- 目标 App 仅在主进程加载 Universal Playback Guard；
- 通过 `Instrumentation.callActivityOnPause/callActivityOnStop` 精确标记生命周期退后台调用栈，不使用固定延迟；
- 仅在这个生命周期调用栈内阻止播放器的 `pause()/stop()` 或 `setPlayWhenReady(false)`；
- 用户点击暂停、切换视频、正常退出时没有生命周期保护标记，因此仍按原逻辑执行；
- 通用覆盖 Android MediaPlayer/VideoView、Media3/ExoPlayer、IjkPlayer、TTVideoEngine/VideoShop、VLC、腾讯 TXVod、七牛 PLMediaPlayer、百度云播放器等常见播放栈；
- BACKGROUND_PROTECTED 的防 Stop、防杀、防冻结逻辑继续由 system_server 负责，因此 App 的 onStop 通常不会到达，App 进程保护层主要解决 onPause 主动暂停；
- 首次勾选某 App 时 LSPosed 会请求新增作用域；批准后重新打开目标 App 即可，不要求因为本次更新重启整机。

## 5.4.1 — 修复返回桌面导致 system_server 卡死

本次诊断的关键时间线：14:28:45 Launcher 恢复前台，约 14:28:47 system_server 已开始阻塞；14:28:51 才按下电源键；14:29:55 Watchdog 报告 ActivityManager、Display、Animation 三条系统线程已连续阻塞 68 秒。因此触发点是“返回桌面/切换前台应用”，不是锁屏。

5.4.1 修复：

- 普通返回桌面、切换其他应用、进入最近任务时，不再从 `TaskFragment.startPausing()` 阻止系统 Pause；仍建立 `BACKGROUND_PROTECTED` 状态，并继续使用防 Stop、防杀、防冻结和进程重要性保护；
- 仅 `uiSleeping` 的锁屏/熄屏场景继续保留原有 Pause 抑制，避免改变现有锁屏播放行为；
- `DisplayContent.setFocusedApp()`、OPlus task callback、后台保护回调不再同步执行音频查询、PackageManager 查询或 `ContentResolver.call()`；
- 后台播放通知全部移到独立 `MiniWindowGuard-Notifier` HandlerThread，避免在 WMS/ATMS 锁事务中跨 Binder 调用造成锁反转；
- Bootstrap API 不变，5.4.0 已在 Bootstrap 5 的设备可直接热重载 5.4.1 Engine。

## 5.4.0 — 修复真正的 Engine 热重载

最新诊断确认：APK 已是 5.3.9/code89，但 system_server 在自动 reload 后仍加载出 version 88。根因是 EngineBridge 使用普通 PathClassLoader；Android 默认父优先，HotReloadEngine 会先从 system_server 已驻留的旧模块 ClassLoader 命中，所以之前的“热重载”实际上会重新实例化旧版本类。

5.4.0 修复：

- 新增专用 ReloadableEngineClassLoader；对 com.yagay.MiniWindowGuard.* 使用 child-first，优先从当前已安装 APK 查找 Engine 及其依赖；
- Android/Java/framework 类仍走父加载器；
- 强制校验 HotReloadEngine.class 的实际 ClassLoader 必须就是新的 reload loader；
- 强制校验 candidate versionCode 必须等于 PackageManager 当前已安装版本；不再允许“reload success 但实际还是旧 Engine”；
- reload 失败时保留并恢复旧 Engine，不破坏现有运行状态；
- 后续只修改 HotReloadEngine/OplusFlexibleWindowController/GuardConfig 等 reloadable 逻辑时，升级 APK 后才会真正加载新代码。

Bootstrap API 升为 5。因为这次修改的是固定驻留在 system_server 的 EngineBridge，本版安装后必须完整重启一次设备；从 Bootstrap 5 开始，后续纯 Engine 更新才可以可靠热重载。

## 5.3.9 — 后台通知与生命周期保护解耦

5.3.8 新诊断确认：抖音退后台后虽然进入 PAUSED/STOPPED，但系统 MediaSession 仍持续 PLAYING 超过 8 秒。也就是说抖音本身可以后台播放，问题只是旧通知逻辑要求先建立 BACKGROUND_PROTECTED Session，导致“不需要保护也能播放”的 App 永远没有 MiniWindowGuard 通知。

5.3.9 将后台通知改为独立、按包名的状态机：

- 直接遍历“后台播放应用”名单，不再要求已有 Controller Session；
- AudioManager.AudioPlaybackCallback 检测目标包 UID 是否仍有 active playback；
- 记录系统当前 focused package：目标 App 在前台时立即隐藏通知；
- 目标 App 在普通后台且仍播放时显示 ongoing 通知；
- 播放停止后由音频回调立即撤销；
- 锁屏时不显示；
- 真实 OPlus FlexibleWindow / FloatHandle 小窗状态不显示；
- 生命周期保护仍独立工作：需要保护的 App 继续走 BACKGROUND_PROTECTED，像抖音这种原生可后台播放的 App 不必强行进入保护状态；
- 通知继续显示目标 App 的 large icon，状态栏 small icon 保持 MiniWindowGuard 单色图标。

Bootstrap API 仍为 4，仅修改 HotReload Engine，可直接热重载。

## 5.3.8 — 从真实 Task 身份建立后台 Session

5.3.7 后的新诊断确认，抖音在普通后台切换时 `startPausing()` 已拿到真实
`Task{#17283 A=10418:com.ss.android.ugc.aweme}`，系统 Task 快照也明确包含
`mActivityComponent=com.ss.android.ugc.aweme/.main.MainActivity`。但 Controller 的
`taskPackage()` 仍只查询 top Activity 与 `realActivity`，在 Recents 动画期间这些值可能为空，
导致 `sessionForTask()` 无法为后台-only App 建立 Session。

5.3.8 增加稳定回退：

- top Activity 取不到包名时，直接对真实 Task 调用统一后的 `objectPackage(task)`；
- 可从 Task 的 `mActivityComponent / intent / baseIntent / mPackageName` 等身份字段解析；
- 再增加 `mLastPausedActivity` 回退，覆盖 Recents 动画已经开始暂停的瞬间；
- 成功解析后直接由 `sessionForTask()` 建立后台 Session，不再依赖此前是否收到 RESUMED 捕获或有效 OPlus TaskInfo 包名。

目标链路：
`startPausing(Task) → taskPackage → BACKGROUND_PROTECTED → BACKGROUND_PAUSE_BLOCK → BACKGROUND_NOTIFICATION_SHOW`。

Bootstrap API 仍为 4，只修改 HotReload Engine，可直接热重载。

## 5.3.7 — 修复后台-only App 的 TaskInfo 包名识别

5.3.6 已允许后台播放名单建立 OPlus Task Session，但新诊断确认 Controller 自己的
`taskInfoPackage()` 提取逻辑比 Bootstrap 侧更窄，部分 OPlus TaskInfo（例如抖音）在
GuardModule 能识别出包名，进入 HotReload Engine 后却变成 `null`，因此仍无法创建 Session。

5.3.7 将 Controller 的包名识别补齐：

- 支持 `packageName` 和 `mPackageName`；
- 支持 `Intent` 的 component/package；
- 支持 `baseIntent / intent / mIntent`；
- 支持 `topActivity / baseActivity / realActivity / mActivityComponent`；
- 支持 `topActivityInfo`；
- 支持 `topRunningActivity()` 和 `getTopNonFinishingActivity()` 回退；
- 统一包名 normalize，避免 `package/class` 形式造成名单匹配失败。

这样只加入“后台播放应用”的 App 也能真正建立 Session，随后进入
`BACKGROUND_PROTECTED → BACKGROUND_PAUSE_BLOCK → 音频检测 → BACKGROUND_NOTIFICATION_SHOW`。

Bootstrap API 仍为 4，本版只有 HotReload Engine 改动，不需要新增完整重启。

## 5.3.6 — 后台-only App Session + 目标 App 图标

修复只加入“后台播放应用”但没有加入“始终前台应用”的 App（例如抖音）无法建立 OPlus Task Session：

- `onOplusTaskInfoChanged()` 现在使用 `wantsPackage()`，同时接受始终前台名单和后台播放名单；
- 后台-only App 可以正常进入 `BACKGROUND_PROTECTED`、触发音频检测和后台通知；
- OPlus 小窗/FloatHandle/锁屏小窗保护仍然继续严格检查 `foregroundPackage`，不会因为加入后台播放名单而自动获得小窗保护；
- Task 真正消失或 Task 切换为其他包时会撤销旧后台通知，避免残留。

通知显示优化：

- 状态栏 small icon 仍使用 MiniWindowGuard 的单色通知图标，符合 Android 通知栏限制；
- 展开通知后使用对应目标 App 的真实应用图标作为 large icon；
- 点击通知仍直接返回对应 App。

Bootstrap API 仍为 4，本版没有新增固定 system_server Hook，可热重载。

## 5.3.5 — 普通后台播放常驻通知

新增只针对普通 `BACKGROUND_PROTECTED` 状态的通知：

- 仅普通后台显示；OPlus 小窗、FloatHandle、单纯前台锁屏不显示；
- 进入普通后台且目标 App 仍有活跃音频播放时显示；
- 通知使用 ongoing 模式，普通滑动不可清除；
- 点击通知直接返回对应目标 App；
- 返回目标 App 前台时立即撤销通知；
- system_server 使用 `AudioManager.AudioPlaybackCallback` 监听真实音频状态；
- 当目标 App 已无 active playback configuration 时自动撤销通知；
- 多个后台播放 App 各自显示独立通知；
- 通知由 MiniWindowGuard 发布，不伪装成目标 App；
- Android 13+ 首次需要授予通知权限。

Bootstrap API 仍为 4，本版未新增固定 system_server Hook；Bootstrap 4 环境可直接热重载。

## 5.3.4 — 兼容 Home/Recents 的 userLeaving=false 路径

新诊断确认 OxygenOS 16 普通全屏按 Home/手势退后台存在另一条合法路径：
`TaskFragment.startPausing(... userLeaving=false, reason=pauseInRecentsAnim)`。
这条路径没有传入可用的 resuming Activity，因此 5.3.3 没有建立 `BACKGROUND_PROTECTED`，
随后继续执行 `wm_pause_activity → wm_stop_activity → STOP_ACTIVITY_ITEM`。

5.3.4 调整普通后台状态识别：

- 后台播放名单遇到 `pauseInRecentsAnim` 或 `pauseBackTasks` 时可直接建立 `BACKGROUND_PROTECTED`；
- 不再依赖 `userLeaving=true` 或 resuming Activity 一定存在；
- 真实 OPlus FloatHandle 的 edge pause guard 仍然优先执行，因此不会破坏小窗路径；
- 建立状态后继续复用 5.3.3 的 STOP 阶段保护；
- Bootstrap API 仍为 4，本版只修改热加载 Engine 逻辑。

## 5.3.3 — 修复普通后台进入 STOPPED 后暂停

5.3.2 已确认普通后台名单同步成功，且 `BACKGROUND_PAUSE_BLOCK` 能命中。
新诊断进一步确认：OxygenOS 的 Recents/Home 过渡在 pause 被拦截后，仍会在过渡结束时调用
`ActivityRecord.stopIfPossible()`。Android 随后发送 `StopActivityItem`；客户端为了执行 Stop 会先补一次
`performPause()`，因此播放器仍然停止。

5.3.3 新增普通后台 STOP 阶段保护：

- 只对已经进入 `BACKGROUND_PROTECTED` 的后台播放名单 Activity 生效；
- 继续允许窗口变为不可见，因此桌面/其他 App 正常显示和获得焦点；
- 拦截 `ActivityRecord.stopIfPossible()`，不向目标 App 发送 `StopActivityItem`；
- 新增 `BACKGROUND_STOP_SUPPRESS / BACKGROUND_STOP_BLOCK` 日志；
- Activity finishing、强制停止、更新和真实关闭不使用此保护；
- 返回目标 App 时仍由现有焦点状态自动退出 `BACKGROUND_PROTECTED`；
- 不修改已经稳定的小窗/FloatHandle 路径。

> 本版新增固定 system_server Hook，Bootstrap API 升级为 4，安装后需要完整重启一次。

## 5.3.2 — 修复普通后台/普通锁屏名单未同步

5.3.0 加入了 `background_playback_packages`，但 App 侧 `GuardApp.syncAll()` 的
`STRING_SET_KEYS` 漏掉了这个新 key。结果是界面可以勾选“后台播放应用”，但 LSPosed/system_server
永远收不到该名单，因此：

- 普通全屏切后台不会出现 `BACKGROUND_PAUSE_SUPPRESS / BACKGROUND_PAUSE_BLOCK`；
- 普通全屏锁屏仍会执行 `wm_pause_activity ... reason=sleep`；
- 只有原来的 OPlus 小窗名单继续有效。

5.3.2 修复：

- 将 `BACKGROUND_PLAYBACK_PACKAGES` 加入 Remote Preferences 同步；
- 已经勾选的后台播放应用会在新版本启动后自动同步，无需重新勾选；
- 诊断摘要新增 `background_playback_packages=[...]`；
- 诊断目标列表也包含后台播放应用；
- 不修改 5.3.1 已稳定的小窗/FloatHandle 状态机；
- Bootstrap API 仍为 3，本版本没有新增固定 system_server Hook。

> 如果当前已经运行 5.3.1 / Bootstrap API 3，安装 5.3.2 后不需要完整重启；让 App 启动并连接 LSPosed 后即可同步配置，Engine 可自动热重载。

## 5.3.1 — 修复 FloatHandle 重复开关后停止播放

诊断确认：第一次缩成 FloatHandle 后，从图标重新打开小窗时，OPlus 会执行
`FloatHandleController.startActivityByFloatInfo()` 并移除原 FloatHandle。旧逻辑没有在这个明确的“恢复为小窗”事件上清理
`edgeHung / edgeMinimizeRequested`，导致第二次缩小时可能走 `pending_exit_to=6 / exitTo:6`，
随后出现 `pauseInRecentsAnim → moveTaskToBack → STOPPED`。

5.3.1 新增原生 FloatHandle 恢复 Hook：

- 点击侧边图标打开小窗时立即触发 `OPLUS_EDGE_RESTORE`；
- 清理上一次缩小遗留的 `edgeHung` 和 `edgeMinimizeRequested`；
- 下一次缩小时重新按新的 OPlus FloatHandle 事件建立保护状态；
- 不使用延迟，不根据动画时间猜测；
- 不修改 5.2.0 已验证成功的第一次缩小/锁屏保活逻辑；
- 5.3.0 的普通后台播放状态保持不变。

> Bootstrap API 升级为 3，安装后需要完整重启一次。

## 5.3.0 — 普通后台播放状态

5.3.0 在已经稳定的 5.2.0 OPlus 小窗状态机旁边增加独立的 `BACKGROUND_PROTECTED`，不改变原有小窗逻辑。

- 新增“后台播放应用”名单；
- 普通全屏 App 切到不同包、Home/用户离开，或屏幕 sleep 时才进入 `BACKGROUND_PROTECTED`；
- `TaskFragment.startPausing()` 直接依据当前 Activity、resuming Activity、`userLeaving` 和 `uiSleeping` 判断，不使用延迟；
- 同 App 内页面切换正常 pause/resume，不拦；
- Activity finishing、强制停止和应用更新正常放行；
- 返回受保护 App、重新获得焦点时自动退出 `BACKGROUND_PROTECTED`；
- 后台状态复用现有 TOP / hasResumedActivity / Hans / freezer / stopUid 保护；
- 用户从最近任务划掉普通后台 App 时允许正常关闭，不套用小窗的 task-removal kill guard；
- 小窗/FloatHandle/锁屏小窗继续完全使用 5.2.0 的原逻辑。

> 5.3.0 的 Bootstrap API 升级为 2。旧 Bootstrap 不会错误热加载本版本；安装后需要完整重启一次。

## 5.2.0 — 状态驱动生命周期守护

5.2.0 不再依赖“锁屏前提前几毫秒”或“动画结束后再补救”的时序方案。

核心改动：

- 锁屏：只要任务仍是“始终前台白名单 + 真实 OPlus FlexibleWindow”，`TaskFragment.sleepIfPossible()` 从第一次睡眠请求开始就保持该 Activity，不等待 `onScreenLockedChanged`；
- 锁屏可见性：按任务真实 sleeping 状态判断，不再依赖同线程 `ThreadLocal`；
- 侧边小图标：监听 OPlus `notifyFlexibleTaskEvent`，仅 `event=2002`（缩成 FloatHandle）进入 MINIMIZING 状态；
- 仅在 MINIMIZING/EDGE_HIDDEN 状态拦截 `startPausing(..., "pauseInRecentsAnim")`；
- `event=2003`（真实退到后台/关闭路径）会取消侧边保活，不阻止正常 pause/stop；
- 关机 `shuttingDown=true` 时完全放行，不干扰系统正常关机；
- 继续保留现有 kill/freezer/stopUid/importance 保护。

这套逻辑依赖“当前任务状态 + OPlus 明确事件语义”，不依赖毫秒延迟。

> 5.2.0 修改了 system_server Bootstrap Hook，安装后需要完整重启一次。

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
