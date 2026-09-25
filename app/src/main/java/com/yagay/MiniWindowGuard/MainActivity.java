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
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * OPlus FlexibleWindow control panel.
 *
 * No custom window settings exist here. OxygenOS owns all window rendering,
 * size, animation, caption, minimize/restore and input behavior.
 */
public final class MainActivity extends Activity {
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor();

    private TextView engineStatus;
    private TextView diagnosticsStatus;
    private Button diagnosticsExport;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll =
                new ScrollView(this);

        LinearLayout root =
                new LinearLayout(this);

        root.setOrientation(
                LinearLayout.VERTICAL);

        root.setPadding(
                dp(18),
                dp(18),
                dp(18),
                dp(28));

        root.setBackgroundColor(
                0xFFF4F5F7);

        scroll.addView(
                root,
                new ScrollView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

        addHeader(root);
        addEngineCard(root);
        addAppListsCard(root);
        addForegroundCard(root);
        addDiagnosticsCard(root);

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
        TextView title =
                new TextView(this);

        title.setText("小窗守护");
        title.setTextSize(30);
        title.setTextColor(0xFF111318);
        title.setTypeface(
                Typeface.DEFAULT,
                Typeface.BOLD);

        parent.addView(title);

        TextView subtitle =
                new TextView(this);

        subtitle.setText(
                "OPlus FlexibleWindow · System Scope");
        subtitle.setTextSize(15);
        subtitle.setTextColor(0xFF656A73);
        subtitle.setPadding(
                0,
                dp(6),
                0,
                dp(16));

        parent.addView(subtitle);
    }

    private void addEngineCard(
            LinearLayout parent
    ) {
        LinearLayout card =
                card(
                        parent,
                        "运行状态",
                        "核心运行在 system_server，目标 App 不需要加入 LSPosed 作用域。");

        engineStatus =
                statusLine(
                        "System Engine：检测中…");

        card.addView(engineStatus);

        card.addView(
                statusLine(
                        "LSPosed 固定作用域：system / system_server"));

        addSwitch(
                card,
                "启用小窗守护",
                "总开关。关闭后不修改一加小窗支持判断，也不应用前台/防清理保护。",
                ConfigKeys.MASTER_ENABLED);

        addSwitch(
                card,
                "自动热重载",
                "更新 APK 后自动加载新的 Engine。Bootstrap Hook 结构变化仍需要重启一次。",
                ConfigKeys.ENGINE_AUTO_RELOAD);

        Button reload =
                button(
                        "立即重新加载 System Engine");

        reload.setOnClickListener(v -> {
            if (!GuardApp.isHotReloadAvailable()) {
                Toast.makeText(
                        this,
                        "当前 Bootstrap 不支持热重载，请重启一次手机。",
                        Toast.LENGTH_LONG).show();
                return;
            }

            boolean sent =
                    GuardApp.requestEngineReload();

            Toast.makeText(
                    this,
                    sent
                            ? "已请求重新加载 OPlus Engine。"
                            : "热重载请求同步失败。",
                    Toast.LENGTH_LONG).show();

            if (engineStatus != null) {
                engineStatus.postDelayed(
                        this::refreshStatus,
                        2500L);
            }
        });

        card.addView(reload);

        Button refresh =
                button("重新检测");

        refresh.setOnClickListener(
                v -> refreshStatus());

        card.addView(refresh);
    }

