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
    private Button scopeSyncButton;
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
                        ? "勾选后可为每个 App 独立选择：自动检测 / 始终强制 / 仅原生。"
                        + "自动/强制模式离开前台时会优先把当前 Task 转成 OxygenOS 真实 FlexibleWindow，并立即最小化成浮动图标继续播放。"
                        + "播放器仍采用 5.4.5 的生命周期保护：只阻止 onPause/onStop 同步触发的 pause/stop，不主动恢复、不判断播放器实例，剧集切换和下一集由 App 自己管理。"
                        + "仅原生模式不强制转小窗，也不阻止系统 STOP。首次授权作用域后请重新打开目标 App。"
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

        if (MODE_BACKGROUND_PLAYBACK
                .equals(mode)) {
            scopeSyncButton =
                    new Button(this);
            scopeSyncButton.setText(
                    "同步到 LSPosed 作用域");
            scopeSyncButton.setAllCaps(false);
            scopeSyncButton.setOnClickListener(
                    v -> syncSelectedScopes());
            root.addView(
                    scopeSyncButton,
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
        }

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
        SystemBarInsets.apply(root);

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

        filteredApps.sort(
                Comparator
                        .comparing(
                                (AppItem item) ->
                                        selected.contains(
                                                item.packageName)
                                                ? 0
                                                : 1)
                        .thenComparing(
                                item ->
                                        item.label,
                                String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(
                                item ->
                                        item.packageName));

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

            if (MODE_BACKGROUND_PLAYBACK
                    .equals(mode)) {
                GuardApp.putBackgroundPlaybackMode(
                        item.packageName,
                        GuardApp.getBackgroundPlaybackMode(
                                item.packageName));
            }
        } else {
            selected.remove(item.packageName);

            if (MODE_BACKGROUND_PLAYBACK
                    .equals(mode)) {
                GuardApp.removeBackgroundPlaybackMode(
                        item.packageName);
            }
        }

        GuardApp.putStringSet(
                preferenceKey,
                selected);

        applyFilter(
                search == null
                        ? ""
                        : search
                        .getText()
                        .toString());
    }

    private void syncSelectedScopes() {
        if (!MODE_BACKGROUND_PLAYBACK
                .equals(mode)) {
            return;
        }

        if (selected.isEmpty()) {
            Toast.makeText(
                    this,
                    "请先选择后台播放应用",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        if (scopeSyncButton != null) {
            scopeSyncButton.setEnabled(
                    false);
            scopeSyncButton.setText(
                    "正在请求 LSPosed…");
        }

        GuardApp.syncAll();

        GuardApp.requestSelectedPlaybackScopes(
                new HashSet<>(selected),
                (selectedCount,
                 alreadyPresent,
                 addedCount,
                 notAddedCount,
                 error) ->
                        runOnUiThread(() -> {
                            if (scopeSyncButton != null) {
                                scopeSyncButton.setEnabled(
                                        true);
                                scopeSyncButton.setText(
                                        "同步到 LSPosed 作用域");
                            }

                            String message;

                            if (error != null
                                    && !error.isBlank()) {
                                message =
                                        "同步失败："
                                                + error;
                            } else if (notAddedCount > 0) {
                                message =
                                        "已选择 "
                                                + selectedCount
                                                + " 个，已在作用域 "
                                                + alreadyPresent
                                                + " 个，本次新增 "
                                                + addedCount
                                                + " 个，仍有 "
                                                + notAddedCount
                                                + " 个未加入";
                            } else {
                                message =
                                        "同步完成：已选择 "
                                                + selectedCount
                                                + " 个，已在作用域 "
                                                + alreadyPresent
                                                + " 个，本次新增 "
                                                + addedCount
                                                + " 个";
                            }

                            Toast.makeText(
                                    this,
                                    message
                                            + "\n新增后重新打开目标 App 生效",
                                    notAddedCount > 0
                                            || error != null
                                            ? Toast.LENGTH_LONG
                                            : Toast.LENGTH_SHORT)
                                    .show();

                            refreshCount();
                        }));
    }

    private void refreshCount() {
        if (countView == null) return;

        if (MODE_BACKGROUND_PLAYBACK
                .equals(mode)) {
            int scoped = 0;

            for (String packageName :
                    selected) {
                if (GuardApp.hasPlaybackScope(
                        packageName)) {
                    scoped++;
                }
            }

            countView.setText(
                    "已选择 "
                            + selected.size()
                            + " 个 · 已加入 LSPosed "
                            + scoped
                            + " 个 · 当前显示 "
                            + filteredApps.size()
                            + " 个");
        } else {
            countView.setText(
                    "已选择 "
                            + selected.size()
                            + " 个 · 当前显示 "
                            + filteredApps.size()
                            + " 个");
        }
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
        SystemBarInsets.apply(root);
    }

    private int dp(float value) {
        return Math.round(
                value
                        * getResources()
                        .getDisplayMetrics()
                        .density);
    }

    private static String playbackModeLabel(
            String mode
    ) {
        if (GuardConfig.PLAYBACK_MODE_FORCE.equals(mode)) {
            return "始终强制";
        }

        if (GuardConfig.PLAYBACK_MODE_NATIVE.equals(mode)) {
            return "仅原生";
        }

        return "自动检测";
    }

    private static String shortPlaybackModeLabel(
            String mode
    ) {
        if (GuardConfig.PLAYBACK_MODE_FORCE.equals(mode)) {
            return "强制";
        }

        if (GuardConfig.PLAYBACK_MODE_NATIVE.equals(mode)) {
            return "原生";
        }

        return "自动";
    }

    private static String nextPlaybackMode(
            String mode
    ) {
        if (GuardConfig.PLAYBACK_MODE_FORCE.equals(mode)) {
            return GuardConfig.PLAYBACK_MODE_NATIVE;
        }

        if (GuardConfig.PLAYBACK_MODE_NATIVE.equals(mode)) {
            return GuardConfig.PLAYBACK_MODE_AUTO;
        }

        return GuardConfig.PLAYBACK_MODE_FORCE;
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
            boolean isSelected =
                    selected.contains(
                            item.packageName);

            String modeSuffix =
                    MODE_BACKGROUND_PLAYBACK.equals(mode)
                            && isSelected
                            ? " · "
                            + playbackModeLabel(
                            GuardApp.getBackgroundPlaybackMode(
                                    item.packageName))
                            : "";

            holder.subtitle.setText(
                    item.packageName
                            + (item.system
                            ? " · 系统应用"
                            : "")
                            + modeSuffix);

            holder.check
                    .setOnCheckedChangeListener(
                            null);

            holder.check.setChecked(
                    isSelected);

            holder.check
                    .setOnCheckedChangeListener(
                            (button, checked) ->
                                    toggle(
                                            item,
                                            checked));

            if (MODE_BACKGROUND_PLAYBACK.equals(mode)
                    && isSelected) {
                String playbackMode =
                        GuardApp.getBackgroundPlaybackMode(
                                item.packageName);

                holder.modeButton.setVisibility(
                        View.VISIBLE);
                holder.modeButton.setText(
                        shortPlaybackModeLabel(
                                playbackMode));
                holder.modeButton.setOnClickListener(v -> {
                    String next =
                            nextPlaybackMode(
                                    GuardApp.getBackgroundPlaybackMode(
                                            item.packageName));

                    GuardApp.putBackgroundPlaybackMode(
                            item.packageName,
                            next);

                    applyFilter(
                            search == null
                                    ? ""
                                    : search.getText()
                                    .toString());
                });
            } else {
                holder.modeButton.setVisibility(
                        View.GONE);
                holder.modeButton.setOnClickListener(
                        null);
            }

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

            Button modeButton =
                    new Button(
                            TargetAppsActivity.this);
            modeButton.setAllCaps(false);
            modeButton.setTextSize(12);
            modeButton.setMinWidth(0);
            modeButton.setMinimumWidth(0);
            modeButton.setPadding(
                    dp(8),
                    0,
                    dp(8),
                    0);
            modeButton.setVisibility(
                    View.GONE);
            row.addView(
                    modeButton,
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            dp(40)));

            CheckBox check =
                    new CheckBox(
                            TargetAppsActivity.this);
            row.addView(check);

            RowHolder holder =
                    new RowHolder(
                            row,
                            title,
                            subtitle,
                            modeButton,
                            check);

            row.setTag(holder);

            return holder;
        }
    }

    private static final class RowHolder {
        final LinearLayout root;
        final TextView title;
        final TextView subtitle;
        final Button modeButton;
        final CheckBox check;

        RowHolder(
                LinearLayout root,
                TextView title,
                TextView subtitle,
                Button modeButton,
                CheckBox check
        ) {
            this.root = root;
            this.title = title;
            this.subtitle = subtitle;
            this.modeButton = modeButton;
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
