package com.jieei.alwaysforeground;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class MainActivity extends Activity {
    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int selectedMode = AlwaysForegroundApp.getConfiguredMode();

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(24), dp(24), dp(32));
        root.setGravity(Gravity.TOP);
        root.setBackgroundColor(0xFFFAFAFA);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("小窗守护");
        title.setTextSize(28);
        title.setTextColor(0xFF111111);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView subtitle = new TextView(this);
        subtitle.setText("LSPosed + Root 通用小窗与虚拟前台运行保护");
        subtitle.setTextSize(15);
        subtitle.setTextColor(0xFF666666);
        subtitle.setPadding(0, dp(8), 0, dp(10));
        root.addView(subtitle);

        TextView serviceStatus = new TextView(this);
        serviceStatus.setText(AlwaysForegroundApp.isServiceConnected()
                ? "LSPosed 配置服务：已连接"
                : "LSPosed 配置服务：等待连接（选择会先保存，连接后自动同步）");
        serviceStatus.setTextSize(14);
        serviceStatus.setTextColor(0xFF666666);
        serviceStatus.setPadding(0, 0, 0, dp(20));
        root.addView(serviceStatus);

        RadioGroup modes = new RadioGroup(this);
        modes.setOrientation(RadioGroup.VERTICAL);
        addMode(modes, ModeConfig.MODE_STANDARD, "普通模式",
                "保持真实 Activity/窗口前后台状态，仅放宽应用侧后台限制判断。兼容性最高。", selectedMode);
        addMode(modes, ModeConfig.MODE_ENHANCED, "增强模式",
                "在普通模式基础上，放宽待机、省电和电池优化查询，但不伪装窗口焦点或进程前台。", selectedMode);
        addMode(modes, ModeConfig.MODE_STRONG, "强力模式",
                "在增强模式基础上启用通用媒体连续播放：确认应用真正进入后台后，恢复由生命周期导致的播放器暂停；若应用自己启动后台播放器则自动让位。", selectedMode);
        modes.setOnCheckedChangeListener((group, checkedId) -> {
            if (!ModeConfig.isValid(checkedId)) return;
            boolean synced = AlwaysForegroundApp.setConfiguredMode(checkedId);
            serviceStatus.setText(synced
                    ? "LSPosed 配置服务：已同步"
                    : "LSPosed 配置服务：未连接，设置已保存并会在连接后同步");
            Toast.makeText(this,
                    synced ? "设置已生效" : "设置已保存，等待 LSPosed 服务连接",
                    Toast.LENGTH_SHORT).show();
        });
        root.addView(modes, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView windowHeading = new TextView(this);
        windowHeading.setText("Root + LSPosed 小窗容器");
        windowHeading.setTextSize(19);
        windowHeading.setTextColor(0xFF111111);
        windowHeading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        windowHeading.setPadding(0, dp(26), 0, dp(8));
        root.addView(windowHeading);

        TextView windowHelp = new TextView(this);
        windowHelp.setText("强力模式下，后台应用自己启动本包页面时，优先通过 Root 调用系统/OPlus 小窗 API。三种形态都保留真实 Activity 生命周期，不伪装窗口焦点。");
        windowHelp.setTextSize(14);
        windowHelp.setTextColor(0xFF555555);
        windowHelp.setLineSpacing(0, 1.15f);
        root.addView(windowHelp);

        int selectedForm = AlwaysForegroundApp.getConfiguredSmallWindowForm();
        RadioGroup windowForms = new RadioGroup(this);
        windowForms.setOrientation(RadioGroup.VERTICAL);
        addSmallWindowForm(windowForms, ModeConfig.SMALL_WINDOW_FORM_WINDOW,
                "自由小窗", "按下面设置的宽高比例打开系统小窗。", selectedForm);
        addSmallWindowForm(windowForms, ModeConfig.SMALL_WINDOW_FORM_ICON,
                "图标", "先进入系统小窗，再自动缩成 Mini Zoom/悬浮图标。", selectedForm);
        addSmallWindowForm(windowForms, ModeConfig.SMALL_WINDOW_FORM_HIDDEN,
                "最小化隐藏", "保持任务活动，但隐藏小窗和图标。", selectedForm);
        root.addView(windowForms, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout sizeRow = new LinearLayout(this);
        sizeRow.setOrientation(LinearLayout.HORIZONTAL);
        sizeRow.setPadding(0, dp(6), 0, dp(6));

        EditText widthInput = new EditText(this);
        widthInput.setSingleLine(true);
        widthInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        widthInput.setHint("宽度 %");
        widthInput.setText(String.valueOf(
                AlwaysForegroundApp.getConfiguredSmallWindowWidth()));
        sizeRow.addView(widthInput, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        EditText heightInput = new EditText(this);
        heightInput.setSingleLine(true);
        heightInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        heightInput.setHint("高度 %");
        heightInput.setText(String.valueOf(
                AlwaysForegroundApp.getConfiguredSmallWindowHeight()));
        sizeRow.addView(heightInput, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(sizeRow);

        Button saveWindow = new Button(this);
        saveWindow.setText("保存小窗设置");
        saveWindow.setOnClickListener(v -> {
            int form = windowForms.getCheckedRadioButtonId() - 100;
            int width = parsePercent(
                    widthInput.getText().toString(),
                    ModeConfig.DEFAULT_SMALL_WINDOW_WIDTH);
            int height = parsePercent(
                    heightInput.getText().toString(),
                    ModeConfig.DEFAULT_SMALL_WINDOW_HEIGHT);
            boolean synced = AlwaysForegroundApp.setSmallWindowConfig(
                    form, width, height);
            widthInput.setText(String.valueOf(
                    ModeConfig.clampPercent(width, ModeConfig.DEFAULT_SMALL_WINDOW_WIDTH)));
            heightInput.setText(String.valueOf(
                    ModeConfig.clampPercent(height, ModeConfig.DEFAULT_SMALL_WINDOW_HEIGHT)));
            Toast.makeText(this,
                    synced ? "小窗设置已同步" : "小窗设置已保存，等待 LSPosed 服务连接",
                    Toast.LENGTH_SHORT).show();
        });
        root.addView(saveWindow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView diagHeading = new TextView(this);
        diagHeading.setText("自动定位 Hook 点");
        diagHeading.setTextSize(19);
        diagHeading.setTextColor(0xFF111111);
        diagHeading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        diagHeading.setPadding(0, dp(28), 0, dp(8));
        root.addView(diagHeading);

        TextView diagHelp = new TextView(this);
        diagHelp.setText("开始诊断后，模块会记录真实 Activity 前后台切换、通用媒体连续播放决策，以及 MediaPlayer、AudioTrack、ExoPlayer/Media3、TTVideoEngine 的 pause/stop/release 调用链。ZIP 会同时保留候选 Hook 点和系统事件，方便判断是应用主动暂停、原生后台播放器接管，还是系统策略限制。");
        diagHelp.setTextSize(14);
        diagHelp.setTextColor(0xFF555555);
        diagHelp.setLineSpacing(0, 1.15f);
        root.addView(diagHelp);

        EditText targetPackage = new EditText(this);
        targetPackage.setSingleLine(true);
        targetPackage.setHint("目标包名，例如 com.phoenix.read");
        targetPackage.setText(DiagnosticsManager.getTarget(this));
        targetPackage.setTextSize(15);
        targetPackage.setPadding(0, dp(8), 0, dp(8));
        root.addView(targetPackage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView diagStatus = new TextView(this);
        diagStatus.setTextSize(14);
        diagStatus.setPadding(0, dp(6), 0, dp(8));
        root.addView(diagStatus);

        Button diagButton = new Button(this);
        root.addView(diagButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        updateDiagnosticUi(targetPackage, diagStatus, diagButton);
        diagButton.setOnClickListener(v -> {
            if (!DiagnosticsManager.isActive(this)) {
                DiagnosticsManager.start(this, targetPackage.getText().toString());
                updateDiagnosticUi(targetPackage, diagStatus, diagButton);
                Toast.makeText(this,
                        "自动定位已开始：现在去目标应用播放并复现后台停止",
                        Toast.LENGTH_LONG).show();
                return;
            }

            diagButton.setEnabled(false);
            diagButton.setText("正在分析并打包日志…");
            diagStatus.setText("正在抓取播放器终点调用栈、logcat、system events 和 LSPosed 日志，请保持应用在前台直到完成。");
            DiagnosticsManager.stopAndExport(this, result -> runOnUiThread(() -> {
                diagButton.setEnabled(true);
                updateDiagnosticUi(targetPackage, diagStatus, diagButton);
                Toast.makeText(this, result.message, Toast.LENGTH_LONG).show();
                if (result.success) diagStatus.setText(result.message);
            }));
        });

        TextView body = new TextView(this);
        body.setTextSize(15);
        body.setTextColor(0xFF333333);
        body.setLineSpacing(0, 1.25f);
        body.setPadding(0, dp(24), 0, 0);

        SpannableStringBuilder text = new SpannableStringBuilder();
        appendHeading(text, "一次定位流程\n");
        text.append("1. 在 LSPosed 中启用模块，并把目标应用加入作用域。\n");
        text.append("2. 填入目标包名后点“开始自动定位”。\n");
        text.append("3. 打开目标应用播放视频，按 Home 或锁屏，等播放自动停止。\n");
        text.append("4. 回到本模块点“停止并导出”。\n");
        text.append("5. 直接查看/发送 ZIP 中的 hook-candidates.txt，不再逐个猜 onPause/onStop。\n\n");
        appendHeading(text, "候选判断\n");
        text.append("HOOK_CANDIDATE 是真实播放器终点；GENERIC_BACKGROUND 表示已确认整个应用进入后台；GENERIC_CONTINUITY queued/resumed/native handoff won 表示通用连续播放引擎的决策。优先保留应用自己的后台播放机制，通用引擎只在应用没有接管时兜底。\n\n");
        appendHeading(text, "限制\n");
        text.append("如果目标应用完全在 native/JNI 层停止播放，Java 终点可能抓不到；这种情况 ZIP 仍会保留系统事件和 LSPosed 日志，再继续定位 native 或 Surface/AudioFocus 路径。\n");
        body.setText(text);
        root.addView(body);

        setContentView(scroll);
    }

    private void updateDiagnosticUi(EditText targetPackage, TextView status, Button button) {
        boolean active = DiagnosticsManager.isActive(this);
        targetPackage.setEnabled(!active);
        if (active) {
            long start = DiagnosticsManager.getStartMs(this);
            String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(start));
            status.setText("● 自动定位中  目标：" + DiagnosticsManager.getTarget(this) + "  开始：" + time);
            status.setTextColor(0xFFD32F2F);
            button.setText("停止并导出诊断 ZIP");
        } else {
            status.setText("未开始自动定位");
            status.setTextColor(0xFF666666);
            button.setText("开始自动定位 Hook 点");
        }
    }

    private void addSmallWindowForm(
            RadioGroup group,
            int form,
            String title,
            String description,
            int selectedForm
    ) {
        RadioButton radio = new RadioButton(this);
        radio.setId(100 + form);
        radio.setText(title + "\n" + description);
        radio.setTextSize(15);
        radio.setTextColor(0xFF111111);
        radio.setGravity(Gravity.TOP);
        radio.setPadding(0, dp(5), 0, dp(8));
        radio.setLineSpacing(0, 1.12f);
        radio.setChecked(form == selectedForm);
        group.addView(radio, new RadioGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private static int parsePercent(String text, int fallback) {
        try {
            return ModeConfig.clampPercent(Integer.parseInt(text.trim()), fallback);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private void addMode(RadioGroup group, int id, String title, String description, int selectedMode) {
        RadioButton radio = new RadioButton(this);
        radio.setId(id);
        radio.setText(title + "\n" + description);
        radio.setTextSize(16);
        radio.setTextColor(0xFF111111);
        radio.setGravity(Gravity.TOP);
        radio.setPadding(0, dp(6), 0, dp(10));
        radio.setLineSpacing(0, 1.15f);
        radio.setChecked(id == selectedMode);
        group.addView(radio, new RadioGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private static void appendHeading(SpannableStringBuilder text, String s) {
        int start = text.length();
        text.append(s);
        text.setSpan(new StyleSpan(Typeface.BOLD), start, text.length(), 0);
    }
}
