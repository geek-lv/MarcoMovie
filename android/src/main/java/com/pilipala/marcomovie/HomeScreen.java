package com.pilipala.marcomovie;

import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.*;
import org.json.JSONObject;

/**
 * TV 首页：历史记录 + 豆瓣热播瀑布流。
 *
 * <p>上游自带的「发现」分类瀑布流（片源 home()/category() 片单）已整体移除：片源海报
 * 防盗链 403 屡屡加载不出，片单也不跟热播走。首页改为 {@link DoubanHot#attachGrid}
 * 的豆瓣纵向网格：顶部「剧集 / 电影 / 综艺」下拉切一级大类，下方一行细分榜单，
 * 再往下是海报网格 + 加载更多翻页；点封面拿片名去全部片源搜索，播放链路不变。
 * 首页不设线路切换按钮（搜索本就遍历所有片源，切当前片源只影响排序）。
 * 片源自身的片库仍可从遥控器 MENU 键、搜索、收藏进入。
 */
final class HomeScreen {
    final MainActivity a;
    final Cms.Site site;
    final int COLUMNS;
    final float navScale;
    final List<View> historyCards = new ArrayList<>();
    TextView allHistory;
    TextView majorPill;
    int ticket, posterWidth;
    HomeAmbient ambient;
    DoubanHot douban;
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
        final Map<String, Cms.Listing> categoryCache = new HashMap<>();

