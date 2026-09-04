package com.jieei.alwaysforeground;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
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
        title.setText("始终前台");
        title.setTextSize(28);
        title.setTextColor(0xFF111111);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView subtitle = new TextView(this);
        subtitle.setText("为 LSPosed 作用域中的应用选择前台状态伪装强度");
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
                "伪装亮屏、解锁和当前进程前台重要性。默认模式，风险最低。", selectedMode);
        addMode(modes, ModeConfig.MODE_ENHANCED, "增强模式",
                "在普通模式基础上，再伪装后台限制、待机/省电状态和 UID 重要性。", selectedMode);
        addMode(modes, ModeConfig.MODE_STRONG, "强力模式",
                "在增强模式基础上，额外启用安全的强力前台查询 Hook 和应用专用诊断。", selectedMode);
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

        TextView diagHeading = new TextView(this);
        diagHeading.setText("一键诊断日志");
        diagHeading.setTextSize(19);
        diagHeading.setTextColor(0xFF111111);
        diagHeading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        diagHeading.setPadding(0, dp(28), 0, dp(8));
        root.addView(diagHeading);

        TextView diagHelp = new TextView(this);
        diagHelp.setText("开始诊断 → 去目标应用复现问题 → 回来点“停止并导出”。Root 可用时会自动附带 LSPosed 日志。ZIP 保存到 Download/AlwaysForegroundX/。 ");
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
                Toast.makeText(this, "诊断已开始，现在去目标应用复现问题", Toast.LENGTH_LONG).show();
                return;
            }

            diagButton.setEnabled(false);
            diagButton.setText("正在收集并打包日志…");
            diagStatus.setText("正在抓取 logcat、system events 和 LSPosed 日志，请保持应用在前台直到完成。 ");
            DiagnosticsManager.stopAndExport(this, result -> runOnUiThread(() -> {
                diagButton.setEnabled(true);
                updateDiagnosticUi(targetPackage, diagStatus, diagButton);
                Toast.makeText(this, result.message,
                        result.success ? Toast.LENGTH_LONG : Toast.LENGTH_LONG).show();
                if (result.success) diagStatus.setText(result.message);
            }));
        });

        TextView body = new TextView(this);
        body.setTextSize(15);
        body.setTextColor(0xFF333333);
        body.setLineSpacing(0, 1.25f);
        body.setPadding(0, dp(24), 0, 0);

        SpannableStringBuilder text = new SpannableStringBuilder();
        appendHeading(text, "使用方法\n");
        text.append("1. 在 LSPosed 中启用本模块。\n");
        text.append("2. 在模块作用域中勾选需要后台播放或挂机的应用。\n");
        text.append("3. 从普通模式开始测试；改模式后强制停止并重新打开目标应用最稳妥。\n\n");
        appendHeading(text, "日志判断\n");
        text.append("LSPosed 日志中 INSTALLED 表示 Hook 安装成功；HIT 表示目标应用真的调用了该检测点。诊断 ZIP 会自动收集本次复现窗口中的这些记录。\n\n");
        appendHeading(text, "限制\n");
        text.append("本模块欺骗的是应用自身的前后台查询，不会把 Android 系统中的 Activity 真正保持为前台，也不会阻止系统/OEM 杀进程或冻结。直接在 onPause/onStop 中暂停的应用仍可能需要专用 Hook。\n");
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
            status.setText("● 诊断中  目标：" + DiagnosticsManager.getTarget(this) + "  开始：" + time);
            status.setTextColor(0xFFD32F2F);
            button.setText("停止并导出诊断 ZIP");
        } else {
            status.setText("未开始诊断");
            status.setTextColor(0xFF666666);
            button.setText("开始诊断");
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
