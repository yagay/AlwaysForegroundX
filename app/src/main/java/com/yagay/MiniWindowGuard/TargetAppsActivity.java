package com.yagay.MiniWindowGuard;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class TargetAppsActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ArrayList<AppItem> allApps = new ArrayList<>();
    private final ArrayList<AppItem> filteredApps = new ArrayList<>();
    private final LinkedHashSet<String> selected = new LinkedHashSet<>();

    private AppAdapter adapter;
    private TextView countView;
    private ProgressBar progress;
    private EditText search;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            selected.addAll(GuardApp.getTargetPackages());
            buildUi();
            loadAppsAsync();
        } catch (Throwable t) {
            CrashStore.record(this, "TargetAppsActivity.onCreate", t);
            showFatal(t);
        }
    }

    @Override
    protected void onPause() {
        persistSelection(false);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        root.setBackgroundColor(0xFFF5F6F8);

        TextView title = new TextView(this);
        title.setText("受保护应用");
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(title);

        TextView help = new TextView(this);
        help.setText("勾选后立即自动保存，不需要再点保存。"
                + "这里只是小窗守护自己的保护名单；LSPosed 仍只需要选择 System Framework / system_server。");
        help.setTextSize(13.5f);
        help.setTextColor(0xFF666A73);
        help.setPadding(0, dp(4), 0, dp(10));
        root.addView(help);

        search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("搜索应用名或包名");
        search.setTextSize(14);
        root.addView(search, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);

        Button all = new Button(this);
        all.setText("全选");
        all.setAllCaps(false);
        all.setOnClickListener(v -> {
            for (AppItem item : filteredApps) selected.add(item.packageName);
            persistSelection(true);
            if (adapter != null) adapter.notifyDataSetChanged();
            refreshCount();
        });
        actions.addView(all, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button none = new Button(this);
        none.setText("取消当前");
        none.setAllCaps(false);
        none.setOnClickListener(v -> {
            for (AppItem item : filteredApps) selected.remove(item.packageName);
            persistSelection(true);
            if (adapter != null) adapter.notifyDataSetChanged();
            refreshCount();
        });
        actions.addView(none, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(actions);

        countView = new TextView(this);
        countView.setTextSize(13.5f);
        countView.setTextColor(0xFF3F444C);
        countView.setPadding(0, dp(4), 0, dp(6));
        root.addView(countView);

        progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        root.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        ListView list = new ListView(this);
        list.setDividerHeight(1);
        adapter = new AppAdapter();
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f));

        Button save = new Button(this);
        save.setText("完成（勾选已自动保存）");
        save.setAllCaps(false);
        save.setOnClickListener(v -> {
            persistSelection(true);
            finish();
        });
        root.addView(save);

        setContentView(root);

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(
                    CharSequence s, int start, int count, int after) {}

            @Override public void onTextChanged(
                    CharSequence s, int start, int before, int count) {
                applyFilter(s == null ? "" : s.toString());
            }

            @Override public void afterTextChanged(Editable s) {}
        });

        refreshCount();
    }

    private void loadAppsAsync() {
        progress.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            ArrayList<AppItem> loaded = new ArrayList<>();
            Throwable failure = null;

            try {
                PackageManager pm = getPackageManager();
                List<ApplicationInfo> infos;

                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    infos = pm.getInstalledApplications(
                            PackageManager.ApplicationInfoFlags.of(0));
                } else {
                    infos = pm.getInstalledApplications(0);
                }

                for (ApplicationInfo info : infos) {
                    if (Thread.currentThread().isInterrupted()) return;
                    if (info == null || info.packageName == null) continue;
                    if (getPackageName().equals(info.packageName)) continue;

                    String label;
                    try {
                        CharSequence labelCs = info.loadLabel(pm);
                        label = labelCs == null
                                ? info.packageName
                                : labelCs.toString().trim();
                        if (label.isEmpty()) label = info.packageName;
                    } catch (Throwable ignored) {
                        label = info.packageName;
                    }

                    boolean system = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                    loaded.add(new AppItem(label, info.packageName, system));
                }

                for (String pkg : selected) {
                    boolean present = false;
                    for (AppItem item : loaded) {
                        if (item.packageName.equals(pkg)) {
                            present = true;
                            break;
                        }
                    }
                    if (!present) loaded.add(new AppItem(pkg, pkg, false));
                }

                loaded.sort(Comparator
                        .comparing((AppItem a) -> a.label, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(a -> a.packageName));
            } catch (Throwable t) {
                failure = t;
            }

            Throwable finalFailure = failure;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;

                progress.setVisibility(View.GONE);

                if (finalFailure != null) {
                    CrashStore.record(this, "TargetAppsActivity.loadApps", finalFailure);
                    Toast.makeText(
                            this,
                            "读取应用列表失败：" + finalFailure.getClass().getSimpleName(),
                            Toast.LENGTH_LONG).show();
                }

                allApps.clear();
                allApps.addAll(loaded);
                applyFilter(search == null ? "" : search.getText().toString());
            });
        });
    }

    private void applyFilter(String raw) {
        String query = raw == null
                ? ""
                : raw.trim().toLowerCase(Locale.ROOT);

        filteredApps.clear();
        if (query.isEmpty()) {
            filteredApps.addAll(allApps);
        } else {
            for (AppItem item : allApps) {
                if (item.label.toLowerCase(Locale.ROOT).contains(query)
                        || item.packageName.toLowerCase(Locale.ROOT).contains(query)) {
                    filteredApps.add(item);
                }
            }
        }

        if (adapter != null) adapter.notifyDataSetChanged();
        refreshCount();
    }

    private void refreshCount() {
        if (countView == null) return;
        countView.setText("已选 " + selected.size()
                + " 个 · 当前显示 " + filteredApps.size()
                + " 个");
    }

    private void persistSelection(boolean applyRoot) {
        Set<String> before = new LinkedHashSet<>(GuardApp.getTargetPackages());
        Set<String> after = new LinkedHashSet<>(selected);

        if (before.equals(after)) return;

        GuardApp.setTargetPackages(after);

        if (applyRoot) {
            executor.execute(() -> {
                try {
                    RootPolicyManager.reconcile(this, before, after);
                } catch (Throwable t) {
                    CrashStore.record(
                            this,
                            "TargetAppsActivity.persistSelection",
                            t);
                }
            });
        }
    }

    private void launchContainer(AppItem item, Button button) {
        if (!selected.contains(item.packageName)) {
            selected.add(item.packageName);
            persistSelection(true);
            refreshCount();
        }

        if (!GuardApp.isSystemEngineCurrent()) {
            Toast.makeText(
                    this,
                    "当前版本的 system_server 引擎还没有加载。安装/更新模块后请先重启手机。",
                    Toast.LENGTH_LONG).show();
            return;
        }

        button.setEnabled(false);

        executor.execute(() -> {
            Throwable failure = null;

            try {
                RootPolicyManager.apply(this, item.packageName);

                Intent launch = getPackageManager()
                        .getLaunchIntentForPackage(item.packageName);
                if (launch == null) {
                    throw new IllegalStateException("No launch intent");
                }

                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(launch);
            } catch (Throwable t) {
                failure = t;
                CrashStore.record(
                        this,
                        "TargetAppsActivity.launchContainer:" + item.packageName,
                        t);
            }

            Throwable finalFailure = failure;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;

                button.setEnabled(true);
                if (finalFailure != null) {
                    Toast.makeText(
                            this,
                            "容器启动失败：" + finalFailure.getClass().getSimpleName(),
                            Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(
                            this,
                            "已启动，system_server 将接管真实 Task",
                            Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void showFatal(Throwable t) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(18));

        TextView title = new TextView(this);
        title.setText("受保护应用页面启动失败");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(title);

        TextView detail = new TextView(this);
        detail.setText(t.getClass().getName() + "\n"
                + String.valueOf(t.getMessage())
                + "\n\n错误已写入诊断崩溃日志。");
        detail.setTextSize(14);
        detail.setPadding(0, dp(10), 0, dp(10));
        root.addView(detail);

        Button close = new Button(this);
        close.setText("返回");
        close.setOnClickListener(v -> finish());
        root.addView(close);

        setContentView(root);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class AppAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return filteredApps.size();
        }

        @Override
        public Object getItem(int position) {
            return filteredApps.get(position);
        }

        @Override
        public long getItemId(int position) {
            return filteredApps.get(position).packageName.hashCode();
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            RowHolder holder;
            if (convertView instanceof LinearLayout) {
                Object tag = convertView.getTag();
                if (tag instanceof RowHolder) {
                    holder = (RowHolder) tag;
                } else {
                    holder = createRow();
                    convertView = holder.root;
                }
            } else {
                holder = createRow();
                convertView = holder.root;
            }

            AppItem item = filteredApps.get(position);
            holder.title.setText(item.label);
            holder.subtitle.setText(item.packageName
                    + (item.system ? " · 系统应用" : ""));
            holder.check.setOnCheckedChangeListener(null);
            holder.check.setChecked(selected.contains(item.packageName));
            holder.check.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isChecked) selected.add(item.packageName);
                else selected.remove(item.packageName);

                persistSelection(true);
                refreshCount();
            });

            holder.launch.setOnClickListener(
                    v -> launchContainer(item, holder.launch));

            return convertView;
        }

        private RowHolder createRow() {
            LinearLayout row = new LinearLayout(TargetAppsActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));

            CheckBox check = new CheckBox(TargetAppsActivity.this);
            row.addView(check);

            LinearLayout texts = new LinearLayout(TargetAppsActivity.this);
            texts.setOrientation(LinearLayout.VERTICAL);

            TextView title = new TextView(TargetAppsActivity.this);
            title.setTextSize(15);
            title.setTextColor(0xFF202124);
            texts.addView(title);

            TextView subtitle = new TextView(TargetAppsActivity.this);
            subtitle.setTextSize(12);
            subtitle.setTextColor(0xFF70757A);
            texts.addView(subtitle);

            row.addView(texts, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Button launch = new Button(TargetAppsActivity.this);
            launch.setText("容器");
            launch.setAllCaps(false);
            row.addView(launch);

            RowHolder holder = new RowHolder(row, check, title, subtitle, launch);
            row.setTag(holder);
            return holder;
        }
    }

    private static final class RowHolder {
        final LinearLayout root;
        final CheckBox check;
        final TextView title;
        final TextView subtitle;
        final Button launch;

        RowHolder(
                LinearLayout root,
                CheckBox check,
                TextView title,
                TextView subtitle,
                Button launch
        ) {
            this.root = root;
            this.check = check;
            this.title = title;
            this.subtitle = subtitle;
            this.launch = launch;
        }
    }

    private static final class AppItem {
        final String label;
        final String packageName;
        final boolean system;

        AppItem(String label, String packageName, boolean system) {
            this.label = label;
            this.packageName = packageName;
            this.system = system;
        }
    }
}
