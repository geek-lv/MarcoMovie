package com.pilipala.marcomovie;

import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.*;
import org.json.JSONObject;

/** Compact continue-watching cards above one category-based, vertically browsed library. */
final class HomeScreen {
    static final int MAX_RETAINED_CARDS = 120;
    final MainActivity a;
    final Cms.Site site;
    final int COLUMNS;
    final Map<View, String> tabIds = new HashMap<>();
    final List<View> historyCards = new ArrayList<>(), tabs = new ArrayList<>();
    final List<List<View>> rows = new ArrayList<>();
    LinearLayout content;
    CategoryFlow filters;
    HomeAmbient ambient;
    TextView status, source, more, earlier, allHistory;
    int ticket, request, posterWidth, gridEpoch;
    Runnable pendingGrid;

    /** Tag of the card that should take focus once drawn after "加载更多". */
    String pendingFocus;

    boolean busy;
    Snapshot snapshot;
    State state;
    FrameLayout retainedRoot;
    LinearLayout retainedBody;
    android.widget.ScrollView retainedScroll;
    HomeDock retainedDock;
    int retainedWidth, retainedHeight, retainedEngineEpoch;
    float retainedFontScale;
    Map<String, Object> retainedAppearance;

    static final class Snapshot {
        final long time = System.currentTimeMillis();
        final List<Cms.Video> videos;
        final List<Cms.Category> categories;
        final String skip;
        final int pages;
        String filterSchema = "{}";

        Snapshot(List<Cms.Video> videos, List<Cms.Category> categories, String skip, int pages) {
            this.videos = videos;
            this.categories = categories;
            this.skip = skip;
            this.pages = pages;
        }
    }

    /** Navigation-entry state survives detail/back without changing the active category. */
    static final class State {
        String site, category = "";
        int engineEpoch;
        List<Cms.Video> videos = new ArrayList<>();
        int page = 1, pages = 1, windowStart = 0;
        boolean ready, initialized;
        Snapshot snapshot;
        final Map<String, String> selectedFilters = new LinkedHashMap<>();

        State(String key) {
            site = key;
        }
    }

    HomeScreen(MainActivity activity, Cms.Site selected) {
        a = activity;
        site = selected;
        COLUMNS =
                TvLayoutPolicy.responsiveHomeColumns(
                        a.windowWidth() / a.getResources().getDisplayMetrics().density,
                        a.prefs.getInt(Prefs.HOME_COLUMNS, 5),
                        a.formFactor,
                        a.isPortrait());
    }

    boolean active() {
        return ticket == a.generation && !a.isFinishing();
    }

