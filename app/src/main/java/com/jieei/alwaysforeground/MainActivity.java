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
        diagHeading.setText("自动定位 Hook 点");
        diagHeading.setTextSize(19);
        diagHeading.setTextColor(0xFF111111);
        diagHeading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        diagHeading.setPadding(0, dp(28), 0, dp(8));
        root.addView(diagHeading);

        TextView diagHelp = new TextView(this);
        diagHelp.setText("开始诊断后，模块会自动监听 MediaPlayer、AudioTrack、ExoPlayer/Media3、TTVideoEngine 等播放器的 pause/stop/release 终点。去目标应用播放视频并退到后台，等播放停止后回来导出。ZIP 里的 hook-candidates.txt 会包含建议 Hook 点和完整调用栈。Root 可用时同时附带 LSPosed 原始日志。");
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
        text.append("HOOK_CANDIDATE 是真正发生的播放器停止终点；HOOK_SUGGEST 是自动评分后的建议上游调用者；HOOK_STACK 是完整调用链。优先 Hook 分数高的应用自身方法，而不是 MediaPlayer/Fragment 生命周期本身。\n\n");
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
