package com.yagay.MiniWindowGuard;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor();

    private TextView engineStatus;
    private TextView diagnosticsStatus;
    private TextView widthLabel;
    private TextView heightLabel;
    private Button diagnosticsExport;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(20), dp(18), dp(36));
        root.setBackgroundColor(0xFFF5F6F8);
        scroll.addView(root);

        addHeader(root);
        addEngineCard(root);
        addLauncherCard(root);
        addForegroundCard(root);
        addWindowCard(root);
        addDiagnosticsCard(root);
        addAboutCard(root);

        setContentView(scroll);
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        GuardApp.syncAll();
        refreshStatus();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void addHeader(LinearLayout parent) {
        TextView title = new TextView(this);
        title.setText("小窗守护");
        title.setTextSize(30);
        title.setTextColor(0xFF111318);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        parent.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("VirtualDisplay 小窗 · System Scope");
        subtitle.setTextSize(15);
        subtitle.setTextColor(0xFF656A73);
        subtitle.setPadding(0, dp(6), 0, dp(16));
        parent.addView(subtitle);
    }

    private void addEngineCard(LinearLayout parent) {
        LinearLayout card = card(
                parent,
                "运行状态",
                "核心运行在 system_server。目标 App 不需要加入 LSPosed 作用域。");

        engineStatus = statusLine("System 引擎：检测中…");
        card.addView(engineStatus);

        card.addView(statusLine(
                "LSPosed 固定作用域：system / system_server"));

        addSwitch(
                card,
                "启用小窗守护",
                "关闭后不创建 VirtualDisplay，也不应用始终前台保护。",
                ConfigKeys.MASTER_ENABLED);

        addSwitch(
                card,
                "自动热重载",
                "安装新版 APK 后，如果当前没有活动小窗，system_server 会自动加载新版 Engine，不需要重启手机。",
                ConfigKeys.ENGINE_AUTO_RELOAD);

        Button reload = button("立即重新加载 System Engine");
        reload.setOnClickListener(v -> {
            if (!GuardApp.isHotReloadAvailable()) {
                Toast.makeText(
                        this,
                        "当前 system_server 还是旧 Bootstrap。安装这一版后需要最后重启一次，之后才能热重载。",
                        Toast.LENGTH_LONG).show();
                return;
            }

            boolean sent = GuardApp.requestEngineReload();
            Toast.makeText(
                    this,
                    sent
                            ? "已请求重新加载 Engine。当前小窗会关闭并恢复到原屏幕。"
                            : "热重载请求同步失败，请重新检测 LSPosed 连接。",
                    Toast.LENGTH_LONG).show();

            if (engineStatus != null) {
                engineStatus.postDelayed(
                        this::refreshStatus,
                        2500L);
            }
        });
        card.addView(reload);

        Button refresh = button("重新检测");
        refresh.setOnClickListener(v -> refreshStatus());
        card.addView(refresh);
    }

    private void addLauncherCard(LinearLayout parent) {
        LinearLayout card = card(
                parent,
                "VirtualDisplay 小窗",
                "完全重写的 system_server VirtualDisplay 引擎："
                        + "使用稳定 TextureView Surface，Surface 就绪后再迁移 Task；"
                        + "不使用原开源项目窗口实现源码。");

        Button openApps = button("选择应用并打开小窗");
        openApps.setOnClickListener(v -> {
            try {
                startActivity(
                        new Intent(this, TargetAppsActivity.class));
            } catch (Throwable t) {
                CrashStore.record(
                        this,
                        "MainActivity.openAppList",
                        t);
                Toast.makeText(
                        this,
                        "打开应用列表失败："
                                + t.getClass().getSimpleName(),
                        Toast.LENGTH_LONG).show();
            }
        });
        card.addView(openApps);

        card.addView(detailBlock(
                "窗口操作",
                "标题栏直接提供返回、缩小成图标、隐藏和关闭；"
                        + "不再使用旧三点菜单。图标/隐藏只把宿主窗口移出屏幕，"
                        + "VirtualDisplay 与 TextureView Surface 保持存活，点击恢复控件即可还原。"));
    }

    private void addForegroundCard(LinearLayout parent) {
        LinearLayout card = card(
                parent,
                "始终前台",
                "只保留 MiniWindowGuard 原来的前台保护思路。"
                        + "VirtualDisplay 负责显示，system_server 负责让容器中的 App"
                        + "保持前台级进程状态和 Activity 生命周期。");

        addSwitch(
                card,
                "进程状态保持 TOP",
                "ActivityManager 对当前 VirtualDisplay 容器中的 App"
                        + "返回前台级进程状态。",
                ConfigKeys.SYSTEM_IMPORTANCE_TOP);

        addSwitch(
                card,
                "系统视为存在 Resumed Activity",
                "ActivityTaskManager 对容器 App 的 UID"
                        + "视为仍存在 Resumed Activity。",
                ConfigKeys.SYSTEM_HAS_RESUMED);

        addSwitch(
                card,
                "保持 Resumed",
                "阻止容器中的顶层 Activity 因主屏切换而进入 pause/stop。",
                ConfigKeys.SYSTEM_KEEP_CONTAINER_RESUMED);

        addSwitch(
                card,
                "保持 Visible",
                "目标 Task 位于 VirtualDisplay 时持续保持逻辑可见，"
                        + "避免 OEM 因主屏焦点变化把窗口变为不可见。",
                ConfigKeys.SYSTEM_KEEP_CONTAINER_VISIBLE);

        addSwitch(
                card,
                "阻止最近任务清理强杀",
                "仅保护已经进入 VirtualDisplay 的 App；"
                        + "应用更新和明确强制停止仍然放行。",
                ConfigKeys.SYSTEM_BLOCK_REMOVE_KILL);
    }

    private void addWindowCard(LinearLayout parent) {
        LinearLayout card = card(
                parent,
                "小窗显示与兼容性",
                "先用安全尺寸建立视频，再让视频跟随窗口动态调整。");

        addSwitch(
                card,
                "安全启动模式（推荐）",
                "首次创建小窗时，先使用已验证兼容的内部尺寸，"
                        + "避免视频 SurfaceView / MediaCodec 在启动阶段黑屏。",
                ConfigKeys.SAFE_INITIAL_DISPLAY);

        int internalScale = ConfigKeys.sanitizePercent(
                GuardApp.getInt(ConfigKeys.INTERNAL_DISPLAY_SCALE),
                48);
        TextView internalScaleLabel =
                label("安全启动比例：" + internalScale + "%");
        card.addView(internalScaleLabel);

        SeekBar internalScaleSeek = percentSeek(internalScale);
        internalScaleSeek.setOnSeekBarChangeListener(
                new SimpleSeekListener() {
                    @Override
                    public void onProgressChanged(
                            SeekBar seekBar,
                            int progress,
                            boolean fromUser
                    ) {
                        int value = Math.min(95, progress + 30);
                        internalScaleLabel.setText(
                                "安全启动比例：" + value + "%");
                        if (fromUser) {
                            GuardApp.putInt(
                                    ConfigKeys.INTERNAL_DISPLAY_SCALE,
                                    value);
                        }
                    }
                });
        card.addView(internalScaleSeek);

        addSwitch(
                card,
                "启动后同步视频尺寸",
                "开启后，小窗拖动缩放时先实时预览；松手后再调整 VirtualDisplay，"
                        + "让目标 App 和视频真正适配新的窗口长宽。"
                        + "关闭后则保持 4.4.1 的固定内部画布模式。",
                ConfigKeys.FOLLOW_WINDOW_AFTER_START);

        int settleMs = Math.max(
                0,
                Math.min(
                        5000,
                        GuardApp.getInt(
                                ConfigKeys.STARTUP_SETTLE_MS)));
        TextView settleLabel =
                label("启动保护时间：" + settleMs + "ms");
        card.addView(settleLabel);

        SeekBar settleSeek = new SeekBar(this);
        settleSeek.setMax(50);
        settleSeek.setProgress(settleMs / 100);
        settleSeek.setOnSeekBarChangeListener(
                new SimpleSeekListener() {
                    @Override
                    public void onProgressChanged(
                            SeekBar seekBar,
                            int progress,
                            boolean fromUser
                    ) {
                        int value = progress * 100;
                        settleLabel.setText(
                                "启动保护时间：" + value + "ms");
                        if (fromUser) {
                            GuardApp.putInt(
                                    ConfigKeys.STARTUP_SETTLE_MS,
                                    value);
                        }
                    }
                });
        card.addView(settleSeek);

        card.addView(detailBlock(
                "推荐设置",
                "安全启动模式：开启；安全启动比例：48%；"
                        + "启动后同步视频尺寸：开启；启动保护时间：1500ms。"
                        + "这样第一次先保证视频正常建立，之后窗口改变时视频也会真正跟随。"));

        card.addView(detailBlock(
                "普通初始尺寸",
                "关闭安全启动模式时，下面的宽度和高度直接决定首次 VirtualDisplay 尺寸。"
                        + "开启安全启动模式时，第一次会优先使用上面的安全启动比例。"));

        int width = ConfigKeys.sanitizePercent(
                GuardApp.getInt(ConfigKeys.CONTAINER_WIDTH),
                58);
        widthLabel = label("普通初始宽度：" + width + "%");
        card.addView(widthLabel);

        SeekBar widthSeek = percentSeek(width);
        widthSeek.setOnSeekBarChangeListener(
                new SimpleSeekListener() {
                    @Override
                    public void onProgressChanged(
                            SeekBar seekBar,
                            int progress,
                            boolean fromUser
                    ) {
                        int value = Math.min(95, progress + 30);
                        widthLabel.setText(
                                "普通初始宽度：" + value + "%");
                        if (fromUser) {
                            GuardApp.putInt(
                                    ConfigKeys.CONTAINER_WIDTH,
                                    value);
                        }
                    }
                });
        card.addView(widthSeek);

        int height = ConfigKeys.sanitizePercent(
                GuardApp.getInt(ConfigKeys.CONTAINER_HEIGHT),
                66);
        heightLabel = label("普通初始高度：" + height + "%");
        card.addView(heightLabel);

        SeekBar heightSeek = percentSeek(height);
        heightSeek.setOnSeekBarChangeListener(
                new SimpleSeekListener() {
                    @Override
                    public void onProgressChanged(
                            SeekBar seekBar,
                            int progress,
                            boolean fromUser
                    ) {
                        int value = Math.min(95, progress + 30);
                        heightLabel.setText(
                                "普通初始高度：" + value + "%");
                        if (fromUser) {
                            GuardApp.putInt(
                                    ConfigKeys.CONTAINER_HEIGHT,
                                    value);
                        }
                    }
                });
        card.addView(heightSeek);

        int minWidth = Math.max(
                120,
                Math.min(
                        320,
                        GuardApp.getInt(
                                ConfigKeys.OUTER_MIN_WIDTH_DP)));
        TextView minWidthLabel =
                label("最小窗口宽度：" + minWidth + "dp");
        card.addView(minWidthLabel);

        SeekBar minWidthSeek = new SeekBar(this);
        minWidthSeek.setMax(200);
        minWidthSeek.setProgress(minWidth - 120);
        minWidthSeek.setOnSeekBarChangeListener(
                new SimpleSeekListener() {
                    @Override
                    public void onProgressChanged(
                            SeekBar seekBar,
                            int progress,
                            boolean fromUser
                    ) {
                        int value = progress + 120;
                        minWidthLabel.setText(
                                "最小窗口宽度：" + value + "dp");
                        if (fromUser) {
                            GuardApp.putInt(
                                    ConfigKeys.OUTER_MIN_WIDTH_DP,
                                    value);
                        }
                    }
                });
        card.addView(minWidthSeek);

        int minHeight = Math.max(
                160,
                Math.min(
                        480,
                        GuardApp.getInt(
                                ConfigKeys.OUTER_MIN_HEIGHT_DP)));
        TextView minHeightLabel =
                label("最小窗口高度：" + minHeight + "dp");
        card.addView(minHeightLabel);

        SeekBar minHeightSeek = new SeekBar(this);
        minHeightSeek.setMax(320);
        minHeightSeek.setProgress(minHeight - 160);
        minHeightSeek.setOnSeekBarChangeListener(
                new SimpleSeekListener() {
                    @Override
                    public void onProgressChanged(
                            SeekBar seekBar,
                            int progress,
                            boolean fromUser
                    ) {
                        int value = progress + 160;
                        minHeightLabel.setText(
                                "最小窗口高度：" + value + "dp");
                        if (fromUser) {
                            GuardApp.putInt(
                                    ConfigKeys.OUTER_MIN_HEIGHT_DP,
                                    value);
                        }
                    }
                });
        card.addView(minHeightSeek);

        card.addView(detailBlock(
                "缩放过程",
                "拖动过程中只缩放最后一帧，避免连续 display configuration change；"
                        + "松手后才提交一次新的 VirtualDisplay 尺寸。"
                        + "提交以后触摸坐标会重新按新的内部尺寸工作。"));
    }

    private void addDiagnosticsCard(LinearLayout parent) {
        LinearLayout card = card(
                parent,
                "完整诊断",
                "保留原来的持续诊断和一键导出 ZIP。"
                        + "新版重点记录 VirtualDisplay、Task displayId、"
                        + "Surface、输入注入和前台保护事件。");

        diagnosticsStatus = statusLine("");
        card.addView(diagnosticsStatus);

        Switch persistentLog = new Switch(this);
        persistentLog.setText("持续开启详细诊断日志");
        persistentLog.setTextSize(15);
        persistentLog.setChecked(
                GuardApp.getBoolean(
                        ConfigKeys.DIAGNOSTICS_ACTIVE));
        persistentLog.setOnCheckedChangeListener(
                (button, checked) -> {
                    if (checked) {
                        DiagnosticsManager.startSession();
                        Toast.makeText(
                                this,
                                "详细日志已持续开启。",
                                Toast.LENGTH_SHORT).show();
                    } else {
                        DiagnosticsManager.stopSession();
                        Toast.makeText(
                                this,
                                "详细日志已关闭。",
                                Toast.LENGTH_SHORT).show();
                    }
                    refreshDiagnosticsStatus();
                });
        card.addView(persistentLog);

        card.addView(detailBlock(
                "诊断重点",
                "• VD_WINDOW_CREATED / VD_SURFACE_READY / VD_TASK_MOVED\n"
                        + "• VD_FOCUS / VD_INPUT_DOWN / VD_INPUT_ERROR\n"
                        + "• VD_RESIZE_COMMIT / VD_MINIMIZED / VD_HIDDEN / VD_RESTORE\n"
                        + "• Activity pause/visible/resumed 拦截\n"
                        + "• 进程状态、Audio、MediaSession、Window、Task 快照\n"
                        + "• 最近 30000 行 logcat"));

        diagnosticsExport = button("导出诊断 ZIP");
        diagnosticsExport.setOnClickListener(
                v -> exportDiagnostics());
        card.addView(diagnosticsExport);

        Button reset = button("重置诊断起点");
        reset.setOnClickListener(v -> {
            if (GuardApp.getBoolean(
                    ConfigKeys.DIAGNOSTICS_ACTIVE)) {
                DiagnosticsManager.startSession();
                Toast.makeText(
                        this,
                        "诊断起点已重置。",
                        Toast.LENGTH_SHORT).show();
                refreshDiagnosticsStatus();
            } else {
                Toast.makeText(
                        this,
                        "请先打开持续诊断日志。",
                        Toast.LENGTH_SHORT).show();
            }
        });
        card.addView(reset);
    }

    private void addAboutCard(LinearLayout parent) {
        LinearLayout card = card(
                parent,
                "架构说明",
                "窗口引擎已经完全重写。YAMF/YAMF² 与 FreeformShell"
                        + "只作为公开架构思路参考，不复制其实现源码；"
                        + "MiniWindowGuard 使用自己的 VirtualDisplay、输入、"
                        + "前台保护和诊断实现。");

        card.addView(detailBlock(
                "许可证",
                "本项目采用 GPLv3。仓库中包含 LICENSE"
                        + " 和 THIRD_PARTY_NOTICES.md。"));
    }

    private void exportDiagnostics() {
        if (diagnosticsExport != null) {
            diagnosticsExport.setEnabled(false);
        }

        Toast.makeText(
                this,
                "正在收集 VirtualDisplay、system_server 和系统状态…",
                Toast.LENGTH_SHORT).show();

        executor.execute(() -> {
            DiagnosticsManager.ExportResult result =
                    DiagnosticsManager.export(this);

            runOnUiThread(() -> {
                if (diagnosticsExport != null) {
                    diagnosticsExport.setEnabled(true);
                }

                refreshDiagnosticsStatus();

                if (!result.ok()) {
                    Toast.makeText(
                            this,
                            "导出失败：" + result.error,
                            Toast.LENGTH_LONG).show();
                    return;
                }

                Toast.makeText(
                        this,
                        "已保存到 Download/MiniWindowGuard/"
                                + result.fileName,
                        Toast.LENGTH_LONG).show();

                try {
                    Intent share = new Intent(
                            Intent.ACTION_SEND);
                    share.setType("application/zip");
                    share.putExtra(
                            Intent.EXTRA_STREAM,
                            result.uri);
                    share.addFlags(
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(
                            Intent.createChooser(
                                    share,
                                    "分享诊断 ZIP"));
                } catch (Throwable ignored) {
                }
            });
        });
    }

    private void refreshStatus() {
        if (engineStatus != null) {
            long expected =
                    GuardApp.getExpectedVersionCode();
            long loaded =
                    GuardApp.getLoadedEngineVersionCode();

            boolean connected =
                    GuardApp.isXposedServiceConnected();
            boolean hasSystem =
                    GuardApp.hasSystemScope();
            boolean active =
                    GuardApp.isSystemEngineActive();
            boolean current =
                    GuardApp.isSystemEngineCurrent();

            if (!connected) {
                engineStatus.setText(
                        "System 引擎：LSPosed 服务未连接");
                engineStatus.setTextColor(0xFFB3261E);
            } else if (!hasSystem) {
                engineStatus.setText(
                        "System 引擎：作用域缺少 system · 实际="
                                + GuardApp.getFrameworkScope());
                engineStatus.setTextColor(0xFFB3261E);
            } else if (current) {
                if (GuardApp.isHotReloadAvailable()) {
                    engineStatus.setText(
                            "System Engine：code "
                                    + loaded
                                    + " · Bootstrap "
                                    + GuardApp.getBootstrapVersionCode()
                                    + " · 热重载可用"
                                    + " · gen "
                                    + GuardApp.getEngineGeneration()
                                    + " · 活动小窗 "
                                    + GuardApp.getEngineActiveSessions()
                                    + "\n"
                                    + GuardApp.getEngineReloadMessage());
                } else {
                    engineStatus.setText(
                            "System 引擎：已激活当前版本 · code "
                                    + loaded
                                    + " · 当前 Bootstrap 不支持热重载");
                }
                engineStatus.setTextColor(0xFF16794A);
            } else if (active) {
                if (GuardApp.isHotReloadAvailable()) {
                    engineStatus.setText(
                            "System Engine：旧 Engine "
                                    + loaded
                                    + " / 已安装 APK "
                                    + expected
                                    + " · 热重载可用"
                                    + " · 活动小窗 "
                                    + GuardApp.getEngineActiveSessions()
                                    + "\n"
                                    + "无活动小窗时会自动更新，也可以点击“立即重新加载”。");
                } else {
                    engineStatus.setText(
                            "System 引擎：当前仍是旧 Bootstrap · system="
                                    + loaded
                                    + " / App="
                                    + expected
                                    + "\n安装这一版后需要最后重启一次；以后更新 APK 不再需要重启手机。");
                }
                engineStatus.setTextColor(0xFF9A6700);
            } else {
                engineStatus.setText(
                        "System 引擎：未检测到有效心跳 · scope="
                                + GuardApp.getFrameworkScope());
                engineStatus.setTextColor(0xFFB3261E);
            }
        }

        refreshDiagnosticsStatus();
    }

    private void refreshDiagnosticsStatus() {
        if (diagnosticsStatus == null) return;

        boolean active =
                GuardApp.getBoolean(
                        ConfigKeys.DIAGNOSTICS_ACTIVE);
        String started =
                GuardApp.getString(
                        ConfigKeys.DIAGNOSTICS_STARTED_AT);

        if (active) {
            diagnosticsStatus.setText(
                    "详细日志：持续开启"
                            + (started.isEmpty()
                            ? ""
                            : " · start=" + started));
            diagnosticsStatus.setTextColor(0xFFB3261E);
        } else {
            diagnosticsStatus.setText("详细日志：关闭");
            diagnosticsStatus.setTextColor(0xFF16794A);
        }
    }

    private void addSwitch(
            LinearLayout parent,
            String title,
            String description,
            String key
    ) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);

        TextView titleView = label(title);
        texts.addView(titleView);

        TextView descriptionView = new TextView(this);
        descriptionView.setText(description);
        descriptionView.setTextSize(12.5f);
        descriptionView.setTextColor(0xFF6C717A);
        descriptionView.setPadding(
                0,
                dp(2),
                dp(8),
                0);
        texts.addView(descriptionView);

        row.addView(
                texts,
                new LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f));

        Switch toggle = new Switch(this);
        toggle.setChecked(
                GuardApp.getBoolean(key));
        toggle.setOnCheckedChangeListener(
                (button, checked) ->
                        GuardApp.putBoolean(
                                key,
                                checked));

        row.addView(toggle);
        parent.addView(row);
    }

    private SeekBar percentSeek(int value) {
        SeekBar seek = new SeekBar(this);
        seek.setMax(65);
        seek.setProgress(
                Math.max(0, Math.min(65, value - 30)));
        return seek;
    }

    private LinearLayout card(
            LinearLayout parent,
            String title,
            String subtitle
    ) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(
                dp(16),
                dp(14),
                dp(16),
                dp(14));

        GradientDrawable background =
                new GradientDrawable();
        background.setColor(0xFFFFFFFF);
        background.setCornerRadius(dp(16));
        card.setBackground(background);

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(12));
        parent.addView(card, params);

        TextView heading = new TextView(this);
        heading.setText(title);
        heading.setTextSize(19);
        heading.setTypeface(
                Typeface.DEFAULT,
                Typeface.BOLD);
        card.addView(heading);

        TextView sub = new TextView(this);
        sub.setText(subtitle);
        sub.setTextSize(13);
        sub.setTextColor(0xFF6C717A);
        sub.setPadding(0, dp(4), 0, dp(8));
        card.addView(sub);

        return card;
    }

    private TextView statusLine(String text) {
        TextView view = label(text);
        view.setPadding(0, dp(4), 0, dp(4));
        return view;
    }

    private TextView label(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(15);
        view.setTextColor(0xFF202124);
        return view;
    }

    private TextView detailBlock(
            String title,
            String text
    ) {
        TextView view = new TextView(this);
        view.setText(title + "\n" + text);
        view.setTextSize(13.5f);
        view.setTextColor(0xFF41464F);
        view.setLineSpacing(0, 1.18f);
        view.setPadding(0, dp(8), 0, dp(8));
        return view;
    }

    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        return button;
    }

    private int dp(float value) {
        return Math.round(
                value * getResources()
                        .getDisplayMetrics()
                        .density);
    }

    private abstract static class SimpleSeekListener
            implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {}

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {}
    }
}