    void show() {
        a.renderPage("", "", true);
        ambient = a.homeAmbient;
        ticket = a.generation;
        if (a.current != null) a.current.home = true;
        state = a.current == null ? null : a.current.homeState;
        if (state == null || !site.key.equals(state.site) || state.engineEpoch != a.engineEpoch) {
            state = new State(site.key);
            state.engineEpoch = a.engineEpoch;
            if (a.current != null) a.current.homeState = state;
        }
        boolean bottomDock = a.useBottomDock();
        if (bottomDock) {
            a.body.setPadding(a.dp(18), a.dp(16), a.dp(18), a.dp(86));
        } else {
            a.body.setPadding(a.dp(34), a.dp(18), a.dp(100), a.dp(36));
        }
        a.body.setClipChildren(false);
        a.body.setClipToPadding(false);
        a.scroll.setClipChildren(false);
        float width = a.windowWidth() / a.getResources().getDisplayMetrics().density;
        int marginDp = bottomDock ? 36 : 134;
        posterWidth = Math.max(48, (int) ((width - marginDp - (COLUMNS - 1) * 14) / COLUMNS) - 8);
        historySection();
        LinearLayout heading = a.row();
        heading.setClipChildren(false);
        heading.setClipToPadding(false);
        heading.setPadding(0, a.dp(6), 0, a.dp(6));
        TextView title = a.text("发现", 22, MainActivity.WHITE);
        title.setTypeface(null, Typeface.BOLD);
        heading.addView(title, new LinearLayout.LayoutParams(-2, -2));
        source = pill(site.name, "source-switcher", a::chooseSources);
        source.setPadding(a.dp(14), 0, a.dp(14), 0);
        source.setCompoundDrawablePadding(a.dp(9));
        source.setCompoundDrawables(null, null, new SourceChevron(a), null);
        source.setOnFocusChangeListener((v, f) -> styleSource(f));
        styleSource(false);
        source.setMaxWidth(a.dp(220));
        source.setContentDescription("当前片源：" + site.name + "，按确认键切换");
        LinearLayout.LayoutParams sourceSize = new LinearLayout.LayoutParams(-2, a.dp(34));
        sourceSize.leftMargin = a.dp(14);
        heading.addView(source, sourceSize);
        a.body.addView(heading);
        filters = new CategoryFlow(a, this::wireNavigation);
        filters.setPadding(0, a.dp(2), 0, a.dp(8));
        a.body.addView(filters);
        status = a.text("正在整理片单…", 13, MainActivity.MUTED);
        status.setPadding(a.dp(4), a.dp(12), 0, a.dp(12));
        a.body.addView(status);
        earlier =
                pill(
                        "查看前面的影片",
                        "home-earlier",
                        () -> showWindow(Math.max(0, state.windowStart - windowSize())));
        earlier.setVisibility(View.GONE);
        a.body.addView(earlier, new LinearLayout.LayoutParams(a.dp(180), a.dp(40)));
        content = a.vertical();
        content.setClipChildren(false);
        content.setClipToPadding(false);
        a.body.addView(content);
        more =
                pill(
                        "加载更多",
                        "home-load-more",
                        () -> {
                            if (snapshot == null) {
                                loadHome();
                                return;
                            }
                            if (windowEnd() < state.videos.size()) {
                                showWindow(state.windowStart + windowSize());
                                return;
                            }
                            loadCategory(!state.ready);
                        });
        LinearLayout footer = a.row();
        footer.setClipChildren(false);
        footer.setClipToPadding(false);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, a.dp(14), 0, a.dp(8));
        footer.addView(more, new LinearLayout.LayoutParams(a.dp(180), a.dp(40)));
        a.body.addView(footer);
        more.setVisibility(View.GONE);
        wireNavigation();
        retainedRoot = a.root;
        retainedBody = a.body;
        retainedScroll = a.scroll;
        retainedDock = a.homeDock;
        retainedWidth = a.windowWidth();
        retainedHeight = a.windowHeight();
        retainedFontScale = a.getResources().getConfiguration().fontScale;
        retainedEngineEpoch = a.engineEpoch;
        retainedAppearance = appearance();
        if (a.current != null) a.current.retainedHome = this;
        Snapshot saved = state.snapshot != null ? state.snapshot : a.homeData.get(site.key);
        if (saved != null
                && (state.snapshot != null || System.currentTimeMillis() - saved.time < 300000)) {
            snapshot = saved;
            renderSnapshot();
        } else loadHome();
    }

    Map<String, Object> appearance() {
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, ?> prefs = a.prefs.getAll();
        for (String key :
                new String[] {
                    Prefs.HOME_COLUMNS,
                    Prefs.HOME_ROWS,
                    Prefs.HOME_SECTIONS,
                    Prefs.HOME_AMBIENT,
                    Prefs.WALLPAPER_ENABLED,
                    Prefs.WALLPAPER_DIM,
                    Prefs.REDUCED,
                    Prefs.REDUCED_TRANSPARENCY,
                    Prefs.UI_SCALE_PERCENT
                }) values.put(key, prefs.get(key));
        return values;
    }

    /** Reattach the exact home tree after detail/playback, without a catalogue request. */
    boolean resume() {
        if (retainedRoot == null
                || state == null
                || !state.ready
                || busy
                || retainedWidth != a.windowWidth()
                || retainedHeight != a.windowHeight()
                || retainedFontScale != a.getResources().getConfiguration().fontScale
                || retainedEngineEpoch != a.engineEpoch
                || !appearance().equals(retainedAppearance)
                || !site.key.equals(a.prefs.getString(Prefs.SELECTED_SITE, ""))) return false;
        a.preparePage(true);
        ticket = a.generation;
        request++;
        cancelGrid();
        a.root = retainedRoot;
        a.body = retainedBody;
        a.scroll = retainedScroll;
        a.homeDock = retainedDock;
        a.homeAmbient = ambient;
        a.setContentView(retainedRoot);
        int drawn = state.windowStart;
        for (List<View> row : rows) drawn += row.size();
        if (drawn < windowEnd()) appendCards(drawn, gridEpoch, true);
        resumeMissingPosters(retainedRoot);
        updateMore();
        wireNavigation();
        return true;
    }

    void resumeMissingPosters(View view) {
        if (view instanceof ImageView
                && ((ImageView) view).getDrawable() == null
                && view.getTag() instanceof String) {
            a.poster((String) view.getTag(), (ImageView) view);
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++)
                resumeMissingPosters(group.getChildAt(i));
        }
    }

    void loadHome() {
        if (busy) return;
        busy = true;
        status.setVisibility(View.VISIBLE);
        status.setText("正在整理片单…");
        more.setVisibility(View.GONE);
        int token = ++request;
        a.prefs.edit().putString(Prefs.LOADING_SITE, site.key).commit();
        a.submitPage(
                () -> {
                    try {
                        SourceEngine engine = a.engine(site);
                        String raw = engine.home();
                        List<Cms.Category> categories = Cms.categories(raw);
                        Cms.Listing list =
                                new JSONObject(raw).has("list")
                                        ? Cms.listing(raw, site)
                                        : new Cms.Listing();
                        clean(list.videos);
                        if (list.videos.isEmpty())
                            try {
                                list = Cms.listing(engine.homeVideos(), site);
                                clean(list.videos);
                            } catch (Throwable ignored) {
                            }
                        String skip = "";
                        if (list.videos.isEmpty() && !categories.isEmpty()) {
                            skip = categories.get(0).id;
                            list = Cms.listing(engine.category(skip, 1), site);
                            clean(list.videos);
                        }
                        Snapshot result =
                                new Snapshot(list.videos, categories, skip, list.pageCount);
                        result.filterSchema = raw;
                        a.ui.post(
                                () -> {
                                    a.prefs.edit().putString(Prefs.LOADING_SITE, "").apply();
                                    if (!active() || token != request) return;
                                    busy = false;
                                    snapshot = result;
                                    a.homeData.put(site.key, result);
                                    renderSnapshot();
                                });
                    } catch (Throwable e) {
                        AppLog.w("HomeScreen", "loadHome failed for " + site.key, e);
                        a.ui.post(
                                () -> {
                                    a.prefs.edit().putString(Prefs.LOADING_SITE, "").apply();
                                    if (!active() || token != request) return;
                                    busy = false;
                                    status.setText(a.message(e));
                                    more.setText("重新加载");
                                    more.setVisibility(View.VISIBLE);
                                    wireNavigation();
                                    if (source != null) source.requestFocus();
                                });
                    }
                });
    }

    void historySection() {
        List<WatchHistory.Entry> entries = a.watchHistory();
        if (entries.isEmpty()) return;
        boolean compactStack = a.useBottomDock();
        LinearLayout section = compactStack ? a.vertical() : a.row();
        section.setTag("home-history-section");
        section.setGravity(Gravity.TOP);
        section.setClipChildren(false);
        a.body.addView(section);
        LinearLayout resume = a.vertical(), past = a.vertical();
        resume.setClipChildren(false);
        resume.setClipToPadding(false);
        past.setClipChildren(false);
        past.setClipToPadding(false);
        section.setClipToPadding(false);
        if (compactStack) {
            LinearLayout.LayoutParams top = new LinearLayout.LayoutParams(-1, -2);
            top.bottomMargin = a.dp(10);
            section.addView(resume, top);
            section.addView(past, new LinearLayout.LayoutParams(-1, -2));
        } else {
            LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, -2, 1);
            left.rightMargin = a.dp(20);
            section.addView(resume, left);
            section.addView(past, new LinearLayout.LayoutParams(0, -2, 3));
        }
        TextView title = a.text("继续播放", 17, MainActivity.WHITE);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER_VERTICAL);
        resume.addView(title, new LinearLayout.LayoutParams(-1, a.dp(30)));
        LinearLayout heading = a.row();
        heading.setClipChildren(false);
        heading.setClipToPadding(false);
        TextView historyTitle = a.text("历史记录", 17, MainActivity.WHITE);
        historyTitle.setTag("home-history-title");
        historyTitle.setTypeface(null, Typeface.BOLD);
        heading.addView(historyTitle, new LinearLayout.LayoutParams(0, -2, 1));
        allHistory =
                pill(
                        "全部历史  ›",
                        "home-history-all",
                        () -> a.open(new MainActivity.Screen(a::historyPage), true));
        heading.addView(allHistory, new LinearLayout.LayoutParams(-2, a.dp(30)));
        past.addView(heading);
        a.gap(resume, 6);
        a.gap(past, 6);
        LinearLayout recent = a.row();
        recent.setTag("home-history-list");
        recent.setClipChildren(false);
        recent.setClipToPadding(false);
        past.addView(recent);
        if (entries.size() == 1) {
            TextView empty = a.text("更多观看记录会显示在这里", 12, MainActivity.MUTED);
            empty.setGravity(Gravity.CENTER_VERTICAL);
            recent.addView(empty, new LinearLayout.LayoutParams(-1, a.dp(72)));
        }
        for (int i = 0; i < Math.min(4, entries.size()); i++) {
            LinearLayout row = i == 0 ? resume : recent;
            LinearLayout.LayoutParams cell =
                    i == 0
                            ? new LinearLayout.LayoutParams(-1, a.dp(72))
                            : new LinearLayout.LayoutParams(0, a.dp(72), 1);
            cell.rightMargin = a.dp(i == 0 || i == Math.min(3, entries.size() - 1) ? 0 : 12);
            WatchHistory.Entry e = entries.get(i);
            LinearLayout card = a.row();
            card.setPadding(a.dp(9), a.dp(6), a.dp(12), a.dp(6));
            card.setBackground(a.bg(0xff222228, 14));
            ImageView image = new AmbientPoster();
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setBackground(a.bg(0xff303038, 7));
            image.setClipToOutline(true);
            LinearLayout.LayoutParams art = new LinearLayout.LayoutParams(a.dp(40), a.dp(60));
            art.rightMargin = a.dp(12);
            card.addView(image, art);
            a.poster(e.poster, image);
            LinearLayout info = a.vertical();
            info.setGravity(Gravity.CENTER_VERTICAL);
            card.addView(info, new LinearLayout.LayoutParams(0, -1, 1));
            TextView name = a.text(e.title, 14, MainActivity.WHITE);
            single(name);
            info.addView(name);
            a.gap(info, 6);
            TextView detail = a.text(a.historyLabel(e), 11, MainActivity.MUTED);
            single(detail);
            info.addView(detail);
            a.gap(info, 9);
            FrameLayout track = new FrameLayout(a);
            track.setBackground(a.bg(0xff45454e, 2));
            track.setClipToOutline(true);
            info.addView(track, new LinearLayout.LayoutParams(-1, a.dp(3)));
            View progress = new View(a);
            progress.setBackgroundColor(0xffededf2);
            float fraction =
                    e.duration > 0 ? Math.max(0, Math.min(1, e.position / (float) e.duration)) : 0;
            track.addView(progress, new FrameLayout.LayoutParams(0, -1));
            track.addOnLayoutChangeListener(
                    (v, l, t, r, b, ol, ot, or, ob) -> {
                        android.view.ViewGroup.LayoutParams p = progress.getLayoutParams();
                        int w = Math.round((r - l) * fraction);
                        if (p.width != w) {
                            p.width = w;
                            progress.setLayoutParams(p);
                        }
                    });
            focusable(card, "home-history:" + i);
            outline(card, false, 14, 0xff222228);
            card.setContentDescription(e.title + "，" + a.historyLabel(e));
            card.setOnClickListener(v -> a.resumeHistory(e));
            card.setOnFocusChangeListener(
                    (v, f) -> {
                        outline(card, f, 14, 0xff222228);
                        if (f && ambient != null) ambient.follow(image);
                    });
            if (i == 0
                    && ambient != null
                    && (ambient.wanted == null || !ambient.wanted.isAttachedToWindow()))
                ambient.follow(image);
            row.addView(card, cell);
            historyCards.add(card);
        }
        a.gap(a.body, 4);
    }

    void renderSnapshot() {
        state.snapshot = snapshot;
        boolean first = !state.initialized;
        if (first) {
            state.initialized = true;
            state.category = "";
        }
        buildTabs();
        if (first && a.current != null && a.current.focus == null) {
            a.current.focus = String.valueOf(selectedTab().getTag());
            restoreFocus();
        }
        if (state.ready) {
            drawGrid(false);
            restoreFocus();
        } else choose(state.category, false);
    }

    void buildTabs() {
        filters.removeAllViews();
        filters.lines.clear();
        tabs.clear();
        tabIds.clear();
        addTab("推荐", "", 0);
        int visible = Math.min(4, snapshot.categories.size());
        for (int i = 0; i < visible; i++) {
            Cms.Category c = snapshot.categories.get(i);
            addTab(c.name, c.id, i + 1);
        }
        if (snapshot.categories.size() > visible) {
            TextView extra =
                    pill(
                            "更多分类  ⌄",
                            "home-category-more",
                            () -> {
                                String[] names = new String[snapshot.categories.size()];
                                int selected = -1;
                                for (int i = 0; i < names.length; i++) {
                                    Cms.Category c = snapshot.categories.get(i);
                                    names[i] = c.name;
                                    if (c.id.equals(state.category)) selected = i;
                                }
                                new AppDialog.Builder(a)
                                        .setTitle("选择分类")
                                        .wrapChoices()
                                        .setSingleChoiceItems(
                                                names,
                                                selected,
                                                (d, n) ->
                                                        choose(snapshot.categories.get(n).id, true))
                                        .setNegativeButton("关闭", null)
                                        .show();
                            });
            extra.setOnFocusChangeListener(
                    (v, f) -> outline(extra, f, 17, extra.isSelected() ? 0xffededf2 : 0xff24242b));
            addTabView(extra);
        }
        addTabView(pill("筛选", "home-filters", this::showFilters));
        updateTabs();
        wireNavigation();
    }

    List<VideoFilters.Definition> filterDefinitions() {
        String category = state.category;
        if (category.isEmpty() && !snapshot.categories.isEmpty())
            category = snapshot.categories.get(0).id;
        List<VideoFilters.Definition> definitions =
                VideoFilters.definitions(snapshot.filterSchema, category, snapshot.videos);
        if (site.supported())
            for (VideoFilters.Definition def : definitions) {
                if (!VideoFilters.allowedKey(def.key)) def.remote = false;
            }
        return definitions;
    }

    void showFilters() {
        if (snapshot == null) return;
        List<VideoFilters.Definition> definitions = filterDefinitions();
        String[] labels = new String[definitions.size() + 1];
        for (int i = 0; i < definitions.size(); i++) {
            VideoFilters.Definition def = definitions.get(i);
            labels[i] = def.label + "：" + def.labelFor(state.selectedFilters.get(def.dimension));
        }
        labels[definitions.size()] = "清除全部筛选";
        new AppDialog.Builder(a)
                .setTitle("影视筛选")
                .wrapChoices()
                .setItems(
                        labels,
                        (dialog, index) -> {
                            if (index == definitions.size()) {
                                if (!state.selectedFilters.isEmpty()) {
                                    state.selectedFilters.clear();
                                    applyFilters();
                                }
                                return;
                            }
                            VideoFilters.Definition def = definitions.get(index);
                            String[] options = new String[def.choices.size()];
                            int checked = 0;
                            for (int n = 0; n < options.length; n++) {
                                options[n] = def.choices.get(n).label;
                                if (def.choices
                                        .get(n)
                                        .value
                                        .equals(state.selectedFilters.get(def.dimension)))
                                    checked = n;
                            }
                            new AppDialog.Builder(a)
                                    .setTitle(def.label + (def.remote ? "" : " · 按影片资料筛选"))
                                    .wrapChoices()
                                    .setSingleChoiceItems(
                                            options,
                                            checked,
                                            (choiceDialog, n) -> {
                                                String value = def.choices.get(n).value;
                                                if (value.isEmpty())
                                                    state.selectedFilters.remove(def.dimension);
                                                else
                                                    state.selectedFilters.put(def.dimension, value);
                                                applyFilters();
                                            })
                                    .setNegativeButton("取消", null)
                                    .show();
                        })
                .setNegativeButton("关闭", null)
                .show();
    }

    void applyFilters() {
        String category = state.category;
        if (category.isEmpty()
                && !state.selectedFilters.isEmpty()
                && !snapshot.categories.isEmpty()) {
            category = snapshot.categories.get(0).id;
        }
        // A new filter starts at page one; detail/back keeps this exact state.
        state.category = category;
        state.ready = false;
        choose(category, true);
        View filter = filters.findViewWithTag("home-filters");
        if (filter != null) {
            if (a.current != null) a.current.focus = "home-filters";
            filter.requestFocus();
        }
    }

    void addTab(String label, String id, int index) {
        TextView button = pill(label, "home-tab:" + index, () -> choose(id, true));
        tabIds.put(button, id);
        button.setOnFocusChangeListener((v, f) -> styleTab(button));
        addTabView(button);
    }

    void addTabView(TextView button) {
        button.setSingleLine(false);
        button.setMaxLines(Integer.MAX_VALUE);
        button.setEllipsize(null);
        button.setMinHeight(a.dp(34));
        button.setPadding(a.dp(15), a.dp(8), a.dp(15), a.dp(8));
        filters.addView(button, new android.view.ViewGroup.LayoutParams(-2, -2));
        tabs.add(button);
    }

    void styleTab(TextView b) {
        boolean selected = state.category.equals(tabIds.get(b));
        b.setSelected(selected);
        FocusMotion.style(
                a,
                b,
                b.hasFocus(),
                selected ? 0xffededf2 : 0xff222228,
                selected ? 0xffededf2 : 0xff36363e,
                17,
                selected ? 0xff17171b : MainActivity.WHITE,
                selected ? 0xff17171b : MainActivity.WHITE,
                true,
                1.02f);
    }

    void updateTabs() {
        for (View v : tabs) {
            TextView b = (TextView) v;
            if (tabIds.get(b) != null) styleTab(b);
            else if ("home-filters".equals(b.getTag())) {
                b.setText(
                        state.selectedFilters.isEmpty()
                                ? "筛选"
                                : "筛选 · " + state.selectedFilters.size());
                b.setContentDescription("类型、国家地区、年份筛选");
            } else {
                boolean hidden = false;
                String name = "更多分类";
                for (int i = 4; i < snapshot.categories.size(); i++)
                    if (snapshot.categories.get(i).id.equals(state.category)) {
                        hidden = true;
                        name = snapshot.categories.get(i).name;
                        break;
                    }
                b.setText(name + "  ⌄");
                b.setSelected(hidden);
                FocusMotion.style(
                        a,
                        b,
                        b.hasFocus(),
                        hidden ? 0xffededf2 : 0xff24242b,
                        hidden ? 0xffededf2 : 0xff36363e,
                        17,
                        hidden ? 0xff17171b : MainActivity.WHITE,
                        hidden ? 0xff17171b : MainActivity.WHITE,
                        true,
                        1.02f);
            }
        }
    }

    View selectedTab() {
        for (View v : tabs) if (tabIds.containsKey(v) && v.isSelected()) return v;
        return tabs.isEmpty() ? source : tabs.get(0);
    }

    void choose(String category, boolean user) {
        if (state.ready && category.equals(state.category)) return;
        a.cancelPageRequests();
        request++;
        busy = false;
        if (!state.category.equals(category)) state.selectedFilters.clear();
        state.category = category;
        state.ready = false;
        state.videos.clear();
        state.windowStart = 0;
        state.page = 1;
        state.pages = 1;
        pendingFocus = null;
        cancelGrid();
        clearGrid();
        more.setVisibility(View.GONE);
        updateTabs();
        wireNavigation();
        if (user) {
            if (a.current != null) {
                a.current.focus = String.valueOf(selectedTab().getTag());
                a.current.scroll = 0;
            }
            selectedTab().requestFocus();
            filters.requestRectangleOnScreen(
                    new Rect(0, -a.dp(8), filters.getWidth(), filters.getHeight()), false);
        }
        if (category.isEmpty()) {
            state.videos.addAll(
                    VideoFilters.apply(
                            snapshot.videos, filterDefinitions(), state.selectedFilters));
            state.pages = snapshot.skip.isEmpty() ? 1 : snapshot.pages;
            state.ready = true;
            drawGrid(false);
            if (!user) restoreFocus();
        } else loadCategory(true);
    }

    void loadCategory(boolean first) {
        if (busy || snapshot == null) return;
        String category = state.category.isEmpty() ? snapshot.skip : state.category;
        if (category.isEmpty()) return;
        busy = true;
        int token = ++request;
        int page = first ? 1 : state.page + 1;
        List<VideoFilters.Definition> definitions = filterDefinitions();
        Map<String, String> selections = new LinkedHashMap<>(state.selectedFilters);
        Map<String, String> remoteFilters = VideoFilters.remoteValues(definitions, selections);
        status.setVisibility(first ? View.VISIBLE : View.GONE);
        status.setText("正在读取分类…");
        more.setText("正在加载…");
        more.setVisibility(first ? View.GONE : View.VISIBLE);
        a.submitPage(
                () -> {
                    try {
                        Cms.Listing list =
                                Cms.listing(
                                        a.engine(site).category(category, page, remoteFilters),
                                        site);
                        clean(list.videos);
                        int received = list.videos.size();
                        List<Cms.Video> matching =
                                VideoFilters.apply(list.videos, definitions, selections);
                        a.ui.post(
                                () -> {
                                    if (!active() || token != request) return;
                                    busy = false;
                                    int previous = state.videos.size();
                                    boolean focusMore = more.hasFocus();
                                    Set<String> ids = new HashSet<>();
                                    for (Cms.Video v : state.videos) ids.add(v.id);
                                    int added = 0;
                                    for (Cms.Video v : matching)
                                        if (ids.add(v.id)) {
                                            state.videos.add(v);
                                            added++;
                                        }
                                    int oldWindow = state.windowStart;
                                    int focusIndex = previous;
                                    if (added > 0 && previous >= windowEnd()) {
                                        state.windowStart =
                                                (previous / windowSize()) * windowSize();
                                    }
                                    boolean changedWindow = oldWindow != state.windowStart;
                                    state.page = page;
                                    state.pages =
                                            received == 0 ? page : Math.max(page, list.pageCount);
                                    state.ready = true;
                                    // Keep focus on the button until the first new card exists,
                                    // so focus never falls back to the category tabs at the top.
                                    pendingFocus =
                                            !first && focusMore ? "home-video:" + focusIndex : null;
                                    drawGrid(!first && !changedWindow);
                                    if (first
                                            && a.current != null
                                            && a.current.focus != null
                                            && a.current.focus.startsWith("home-video:"))
                                        restoreFocus();
                                });
                    } catch (Throwable e) {
                        AppLog.w("HomeScreen", "loadCategory failed for " + site.key, e);
                        a.ui.post(
                                () -> {
                                    if (!active() || token != request) return;
                                    busy = false;
                                    status.setVisibility(View.VISIBLE);
                                    status.setText(a.message(e));
                                    more.setText("重新加载");
                                    more.setVisibility(View.VISIBLE);
                                    wireNavigation();
                                });
                    }
                });
    }

    static void clean(List<Cms.Video> videos) {
        Set<String> ids = new HashSet<>();
        Iterator<Cms.Video> it = videos.iterator();
        while (it.hasNext()) {
            Cms.Video v = it.next();
            if (v == null || v.id == null || v.id.trim().isEmpty() || !ids.add(v.id)) {
                it.remove();
            }
        }
    }

    void cancelGrid() {
        gridEpoch++;
        if (pendingGrid != null) {
            a.ui.removeCallbacks(pendingGrid);
            content.removeCallbacks(pendingGrid);
        }
        pendingGrid = null;
    }

    void clearGrid() {
        for (int i = 0; i < content.getChildCount(); i++) {
            android.view.ViewGroup row = (android.view.ViewGroup) content.getChildAt(i);
            for (int j = 0; j < row.getChildCount(); j++)
                if (row.getChildAt(j) instanceof android.view.ViewGroup) {
                    android.view.ViewGroup card = (android.view.ViewGroup) row.getChildAt(j);
                    if (card.getChildCount() > 0) card.getChildAt(0).setTag(null);
                }
        }
        content.removeAllViews();
        rows.clear();
    }

    int windowSize() {
        return Math.max(COLUMNS, (MAX_RETAINED_CARDS / COLUMNS) * COLUMNS);
    }

    int windowEnd() {
        return Math.min(state.videos.size(), state.windowStart + windowSize());
    }

    void showWindow(int start) {
        if (busy || start < 0 || start >= state.videos.size()) return;
        state.windowStart = start;
        pendingFocus = "home-video:" + start;
        if (a.current != null) a.current.focus = pendingFocus;
        drawGrid(false);
        a.smoothHomeScroll(0);
    }

    void drawGrid(boolean append) {
        cancelGrid();
        int existing = state.windowStart;
        for (List<View> row : rows) existing += row.size();
        if (!append) {
            clearGrid();
            existing = state.windowStart;
        }
        earlier.setVisibility(state.windowStart > 0 ? View.VISIBLE : View.GONE);
        if (existing < windowEnd()) a.learnTitles(state.videos.subList(existing, windowEnd()));
        status.setText(
                state.selectedFilters.isEmpty()
                        ? "这个分类暂无内容，可切换其他分类或使用全局搜索。"
                        : "当前已读取的影片没有匹配结果，可继续加载或调整筛选。");
        boolean localFiltering = VideoFilters.hasLocal(filterDefinitions(), state.selectedFilters);
        if (localFiltering && !state.videos.isEmpty()) status.setText("按片源已提供的影片资料筛选，可继续加载更多结果。");
        status.setVisibility(state.videos.isEmpty() || localFiltering ? View.VISIBLE : View.GONE);
        // Hiding the focused button would hand focus to the first tab and jump to the top.
        if (pendingFocus == null || !more.hasFocus()) more.setVisibility(View.GONE);
        appendCards(existing, gridEpoch, !append);
    }

    /** Moves focus to the card awaited after "加载更多", once it is laid out. */
    void resolvePendingFocus(boolean finished) {
        if (pendingFocus == null) return;
        View target = a.root.findViewWithTag(pendingFocus);
        if (target == null && finished && !rows.isEmpty()) {
            List<View> last = rows.get(rows.size() - 1);
            target = last.isEmpty() ? null : last.get(0);
        }
        if (target == null) return;
        pendingFocus = null;
        final View card = target;
        if (card.isLaidOut()) card.requestFocus();
        else
            card.addOnLayoutChangeListener(
                    new View.OnLayoutChangeListener() {
                        public void onLayoutChange(
                                View v,
                                int l,
                                int t,
                                int r,
                                int b,
                                int ol,
                                int ot,
                                int or,
                                int ob) {
                            v.removeOnLayoutChangeListener(this);
                            if (active() && v.isAttachedToWindow()) v.requestFocus();
                            if (pendingGrid == null) updateMore();
                        }
                    });
    }

    /** Shows or hides "加载更多" without ever hiding it while it still holds focus. */
    void updateMore() {
        more.setText(windowEnd() < state.videos.size() ? "查看后面的影片" : "加载更多");
        boolean hasMore = windowEnd() < state.videos.size() || state.page < state.pages;
        if (hasMore || !more.hasFocus()) more.setVisibility(hasMore ? View.VISIBLE : View.GONE);
    }

    void appendCards(int start, int epoch, boolean restore) {
        pendingGrid = null;
        if (!active() || epoch != gridEpoch) return;
        int end = start;
        long batchStarted = System.nanoTime();
        for (int index = start; index < windowEnd() && index < start + 4; index++) {
            int column = index % COLUMNS;
            LinearLayout row;
            List<View> focusRow;
            if (column == 0) {
                row = a.row();
                row.setGravity(Gravity.TOP);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                row.setPadding(0, a.dp(5), 0, a.dp(18));
                content.addView(row);
                focusRow = new ArrayList<>();
                rows.add(focusRow);
                for (int c = 0; c < COLUMNS; c++) {
                    LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, -2, 1);
                    cell.rightMargin = a.dp(c == COLUMNS - 1 ? 0 : 14);
                    row.addView(new View(a), cell);
                }
            } else {
                row = (LinearLayout) content.getChildAt(content.getChildCount() - 1);
                focusRow = rows.get(rows.size() - 1);
            }
            android.view.ViewGroup.LayoutParams size = row.getChildAt(column).getLayoutParams();
            row.removeViewAt(column);
            View card = card(state.videos.get(index), index);
            row.addView(card, column, size);
            focusRow.add(card);
            end = index + 1;
            if (System.nanoTime() - batchStarted >= 3000000L) break;
        }
        wireNavigation(Math.max(0, (start - state.windowStart) / COLUMNS - 1));
        resolvePendingFocus(false);
        if (end < windowEnd()) {
            final int next = end;
            pendingGrid = () -> appendCards(next, epoch, restore);
            Runnable nextBatch = pendingGrid;
            content.postOnAnimation(
                    () -> {
                        if (active() && epoch == gridEpoch) a.ui.post(nextBatch);
                    });
        } else {
            resolvePendingFocus(true);
            updateMore();
            wireNavigation(Math.max(0, rows.size() - 1));
            if (restore
                    && a.current != null
                    && a.current.focus != null
                    && a.current.focus.startsWith("home-video:")) restoreFocus();
        }
    }

    View card(Cms.Video video, int index) {
        LinearLayout card = a.vertical();
        card.setPadding(a.dp(4), a.dp(4), a.dp(4), a.dp(4));
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setClipChildren(false);
        ImageView art = new AmbientPoster();
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setBackground(a.bg(0xff25252d, 10));
        art.setClipToOutline(true);
        card.addView(
                art, new LinearLayout.LayoutParams(a.dp(posterWidth), a.dp(posterWidth * 1.5f)));
        a.poster(video.poster, art);
        TextView title = a.text(video.title, 13, 0xffc8c8d0);
        single(title);
        title.setPadding(a.dp(2), a.dp(7), a.dp(2), 0);
        card.addView(title, new LinearLayout.LayoutParams(-1, a.dp(27)));
        focusable(card, "home-video:" + index);
        outline(card, false, 13, Color.TRANSPARENT);
        card.setContentDescription(video.title);
        card.setOnClickListener(v -> a.openListing(site, video));
        card.setOnFocusChangeListener(
                (v, f) -> {
                    outline(card, f, 13, Color.TRANSPARENT);
                    title.setTextColor(f ? Color.WHITE : 0xffc8c8d0);
                    if (f && ambient != null) ambient.follow(art);
                    if (f)
                        v.requestRectangleOnScreen(
                                new Rect(0, -a.dp(8), v.getWidth(), v.getHeight() + a.dp(10)),
                                false);
                });
        if (index == 0
                && ambient != null
                && (ambient.wanted == null || !ambient.wanted.isAttachedToWindow()))
            ambient.follow(art);
        return card;
    }

    void outline(View view, boolean focused, int radius, int color) {
        FocusMotion.style(
                a,
                view,
                focused,
                color,
                color,
                radius,
                MainActivity.WHITE,
                MainActivity.WHITE,
                true,
                view instanceof TextView ? 1.02f : 1.035f);
    }

    void single(TextView text) {
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
    }

    void focusable(View view, String tag) {
        view.setTag(tag);
        view.setId(View.generateViewId());
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        view.setClickable(true);
        a.pressFeedback(view);
    }

    TextView pill(String label, String tag, Runnable action) {
        TextView b = a.text(label, 12, MainActivity.WHITE);
        single(b);
        b.setGravity(Gravity.CENTER);
        b.setIncludeFontPadding(false);
        b.setPadding(a.dp(15), 0, a.dp(15), 0);
        focusable(b, tag);
        b.setBackground(a.bg(0xff24242b, 18));
        b.setOnClickListener(v -> action.run());
        b.setOnFocusChangeListener((v, f) -> outline(b, f, 18, 0xff24242b));
        return b;
    }

    void horizontal(List<View> line) {
        for (int i = 0; i < line.size(); i++) {
            line.get(i).setNextFocusLeftId(line.get(Math.max(0, i - 1)).getId());
            line.get(i).setNextFocusRightId(line.get(Math.min(line.size() - 1, i + 1)).getId());
        }
    }

    void wireNavigation() {
        wireNavigation(0);
    }

    void wireNavigation(int firstRow) {
        View nav = a.root.findViewWithTag("nav-home");
        if (nav != null && nav.getId() == View.NO_ID) nav.setId(View.generateViewId());
        horizontal(historyCards);
        for (View h : historyCards) {
            h.setNextFocusUpId(h.getId());
            h.setNextFocusDownId(source.getId());
        }
        if (!historyCards.isEmpty()) {
            historyCards.get(historyCards.size() - 1).setNextFocusRightId(allHistory.getId());
            allHistory.setNextFocusLeftId(historyCards.get(historyCards.size() - 1).getId());
            allHistory.setNextFocusDownId(source.getId());
        }
        source.setNextFocusUpId(
                historyCards.isEmpty() ? source.getId() : historyCards.get(0).getId());
        source.setNextFocusDownId(selectedTab().getId());
        for (int r = 0; r < filters.lines.size(); r++) {
            List<View> line = filters.lines.get(r);
            horizontal(line);
            for (View tab : line) {
                tab.setNextFocusUpId(
                        r == 0
                                ? source.getId()
                                : filters.nearest(filters.lines.get(r - 1), tab).getId());
                tab.setNextFocusDownId(
                        r + 1 < filters.lines.size()
                                ? filters.nearest(filters.lines.get(r + 1), tab).getId()
                                : earlier.isShown()
                                        ? earlier.getId()
                                        : rows.isEmpty()
                                                ? (more.isShown() ? more.getId() : tab.getId())
                                                : rows.get(0).get(0).getId());
            }
            if (a.homeDock != null && !line.isEmpty())
                a.homeDock.rightEdge(line.get(line.size() - 1));
        }
        for (int r = Math.max(0, firstRow); r < rows.size(); r++) {
            List<View> row = rows.get(r);
            horizontal(row);
            for (int c = 0; c < row.size(); c++) {
                View card = row.get(c);
                card.setNextFocusUpId(
                        r == 0
                                ? (earlier.isShown() ? earlier.getId() : selectedTab().getId())
                                : rows.get(r - 1)
                                        .get(Math.min(c, rows.get(r - 1).size() - 1))
                                        .getId());
                card.setNextFocusDownId(
                        r + 1 < rows.size()
                                ? rows.get(r + 1)
                                        .get(Math.min(c, rows.get(r + 1).size() - 1))
                                        .getId()
                                : more.isShown() ? more.getId() : card.getId());
            }
        }
        earlier.setNextFocusUpId(selectedTab().getId());
        earlier.setNextFocusDownId(rows.isEmpty() ? more.getId() : rows.get(0).get(0).getId());
        more.setNextFocusUpId(
                rows.isEmpty() ? selectedTab().getId() : rows.get(rows.size() - 1).get(0).getId());
        if (a.homeDock != null) {
            a.homeDock.rightEdge(source);
            a.homeDock.rightEdge(allHistory);
            a.homeDock.rightEdge(more);
            a.homeDock.rightEdge(earlier);
            if (!tabs.isEmpty()) a.homeDock.rightEdge(tabs.get(tabs.size() - 1));
            for (int r = Math.max(0, firstRow); r < rows.size(); r++) {
                List<View> row = rows.get(r);
                if (!row.isEmpty()) a.homeDock.rightEdge(row.get(row.size() - 1));
            }
        }
    }

    static boolean navigationKey(MainActivity a, android.view.KeyEvent event) {
        int key = event.getKeyCode();
        if (key != android.view.KeyEvent.KEYCODE_DPAD_UP
                && key != android.view.KeyEvent.KEYCODE_DPAD_DOWN
                && key != android.view.KeyEvent.KEYCODE_DPAD_LEFT
                && key != android.view.KeyEvent.KEYCODE_DPAD_RIGHT) return false;
        View focus = a.getCurrentFocus();
        if (focus == null || a.scroll == null) return false;
        android.view.ViewParent parent = focus.getParent();
        while (parent != null && parent != a.scroll) parent = parent.getParent();
        if (parent != a.scroll) return false;
        int id =
                key == android.view.KeyEvent.KEYCODE_DPAD_UP
                        ? focus.getNextFocusUpId()
                        : key == android.view.KeyEvent.KEYCODE_DPAD_DOWN
                                ? focus.getNextFocusDownId()
                                : key == android.view.KeyEvent.KEYCODE_DPAD_LEFT
                                        ? focus.getNextFocusLeftId()
                                        : focus.getNextFocusRightId();
        View target = id == View.NO_ID ? null : a.root.findViewById(id);
        if (target == null || !target.isShown() || !target.isFocusable()) return false;
        if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
            target.requestFocus();
            String tag = String.valueOf(target.getTag());
            if (key == android.view.KeyEvent.KEYCODE_DPAD_UP
                    && (tag.startsWith("home-history:")
                            || tag.equals("home-history-all")
                            || target == focus)) {
                a.smoothHomeScroll(0);
            } else if (!(a.scroll instanceof TvScrollView))
                target.requestRectangleOnScreen(
                        new Rect(0, -a.dp(12), target.getWidth(), target.getHeight() + a.dp(12)),
                        false);
        }
        return true;
    }

    void styleSource(boolean focused) {
        FocusMotion.style(
                a,
                source,
                focused,
                0x66344352,
                0xff344452,
                // Fully rounded capsule matching the 34dp pill height.
                17,
                0xffd7e2ed,
                Color.WHITE,
                true,
                1.02f);
    }

    final class AmbientPoster extends ImageView {
        AmbientPoster() {
            super(a);
        }

        @Override
        public void setImageBitmap(android.graphics.Bitmap bitmap) {
            super.setImageBitmap(bitmap);
            if (ambient != null) ambient.posterReady(this);
        }
    }

    static final class SourceChevron extends android.graphics.drawable.Drawable {
        final android.graphics.Paint paint = new android.graphics.Paint(3);
        final MainActivity a;

        SourceChevron(MainActivity a) {
            this.a = a;
            setBounds(0, 0, a.dp(10), a.dp(10));
        }

        public void draw(android.graphics.Canvas canvas) {
            android.graphics.Rect b = getBounds();
            paint.setColor(0xffd7e2ed);
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeWidth(a.dp(1.4f));
            paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            paint.setStrokeJoin(android.graphics.Paint.Join.ROUND);
            android.graphics.Path path = new android.graphics.Path();
            path.moveTo(b.left + b.width() * .15f, b.top + b.height() * .35f);
            path.lineTo(b.exactCenterX(), b.top + b.height() * .65f);
            path.lineTo(b.left + b.width() * .85f, b.top + b.height() * .35f);
            canvas.drawPath(path, paint);
        }

        public void setAlpha(int value) {
            paint.setAlpha(value);
        }

        public void setColorFilter(android.graphics.ColorFilter value) {
            paint.setColorFilter(value);
        }

        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    /** Late home data must not pull focus back once the user has moved it (dock, account panel). */
    void restoreFocus() {
        a.ui.post(
                () -> {
                    if (!active() || a.current == null) return;
                    View saved =
                            a.current.focus == null
                                    ? null
                                    : a.root.findViewWithTag(a.current.focus);
                    View now = a.getCurrentFocus();
                    if (saved != null && (now == null || now == a.autoFocus || now == saved)) {
                        saved.requestFocus();
                        a.autoFocus = saved;
                        a.scroll.scrollTo(0, a.current.scroll);
                    }
                });
    }
}