    private void addAppListsCard(
            LinearLayout parent
    ) {
        LinearLayout card =
                card(
                        parent,
                        "应用名单",
                        "App 的启动、进入小窗、恢复和关闭完全使用 OxygenOS 自己的方式。MiniWindowGuard 只监听一加小窗状态。");

        Button foreground =
                button(
                        "始终前台应用");

        foreground.setOnClickListener(v -> {
            Intent intent =
                    new Intent(
                            this,
                            TargetAppsActivity.class);
            intent.putExtra(
                    TargetAppsActivity.EXTRA_MODE,
                    TargetAppsActivity.MODE_FOREGROUND);
            startActivity(intent);
        });

        card.addView(foreground);

        card.addView(
                detailBlock(
                        "始终前台",
                        "勾选的 App 只有在真实 OPlus FlexibleWindow、贴边/最小化小窗，或该小窗进入锁屏状态时才保持运行。普通全屏状态不干预。"));

        Button background =
                button(
                        "后台播放应用");

        background.setOnClickListener(v -> {
            Intent intent =
                    new Intent(
                            this,
                            TargetAppsActivity.class);
            intent.putExtra(
                    TargetAppsActivity.EXTRA_MODE,
                    TargetAppsActivity.MODE_BACKGROUND_PLAYBACK);
            startActivity(intent);
        });

        card.addView(background);

        card.addView(
                detailBlock(
                        "普通后台播放",
                        "从普通全屏切到桌面/其他 App 或锁屏时进入 BACKGROUND_PROTECTED；返回原 App 自动解除。"));

        Button support =
                button(
                        "强制允许一加小窗应用");

        support.setOnClickListener(v -> {
            Intent intent =
                    new Intent(
                            this,
                            TargetAppsActivity.class);
            intent.putExtra(
                    TargetAppsActivity.EXTRA_MODE,
                    TargetAppsActivity.MODE_FORCE_SUPPORT);
            startActivity(intent);
        });

        card.addView(support);

        card.addView(
                detailBlock(
                        "小窗支持",
                        "只对勾选的 App 放行一加 FlexibleWindow 支持/黑名单检查；不会主动启动或主动切换小窗。"));
    }

    private void addForegroundCard(
            LinearLayout parent
    ) {
        LinearLayout card =
                card(
                        parent,
                        "运行保护",
                        "小窗继续使用 OPlus 状态驱动；后台播放名单只在离开普通全屏后进入 BACKGROUND_PROTECTED。");

        addSwitch(
                card,
                "受保护进程状态保持 TOP",
                "对真实一加小窗/贴边/锁屏，以及 BACKGROUND_PROTECTED 普通后台进程返回 TOP。",
                ConfigKeys.SYSTEM_IMPORTANCE_TOP);

        addSwitch(
                card,
                "受保护任务视为存在 Resumed Activity",
                "对真实一加小窗和 BACKGROUND_PROTECTED 任务返回 true；普通前台不修改。",
                ConfigKeys.SYSTEM_HAS_RESUMED);

        addSwitch(
                card,
                "阻止系统清理受保护进程",
                "保护一加小窗和 BACKGROUND_PROTECTED 进程；强制停止/更新放行，普通后台任务被划掉时仍允许正常关闭。",
                ConfigKeys.SYSTEM_BLOCK_REMOVE_KILL);
    }

    private void addDiagnosticsCard(
            LinearLayout parent
    ) {
        LinearLayout card =
                card(
                        parent,
                        "详细诊断",
                        "记录 OPlus FlexibleWindow 回调、支持判断、Task 状态、前台查询和 OEM 清理链路。");

        diagnosticsStatus =
                statusLine(
                        "详细日志：检测中…");

        card.addView(diagnosticsStatus);

        Button start =
                button(
                        "开始详细日志");

        start.setOnClickListener(v -> {
            DiagnosticsManager.startSession();
            refreshDiagnosticsStatus();

            Toast.makeText(
                    this,
                    "已开始记录 OPlus 小窗详细日志。",
                    Toast.LENGTH_SHORT).show();
        });

        card.addView(start);

        Button stop =
                button(
                        "停止详细日志");

        stop.setOnClickListener(v -> {
            DiagnosticsManager.stopSession();
            refreshDiagnosticsStatus();

            Toast.makeText(
                    this,
                    "详细日志已停止。",
                    Toast.LENGTH_SHORT).show();
        });

        card.addView(stop);

        diagnosticsExport =
                button(
                        "导出诊断 ZIP");

        diagnosticsExport.setOnClickListener(
                v -> exportDiagnostics());

        card.addView(diagnosticsExport);
    }

    private void exportDiagnostics() {
        if (diagnosticsExport != null) {
            diagnosticsExport.setEnabled(false);
        }

        Toast.makeText(
                this,
                "正在收集 OPlus FlexibleWindow、system_server 和系统状态…",
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
                            "导出失败："
                                    + result.error,
                            Toast.LENGTH_LONG).show();
                    return;
                }

                Toast.makeText(
                        this,
                        "已保存到 Download/MiniWindowGuard/"
                                + result.fileName,
                        Toast.LENGTH_LONG).show();

                try {
                    Intent share =
                            new Intent(
                                    Intent.ACTION_SEND);

                    share.setType(
                            "application/zip");

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
                        "System Engine：LSPosed 服务未连接");

