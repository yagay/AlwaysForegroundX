package com.yagay.MiniWindowGuard;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class TargetAppsActivity extends Activity {
    private final Map<String, CheckBox> rows = new LinkedHashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Set<String> original;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        original = new LinkedHashSet<>(GuardApp.getTargetPackages());

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(28));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("受保护应用");
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(title);

        TextView help = new TextView(this);
        help.setText("这里只是小窗守护自己的保护名单，不是 LSPosed 作用域。"
                + "每个 App 右侧“小窗”按钮会按首页设置的自由小窗 / 图标 / 隐藏形态启动。");
        help.setTextSize(14);
        help.setPadding(0, dp(6), 0, dp(12));
        root.addView(help);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);

        Button all = new Button(this);
        all.setText("全选");
        all.setOnClickListener(v -> rows.values().forEach(c -> c.setChecked(true)));
        actions.addView(all, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button none = new Button(this);
        none.setText("全部取消");
        none.setOnClickListener(v -> rows.values().forEach(c -> c.setChecked(false)));
        actions.addView(none, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(actions);

        for (AppItem app : loadLaunchableApps()) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(3), 0, dp(3));

            CheckBox box = new CheckBox(this);
            box.setText(app.label + "\n" + app.packageName);
            box.setTextSize(14);
            box.setChecked(original.contains(app.packageName));
            row.addView(box, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            rows.put(app.packageName, box);

            Button launch = new Button(this);
            launch.setText("小窗");
            launch.setAllCaps(false);
            launch.setOnClickListener(v -> {
                launch.setEnabled(false);
                executor.execute(() -> {
                    boolean ok = WindowLauncher.launch(this, app.packageName);
                    runOnUiThread(() -> {
                        launch.setEnabled(true);
                        Toast.makeText(this,
                                ok ? "已请求系统小窗" : "小窗启动失败，请检查 Root/系统支持",
                                Toast.LENGTH_SHORT).show();
                    });
                });
            });
            row.addView(launch);

            root.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        Button save = new Button(this);
        save.setText("保存保护名单并应用 Root 策略");
        save.setOnClickListener(v -> save());
        root.addView(save);

        setContentView(scroll);
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void save() {
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        for (Map.Entry<String, CheckBox> entry : rows.entrySet()) {
            if (entry.getValue().isChecked()) selected.add(entry.getKey());
        }

        Set<String> before = new LinkedHashSet<>(original);
        GuardApp.setTargetPackages(selected);
        original = new LinkedHashSet<>(selected);

        executor.execute(() -> {
            RootPolicyManager.reconcile(this, before, selected);
            runOnUiThread(() -> {
                Toast.makeText(this,
                        "已保存 " + selected.size() + " 个受保护应用",
                        Toast.LENGTH_SHORT).show();
                finish();
            });
        });
    }

    private List<AppItem> loadLaunchableApps() {
        Intent launcher = new Intent(Intent.ACTION_MAIN);
        launcher.addCategory(Intent.CATEGORY_LAUNCHER);

        List<ResolveInfo> resolved = getPackageManager().queryIntentActivities(launcher, 0);
        Map<String, AppItem> unique = new LinkedHashMap<>();

        for (ResolveInfo info : resolved) {
            if (info.activityInfo == null) continue;
            String pkg = info.activityInfo.packageName;
            if (pkg == null || pkg.equals(getPackageName())) continue;

            CharSequence labelCs = info.loadLabel(getPackageManager());
            String label = labelCs == null ? pkg : labelCs.toString();
            unique.putIfAbsent(pkg, new AppItem(label, pkg));
        }

        for (String pkg : original) {
            unique.putIfAbsent(pkg, new AppItem(pkg, pkg));
        }

        ArrayList<AppItem> result = new ArrayList<>(unique.values());
        result.sort(Comparator
                .comparing((AppItem a) -> a.label, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(a -> a.packageName));
        return result;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private record AppItem(String label, String packageName) {}
}
