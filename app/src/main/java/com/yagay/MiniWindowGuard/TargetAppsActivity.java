package com.yagay.MiniWindowGuard;

import android.app.Activity;
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
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class TargetAppsActivity extends Activity {
    static final String EXTRA_MODE = "mode";
    static final String MODE_FOREGROUND = "foreground";
    static final String MODE_BACKGROUND_PLAYBACK =
            "background_playback";
    static final String MODE_FORCE_SUPPORT = "force_support";

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor();
    private final ArrayList<AppItem> allApps =
            new ArrayList<>();
    private final ArrayList<AppItem> filteredApps =
            new ArrayList<>();
    private final HashSet<String> selected =
            new HashSet<>();

    private AppAdapter adapter;
    private ProgressBar progress;
    private EditText search;
    private TextView countView;
    private String mode;
    private String preferenceKey;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mode = getIntent() == null
                ? MODE_FOREGROUND
                : getIntent().getStringExtra(EXTRA_MODE);

        if (!MODE_FORCE_SUPPORT.equals(mode)
                && !MODE_BACKGROUND_PLAYBACK.equals(mode)) {
            mode = MODE_FOREGROUND;
        }

        preferenceKey =
                MODE_FORCE_SUPPORT.equals(mode)
                        ? ConfigKeys.FORCE_SUPPORT_PACKAGES
                        : MODE_BACKGROUND_PLAYBACK.equals(mode)
                        ? ConfigKeys.BACKGROUND_PLAYBACK_PACKAGES
                        : ConfigKeys.FOREGROUND_PACKAGES;

        selected.addAll(
                GuardApp.getStringSet(
                        preferenceKey));

        try {
            buildUi();
            loadAppsAsync();
        } catch (Throwable t) {
            CrashStore.record(
                    this,
                    "TargetAppsActivity.onCreate",
                    t);
            showFatal(t);
        }
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void buildUi() {
        LinearLayout root =
                new LinearLayout(this);
        root.setOrientation(
                LinearLayout.VERTICAL);
        root.setPadding(
                dp(16),
                dp(16),
                dp(16),
                dp(16));
        root.setBackgroundColor(
                0xFFF5F6F8);

        TextView title =
                new TextView(this);
        title.setText(
                MODE_FORCE_SUPPORT.equals(mode)
                        ? "强制允许一加小窗"
                        : MODE_BACKGROUND_PLAYBACK.equals(mode)
                        ? "后台播放应用"
                        : "始终前台应用");
        title.setTextSize(26);
        title.setTypeface(
                Typeface.DEFAULT,
                Typeface.BOLD);
        root.addView(title);

        TextView help =
                new TextView(this);
        help.setText(
                MODE_FORCE_SUPPORT.equals(mode)
                        ? "勾选后仅放行该 App 的 OPlus FlexibleWindow 支持/黑名单判断。"
                        + "App 仍由 OxygenOS 自己启动和进入小窗。"
                        : MODE_BACKGROUND_PLAYBACK.equals(mode)
                        ? "勾选后，该 App 从普通全屏切到桌面/其他 App 或锁屏时进入 BACKGROUND_PROTECTED。"
                        + "同 App 页面跳转、Activity finishing、强制停止和真实关闭不拦截。"
                        : "勾选后，只有当该 App 当前真实处于一加小窗、贴边小窗或锁屏中的一加小窗时，"
                        + "MiniWindowGuard 才维持前台和后台播放；普通全屏状态完全不干预。");
        help.setTextSize(13.5f);
        help.setTextColor(0xFF666A73);
        help.setPadding(
                0,
                dp(4),
                0,
                dp(10));
        root.addView(help);

        search =
                new EditText(this);
        search.setSingleLine(true);
        search.setHint("搜索应用名或包名");
        search.setTextSize(14);
        root.addView(
                search,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

        countView =
                new TextView(this);
        countView.setTextSize(13.5f);
        countView.setTextColor(0xFF3F444C);
        countView.setPadding(
                0,
                dp(6),
                0,
                dp(6));
        root.addView(countView);

        progress =
                new ProgressBar(this);
        progress.setIndeterminate(true);
        root.addView(progress);

        ListView list =
                new ListView(this);
        list.setDividerHeight(1);
        adapter = new AppAdapter();
        list.setAdapter(adapter);
        root.addView(
                list,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1f));

        Button close =
                new Button(this);
        close.setText("返回");
        close.setAllCaps(false);
        close.setOnClickListener(
                v -> finish());
        root.addView(close);

        setContentView(root);

        search.addTextChangedListener(
                new TextWatcher() {
                    @Override
                    public void beforeTextChanged(
                            CharSequence s,
                            int start,
                            int count,
                            int after
                    ) {}

                    @Override
                    public void onTextChanged(
                            CharSequence s,
                            int start,
                            int before,
                            int count
                    ) {
                        applyFilter(
                                s == null
                                        ? ""
                                        : s.toString());
                    }

                    @Override
                    public void afterTextChanged(
                            Editable s
                    ) {}
                });
    }

    private void loadAppsAsync() {
        progress.setVisibility(
                View.VISIBLE);

        executor.execute(() -> {
            ArrayList<AppItem> loaded =
                    new ArrayList<>();
            Throwable failure = null;

            try {
                PackageManager pm =
                        getPackageManager();
                List<ApplicationInfo> infos;

                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    infos =
                            pm.getInstalledApplications(
                                    PackageManager
                                            .ApplicationInfoFlags
                                            .of(0));
                } else {
                    infos =
                            pm.getInstalledApplications(0);
                }

                for (ApplicationInfo info : infos) {
                    if (Thread.currentThread()
                            .isInterrupted()) {
                        return;
                    }

                    if (info == null
                            || info.packageName == null
                            || getPackageName()
                            .equals(info.packageName)) {
                        continue;
                    }

                    if (pm.getLaunchIntentForPackage(
                            info.packageName) == null) {
                        continue;
                    }

                    String label;

                    try {
                        CharSequence cs =
                                info.loadLabel(pm);
                        label =
                                cs == null
                                        ? info.packageName
                                        : cs.toString().trim();

                        if (label.isEmpty()) {
                            label = info.packageName;
                        }
                    } catch (Throwable ignored) {
                        label = info.packageName;
                    }

                    boolean system =
                            (info.flags
                                    & ApplicationInfo.FLAG_SYSTEM)
                                    != 0;

                    loaded.add(
                            new AppItem(
                                    label,
                                    info.packageName,
                                    system));
                }

                loaded.sort(
                        Comparator
                                .comparing(
                                        (AppItem item) ->
                                                item.label,
                                        String.CASE_INSENSITIVE_ORDER)
                                .thenComparing(
                                        item ->
                                                item.packageName));
            } catch (Throwable t) {
                failure = t;
            }

            Throwable finalFailure = failure;

            runOnUiThread(() -> {
                if (isFinishing()
                        || isDestroyed()) {
                    return;
                }

                progress.setVisibility(
                        View.GONE);

                if (finalFailure != null) {
                    CrashStore.record(
                            this,
                            "TargetAppsActivity.loadApps",
                            finalFailure);

                    Toast.makeText(
                            this,
                            "读取应用列表失败："
                                    + finalFailure
                                    .getClass()
                                    .getSimpleName(),
                            Toast.LENGTH_LONG).show();
                }

                allApps.clear();
                allApps.addAll(loaded);

                applyFilter(
                        search == null
                                ? ""
                                : search
                                .getText()
                                .toString());
            });
        });
    }

    private void applyFilter(String raw) {
        String query =
                raw == null
                        ? ""
                        : raw.trim()
                        .toLowerCase(Locale.ROOT);

        filteredApps.clear();

        if (query.isEmpty()) {
            filteredApps.addAll(allApps);
        } else {
            for (AppItem item : allApps) {
                if (item.label
                        .toLowerCase(Locale.ROOT)
                        .contains(query)
                        || item.packageName
                        .toLowerCase(Locale.ROOT)
                        .contains(query)) {
                    filteredApps.add(item);
                }
            }
        }

        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }

        refreshCount();
    }

    private void toggle(
            AppItem item,
            boolean enabled
    ) {
        if (enabled) {
            selected.add(item.packageName);
        } else {
            selected.remove(item.packageName);
        }

        GuardApp.putStringSet(
                preferenceKey,
                selected);

        refreshCount();
    }

    private void refreshCount() {
        if (countView == null) return;

        countView.setText(
                "已选择 "
                        + selected.size()
                        + " 个 · 当前显示 "
                        + filteredApps.size()
                        + " 个");
    }

    private void showFatal(Throwable t) {
        LinearLayout root =
                new LinearLayout(this);
        root.setOrientation(
                LinearLayout.VERTICAL);
        root.setPadding(
                dp(18),
                dp(18),
                dp(18),
                dp(18));

        TextView title =
                new TextView(this);
        title.setText("应用列表启动失败");
        title.setTextSize(22);
        title.setTypeface(
                Typeface.DEFAULT,
                Typeface.BOLD);
        root.addView(title);

        TextView detail =
                new TextView(this);
        detail.setText(
                t.getClass().getName()
                        + "\n"
                        + String.valueOf(
                        t.getMessage())
                        + "\n\n错误已写入诊断日志。");
        detail.setTextSize(14);
        detail.setPadding(
                0,
                dp(10),
                0,
                dp(10));
        root.addView(detail);

        Button close =
                new Button(this);
        close.setText("返回");
        close.setOnClickListener(
                v -> finish());
        root.addView(close);

        setContentView(root);
    }

    private int dp(float value) {
        return Math.round(
                value
                        * getResources()
                        .getDisplayMetrics()
                        .density);
    }

    private final class AppAdapter
            extends BaseAdapter {
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
            return filteredApps
                    .get(position)
                    .packageName
                    .hashCode();
        }

        @Override
        public View getView(
                int position,
                View convertView,
                ViewGroup parent
        ) {
            RowHolder holder;

            if (convertView
                    instanceof LinearLayout
                    && convertView.getTag()
                    instanceof RowHolder) {
                holder =
                        (RowHolder) convertView
                                .getTag();
            } else {
                holder = createRow();
                convertView = holder.root;
            }

            AppItem item =
                    filteredApps.get(position);

            holder.title.setText(
                    item.label);
            holder.subtitle.setText(
                    item.packageName
                            + (item.system
                            ? " · 系统应用"
                            : ""));

            holder.check
                    .setOnCheckedChangeListener(
                            null);

            holder.check.setChecked(
                    selected.contains(
                            item.packageName));

            holder.check
                    .setOnCheckedChangeListener(
                            (button, checked) ->
                                    toggle(
                                            item,
                                            checked));

            holder.root.setOnClickListener(v ->
                    holder.check.setChecked(
                            !holder.check
                                    .isChecked()));

            return convertView;
        }

        private RowHolder createRow() {
            LinearLayout row =
                    new LinearLayout(
                            TargetAppsActivity.this);
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
                    new LinearLayout(
                            TargetAppsActivity.this);
            texts.setOrientation(
                    LinearLayout.VERTICAL);

            TextView title =
                    new TextView(
                            TargetAppsActivity.this);
            title.setTextSize(15);
            title.setTextColor(
                    0xFF202124);
            texts.addView(title);

            TextView subtitle =
                    new TextView(
                            TargetAppsActivity.this);
            subtitle.setTextSize(12);
            subtitle.setTextColor(
                    0xFF70757A);
            texts.addView(subtitle);

            row.addView(
                    texts,
                    new LinearLayout.LayoutParams(
                            0,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            1f));

            CheckBox check =
                    new CheckBox(
                            TargetAppsActivity.this);
            row.addView(check);

            RowHolder holder =
                    new RowHolder(
                            row,
                            title,
                            subtitle,
                            check);

            row.setTag(holder);

            return holder;
        }
    }

    private static final class RowHolder {
        final LinearLayout root;
        final TextView title;
        final TextView subtitle;
        final CheckBox check;

        RowHolder(
                LinearLayout root,
                TextView title,
                TextView subtitle,
                CheckBox check
        ) {
            this.root = root;
            this.title = title;
            this.subtitle = subtitle;
            this.check = check;
        }
    }

    private static final class AppItem {
        final String label;
        final String packageName;
        final boolean system;

        AppItem(
                String label,
                String packageName,
                boolean system
        ) {
            this.label = label;
            this.packageName = packageName;
            this.system = system;
        }
    }
}
