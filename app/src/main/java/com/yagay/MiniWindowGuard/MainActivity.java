package com.yagay.MiniWindowGuard;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private TextView rootStatus;
    private TextView xposedStatus;
    private TextView engineStatus;
    private TextView overlayStatus;
    private TextView targetCount;
    private TextView diagnosticsStatus;
    private Button diagnosticsExport;
    private TextView widthLabel;
    private TextView heightLabel;

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

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
        addStatusCard(root);
        addTargetCard(root);
        addRootCard(root);
        addSystemCard(root);
        addWindowCard(root);
        addDiagnosticsCard(root);
        addPermissionDetails(root);
        addMaintenanceCard(root);

        setContentView(scroll);
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
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
        subtitle.setText("System Scope · Root + LSPosed");
        subtitle.setTextSize(15);
        subtitle.setTextColor(0xFF656A73);
        subtitle.setPadding(0, dp(6), 0, dp(16));
        parent.addView(subtitle);
    }

    private void addStatusCard(LinearLayout parent) {
        LinearLayout card = card(parent, "运行状态",
                "LSPosed 作用域已固定为 System Framework / system_server（system），不再需要手动给目标 App 勾作用域。");

        rootStatus = statusLine("Root：检测中…");
        card.addView(rootStatus);

        xposedStatus = statusLine("LSPosed：检测中…");
        card.addView(xposedStatus);

        engineStatus = statusLine("System 引擎：检测中…");
        card.addView(engineStatus);

        overlayStatus = statusLine("悬浮窗：检测中…");
        card.addView(overlayStatus);

        Button overlayPermission = button("授权悬浮窗控制层");
        overlayPermission.setOnClickListener(v -> {
            try {
                Intent intent = new Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Throwable t) {
                Toast.makeText(this,
                        "无法打开悬浮窗设置：" + t.getClass().getSimpleName(),
                        Toast.LENGTH_LONG).show();
            }
        });
        card.addView(overlayPermission);

        card.addView(statusLine("LSPosed 固定作用域：system / system_server"));
        card.addView(statusLine("模块包名：com.yagay.MiniWindowGuard"));

        addSwitch(card,
                "启用小窗守护",
                "关闭后 system_server Hook 仍加载，但不修改受保护 App 状态。",
                ConfigKeys.MASTER_ENABLED);

        Button check = button("重新检测");
        check.setOnClickListener(v -> refreshStatus());
        card.addView(check);
    }

    private void addTargetCard(LinearLayout parent) {
        LinearLayout card = card(parent, "受保护应用",
                "勾选后立即自动保存。只有首页显示“System 引擎：已加载当前版本”时，TaskSurface 容器才会生效。");

        targetCount = statusLine("");
        card.addView(targetCount);

        Button select = button("选择受保护应用");
        select.setOnClickListener(v -> {
            try {
                startActivity(new Intent(this, TargetAppsActivity.class));
            } catch (Throwable t) {
                CrashStore.record(this, "MainActivity.openTargetApps", t);
                Toast.makeText(
                        this,
                        "打开应用列表失败：" + t.getClass().getSimpleName(),
                        Toast.LENGTH_LONG).show();
            }
        });
        card.addView(select);

        Button applyRoot = button("重新应用 Root 策略");
        applyRoot.setOnClickListener(v -> executor.execute(() -> {
            RootPolicyManager.applyAll(this);
            runOnUiThread(() -> Toast.makeText(
                    this, "Root 策略已重新应用", Toast.LENGTH_SHORT).show());
        }));
        card.addView(applyRoot);
    }

    private void addRootCard(LinearLayout parent) {
        LinearLayout card = card(parent, "Root 系统保活",
                "Root 只由小窗守护自己使用，按受保护包名/UID统一应用。");

        addSwitch(card, "Root 系统保活",
                "总开关。", ConfigKeys.ROOT_KEEP_ALIVE);
        addSwitch(card, "Doze 白名单",
                "加入 deviceidle whitelist。", ConfigKeys.ROOT_DOZE_WHITELIST);
        addSwitch(card, "保持 Active 待机桶",
                "清除 inactive 并设为 active standby bucket。", ConfigKeys.ROOT_STANDBY_ACTIVE);
        addSwitch(card, "允许后台 AppOps",
                "允许 RUN_IN_BACKGROUND / RUN_ANY_IN_BACKGROUND。", ConfigKeys.ROOT_BACKGROUND_APPOPS);
        addSwitch(card, "后台网络白名单",
                "避免省流量策略切断后台网络。", ConfigKeys.ROOT_NETWORK_WHITELIST);
        addSwitch(card, "允许 WakeLock",
                "允许受保护 App 使用 WakeLock。", ConfigKeys.ROOT_WAKELOCK);
    }

    private void addSystemCard(LinearLayout parent) {
        LinearLayout card = card(parent, "System Framework 容器保护",
                "只在 system_server 管理真实 Task/Activity，不注入目标 App。");

        addSwitch(card, "系统进程状态返回 TOP",
                "ActivityManagerService 对受保护包/UID返回前台级进程状态。",
                ConfigKeys.SYSTEM_IMPORTANCE_TOP);

        addSwitch(card, "系统视为存在 Resumed Activity",
                "ActivityTaskManagerService.hasResumedActivity(uid) 返回 true。",
                ConfigKeys.SYSTEM_HAS_RESUMED);

        addSwitch(card, "容器任务保持 Resumed",
                "被 TaskSurface 容器接管后阻止 Activity 因窗口态/图标态/隐藏态被 pause。",
                ConfigKeys.SYSTEM_KEEP_CONTAINER_RESUMED);

        addSwitch(card, "容器任务保持 Visible",
                "system_server 可见性判断对已接管 Activity 保持 true，避免隐藏 Surface 时任务被停止。",
                ConfigKeys.SYSTEM_KEEP_CONTAINER_VISIBLE);
    }

    private void addWindowCard(LinearLayout parent) {
        LinearLayout card = card(parent, "自有 TaskSurface 小窗",
                "目标 App 仍是 display 0 上的真实 Task。MiniWindowGuard 直接控制 Task bounds/focus/Surface，不再调用 OPlus 小窗 API。");

        addSwitch(card, "自动接管受保护 App",
                "受保护 App 进入 RESUMED 后自动交给 TaskSurfaceController。",
                ConfigKeys.AUTO_CONTAINER);

        addSwitch(card, "窗口态置顶",
                "窗口态尝试设置 Task always-on-top。",
                ConfigKeys.CONTAINER_ALWAYS_ON_TOP);

        RadioGroup forms = new RadioGroup(this);
        forms.setOrientation(RadioGroup.VERTICAL);
        addForm(forms, ConfigKeys.STATE_WINDOW,
                "窗口", "真实 Task 缩放到自有窗口区域，触摸仍直接属于目标 App。");
        addForm(forms, ConfigKeys.STATE_ICON,
                "图标", "Task 保持运行，Surface 透明并移出屏幕，显示 MiniWindowGuard 图标。");
        addForm(forms, ConfigKeys.STATE_HIDDEN,
                "完全隐藏", "Task 保持运行但不显示任何图标，可从常驻通知恢复。");

        int currentState = ConfigKeys.sanitizeState(
                GuardApp.getInt(ConfigKeys.CONTAINER_DEFAULT_STATE));
        if (currentState == ConfigKeys.STATE_RELEASED) {
            currentState = ConfigKeys.STATE_WINDOW;
        }
        forms.check(300 + currentState);
        forms.setOnCheckedChangeListener((group, checkedId) -> {
            int state = ConfigKeys.sanitizeState(checkedId - 300);
            if (state == ConfigKeys.STATE_RELEASED) state = ConfigKeys.STATE_WINDOW;
            GuardApp.putInt(ConfigKeys.CONTAINER_DEFAULT_STATE, state);
        });
        card.addView(forms);

        int width = ConfigKeys.sanitizePercent(
                GuardApp.getInt(ConfigKeys.CONTAINER_WIDTH), 58);
        widthLabel = label("窗口宽度：" + width + "%");
        card.addView(widthLabel);
        SeekBar widthSeek = percentSeek(width);
        widthSeek.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = Math.min(95, progress + 30);
                widthLabel.setText("窗口宽度：" + value + "%");
                if (fromUser) GuardApp.putInt(ConfigKeys.CONTAINER_WIDTH, value);
            }
        });
        card.addView(widthSeek);

        int height = ConfigKeys.sanitizePercent(
                GuardApp.getInt(ConfigKeys.CONTAINER_HEIGHT), 66);
        heightLabel = label("窗口高度：" + height + "%");
        card.addView(heightLabel);
        SeekBar heightSeek = percentSeek(height);
        heightSeek.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = Math.min(95, progress + 30);
                heightLabel.setText("窗口高度：" + value + "%");
                if (fromUser) GuardApp.putInt(ConfigKeys.CONTAINER_HEIGHT, value);
            }
        });
        card.addView(heightSeek);
    }

    private void addDiagnosticsCard(LinearLayout parent) {
        LinearLayout card = card(parent, "完整诊断",
                "详细日志可以长期保持开启。打开一次后会持久保存，重启 App/手机后仍保持，不需要每次先点“开始诊断”。");

        diagnosticsStatus = statusLine("");
        card.addView(diagnosticsStatus);

        Switch persistentLog = new Switch(this);
        persistentLog.setText("持续开启详细诊断日志");
        persistentLog.setTextSize(15);
        persistentLog.setChecked(
                GuardApp.getBoolean(ConfigKeys.DIAGNOSTICS_ACTIVE));
        persistentLog.setOnCheckedChangeListener((button, checked) -> {
            if (checked) {
                DiagnosticsManager.startSession();
                Toast.makeText(
                        this,
                        "详细日志已持续开启；以后直接复现问题并导出 ZIP 即可。",
                        Toast.LENGTH_LONG).show();
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

        card.addView(detailBlock("诊断内容",
                "• system_server Hook 安装/命中和每次强制结果。\n"
                        + "• TaskSurface：taskId、bounds、windowing mode、容器状态、Surface alpha。\n"
                        + "• ActivityRecord pause/visible 决策和关键调用栈。\n"
                        + "• Root 策略命令及返回值。\n"
                        + "• Activity/Task/进程/OOM/Window/Doze/NetPolicy/Power/Audio/MediaSession 快照。\n"
                        + "• 每个受保护 App 的 package、AppOps、standby bucket、meminfo。\n"
                        + "• 最近 30000 行完整 logcat + 自动筛选后的重点日志。"));

        TextView privacy = detailBlock("注意",
                "持续日志会增加少量 logcat 输出，而且完整 logcat 可能包含其他应用和系统事件。排查期间可以一直开启，平时不需要时再关闭。");
        privacy.setTextColor(0xFF8A4B08);
        card.addView(privacy);

        diagnosticsExport = button("导出诊断 ZIP");
        diagnosticsExport.setOnClickListener(v -> exportDiagnostics());
        card.addView(diagnosticsExport);

        Button resetSession = button("重置诊断起点");
        resetSession.setOnClickListener(v -> {
            if (GuardApp.getBoolean(ConfigKeys.DIAGNOSTICS_ACTIVE)) {
                DiagnosticsManager.startSession();
                Toast.makeText(
                        this,
                        "诊断起点已重置，详细日志保持开启。",
                        Toast.LENGTH_SHORT).show();
                refreshDiagnosticsStatus();
            } else {
                Toast.makeText(
                        this,
                        "请先打开持续诊断日志。",
                        Toast.LENGTH_SHORT).show();
            }
        });
        card.addView(resetSession);
    }

    private void exportDiagnostics() {
        if (diagnosticsExport != null) diagnosticsExport.setEnabled(false);

        Toast.makeText(this,
                "正在收集 system_server、Root 和系统状态…",
                Toast.LENGTH_SHORT).show();

        executor.execute(() -> {
            DiagnosticsManager.ExportResult result = DiagnosticsManager.export(this);
            runOnUiThread(() -> {
                if (diagnosticsExport != null) diagnosticsExport.setEnabled(true);
                refreshDiagnosticsStatus();

                if (!result.ok()) {
                    Toast.makeText(this,
                            "导出失败：" + result.error,
                            Toast.LENGTH_LONG).show();
                    return;
                }

                Toast.makeText(this,
                        "已保存到 Download/MiniWindowGuard/" + result.fileName,
                        Toast.LENGTH_LONG).show();

                try {
                    Intent share = new Intent(Intent.ACTION_SEND);
                    share.setType("application/zip");
                    share.putExtra(Intent.EXTRA_STREAM, result.uri);
                    share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(share, "分享诊断 ZIP"));
                } catch (Throwable ignored) {
                    // The file is already in Downloads even if no share target is available.
                }
            });
        });
    }

    private void refreshDiagnosticsStatus() {
        if (diagnosticsStatus == null) return;

        boolean active = GuardApp.getBoolean(ConfigKeys.DIAGNOSTICS_ACTIVE);
        String started = GuardApp.getString(ConfigKeys.DIAGNOSTICS_STARTED_AT);

        if (active) {
            diagnosticsStatus.setText("详细日志：持续开启"
                    + (started.isEmpty() ? "" : " · start=" + started));
            diagnosticsStatus.setTextColor(0xFFB3261E);
        } else {
            diagnosticsStatus.setText("详细日志：关闭");
            diagnosticsStatus.setTextColor(0xFF16794A);
        }
    }

    private void addPermissionDetails(LinearLayout parent) {
        LinearLayout card = card(parent, "权限使用详情",
                "核心不依赖厂商小窗，也不 Hook 单独 App。");

        card.addView(detailBlock("LSPosed",
                "• 固定作用域：System Framework / system_server（system）。\n"
                        + "• system_server 直接管理 ActivityRecord、Task 和 SurfaceControl。\n"
                        + "• 不需要给红果、视频 App、浏览器等目标 App勾 LSPosed。"));

        card.addView(detailBlock("Root",
                "• Root 只授予 MiniWindowGuard。\n"
                        + "• 只用于 Doze、待机桶、后台 AppOps、网络和 WakeLock 保活策略。\n"
                        + "• Task/Surface 容器本身由 system_server LSPosed 完成。"));

        card.addView(detailBlock("悬浮窗权限",
                "• 只用于 MiniWindowGuard 自己的标题栏和图标。\n"
                        + "• 目标 App 的画面不是截图，也不是 Overlay View，而是真实 Task Surface。"));

        card.addView(detailBlock("容器状态",
                "• 窗口：真实 Task 在屏幕内，直接接收触摸。\n"
                        + "• 图标/隐藏：Task 保持同样尺寸，移出屏幕并把 Surface alpha 设为 0。\n"
                        + "• 释放：恢复接管前的 bounds 和 windowing mode。"));
    }

    private void addMaintenanceCard(LinearLayout parent) {
        LinearLayout card = card(parent, "维护",
                "修改 system_server Hook 设置后，重启手机最稳妥。受保护列表会通过 RemotePreferences 同步。");

        Button sync = button("立即同步 LSPosed 配置");
        sync.setOnClickListener(v -> Toast.makeText(this,
                GuardApp.syncAll() ? "已同步" : "LSPosed 配置服务未连接",
                Toast.LENGTH_SHORT).show());
        card.addView(sync);

        Button reset = button("恢复默认设置");
        reset.setOnClickListener(v -> {
            GuardApp.resetDefaults();
            Toast.makeText(this, "已恢复默认设置", Toast.LENGTH_SHORT).show();
            recreate();
        });
        card.addView(reset);
    }

    private void refreshStatus() {
        if (targetCount != null) {
            targetCount.setText("当前受保护：" + GuardApp.getTargetPackages().size() + " 个 App");
        }
        refreshDiagnosticsStatus();

        if (engineStatus != null) {
            long expected = GuardApp.getExpectedVersionCode();
            long loaded = GuardApp.getLoadedEngineVersionCode();
            boolean current = GuardApp.isSystemEngineCurrent();

            engineStatus.setText(current
                    ? "System 引擎：已加载当前版本 · code " + loaded
                    : "System 引擎：未加载当前版本 · App=" + expected
                            + " / system_server=" + loaded
                            + "（安装或更新模块后必须重启手机）");
            engineStatus.setTextColor(current ? 0xFF16794A : 0xFFB3261E);
        }

        if (overlayStatus != null) {
            boolean allowed = Settings.canDrawOverlays(this);
            overlayStatus.setText(allowed
                    ? "悬浮窗：已授权"
                    : "悬浮窗：未授权（仍可通过通知控制隐藏态）");
            overlayStatus.setTextColor(allowed ? 0xFF16794A : 0xFFB3261E);
        }

        if (xposedStatus != null) {
            boolean connected = GuardApp.isXposedServiceConnected();
            xposedStatus.setText(connected
                    ? "LSPosed：已连接 · " + GuardApp.getFrameworkName()
                    : "LSPosed：配置服务未连接");
            xposedStatus.setTextColor(connected ? 0xFF16794A : 0xFFB3261E);
        }

        if (rootStatus != null) {
            rootStatus.setText("Root：检测中…");
            rootStatus.setTextColor(0xFF656A73);
        }

        executor.execute(() -> {
            RootManager.RootStatus status = RootManager.checkAccess();
            runOnUiThread(() -> {
                if (rootStatus == null) return;
                rootStatus.setText(status.granted ? "Root：已授权" : "Root：未授权 / 不可用");
                rootStatus.setTextColor(status.granted ? 0xFF16794A : 0xFFB3261E);
            });
        });
    }

    private void addSwitch(LinearLayout parent, String title, String description, String key) {
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
        descriptionView.setPadding(0, dp(2), dp(8), 0);
        texts.addView(descriptionView);

        row.addView(texts, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch toggle = new Switch(this);
        toggle.setChecked(GuardApp.getBoolean(key));
        toggle.setOnCheckedChangeListener((button, checked) -> {
            GuardApp.putBoolean(key, checked);
            if (key.startsWith("root_")) {
                executor.execute(() -> RootPolicyManager.applyAll(this));
            }
        });
        row.addView(toggle);
        parent.addView(row);
    }

    private void addForm(RadioGroup group, int form, String title, String description) {
        RadioButton radio = new RadioButton(this);
        radio.setId(300 + form);
        radio.setText(title + "\n" + description);
        radio.setTextSize(14.5f);
        radio.setPadding(0, dp(5), 0, dp(6));
        group.addView(radio);
    }

    private SeekBar percentSeek(int value) {
        SeekBar seek = new SeekBar(this);
        seek.setMax(70);
        seek.setProgress(Math.max(0, Math.min(70, value - 30)));
        return seek;
    }

    private LinearLayout card(LinearLayout parent, String title, String subtitle) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));

        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFFFFFFFF);
        background.setCornerRadius(dp(16));
        card.setBackground(background);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(12));
        parent.addView(card, params);

        TextView heading = new TextView(this);
        heading.setText(title);
        heading.setTextSize(19);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
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

    private TextView detailBlock(String title, String text) {
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

    private abstract static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar seekBar) {}
        @Override public void onStopTrackingTouch(SeekBar seekBar) {}
    }
}