                engineStatus.setTextColor(
                        0xFFB3261E);
            } else if (!hasSystem) {
                engineStatus.setText(
                        "System Engine：作用域缺少 system · 实际="
                                + GuardApp.getFrameworkScope());

                engineStatus.setTextColor(
                        0xFFB3261E);
            } else if (current) {
                engineStatus.setText(
                        "System Engine：code "
                                + loaded
                                + " · Bootstrap "
                                + GuardApp.getBootstrapVersionCode()
                                + " · gen "
                                + GuardApp.getEngineGeneration()
                                + " · 跟踪 Task "
                                + GuardApp.getEngineActiveSessions()
                                + "\n"
                                + GuardApp.getEngineReloadMessage());

                engineStatus.setTextColor(
                        0xFF16794A);
            } else if (active) {
                engineStatus.setText(
                        "System Engine：旧 Engine "
                                + loaded
                                + " / 已安装 APK "
                                + expected
                                + "\n请重新加载 Engine；若 Bootstrap Hook 已改变，需要重启一次。");

                engineStatus.setTextColor(
                        0xFF9A6700);
            } else {
                engineStatus.setText(
                        "System Engine：未检测到有效心跳");

                engineStatus.setTextColor(
                        0xFFB3261E);
            }
        }

        refreshDiagnosticsStatus();
    }

    private void refreshDiagnosticsStatus() {
        if (diagnosticsStatus == null) {
            return;
        }

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

            diagnosticsStatus.setTextColor(
                    0xFFB3261E);
        } else {
            diagnosticsStatus.setText(
                    "详细日志：关闭");

            diagnosticsStatus.setTextColor(
                    0xFF16794A);
        }
    }

    private void addSwitch(
            LinearLayout parent,
            String title,
            String description,
            String key
    ) {
        LinearLayout row =
                new LinearLayout(this);

        row.setOrientation(
                LinearLayout.HORIZONTAL);

        row.setGravity(
                Gravity.CENTER_VERTICAL);

        row.setPadding(
                0,
                dp(6),
                0,
                dp(6));

        LinearLayout texts =
                new LinearLayout(this);

        texts.setOrientation(
                LinearLayout.VERTICAL);

        TextView titleView =
                label(title);

        texts.addView(titleView);

        TextView descriptionView =
                new TextView(this);

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

        Switch toggle =
                new Switch(this);

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

    private LinearLayout card(
            LinearLayout parent,
            String title,
            String subtitle
    ) {
        LinearLayout card =
                new LinearLayout(this);

        card.setOrientation(
                LinearLayout.VERTICAL);

        card.setPadding(
                dp(16),
                dp(14),
                dp(16),
                dp(14));

        GradientDrawable background =
                new GradientDrawable();

        background.setColor(
                0xFFFFFFFF);

        background.setCornerRadius(
                dp(16));

        card.setBackground(background);

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);

        params.setMargins(
                0,
                0,
                0,
                dp(12));

        parent.addView(
                card,
                params);

        TextView heading =
                new TextView(this);

        heading.setText(title);
        heading.setTextSize(19);
        heading.setTypeface(
                Typeface.DEFAULT,
                Typeface.BOLD);

        card.addView(heading);

        TextView sub =
                new TextView(this);

        sub.setText(subtitle);
        sub.setTextSize(13);
        sub.setTextColor(0xFF6C717A);
        sub.setPadding(
                0,
                dp(4),
                0,
                dp(8));

        card.addView(sub);

        return card;
    }

    private TextView statusLine(String text) {
        TextView view =
                label(text);

        view.setPadding(
                0,
                dp(4),
                0,
                dp(4));

        return view;
    }

    private TextView label(String text) {
        TextView view =
                new TextView(this);

        view.setText(text);
        view.setTextSize(15);
        view.setTextColor(0xFF202124);

        return view;
    }

    private TextView detailBlock(
            String title,
            String text
    ) {
        TextView view =
                new TextView(this);

        view.setText(
                title + "\n" + text);

        view.setTextSize(13.5f);
        view.setTextColor(0xFF41464F);
        view.setLineSpacing(
                0,
                1.18f);

        view.setPadding(
                0,
                dp(8),
                0,
                dp(8));

        return view;
    }

    private Button button(String text) {
        Button button =
                new Button(this);

        button.setText(text);
        button.setAllCaps(false);

        return button;
    }

    private int dp(float value) {
        return Math.round(
                value
                        * getResources()
                        .getDisplayMetrics()
                        .density);
    }
}