        State(String key) {
            site = key;
        }
    }

    HomeScreen(MainActivity activity, Cms.Site selected) {
        a = activity;
        site = selected;
        navScale = Prefs.clampNavScale(a.prefs.getInt(Prefs.NAV_SCALE_PERCENT, 100)) / 100f;
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
        int rightPadDp = Math.max(100, Math.round(50 * navScale) + 50);
        int bottomPadDp = Math.max(86, Math.round(50 * navScale) + 36);
        if (bottomDock) {
            a.body.setPadding(a.dp(18), a.dp(16), a.dp(18), a.dp(bottomPadDp));
        } else {
            a.body.setPadding(a.dp(34), a.dp(18), a.dp(rightPadDp), a.dp(36));
        }
        a.body.setClipChildren(false);
        a.body.setClipToPadding(false);
        a.scroll.setClipChildren(false);
        float width = a.windowWidth() / a.getResources().getDisplayMetrics().density;
        int marginDp = bottomDock ? 36 : (34 + rightPadDp);
        posterWidth = Math.max(48, (int) ((width - marginDp - (COLUMNS - 1) * 14) / COLUMNS) - 8);
        historySection();
        // 上游片源瀑布流已移除，首页主体整体换成豆瓣热播瀑布流（纵向网格 + 加载更多）。
        // 海报走豆瓣图床（带 Referer/UA 内联头，稳定可加载），点封面拿片名到全部片源搜索。
        // 与手机端一致：一级「剧集 / 电影 / 综艺」放顶部下拉，片单区只保留一层细分榜单。
        // 首页本来就不该有「切换线路」按钮 —— 搜索永远遍历所有片源。
        majorHeading();
        douban =
                DoubanHot.attachGrid(
                        a, a.body, COLUMNS, posterWidth, false, DoubanHot.loadMajor(a.prefs));
        if (majorPill != null) douban.setMajorSwitcher(majorPill);
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
        wireNavigation();
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
                    Prefs.UI_SCALE_PERCENT,
                    Prefs.NAV_SCALE_PERCENT
                }) values.put(key, prefs.get(key));
        return values;
    }

    /** Reattach the exact home tree after detail/playback, without refetching the Douban list. */
    boolean resume() {
        if (retainedRoot == null
                || retainedBody == null
                || retainedWidth != a.windowWidth()
                || retainedHeight != a.windowHeight()
                || retainedFontScale != a.getResources().getConfiguration().fontScale
                || retainedEngineEpoch != a.engineEpoch
                || !appearance().equals(retainedAppearance)
                || !site.key.equals(a.prefs.getString(Prefs.SELECTED_SITE, ""))) return false;
        a.preparePage(true);
        ticket = a.generation;
        a.root = retainedRoot;
        a.body = retainedBody;
        a.scroll = retainedScroll;
        a.homeDock = retainedDock;
        a.homeAmbient = ambient;
        a.setContentView(retainedRoot);
        resumeMissingPosters(retainedRoot);
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

    /** 片源/仓库配置被编辑后由 SourceController 调用：清掉旧快照，重新渲染首页。 */
    void reloadCurrentCategory() {
        if (!active() || site == null) return;
        a.homeData.remove(site.key);
        a.refresh();
    }

    /**
     * 豆瓣片单顶部行：一级大类做成一个「剧集 ▾」下拉，与手机端右上角入口同构。
     *
     * <p>历史上 TV 首页这里放的是「当前片源 ▾」线路切换按钮，但首页卡片点进去是
     * 「搜索所有片源」，切换当前片源只影响排序、不影响搜索范围，语义上是无效的，
     * 因此删除后不再复刻，改为真正有用的豆瓣大类切换。
     */
    void majorHeading() {
        final String[] labels = DoubanHot.majorLabels();
        LinearLayout heading = a.row();
        heading.setTag("douban-major-heading");
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setClipChildren(false);
        heading.setClipToPadding(false);
        heading.setPadding(a.dp(16), a.dp(10), a.dp(16), 0);
        TextView title = a.text("豆瓣片单", 16f, MainActivity.WHITE);
        title.setTypeface(null, Typeface.BOLD);
        heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        majorPill =
                glassMajorPill(
                        labels[DoubanHot.loadMajor(a.prefs)] + "  ▾",
                        "douban-major-switcher",
                        this::chooseMajor);
        heading.addView(
                majorPill,
                new LinearLayout.LayoutParams(-2, a.dp(Math.round(32 * navScale))));
        a.body.addView(heading, new LinearLayout.LayoutParams(-1, -2));
    }

    /**
     * 液态玻璃版的一级大类下拉：与手机端右上角胶囊共用 {@code a.glass(view, 999)}
     * 的 GlassworkSurface（背景折射 + 上缘白色高光 + 下缘暗边）。
     *
     * <p>不能复用 {@link #pill}：那套焦点反馈走 FocusMotion.style，会直接
     * {@code setBackground(new GradientDrawable(...))} 把玻璃材质盖回纯色。这里改用
     * foreground 描边 + 轻微缩放/抬升，既不碰 background，遥控器 DPad 上也一眼看得出焦点；
     * 用户开启「降低透明度」时玻璃退化为纯色，焦点环依然有效。
     */
    TextView glassMajorPill(String label, String tag, Runnable action) {
        TextView b = a.text(label, 13f * navScale, MainActivity.WHITE);
        single(b);
        b.setTypeface(null, Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        b.setIncludeFontPadding(false);
        int padH = a.dp(Math.round(16 * navScale));
        b.setPadding(padH, 0, padH, 0);
        focusable(b, tag);
        a.glass(b, 999);

        GradientDrawable ring = new GradientDrawable();
        ring.setColor(Color.TRANSPARENT);
        ring.setCornerRadius(a.dp(Math.round(16 * navScale)));
        ring.setStroke(Math.max(1, a.dp(2)), 0xffffffff);
        b.setOnFocusChangeListener(
                (v, focused) -> {
                    b.setForeground(focused ? ring : null);
                    float s = focused ? 1.04f : 1f;
                    b.animate().scaleX(s).scaleY(s).setDuration(140).start();
                    b.setElevation(focused ? a.dp(6) : 0);
                });
        b.setOnClickListener(v -> action.run());
        return b;
    }

    /** 遥控器友好的单选弹窗（AppDialog 的选项是可聚焦 Button，DPad 上下 + 确认即可）。 */
    void chooseMajor() {
        if (douban == null) return;
        final String[] labels = DoubanHot.majorLabels();
        new AppDialog.Builder(a)
                .setTitle("豆瓣片单 · 选择大类")
                .setMessage("选好大类后，在下方一行继续挑细分榜单。")
                .setSingleChoiceItems(
                        labels,
                        douban.currentMajor(),
                        (d, index) -> {
                            DoubanHot.saveMajor(a.prefs, index);
                            if (majorPill != null) majorPill.setText(labels[index] + "  ▾");
                            douban.selectMajor(index);
                            douban.wire();
                            if (majorPill != null)
                                majorPill.post(
                                        () -> {
                                            View firstSub =
                                                    a.root.findViewWithTag("douban-tab:0");
                                            if (firstSub != null)
                                                majorPill.setNextFocusDownId(firstSub.getId());
                                            majorPill.requestFocus();
                                        });
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    void historySection() {
        List<WatchHistory.Entry> entries = a.watchHistory();
        if (entries.isEmpty()) return;
        boolean compactStack = a.useBottomDock();
        LinearLayout section = a.vertical();
        section.setTag("home-history-section");
        section.setGravity(Gravity.TOP);
        section.setClipChildren(false);
        section.setClipToPadding(false);
        a.body.addView(section);
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
        heading.addView(
                allHistory, new LinearLayout.LayoutParams(-2, a.dp(Math.round(30 * navScale))));
        section.addView(heading, new LinearLayout.LayoutParams(-1, -2));
        a.gap(section, 6);
        LinearLayout recent = compactStack ? a.vertical() : a.row();
        if (!compactStack) recent.setWeightSum(4);
        recent.setTag("home-history-list");
        recent.setClipChildren(false);
        recent.setClipToPadding(false);
        section.addView(recent, new LinearLayout.LayoutParams(-1, -2));
        int count = Math.min(4, entries.size());
        for (int i = 0; i < count; i++) {
            LinearLayout.LayoutParams cell =
                    compactStack
                            ? new LinearLayout.LayoutParams(-1, a.dp(72))
                            : new LinearLayout.LayoutParams(0, a.dp(72), 1);
            boolean last = i == count - 1;
            cell.rightMargin = a.dp(compactStack ? 0 : (last ? (4 - count) * 12 : 12));
            cell.bottomMargin = a.dp(compactStack && !last ? 10 : 0);
            final WatchHistory.Entry e = entries.get(i);
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
                        if (f && !a.reduced && a.config != null) {
                            v.postDelayed(
                                    () -> {
                                        if (v.hasFocus() && active() && a.config != null) {
                                            for (Cms.Site s : a.config.sites) {
                                                if (s.key.equals(e.site)) {
                                                    Cms.Video listing = new Cms.Video();
                                                    listing.id = e.id;
                                                    listing.title = e.title;
                                                    listing.poster = e.poster;
                                                    SourceController.prefetchDetail(a, s, listing);
                                                    break;
                                                }
                                            }
                                        }
                                    },
                                    150);
                        }
                    });
            if (i == 0
                    && ambient != null
                    && (ambient.wanted == null || !ambient.wanted.isAttachedToWindow()))
                ambient.follow(image);
            recent.addView(card, cell);
            historyCards.add(card);
        }
        a.gap(a.body, 4);
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
        TextView b = a.text(label, 12f * navScale, MainActivity.WHITE);
        single(b);
        b.setGravity(Gravity.CENTER);
        b.setIncludeFontPadding(false);
        int padH = a.dp(Math.round(15 * navScale));
        int radius = Math.round(18 * navScale);
        b.setPadding(padH, 0, padH, 0);
        focusable(b, tag);
        b.setBackground(a.bg(0xff24242b, radius));
        b.setOnClickListener(v -> action.run());
        b.setOnFocusChangeListener((v, f) -> outline(b, f, radius, 0xff24242b));
        return b;
    }

    void horizontal(List<View> line) {
        for (int i = 0; i < line.size(); i++) {
            line.get(i).setNextFocusLeftId(line.get(Math.max(0, i - 1)).getId());
            line.get(i).setNextFocusRightId(line.get(Math.min(line.size() - 1, i + 1)).getId());
        }
    }

    /** TV 首页只剩历史区与豆瓣瀑布流：历史区内部显式连线，豆瓣区由 DoubanHot 自己接线。 */
    void wireNavigation() {
        View nav = a.root.findViewWithTag("nav-home");
        if (nav != null && nav.getId() == View.NO_ID) nav.setId(View.generateViewId());
        boolean stackedHistory = a.useBottomDock();
        horizontal(historyCards);
        for (int i = 0; i < historyCards.size(); i++) {
            View card = historyCards.get(i);
            card.setNextFocusUpId(
                    stackedHistory
                            ? (i == 0 ? allHistory : historyCards.get(i - 1)).getId()
                            : card.getId());
            card.setNextFocusDownId(
                    stackedHistory && i + 1 < historyCards.size()
                            ? historyCards.get(i + 1).getId()
                            : View.NO_ID);
            if (stackedHistory) {
                card.setNextFocusLeftId(card.getId());
                card.setNextFocusRightId(card.getId());
            }
        }
        if (!historyCards.isEmpty()) {
            View lastHistory = historyCards.get(historyCards.size() - 1);
            if (!stackedHistory) lastHistory.setNextFocusRightId(allHistory.getId());
            allHistory.setNextFocusLeftId(
                    stackedHistory ? allHistory.getId() : lastHistory.getId());
            allHistory.setNextFocusDownId(
                    stackedHistory ? historyCards.get(0).getId() : View.NO_ID);
        }
        if (douban != null) douban.wire();
        // 下拉 pill 的「下」指到细分条首项；细分条的「上」由 DoubanHot.wire() 指回 pill
        if (majorPill != null) {
            View firstSub = a.root.findViewWithTag("douban-tab:0");
            if (firstSub != null) majorPill.setNextFocusDownId(firstSub.getId());
        }
        if (a.homeDock != null) {
            if (allHistory != null) a.homeDock.rightEdge(allHistory);
            if (douban != null) douban.dockRight(a.homeDock);
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
}
