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
    private TextView targetCount;
    private TextView diagnosticsStatus;
    private Button diagnosticsStart;
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
                "LSPosed 只需要给本模块勾选 Android/System Framework，不要再勾目标 App。");

        rootStatus = statusLine("Root：检测中…");
        card.addView(rootStatus);

        xposedStatus = statusLine("LSPosed：检测中…");
        card.addView(xposedStatus);

        card.addView(statusLine("LSPosed 作用域：android / System Framework"));
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
                "在这里选择 App。它们不需要加入 LSPosed 作用域，也不会被注入模块代码。");

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
        LinearLayout card = card(parent, "System Framework 虚拟前台",
                "这些 Hook 只运行在 system_server，不注入受保护 App。");

        addSwitch(card, "系统进程状态返回 TOP",
                "ActivityManagerService 对受保护包/UID返回 PROCESS_STATE_TOP。",
                ConfigKeys.SYSTEM_IMPORTANCE_TOP);

        addSwitch(card, "系统视为存在 Resumed Activity",
                "ActivityTaskManagerService.hasResumedActivity(uid) 返回 true。",
                ConfigKeys.SYSTEM_HAS_RESUMED);

        addSwitch(card, "Mini/隐藏态保持 Resumed",
                "仅当前 OPlus Zoom/Mini 任务属于受保护 App 时阻止系统发送 pause。",
                ConfigKeys.SYSTEM_KEEP_MINI_RESUMED);

        addSwitch(card, "强制 OPlus Multi-Resume",
                "OPlus Compact/Zoom Window 对受保护 App统一允许 Multi-Resume。",
                ConfigKeys.SYSTEM_OPLUS_MULTI_RESUME);

        addSwitch(card, "强制允许 OPlus 小窗",
                "厂商小窗支持检查包含受保护包时返回支持。",
                ConfigKeys.SYSTEM_FORCE_ZOOM_SUPPORT);
    }

    private void addWindowCard(LinearLayout parent) {
        LinearLayout card = card(parent, "小窗启动默认值",
                "用于从小窗守护主动以 Root 打开受保护 App。系统手动开启的小窗仍使用系统自己的大小。");

        addSwitch(card, "AOSP Freeform 兜底",
                "OPlus 小窗 API 不可用时尝试 Android Freeform。",
                ConfigKeys.AOSP_FREEFORM_FALLBACK);

        RadioGroup forms = new RadioGroup(this);
        forms.setOrientation(RadioGroup.VERTICAL);
        addForm(forms, ConfigKeys.FORM_WINDOW, "自由小窗", "保持普通系统小窗。");
        addForm(forms, ConfigKeys.FORM_ICON, "图标", "启动后缩成 Mini Zoom 图标。");
        addForm(forms, ConfigKeys.FORM_HIDDEN, "隐藏", "启动后进入 Mini，再隐藏图标。");
        forms.check(300 + ConfigKeys.sanitizeForm(
                GuardApp.getInt(ConfigKeys.SMALL_WINDOW_FORM)));
        forms.setOnCheckedChangeListener((group, checkedId) ->
                GuardApp.putInt(ConfigKeys.SMALL_WINDOW_FORM,
                        ConfigKeys.sanitizeForm(checkedId - 300)));
        card.addView(forms);

        int width = ConfigKeys.sanitizePercent(
                GuardApp.getInt(ConfigKeys.SMALL_WINDOW_WIDTH), 58);
        widthLabel = label("默认宽度：" + width + "%");
        card.addView(widthLabel);
        SeekBar widthSeek = percentSeek(width);
        widthSeek.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = progress + 30;
                widthLabel.setText("默认宽度：" + value + "%");
                if (fromUser) GuardApp.putInt(ConfigKeys.SMALL_WINDOW_WIDTH, value);
            }
        });
        card.addView(widthSeek);

        int height = ConfigKeys.sanitizePercent(
                GuardApp.getInt(ConfigKeys.SMALL_WINDOW_HEIGHT), 66);
        heightLabel = label("默认高度：" + height + "%");
        card.addView(heightLabel);
        SeekBar heightSeek = percentSeek(height);
        heightSeek.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = progress + 30;
                heightLabel.setText("默认高度：" + value + "%");
                if (fromUser) GuardApp.putInt(ConfigKeys.SMALL_WINDOW_HEIGHT, value);
            }
        });
        card.addView(heightSeek);
    }

    private void addDiagnosticsCard(LinearLayout parent) {
        LinearLayout card = card(parent, "完整诊断",
                "开始后再复现问题。详细日志只在诊断期间开启，结束后会导出系统状态和日志 ZIP。");

        diagnosticsStatus = statusLine("");
        card.addView(diagnosticsStatus);

        card.addView(detailBlock("诊断内容",
                "• system_server Hook 安装/命中和每次强制结果。\n"
                        + "• OPlus Zoom/Mini：zoomPkg、windowType、windowShown、zoomRect、组件和退出原因。\n"
                        + "• ActivityRecord.shouldPauseActivity 决策和关键调用栈。\n"
                        + "• Root 策略命令及返回值。\n"
                        + "• Activity/Task/进程/OOM/Window/Doze/NetPolicy/Power/Audio/MediaSession 快照。\n"
                        + "• 每个受保护 App 的 package、AppOps、standby bucket、meminfo。\n"
                        + "• 最近 30000 行完整 logcat + 自动筛选后的重点日志。"));

        TextView privacy = detailBlock("注意",
                "完整 logcat 可能包含其他应用和系统事件。诊断 ZIP 只用于排查时分享，完成后建议关闭诊断模式。");
        privacy.setTextColor(0xFF8A4B08);
        card.addView(privacy);

        diagnosticsStart = button("开始诊断");
        diagnosticsStart.setOnClickListener(v -> {
            DiagnosticsManager.startSession();
            Toast.makeText(this,
                    "诊断已开始。现在复现问题，然后返回这里导出。",
                    Toast.LENGTH_LONG).show();
            refreshDiagnosticsStatus();
        });
        card.addView(diagnosticsStart);

        diagnosticsExport = button("结束并导出诊断 ZIP");
        diagnosticsExport.setOnClickListener(v -> exportDiagnostics(true));
        card.addView(diagnosticsExport);

        Button snapshot = button("直接导出当前状态");
        snapshot.setOnClickListener(v -> exportDiagnostics(false));
        card.addView(snapshot);
    }

    private void exportDiagnostics(boolean stopSession) {
        if (diagnosticsExport != null) diagnosticsExport.setEnabled(false);
        if (diagnosticsStart != null) diagnosticsStart.setEnabled(false);

        if (stopSession) DiagnosticsManager.stopSession();

        Toast.makeText(this,
                "正在收集 system_server、Root 和系统状态…",
                Toast.LENGTH_SHORT).show();

        executor.execute(() -> {
            DiagnosticsManager.ExportResult result = DiagnosticsManager.export(this);
            runOnUiThread(() -> {
                if (diagnosticsExport != null) diagnosticsExport.setEnabled(true);
                if (diagnosticsStart != null) diagnosticsStart.setEnabled(true);
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
            diagnosticsStatus.setText("诊断状态：记录中"
                    + (started.isEmpty() ? "" : " · start=" + started));
            diagnosticsStatus.setTextColor(0xFFB3261E);
        } else {
            diagnosticsStatus.setText("诊断状态：未开启");
            diagnosticsStatus.setTextColor(0xFF16794A);
        }
    }

    private void addPermissionDetails(LinearLayout parent) {
        LinearLayout card = card(parent, "权限使用详情",
                "新架构不再维护推荐 Hook App 列表。");

        card.addView(detailBlock("LSPosed",
                "• 作用域只选 Android/System Framework (android)。\n"
                        + "• 不勾红果、视频 App、浏览器或其他目标 App。\n"
                        + "• Hook 位于 ActivityManagerService、ActivityTaskManagerService、"
                        + "ActivityRecord 和 OPlus 小窗系统服务。\n"
                        + "• 目标 App 选择只在小窗守护内部保存。"));

        card.addView(detailBlock("Root",
                "• Root 只授予小窗守护。\n"
                        + "• 根据内部受保护列表统一配置 Doze、待机桶、AppOps、网络和 WakeLock。\n"
                        + "• 不修改目标 APK，不给目标 App su 权限，不修改 /system。"));

        card.addView(detailBlock("与旧架构的区别",
                "• 删除 App 内 MediaPlayer/ExoPlayer/TTVideoEngine 等播放器 Hook。\n"
                        + "• 删除针对单独 App 的 Activity/Fragment Hook。\n"
                        + "• 不再需要“推荐 Hook 列表”。\n"
                        + "• Mini 图标继续运行依靠 system_server 保持窗口任务 Resumed/Multi-Resume。"));
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
