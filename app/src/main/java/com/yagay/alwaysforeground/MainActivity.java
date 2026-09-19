package com.yagay.alwaysforeground;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
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
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        addHeader(root);
        addStatusCard(root);
        addMasterCard(root);
        addRootCard(root);
        addXposedCard(root);
        addMediaCard(root);
        addWindowCard(root);
        addAdvancedCard(root);
        addPermissionDetails(root);
        addResetCard(root);

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
        subtitle.setText("MiniWindow Guard · Root + LSPosed 通用虚拟前台");
        subtitle.setTextSize(15);
        subtitle.setTextColor(0xFF656A73);
        subtitle.setPadding(0, dp(6), 0, dp(16));
        parent.addView(subtitle);
    }

    private void addStatusCard(LinearLayout parent) {
        LinearLayout card = card(parent, "运行状态",
                "Root 只授予小窗守护；目标 App 只需要加入 LSPosed 作用域。");

        rootStatus = statusLine("Root：检测中…");
        card.addView(rootStatus);

        xposedStatus = statusLine("LSPosed：检测中…");
        card.addView(xposedStatus);

        TextView packageLine = statusLine("包名：com.yagay.alwaysforeground");
        card.addView(packageLine);

        TextView apiLine = statusLine("LSPosed API：102");
        card.addView(apiLine);

        Button check = button("重新检测 Root / LSPosed");
        check.setOnClickListener(v -> refreshStatus());
        card.addView(check);
    }

    private void addMasterCard(LinearLayout parent) {
        LinearLayout card = card(parent, "总开关",
                "关闭后，LSPosed Hook 保留但不修改目标 App 行为；Root 策略会在目标 App 下次启动/恢复时撤销。");

        addSwitch(card,
                "启用小窗守护",
                "总控制开关。",
                ConfigKeys.MASTER_ENABLED);
    }

    private void addRootCard(LinearLayout parent) {
        LinearLayout card = card(parent, "Root 系统保活",
                "这些操作由小窗守护自己的 Root 进程执行，不给作用域 App Root 权限，也不修改 /system。");

        addSwitch(card,
                "Root 系统保活",
                "允许对作用域 App 应用下面的系统策略。",
                ConfigKeys.ROOT_KEEP_ALIVE);

        addSwitch(card,
                "Doze 白名单",
                "使用 deviceidle whitelist，降低待机/息屏时被冻结的概率。",
                ConfigKeys.ROOT_DOZE_WHITELIST);

        addSwitch(card,
                "保持 Active 待机桶",
                "把目标 App 设为 active standby bucket，并清除 inactive 状态。",
                ConfigKeys.ROOT_STANDBY_ACTIVE);

        addSwitch(card,
                "允许后台 AppOps",
                "允许 RUN_IN_BACKGROUND / RUN_ANY_IN_BACKGROUND。",
                ConfigKeys.ROOT_BACKGROUND_APPOPS);

        addSwitch(card,
                "后台网络白名单",
                "加入 restrict-background whitelist，避免省流量策略切断后台网络。",
                ConfigKeys.ROOT_NETWORK_WHITELIST);

        addSwitch(card,
                "允许 WakeLock",
                "允许目标 App 在需要时继续持有 WakeLock。",
                ConfigKeys.ROOT_WAKELOCK);
    }

    private void addXposedCard(LinearLayout parent) {
        LinearLayout card = card(parent, "LSPosed 虚拟前台",
                "只 Hook Android/Jetpack 通用接口；真实 Activity onPause/onStop 仍然执行，避免破坏系统状态机。");

        addSwitch(card,
                "伪装进程前台重要性",
                "getUidImportance / getPackageImportance / getMyMemoryState 等返回前台级状态。",
                ConfigKeys.SPOOF_PROCESS_IMPORTANCE);

        addSwitch(card,
                "伪装 ProcessLifecycleOwner",
                "目标 App 在后台时，ProcessLifecycleOwner 查询保持 RESUMED。",
                ConfigKeys.SPOOF_PROCESS_LIFECYCLE);

        addSwitch(card,
                "伪装窗口焦点",
                "后台时 hasWindowFocus() 返回 true。兼容性较激进，某些 App 可能因此触发前台 UI。",
                ConfigKeys.SPOOF_WINDOW_FOCUS);

        addSwitch(card,
                "伪装亮屏状态",
                "后台时 isInteractive()/isScreenOn() 返回 true。",
                ConfigKeys.SPOOF_SCREEN_INTERACTIVE);

        addSwitch(card,
                "伪装未锁屏",
                "后台时 isKeyguardLocked()/isDeviceLocked() 返回 false。",
                ConfigKeys.SPOOF_KEYGUARD);

        addSwitch(card,
                "忽略应用侧后台限制",
                "isBackgroundRestricted() 返回 false。",
                ConfigKeys.SPOOF_BACKGROUND_RESTRICTION);

        addSwitch(card,
                "忽略省电 / Doze 查询",
                "应用侧查询到非省电、非 Doze、已忽略电池优化。",
                ConfigKeys.SPOOF_POWER_STATE);
    }

    private void addMediaCard(LinearLayout parent) {
        LinearLayout card = card(parent, "媒体连续运行",
                "用于处理 App 直接在 onPause/onStop 中暂停播放器的情况，仍保留用户手动暂停、Audio Focus、MediaSession 和来电中断。");

        addSwitch(card,
                "生命周期暂停自动恢复",
                "只恢复被确认由 Activity 进入后台触发的播放器暂停。",
                ConfigKeys.MEDIA_CONTINUITY);

        addSwitch(card,
                "恢复回声保护",
                "恢复播放后短时间抑制由状态回调再次触发的 pause。",
                ConfigKeys.MEDIA_ECHO_GUARD);
    }

    private void addWindowCard(LinearLayout parent) {
        LinearLayout card = card(parent, "系统小窗",
                "后台 App 自己打开本包 Activity 时，优先通过 Root 调用 OPlus/ColorOS/OxygenOS 小窗；其他 ROM 可尝试 AOSP Freeform。");

        addSwitch(card,
                "后台页面自动转小窗",
                "避免后台 App 自己启动 Activity 时直接抢占全屏。",
                ConfigKeys.AUTO_SMALL_WINDOW);

        addSwitch(card,
                "AOSP Freeform 兜底",
                "厂商小窗 API 不可用时，尝试 Android Freeform。",
                ConfigKeys.AOSP_FREEFORM_FALLBACK);

        TextView formTitle = label("默认形态");
        formTitle.setPadding(0, dp(12), 0, dp(4));
        card.addView(formTitle);

        RadioGroup forms = new RadioGroup(this);
        forms.setOrientation(RadioGroup.VERTICAL);
        addForm(forms, ConfigKeys.FORM_WINDOW,
                "自由小窗", "显示系统小窗，可按系统方式拖动/调整。");
        addForm(forms, ConfigKeys.FORM_ICON,
                "图标", "先进入系统小窗，再缩成 Mini Zoom/悬浮图标。");
        addForm(forms, ConfigKeys.FORM_HIDDEN,
                "最小化隐藏", "保持任务运行，再隐藏小窗和图标。");

        int currentForm = ConfigKeys.sanitizeForm(
                GuardApp.getInt(ConfigKeys.SMALL_WINDOW_FORM));
        forms.check(300 + currentForm);
        forms.setOnCheckedChangeListener((group, checkedId) -> {
            int form = checkedId - 300;
            GuardApp.putInt(
                    ConfigKeys.SMALL_WINDOW_FORM,
                    ConfigKeys.sanitizeForm(form));
        });
        card.addView(forms);

        int width = ConfigKeys.sanitizePercent(
                GuardApp.getInt(ConfigKeys.SMALL_WINDOW_WIDTH), 58);
        widthLabel = label("小窗宽度：" + width + "%");
        widthLabel.setPadding(0, dp(10), 0, 0);
        card.addView(widthLabel);

        SeekBar widthSeek = percentSeek(width);
        widthSeek.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(
                    SeekBar seekBar, int progress, boolean fromUser) {
                int value = progress + 30;
                widthLabel.setText("小窗宽度：" + value + "%");
                if (fromUser) GuardApp.putInt(ConfigKeys.SMALL_WINDOW_WIDTH, value);
            }
        });
        card.addView(widthSeek);

        int height = ConfigKeys.sanitizePercent(
                GuardApp.getInt(ConfigKeys.SMALL_WINDOW_HEIGHT), 66);
        heightLabel = label("小窗高度：" + height + "%");
        heightLabel.setPadding(0, dp(8), 0, 0);
        card.addView(heightLabel);

        SeekBar heightSeek = percentSeek(height);
        heightSeek.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(
                    SeekBar seekBar, int progress, boolean fromUser) {
                int value = progress + 30;
                heightLabel.setText("小窗高度：" + value + "%");
                if (fromUser) GuardApp.putInt(ConfigKeys.SMALL_WINDOW_HEIGHT, value);
            }
        });
        card.addView(heightSeek);
    }

    private void addAdvancedCard(LinearLayout parent) {
        LinearLayout card = card(parent, "高级时序",
                "只在出现兼容性问题时调整。范围 100–5000 ms。");

        EditText background = numberInput(
                "后台确认延迟",
                GuardApp.getInt(ConfigKeys.BACKGROUND_CONFIRM_MS));
        card.addView(background);

        EditText resume = numberInput(
                "媒体恢复延迟",
                GuardApp.getInt(ConfigKeys.MEDIA_RESUME_DELAY_MS));
        card.addView(resume);

        EditText echo = numberInput(
                "回声保护时间",
                GuardApp.getInt(ConfigKeys.ECHO_GUARD_MS));
        card.addView(echo);

        Button save = button("保存高级参数");
        save.setOnClickListener(v -> {
            GuardApp.putInt(
                    ConfigKeys.BACKGROUND_CONFIRM_MS,
                    ConfigKeys.sanitizeDelay(
                            parseInt(background),
                            ConfigKeys.DEFAULT_BACKGROUND_CONFIRM_MS));
            GuardApp.putInt(
                    ConfigKeys.MEDIA_RESUME_DELAY_MS,
                    ConfigKeys.sanitizeDelay(
                            parseInt(resume),
                            ConfigKeys.DEFAULT_MEDIA_RESUME_DELAY_MS));
            GuardApp.putInt(
                    ConfigKeys.ECHO_GUARD_MS,
                    ConfigKeys.sanitizeDelay(
                            parseInt(echo),
                            ConfigKeys.DEFAULT_ECHO_GUARD_MS));
            Toast.makeText(this, "高级参数已保存并同步", Toast.LENGTH_SHORT).show();
        });
        card.addView(save);
    }

    private void addPermissionDetails(LinearLayout parent) {
        LinearLayout card = card(parent, "Root / LSPosed 权限使用详情",
                "这里列出模块会做什么，方便确认权限范围。");

        card.addView(detailBlock(
                "Root 权限",
                "• Root 只由 com.yagay.alwaysforeground 使用。\n"
                        + "• 不会把 su/root 权限转交给作用域 App。\n"
                        + "• 用于 deviceidle 白名单、standby bucket、后台 AppOps、"
                        + "后台网络白名单、WakeLock AppOp。\n"
                        + "• 用于以 Root Binder 身份调用 OPlus 小窗 / Mini / hideZoomWindow。\n"
                        + "• 不修改 /system，不刷入模块，不改目标 APK。"));

        card.addView(detailBlock(
                "LSPosed 权限",
                "• 只注入你在 LSPosed 作用域中选择的普通 App。\n"
                        + "• 不需要把 Android/System Framework 加入作用域。\n"
                        + "• Hook Android/Jetpack 通用前台查询和常见播放器接口。\n"
                        + "• Activity 生命周期本身仍按系统真实状态运行。\n"
                        + "• 设置变化会通过 libxposed RemotePreferences 同步。"));

        card.addView(detailBlock(
                "建议作用域",
                "只勾选确实需要小窗/后台持续运行的 App。不要把系统桌面、SystemUI、"
                        + "系统框架或安全类 App 批量加入作用域。"));
    }

    private void addResetCard(LinearLayout parent) {
        LinearLayout card = card(parent, "维护",
                "恢复默认只重置小窗守护配置，不会卸载模块或修改 LSPosed 作用域。");

        Button sync = button("立即同步到 LSPosed");
        sync.setOnClickListener(v -> {
            boolean ok = GuardApp.syncAll();
            Toast.makeText(
                    this,
                    ok ? "已同步" : "LSPosed 配置服务未连接",
                    Toast.LENGTH_SHORT).show();
            refreshStatus();
        });
        card.addView(sync);

        Button reset = button("恢复默认设置");
        reset.setOnClickListener(v -> {
            GuardApp.resetDefaults();
            Toast.makeText(this, "已恢复默认，重新打开页面查看", Toast.LENGTH_LONG).show();
            recreate();
        });
        card.addView(reset);
    }

    private void refreshStatus() {
        if (xposedStatus != null) {
            boolean connected = GuardApp.isXposedServiceConnected();
            xposedStatus.setText(
                    connected
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
                rootStatus.setText(status.granted
                        ? "Root：已授权"
                        : "Root：未授权 / 不可用");
                rootStatus.setTextColor(status.granted ? 0xFF16794A : 0xFFB3261E);
            });
        });
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
        descriptionView.setPadding(0, dp(2), dp(8), 0);
        texts.addView(descriptionView);

        row.addView(texts, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch toggle = new Switch(this);
        toggle.setChecked(GuardApp.getBoolean(key));
        toggle.setOnCheckedChangeListener((button, checked) -> {
            GuardApp.putBoolean(key, checked);
            if (!GuardApp.isXposedServiceConnected()) {
                Toast.makeText(
                        this,
                        "设置已保存；LSPosed 连接后自动同步",
                        Toast.LENGTH_SHORT).show();
            }
        });
        row.addView(toggle);

        parent.addView(row);
    }

    private void addForm(
            RadioGroup group,
            int form,
            String title,
            String description
    ) {
        RadioButton radio = new RadioButton(this);
        radio.setId(300 + form);
        radio.setText(title + "\n" + description);
        radio.setTextSize(14.5f);
        radio.setTextColor(0xFF202124);
        radio.setPadding(0, dp(5), 0, dp(6));
        group.addView(radio);
    }

    private SeekBar percentSeek(int value) {
        SeekBar seek = new SeekBar(this);
        seek.setMax(70);
        seek.setProgress(Math.max(0, Math.min(70, value - 30)));
        return seek;
    }

    private EditText numberInput(String hint, int value) {
        EditText input = new EditText(this);
        input.setHint(hint + " (ms)");
        input.setText(String.valueOf(value));
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setTextSize(14);
        input.setPadding(0, dp(4), 0, dp(4));
        return input;
    }

    private int parseInt(EditText input) {
        try {
            return Integer.parseInt(input.getText().toString().trim());
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private LinearLayout card(
            LinearLayout parent,
            String title,
            String subtitle
    ) {
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
        heading.setTextColor(0xFF111318);
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
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(14.5f);
        view.setTextColor(0xFF30343B);
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

    private abstract static class SimpleSeekListener
            implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {}

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {}
    }
}
