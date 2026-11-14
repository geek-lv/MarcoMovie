package com.pilipala.marcomovie;

import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 豆瓣榜单片单。
 *
 * <ul>
 *   <li>{@link #attach}：手机首页（MobileShell）的横向货架，电视剧/电影两个 chip，取前 20 条横向浏览；
 *   <li>{@link #attachGrid}：TV 首页（HomeScreen）的纵向瀑布流，两级分类：一级大类
 *       剧集 / 电影 / 综艺，二级细分榜单（热门、美剧、日剧、经典、豆瓣高分……），
 *       带「加载更多」按 20 条/页翻页，替代上游自带的分类片单瀑布流。
 * </ul>
 *
 * <p>点某部片直接拿片名去当前片源搜索，不改变原有播放链路。
 *
 * <p>数据源是豆瓣的选片接口 {@code /j/search_subjects}（type + tag），需要带浏览器 UA 与
 * Referer，否则会被判成爬虫。图床（doubanio）有防盗链，所以封面地址追加
 * {@code @Referer=https://movie.douban.com/}，交给 {@link ImageLoader} 的内联头机制处理。
 * 收录的 type/tag 组合都经过实测（返回 20 条）；类型词（喜剧/爱情/科幻等）数据不稳未收。
 */
final class DoubanHot {

    /** 一条榜单条目。豆瓣只给标题/评分/封面，没有剧集结构，所以不做成本地 Cms.Video。 */
    static final class Item {
        String id, title, poster, rate, episodes;
    }

    /** 一个豆瓣细分榜单（type=tv/movie + tag）。 */
    private static final class Category {
        final String label, type, tag;

        Category(String label, String type, String tag) {
            this.label = label;
            this.type = type;
            this.tag = tag;
        }

        String key() {
            return type + "/" + tag;
        }
    }

    /** 一级大类：label + 该类下的细分榜单。 */
    private static final class Major {
        final String label;
        final Category[] subs;

        Major(String label, Category[] subs) {
            this.label = label;
            this.subs = subs;
        }
    }

    /** 手机货架：只保留两个热门榜。 */
    private static final Category[] SHELF_CATEGORIES = {
        new Category("电视剧", "tv", "热门"), new Category("电影", "movie", "热门"),
    };

    /** TV 瀑布流一级大类：剧集 / 电影 / 综艺（细分榜单在各自 subs 里，组合均实测可用）。 */
    private static final Major[] GRID_MAJORS = {
        new Major(
                "剧集",
                new Category[] {
                    new Category("国产剧", "tv", "国产剧"),
                    new Category("热门剧集", "tv", "热门"),
                    new Category("美剧", "tv", "美剧"),
                    new Category("日剧", "tv", "日剧"),
                    new Category("韩剧", "tv", "韩剧"),
                    new Category("英剧", "tv", "英剧"),
                    new Category("纪录剧集", "tv", "纪录片"),
                }),
        new Major(
                "电影",
                new Category[] {
                    new Category("豆瓣高分", "movie", "豆瓣高分"),
                    new Category("热门电影", "movie", "热门"),
                    new Category("最新电影", "movie", "最新"),
                    new Category("经典电影", "movie", "经典"),
                    new Category("冷门佳片", "movie", "冷门佳片"),
                    new Category("华语电影", "movie", "华语"),
                    new Category("欧美电影", "movie", "欧美"),
                    new Category("日本电影", "movie", "日本"),
                    new Category("韩国电影", "movie", "韩国"),
                    new Category("动画电影", "movie", "动画"),
                    new Category("纪录电影", "movie", "纪录片"),
                    new Category("剧情电影", "movie", "剧情"),
                }),
        new Major(
                "综艺",
                new Category[] {
                    new Category("热门综艺", "tv", "综艺"),
                }),
    };

    private static final String ENDPOINT = "https://movie.douban.com/j/search_subjects";
    private static final String REFERER = "https://movie.douban.com/";
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                    + " (KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    /** 豆瓣接口单页上限 20 条，瀑布流翻页步长与此一致 */
    private static final int PAGE = 20;

    /** 每个榜单首批 20 条按自然日缓存；当天首次启动按顺序预取全部榜单。 */
    private static final String PRELOAD_DAY_PREF = "douban.preload.day";

    private static final String CACHE_PREF_PREFIX = "douban.daily.";

    private static final Map<String, Cached> CACHE = new LinkedHashMap<>();
    private static final Map<String, List<FirstPageCallback>> FIRST_PAGE_WAITERS =
            new LinkedHashMap<>();
    private static boolean preloading;
    private static String preloadDay = "";

    private interface FirstPageCallback {
        void accept(List<Item> items, String error);
    }

    private static final class Cached {
        String day;
        List<Item> items;
    }

    private final MainActivity a;

    /** 手机货架的 chip 清单与当前选中（网格模式下 categories 为 null） */
    private final Category[] categories;

    private final List<TextView> chips = new ArrayList<>();
    private int tab;

    /** TV 网格模式的大类/细分状态（货架模式下 majors 为 null） */
    private final Major[] majors;

    private final List<TextView> majorChips = new ArrayList<>();
    private final List<TextView> subChips = new ArrayList<>();
    /** TV 首页把大类做成下拉时，由 HomeScreen 注入那个 pill，DPad 上键回到它。 */
    private View majorSwitcher;
    private LinearLayout subRow;
    private int major, sub;

    /** 每个大类记住上次选中的细分榜单，切回来不用重新找 */
    private final int[] lastSub;

    /** 手机货架模式的行容器与提示行（网格模式下为 null） */
    private final LinearLayout rowBox;

    private final TextView hint;

    /** TV 网格模式的容器 / 状态行 / 「加载更多」（货架模式下 gridBox 为 null） */
    private final LinearLayout gridBox;

    private final TextView gridHint;
    private final int columns, posterWidth;
    private TextView gridMore;

    /** 切分类时作废旧请求，防止返回顺序错乱把旧榜单画上去 */
    private int token;

    // ---- TV 瀑布流的分页状态（按分类 key 分开记） ----
    /** key -> 已加载并画出的条目 */
    private final Map<String, List<Item>> loaded = new LinkedHashMap<>();

    /** key -> 下一页的 page_start */
    private final Map<String, Integer> nextStart = new LinkedHashMap<>();

    /** key -> 豆瓣没有更多了 */
    private final Map<String, Boolean> exhausted = new LinkedHashMap<>();

    /** 网格行 -> 该行的卡片（供 DPad 焦点连线用） */
    private final List<List<View>> gridRows = new ArrayList<>();

    private int drawn;
    private boolean gridFailed, moreBusy;

    /** 当前网格对应的榜单及已画内容签名；相同内容不再清空重建。 */
    private String gridKey = "";

    private final List<String> drawnSignatures = new ArrayList<>();

    /** item id -> 已显示卡片；刷新时复用原 View，避免海报闪空和重复请求。 */
    private final Map<String, View> gridCards = new LinkedHashMap<>();

    private DoubanHot(
            MainActivity activity,
            Category[] categories,
            Major[] majors,
            LinearLayout rowBox,
            TextView hint,
            LinearLayout gridBox,
            TextView gridHint,
            int columns,
            int posterWidth) {
        this.a = activity;
        this.categories = categories;
        this.majors = majors;
        this.rowBox = rowBox;
        this.hint = hint;
        this.gridBox = gridBox;
        this.gridHint = gridHint;
        this.columns = columns;
        this.posterWidth = posterWidth;
        this.lastSub = majors == null ? null : new int[majors.length];
    }

    private boolean grid() {
        return gridBox != null;
    }

    private static String today() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(new java.util.Date());
    }

    /** 当天只启动一次全榜单预取；当前大类排第一，首屏不被其余榜单阻塞。 */
    private void preloadDaily() {
        final String day = today();
        synchronized (DoubanHot.class) {
            if (preloading && day.equals(preloadDay)) return;
            if (day.equals(a.prefs.getString(PRELOAD_DAY_PREF, ""))) return;
            preloading = true;
            preloadDay = day;
        }
        List<Category> ordered = new ArrayList<>();
        Category first = majors[major].subs[lastSub[major]];
        ordered.add(first);
        for (Major group : majors)
            for (Category cat : group.subs) if (!cat.key().equals(first.key())) ordered.add(cat);
        preloadNext(ordered, 0, day);
    }

    private void preloadNext(List<Category> ordered, int index, String day) {
        if (!day.equals(today())) {
            synchronized (DoubanHot.class) {
                preloading = false;
            }
            return;
        }
        int next = index;
        while (next < ordered.size() && cacheGet(ordered.get(next).key()) != null) next++;
        if (next >= ordered.size()) {
            a.prefs.edit().putString(PRELOAD_DAY_PREF, day).apply();
            synchronized (DoubanHot.class) {
                preloading = false;
            }
            return;
        }
        final int current = next;
        fetchFirstPage(
                ordered.get(current),
                false,
                (items, error) -> preloadNext(ordered, current + 1, day));
    }

    private Category category() {
        return grid() ? majors[major].subs[sub] : categories[tab];
    }

    // ---------------------------------------------------------------- 手机货架

    /**
     * 在给定容器尾部追加「豆瓣热播」横向货架（手机首页专用）。
     *
     * @return 该货架的根视图，便于调用方控制可见性
     */
    static View attach(MainActivity a, LinearLayout parent) {
        final LinearLayout section = a.vertical();

        LinearLayout header = a.row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(a.dp(16), a.dp(16), a.dp(16), a.dp(4));

        TextView title = a.text("豆瓣热播", 18, MainActivity.WHITE);
        title.setTypeface(null, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));

        HorizontalScrollView scroll = new HorizontalScrollView(a);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(a.dp(16), a.dp(2), a.dp(16), a.dp(2));
        final LinearLayout row = a.row();
        scroll.addView(row);

        final TextView hint = a.text("正在获取豆瓣榜单…", 11.5f, MainActivity.MUTED);
        hint.setPadding(a.dp(16), a.dp(6), a.dp(16), a.dp(2));

        final DoubanHot shelf =
                new DoubanHot(a, SHELF_CATEGORIES, null, row, hint, null, null, 0, 0);

        LinearLayout tabs = a.row();
        tabs.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < shelf.categories.length; i++) {
            TextView chip = shelf.makeChip(i, 12.5f, 12, 5);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            if (i > 0) lp.leftMargin = a.dp(6);
            tabs.addView(chip, lp);
        }
        header.addView(tabs);

        section.addView(header, new LinearLayout.LayoutParams(-1, -2));
        section.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        section.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        parent.addView(section, new LinearLayout.LayoutParams(-1, -2));

        shelf.select(0);
        return section;
    }

    // ---------------------------------------------------------------- TV 瀑布流

    /**
     * TV 首页瀑布流：豆瓣纵向网格，替代上游片源自带的分类瀑布流。
     *
     * <p>分类分两级：一级大类（剧集 / 电影 / 综艺）和该大类下的细分榜单
     * （热门、美剧、日剧、经典、豆瓣高分……超出屏宽横向滚动）。两端都把一级做成
     * 顶栏下拉（{@code showMajorRow=false}），片单区只显示一行细分 chips；
     * {@code showMajorRow=true} 是保留的旧双层 chips 形态。底部「加载更多」
     * 按 20 条/页继续向豆瓣翻页；已加载的榜单留在内存里，切回来立即重画，不打豆瓣。
     * 每个大类还会记住上次选中的细分。
     *
     * @param columns       网格列数（沿用首页 COLUMNS）
     * @param posterWidthDp 单张海报宽度(dp)，沿用 HomeScreen 的 posterWidth
     * @return 瀑布流控制器，供 HomeScreen 做 DPad 焦点连线（{@link #wire}/{@link #dockRight}）
     */
    static DoubanHot attachGrid(
            MainActivity a, LinearLayout parent, int columns, int posterWidthDp) {
        return attachGrid(a, parent, columns, posterWidthDp, true, 0);
    }

    /**
     * @param showMajorRow true 才在片单区画一级大类 chips 行；TV 与手机现在都传 false
     * @param initialMajor 恢复上次选择的大类，避免页面重建后总跳回剧集
     */
    static DoubanHot attachGrid(
            MainActivity a,
            LinearLayout parent,
            int columns,
            int posterWidthDp,
            boolean showMajorRow,
            int initialMajor) {
        LinearLayout section = a.vertical();

        // 首页不显示「豆瓣片单」标题：顶栏已有「剧集 ▾」大类入口，标题冗余；
        // 直接以分类 chips 开头，视觉更干净（行内自带顶部留白）。

        LinearLayout box = a.vertical();
        box.setClipChildren(false);
        box.setClipToPadding(false);

        TextView hint = a.text("正在获取豆瓣榜单…", 11.5f, MainActivity.MUTED);
        hint.setPadding(a.dp(16), a.dp(6), a.dp(16), a.dp(2));

        final DoubanHot waterfall =
                new DoubanHot(a, null, GRID_MAJORS, null, null, box, hint, columns, posterWidthDp);
        MainActivity.flicker(
                "GRID_ATTACH grid="
                        + System.identityHashCode(waterfall)
                        + " box="
                        + System.identityHashCode(box)
                        + " major="
                        + initialMajor);
        box.addOnAttachStateChangeListener(
                new View.OnAttachStateChangeListener() {
                    @Override
                    public void onViewAttachedToWindow(View v) {
                        MainActivity.flicker("GRID_BOX_ATTACH box=" + System.identityHashCode(v));
                    }

                    @Override
                    public void onViewDetachedFromWindow(View v) {
                        MainActivity.flicker("GRID_BOX_DETACH box=" + System.identityHashCode(v));
                    }
                });

        // 老形态：片单区内再画一行一级大类 chips。现在两端都把一级放到顶部下拉，
        // 这里只为兼容保留，正常路径 showMajorRow=false 不创建。
        HorizontalScrollView majorScroll = null;
        if (showMajorRow) {
            majorScroll = new HorizontalScrollView(a);
            majorScroll.setHorizontalScrollBarEnabled(false);
            majorScroll.setClipToPadding(false);
            majorScroll.setClipChildren(false);
            majorScroll.setPadding(a.dp(16), a.dp(14), a.dp(16), a.dp(2));
            LinearLayout majorRow = a.row();
            majorScroll.addView(majorRow);
            for (int i = 0; i < waterfall.majors.length; i++) {
                TextView chip = waterfall.makeMajorChip(i);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                if (i > 0) lp.leftMargin = a.dp(12);
                majorRow.addView(chip, lp);
            }
        }

        // 第二级：当前大类的细分榜单条（切大类时整体重建）
        HorizontalScrollView subScroll = new HorizontalScrollView(a);
        subScroll.setHorizontalScrollBarEnabled(false);
        subScroll.setClipToPadding(false);
        subScroll.setClipChildren(false);
        subScroll.setPadding(a.dp(16), a.dp(14), a.dp(16), a.dp(10));
        LinearLayout subRowBox = a.row();
        subScroll.addView(subRowBox);
        waterfall.subRow = subRowBox;

        TextView more = a.text("加载更多", 12.5f, MainActivity.WHITE);
        more.setGravity(Gravity.CENTER);
        more.setTag("douban-more");
        int mPad = a.dp(24);
        more.setPadding(mPad, a.dp(9), mPad, a.dp(9));
        waterfall.focusableTouch(more);
        more.setOnClickListener(v -> waterfall.loadMore());
        more.setOnFocusChangeListener(
                (v, f) ->
                        FocusMotion.style(
                                a,
                                more,
                                f,
                                0xff24242b,
                                0xff24242b,
                                18,
                                MainActivity.WHITE,
                                MainActivity.WHITE,
                                true,
                                1.03f));
        FocusMotion.style(
                a,
                more,
                false,
                0xff24242b,
                0xff24242b,
                18,
                MainActivity.WHITE,
                MainActivity.WHITE,
                true,
                1.03f);
        more.setVisibility(View.GONE);
        waterfall.gridMore = more;

        LinearLayout moreWrap = a.row();
        moreWrap.setGravity(Gravity.CENTER);
        moreWrap.setPadding(0, a.dp(12), 0, a.dp(8));
        moreWrap.addView(more, new LinearLayout.LayoutParams(-2, -2));

        if (majorScroll != null)
            section.addView(majorScroll, new LinearLayout.LayoutParams(-1, -2));
        section.addView(subScroll, new LinearLayout.LayoutParams(-1, -2));
        section.addView(box, new LinearLayout.LayoutParams(-1, -2));
        section.addView(moreWrap, new LinearLayout.LayoutParams(-1, -2));
        section.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        parent.addView(section, new LinearLayout.LayoutParams(-1, -2));

        waterfall.major = Math.max(0, Math.min(initialMajor, GRID_MAJORS.length - 1));
        waterfall.preloadDaily();
        waterfall.selectMajor(waterfall.major);
        return waterfall;
    }

    // ---------------------------------------------------------------- 分类与加载

    /** 手机货架的 chip。 */
    private TextView makeChip(final int index, float sizeSp, int padH, int padV) {
        TextView chip = a.text(categories[index].label, sizeSp, MainActivity.MUTED);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(a.dp(padH), a.dp(padV), a.dp(padH), a.dp(padV));
        chip.setTag("douban-tab:" + index);
        focusableTouch(chip);
        chip.setOnClickListener(v -> select(index));
        chip.setOnFocusChangeListener((v, f) -> styleChip(chip, index == tab, false));
        chips.add(chip);
        return chip;
    }

    /** TV 瀑布流的一级大类 chip。 */
    private TextView makeMajorChip(final int index) {
        TextView chip = a.text(majors[index].label, 16f, MainActivity.MUTED);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(a.dp(16), a.dp(7), a.dp(16), a.dp(7));
        chip.setTag("douban-major:" + index);
        focusableTouch(chip);
        chip.setOnClickListener(v -> selectMajor(index));
        chip.setOnFocusChangeListener(
                (v, f) -> {
                    styleChip(chip, index == major, true);
                    if (f) scrollChipIntoView(chip);
                });
        majorChips.add(chip);
        return chip;
    }

    /** TV 瀑布流的二级细分 chip。 */
    private TextView makeSubChip(final int index) {
        TextView chip = a.text(majors[major].subs[index].label, 13f, MainActivity.MUTED);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(a.dp(14), a.dp(6), a.dp(14), a.dp(6));
        chip.setTag("douban-tab:" + index);
        focusableTouch(chip);
        chip.setOnClickListener(v -> selectSub(index));
        chip.setOnFocusChangeListener(
                (v, f) -> {
                    styleChip(chip, index == sub, false);
                    if (f) scrollChipIntoView(chip);
                });
        subChips.add(chip);
        return chip;
    }

    /** 分类条很长：聚焦的 chip 要滚入可见区（横竖两个方向都请求）。 */
    private void scrollChipIntoView(TextView chip) {
        chip.requestRectangleOnScreen(
                new Rect(-a.dp(24), 0, chip.getWidth() + a.dp(24), chip.getHeight()), false);
    }

    static String[] majorLabels() {
        String[] labels = new String[GRID_MAJORS.length];
        for (int i = 0; i < GRID_MAJORS.length; i++) labels[i] = GRID_MAJORS[i].label;
        return labels;
    }

    static int clampMajor(int major) {
        return Math.max(0, Math.min(major, GRID_MAJORS.length - 1));
    }

    /**
     * TV / 手机共用的「上次选中的豆瓣一级大类」。写进 SharedPreferences，
     * 因此杀进程、重启 App、冷启动首页都会恢复到用户最后选的那一项。
     *
     * <p>刻意不注册进 {@link Prefs#KEYS}：那份白名单用于「恢复默认设置」和
     * 配置扫码同步，浏览状态既不该被设置重置清掉，也不该手机同步时覆盖电视自己的选择。
     */
    static int loadMajor(android.content.SharedPreferences prefs) {
        return clampMajor(prefs.getInt(Prefs.DOUBAN_MAJOR, 0));
    }

    static void saveMajor(android.content.SharedPreferences prefs, int major) {
        prefs.edit().putInt(Prefs.DOUBAN_MAJOR, clampMajor(major)).apply();
    }

    /** TV 首页不渲染大类 chips 行时，把顶部的「大类 ▾」下拉挂进来供 DPad 连线。 */
    void setMajorSwitcher(View v) {
        majorSwitcher = v;
    }

    int currentMajor() {
        return major;
    }

    /** 切大类：重建二级细分条，并回到该大类上次选中的细分。 */
    void selectMajor(int index) {
        if (majors == null || index < 0 || index >= majors.length) return;
        // 顶栏重复点当前大类不应清空网格、重新请求。
        if (major == index && subRow != null && subRow.getChildCount() > 0) return;
        MainActivity.flicker(
                "GRID_MAJOR from="
                        + major
                        + " to="
                        + index
                        + " grid="
                        + System.identityHashCode(this));
        major = index;
        token++;
        styleMajors();
        rebuildSubs();
        selectSub(lastSub[index]);
    }

    /** 切二级细分榜单。 */
    private void selectSub(int index) {
        String nextKey = majors[major].subs[index].key();
        if (sub == index && nextKey.equals(gridKey) && (!gridRows.isEmpty() || moreBusy)) return;
        sub = index;
        lastSub[major] = index;
        token++;
        MainActivity.flicker(
                "GRID_SUB key="
                        + nextKey
                        + " token="
                        + token
                        + " grid="
                        + System.identityHashCode(this));
        styleSubs();
        startGridTab();
    }

    private void rebuildSubs() {
        subRow.removeAllViews();
        subChips.clear();
        Category[] subs = majors[major].subs;
        for (int i = 0; i < subs.length; i++) {
            TextView chip = makeSubChip(i);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            if (i > 0) lp.leftMargin = a.dp(10);
            subRow.addView(chip, lp);
        }
        subRow.scrollTo(0, 0);
    }

    private void styleMajors() {
        for (int i = 0; i < majorChips.size(); i++) styleChip(majorChips.get(i), i == major, true);
    }

    private void styleSubs() {
        for (int i = 0; i < subChips.size(); i++) styleChip(subChips.get(i), i == sub, false);
    }

    private void select(int index) {
        if (grid()) {
            selectSub(index);
            return;
        }
        select(index, 0);
    }

    /** 货架模式：加载指定榜单；失败时自动重试，最多额外重试 2 次（共最多 3 次尝试），全部失败才提示手动重试。 */
    private void select(int index, int attempt) {
        tab = index;
        token++;
        for (int i = 0; i < chips.size(); i++) styleChip(chips.get(i), i == tab, false);
        Category cat = categories[index];
        List<Item> cached = cacheGet(cat.key());
        if (cached != null) {
            render(cached);
            return;
        }
        rowBox.removeAllViews();
        hint.setText(
                (attempt == 0 ? "正在获取" : "正在重试获取")
                        + cat.label
                        + (cat.tag.equals("热门") ? "热榜…" : "榜单…"));
        hint.setOnClickListener(null);
        final int mine = token;
        a.submitPage(
                () -> {
                    List<Item> items = null;
                    String error = null;
                    try {
                        items = fetch(cat.type, cat.tag, 0);
                    } catch (Throwable t) {
                        error = a.message(t);
                        AppLog.w("DoubanHot", "fetch failed: " + cat.key(), t);
                    }
                    final List<Item> result = items;
                    final String failure = error;
                    final Category c = cat;
                    final int attemptLeft = attempt;
                    a.ui.post(
                            () -> {
                                if (mine != token) return;
                                if (result == null || result.isEmpty()) {
                                    if (attemptLeft < 2) {
                                        // 首次失败多为瞬时网络抖动，自动重试（最多 2 次），不弹错误提示
                                        hint.setText("正在重试获取" + c.label + "热榜…");
                                        hint.setOnClickListener(null);
                                        final int retryIndex = index;
                                        a.ui.postDelayed(
                                                () -> select(retryIndex, attemptLeft + 1), 800);
                                    } else {
                                        rowBox.removeAllViews();
                                        hint.setText(
                                                "豆瓣热榜暂时取不到"
                                                        + (failure == null
                                                                ? ""
                                                                : "（" + failure + "）")
                                                        + " · 点此重试");
                                        hint.setOnClickListener(v -> select(tab));
                                    }
                                    return;
                                }
                                cachePut(c.key(), result);
                                render(result);
                            });
                });
    }

    /** 网格模式：切细分榜单。已加载的直接重画，否则先试 10 分钟缓存，再打豆瓣第 0 页。 */
    private void startGridTab() {
        gridFailed = false;
        moreBusy = false;
        Category cat = category();
        String key = cat.key();
        List<Item> items = loaded.get(key);
        if (items == null || items.isEmpty()) {
            List<Item> cached = cacheGet(key);
            if (cached != null) {
                items = new ArrayList<>(cached);
                loaded.put(key, items);
                nextStart.put(key, cached.size());
                exhausted.put(key, cached.size() < PAGE);
            }
        }
        if (items != null && !items.isEmpty()) {
            MainActivity.flicker(
                    "GRID_CACHE_DRAW key="
                            + key
                            + " count="
                            + items.size()
                            + " box="
                            + System.identityHashCode(gridBox));
            drawGrid(items, 0);
            return;
        }
        MainActivity.flickerStack(
                "GRID_EMPTY_CLEAR key=" + key + " box=" + System.identityHashCode(gridBox));
        gridRows.clear();
        gridBox.removeAllViews();
        gridKey = key;
        drawnSignatures.clear();
        gridCards.clear();
        drawn = 0;
        gridHint.setText("正在获取" + cat.label + "榜单…");
        gridHint.setVisibility(View.VISIBLE);
        if (gridMore != null && !gridMore.hasFocus()) gridMore.setVisibility(View.GONE);
        fetchGrid(cat, 0, 0);
    }

    /** 返回保留的首页时恢复被 cancelPageRequests 打断的加载，不重建整个片单。 */
    void resume() {
        if (!grid()) return;
        token++;
        moreBusy = false;
        Category cat = category();
        List<Item> items = loaded.get(cat.key());
        if (items == null || items.isEmpty()) {
            gridFailed = false;
            fetchGrid(cat, 0, 0);
        } else if (gridRows.isEmpty()) {
            drawGrid(items, 0);
        } else {
            // 空闲态不显示任何底部说明文字（仅在加载/出错/刷新反馈时出现）
            gridHint.setText("");
            gridHint.setVisibility(View.GONE);
            if (gridMore != null && !Boolean.TRUE.equals(exhausted.get(cat.key()))) {
                gridMore.setText("加载更多");
                gridMore.setVisibility(View.VISIBLE);
            }
            wire();
        }
    }

    /** 下拉刷新：旧片单保持可见，拿到新数据后才替换，避免先清空造成白屏闪烁。 */
    void refresh(Runnable done) {
        if (!grid() || moreBusy) {
            if (done != null) done.run();
            return;
        }
        Category cat = category();
        MainActivity.flicker(
                "GRID_REFRESH_START key="
                        + cat.key()
                        + " visible="
                        + drawn
                        + " generation="
                        + a.generation);
        cacheRemove(cat.key());
        token++;
        gridFailed = false;
        // 不改变 hint 文字：hint 在 ScrollView 内，文字变化会引起内部布局抖动导致视觉闪烁。
        // 下拉刷新期间顶部 badge 已显示「正在刷新…」，结果到达后在 drawGrid 中统一更新 hint。
        fetchGrid(cat, 0, 0, true, done);
    }

    /** 网格模式：「加载更多」/「重新加载」。 */
    private void loadMore() {
        if (!grid() || moreBusy) return;
        if (gridFailed) {
            // 按钮此刻是「重新加载」，直接保留旧内容重试，避免先清空网格
            refresh(null);
            return;
        }
        Category cat = category();
        String key = cat.key();
        if (Boolean.TRUE.equals(exhausted.get(key))) {
            if (gridMore != null && !gridMore.hasFocus()) gridMore.setVisibility(View.GONE);
            return;
        }
        Integer from = nextStart.get(key);
        int pageStart = from == null ? 0 : from;
        if (gridMore != null) gridMore.setText("正在加载…");
        fetchGrid(cat, pageStart, 0);
    }

    /**
     * 网格模式：拉取 pageStart 开始的一页并画上去。
     *
     * <p>pageStart==0 失败时沿用货架的自动重试策略（共 3 次尝试），全部失败把
     * 「加载更多」变成「重新加载」，避免给 TV 用户一个点了没反应的提示行。
     */
    /** 第一页请求去重：每日预取和用户点分类可以同时等待同一次请求，不重复打豆瓣。 */
    private void fetchFirstPage(Category cat, boolean bypassCache, FirstPageCallback callback) {
        final String key = cat.key();
        if (!bypassCache) {
            List<Item> cached = cacheGet(key);
            if (cached != null) {
                a.ui.post(() -> callback.accept(cached, null));
                return;
            }
        }
        synchronized (FIRST_PAGE_WAITERS) {
            List<FirstPageCallback> waiters = FIRST_PAGE_WAITERS.get(key);
            if (waiters != null) {
                waiters.add(callback);
                return;
            }
            waiters = new ArrayList<>();
            waiters.add(callback);
            FIRST_PAGE_WAITERS.put(key, waiters);
        }
        // 不放进 pageRequests：切搜索页不应取消当天的公共预取，其他页面仍在等结果。
        a.worker.execute(
                () -> {
                    List<Item> items = null;
                    String error = null;
                    try {
                        items = fetch(cat.type, cat.tag, 0);
                    } catch (Throwable t) {
                        error = a.message(t);
                        AppLog.w("DoubanHot", "daily first page failed: " + key, t);
                    }
                    if (items != null && !items.isEmpty()) cachePut(key, items);
                    final List<Item> result = items;
                    final String failure =
                            error != null
                                    ? error
                                    : (items == null || items.isEmpty() ? "空片单" : null);
                    final List<FirstPageCallback> callbacks;
                    synchronized (FIRST_PAGE_WAITERS) {
                        callbacks = FIRST_PAGE_WAITERS.remove(key);
                    }
                    a.ui.post(
                            () -> {
                                if (callbacks == null) return;
                                for (FirstPageCallback waiter : callbacks)
                                    waiter.accept(result, failure);
                            });
                });
    }

    private void fetchGrid(final Category cat, final int pageStart, final int attempt) {
        fetchGrid(cat, pageStart, attempt, false, null);
    }

    private void fetchGrid(
            final Category cat,
            final int pageStart,
            final int attempt,
            final boolean replace,
            final Runnable done) {
        moreBusy = true;
        final String key = cat.key();
        final int mine = token;
        if (pageStart == 0) {
            fetchFirstPage(
                    cat,
                    replace,
                    (result, failure) ->
                            onGridPage(
                                    cat, pageStart, attempt, replace, done, key, mine, result,
                                    failure));
            return;
        }
        a.submitPage(
                () -> {
                    List<Item> items = null;
                    String error = null;
                    try {
                        items = fetch(cat.type, cat.tag, pageStart);
                    } catch (Throwable t) {
                        error = a.message(t);
                        AppLog.w("DoubanHot", "grid fetch failed: " + key + "@" + pageStart, t);
                    }
                    final List<Item> result = items;
                    final String failure = error;
                    a.ui.post(
                            () ->
                                    onGridPage(
                                            cat, pageStart, attempt, replace, done, key, mine,
                                            result, failure));
                });
    }

    private void onGridPage(
            Category cat,
            int pageStart,
            int attempt,
            boolean replace,
            Runnable done,
            String key,
            int mine,
            List<Item> result,
            String failure) {
        if (mine != token) {
            if (done != null) done.run();
            return;
        }
        moreBusy = false;
        if (result == null || result.isEmpty()) {
            if (pageStart != 0) {
                // 翻页失败：已画的内容保留，只收起按钮
                exhausted.put(key, true);
                if (gridMore != null && !gridMore.hasFocus()) gridMore.setVisibility(View.GONE);
                return;
            }
            if (attempt < 2) {
                gridHint.setText("正在重试获取" + cat.label + "榜单…");
                a.ui.postDelayed(
                        () -> {
                            if (mine == token) fetchGrid(cat, 0, attempt + 1, replace, done);
                            else if (done != null) done.run();
                        },
                        800);
            } else {
                gridFailed = true;
                gridHint.setText(
                        (gridRows.isEmpty() ? "豆瓣榜单暂时取不到" : "刷新失败，继续显示当前片单")
                                + (failure == null ? "" : "（" + failure + "）"));
                gridHint.setVisibility(View.VISIBLE);
                if (gridMore != null) {
                    gridMore.setText("重新加载");
                    gridMore.setVisibility(View.VISIBLE);
                }
                if (done != null) done.run();
            }
            return;
        }
        nextStart.put(key, pageStart + result.size());
        List<Item> all = replace ? null : loaded.get(key);
        if (all == null) {
            all = new ArrayList<>();
            loaded.put(key, all);
        }
        Set<String> ids = new HashSet<>();
        for (Item it : all) ids.add(it.id);
        for (Item it : result) if (it.id != null && !it.id.isEmpty() && ids.add(it.id)) all.add(it);
        exhausted.put(key, result.size() < PAGE);
        MainActivity.flicker(
                "GRID_RESULT key="
                        + key
                        + " page="
                        + pageStart
                        + " new="
                        + result.size()
                        + " total="
                        + all.size()
                        + " replace="
                        + replace
                        + " token="
                        + mine);
        drawGrid(all, pageStart);
        if (done != null) done.run();
    }

    // ---------------------------------------------------------------- 渲染

    private void styleChip(TextView chip, boolean on, boolean major) {
        chip.setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
        // 一级大类选中=品牌蓝填充+白字（主导），二级细分选中=浅色填充+深字（从属），
        // 未选中透明+灰字；聚焦时由 FocusMotion 加白色描边+轻微放大（手机触摸不触发）。
        int bgOn = major ? 0xff0a84ff : 0xffe9efec;
        int txtOn = major ? 0xffffffff : 0xff101413;
        int bgOff = 0x00000000;
        int txtOff = 0xffb9c4c0;
        FocusMotion.style(
                a,
                chip,
                chip.hasFocus(),
                on ? bgOn : bgOff,
                on ? bgOn : bgOff,
                999,
                on ? txtOn : txtOff,
                on ? txtOn : txtOff,
                true,
                1.03f);
    }

    private void render(List<Item> items) {
        rowBox.removeAllViews();
        int limit = Math.min(items.size(), 20);
        for (int i = 0; i < limit; i++) rowBox.addView(card(items.get(i), i, false));
        // 空闲态不显示底部说明文字
        hint.setText("");
        hint.setVisibility(View.GONE);
    }

    /** 网格模式：同榜刷新复用卡片原位更新；新分类才清空，翻页只追加。 */
    private void drawGrid(List<Item> items, int pageStart) {
        gridFailed = false;
        String key = category().key();
        if (pageStart == 0) {
            if (key.equals(gridKey) && !gridRows.isEmpty()) {
                if (sameVisibleItems(items)) {
                    MainActivity.flicker("GRID_NO_CHANGE key=" + key + " count=" + items.size());
                    gridHint.setText("片单已是最新");
                    gridHint.setVisibility(View.VISIBLE);
                } else {
                    MainActivity.flicker(
                            "GRID_RECONCILE key=" + key + " old=" + drawn + " new=" + items.size());
                    reconcileGrid(items);
                    gridHint.setText("片单已更新");
                    gridHint.setVisibility(View.VISIBLE);
                }
                updateGridMore(items);
                wire();
                return;
            }
            MainActivity.flickerStack(
                    "GRID_FULL_CLEAR key="
                            + key
                            + " oldKey="
                            + gridKey
                            + " drawn="
                            + drawn
                            + " box="
                            + System.identityHashCode(gridBox));
            gridBox.removeAllViews();
            gridRows.clear();
            drawnSignatures.clear();
            gridCards.clear();
            gridKey = key;
            drawn = 0;
        }
        for (int i = drawn; i < items.size(); i++) {
            addGridCard(items.get(i), i);
            drawnSignatures.add(signature(items.get(i)));
        }
        drawn = items.size();
        // 空闲态隐藏底部说明行
        gridHint.setText("");
        gridHint.setVisibility(View.GONE);
        updateGridMore(items);
        wire();
    }

    /**
     * 同榜刷新不销毁任何保留卡片。按豆瓣条目 id 复用 View；仅顺序变化时移动原 View，
     * 标题、评分等文字原位更新，URL 未变的海报不会重新进入 ImageLoader。
     */
    private void reconcileGrid(List<Item> items) {
        List<View> oldOrder = new ArrayList<>();
        for (List<View> row : gridRows) oldOrder.addAll(row);

        List<View> target = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            View view = item.id == null ? null : gridCards.get(item.id);
            if (view == null) view = card(item, i, true);
            bindGridCard(view, item, i);
            target.add(view);
        }

        // 只是评分/剧集信息变化时不触碰布局；顺序变化也只移动已有 View，不重建图片。
        if (!oldOrder.equals(target)) {
            View focused = a.getCurrentFocus();
            for (int i = 0; i < gridBox.getChildCount(); i++)
                ((LinearLayout) gridBox.getChildAt(i)).removeAllViews();
            gridRows.clear();

            int rowCount = (target.size() + columns - 1) / columns;
            while (gridBox.getChildCount() > rowCount)
                gridBox.removeViewAt(gridBox.getChildCount() - 1);
            while (gridBox.getChildCount() < rowCount) {
                LinearLayout row = a.row();
                row.setGravity(Gravity.TOP);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                row.setPadding(0, a.dp(5), 0, a.dp(16));
                gridBox.addView(row);
            }
            for (int i = 0; i < gridBox.getChildCount(); i++) gridRows.add(new ArrayList<>());
            for (int i = 0; i < target.size(); i++) {
                int rowIndex = i / columns;
                int column = i % columns;
                LinearLayout row = (LinearLayout) gridBox.getChildAt(rowIndex);
                View view = target.get(i);
                LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, -2, 1);
                cell.rightMargin = a.dp(column == columns - 1 ? 0 : 14);
                row.addView(view, cell);
                gridRows.get(rowIndex).add(view);
            }
            if (focused != null && target.contains(focused)) focused.requestFocus();
        }

        gridCards.clear();
        drawnSignatures.clear();
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            if (item.id != null && !item.id.isEmpty()) gridCards.put(item.id, target.get(i));
            drawnSignatures.add(signature(item));
        }
        drawn = items.size();
    }

    /** 单向数据绑定：Item 属性映射到已存在 View；不在绑定层创建或销毁整片列表。 */
    private void bindGridCard(View view, Item item, int index) {
        view.setTag("douban-card:" + index);
        view.setContentDescription(
                item.title + (TextUtils.isEmpty(item.rate) ? "，豆瓣榜单" : "，豆瓣评分" + item.rate));
        view.setOnClickListener(v -> a.openSearch(item.title));

        FrameLayout box = (FrameLayout) ((LinearLayout) view).getChildAt(0);
        ImageView poster = (ImageView) box.getChildAt(0);
        String address = TextUtils.isEmpty(item.poster) ? "" : coverAddress(item.poster);
        if (!address.equals(poster.getTag())) {
            if (address.isEmpty()) {
                poster.setTag("");
                poster.setImageDrawable(null);
            } else {
                // 保留旧图直到新图就绪；同一条目换封面时也不先闪成灰色占位。
                a.poster(address, poster);
            }
        }

        if (box.getChildCount() > 1 && box.getChildAt(1) instanceof TextView) {
            TextView rate = (TextView) box.getChildAt(1);
            if (!rate.getText().toString().equals(item.rate)) rate.setText(item.rate);
            rate.setVisibility(TextUtils.isEmpty(item.rate) ? View.GONE : View.VISIBLE);
        }
        TextView name = (TextView) ((LinearLayout) view).getChildAt(1);
        if (!name.getText().toString().equals(item.title)) name.setText(item.title);
        TextView sub = (TextView) ((LinearLayout) view).getChildAt(2);
        String subText = TextUtils.isEmpty(item.episodes) ? "" : item.episodes;
        sub.setVisibility(TextUtils.isEmpty(item.episodes) ? View.INVISIBLE : View.VISIBLE);
        if (!sub.getText().toString().equals(subText)) sub.setText(subText);
    }

    private void updateGridMore(List<Item> items) {
        boolean done = Boolean.TRUE.equals(exhausted.get(category().key()));
        if (gridMore != null) {
            gridMore.setText(done ? "没有更多了" : "加载更多");
            if (done || items.isEmpty()) {
                if (!gridMore.hasFocus()) gridMore.setVisibility(View.GONE);
            } else {
                gridMore.setVisibility(View.VISIBLE);
            }
        }
    }

    private boolean sameVisibleItems(List<Item> items) {
        if (drawnSignatures.size() != items.size()) return false;
        for (int i = 0; i < items.size(); i++)
            if (!drawnSignatures.get(i).equals(signature(items.get(i)))) return false;
        return true;
    }

    private static String signature(Item item) {
        return String.valueOf(item.id)
                + '\u0001'
                + item.title
                + '\u0001'
                + item.poster
                + '\u0001'
                + item.rate
                + '\u0001'
                + item.episodes;
    }

    private void addGridCard(Item item, int index) {
        int column = index % columns;
        LinearLayout row;
        if (column == 0) {
            row = a.row();
            row.setGravity(Gravity.TOP);
            row.setClipChildren(false);
            row.setClipToPadding(false);
            row.setPadding(0, a.dp(5), 0, a.dp(16));
            gridBox.addView(row);
            gridRows.add(new ArrayList<>());
        } else {
            row = (LinearLayout) gridBox.getChildAt(gridBox.getChildCount() - 1);
        }
        View card = card(item, index, true);
        LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, -2, 1);
        cell.rightMargin = a.dp(column == columns - 1 ? 0 : 14);
        row.addView(card, cell);
        gridRows.get(gridRows.size() - 1).add(card);
        if (item.id != null && !item.id.isEmpty()) gridCards.put(item.id, card);
    }

    /**
     * 单张榜单卡片。
     *
     * <p>shelf=false 是手机货架的固定 92dp 小卡；grid=true 是 TV 瀑布流的整列宽大卡。
     * 两种卡都用 {@link #focusableTouch}：DPad 可聚焦（遥控器 OK 直接发 click），
     * 触摸屏单击即点——不再有旧版 bindTouchFeedback 设 focusable=false 导致的
     * 「真电视没法聚焦/确认」问题，也不会引入「先抢焦点再点击」的双触问题。
     */
    private View card(final Item item, int index, boolean grid) {
        LinearLayout card = a.vertical();
        card.setTag(grid ? "douban-card:" + index : "douban:" + item.id);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setClipChildren(false);
        if (grid) card.setPadding(a.dp(4), a.dp(4), a.dp(4), 0);

        FrameLayout box = new FrameLayout(a);
        ImageView poster = new ImageView(a);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setBackground(a.bg(0xff232b28, 10));
        poster.setClipToOutline(true);
        box.addView(poster, new FrameLayout.LayoutParams(-1, -1));

        if (!TextUtils.isEmpty(item.poster)) {
            // 豆瓣图床有防盗链：既要 Referer，也会拒绝非浏览器 UA（okhttp/3.15 直接 403），
            // 两个都以内联头形式交给 ImageLoader 解析
            a.poster(coverAddress(item.poster), poster);
        }

        // 评分视图始终存在，后续同榜刷新可以原位更新，不必因评分增删而重建卡片。
        TextView rate = a.text(item.rate, 10, 0xffffffff);
        rate.setTypeface(null, Typeface.BOLD);
        rate.setBackground(a.bg(0xb3000000, 7));
        rate.setPadding(a.dp(5), a.dp(1), a.dp(5), a.dp(1));
        rate.setVisibility(TextUtils.isEmpty(item.rate) ? View.GONE : View.VISIBLE);
        FrameLayout.LayoutParams rateLp =
                new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        rateLp.topMargin = a.dp(5);
        rateLp.rightMargin = a.dp(5);
        box.addView(rate, rateLp);

        if (grid) {
            card.addView(box, new LinearLayout.LayoutParams(-1, a.dp(posterWidth * 1.42f)));
        } else {
            card.addView(box, new LinearLayout.LayoutParams(a.dp(92), a.dp(128)));
        }

        TextView name = a.text(item.title, 13f, grid ? 0xffc8c8d0 : MainActivity.WHITE);
        // 固定占两行高度：一部剧名可能 1 行也可能 2 行，不锁行数的话同一排卡片高矮不齐
        name.setMinLines(2);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setLineSpacing(0, 0.95f);
        if (grid) name.setPadding(a.dp(2), a.dp(7), a.dp(2), 0);
        card.addView(name, new LinearLayout.LayoutParams(-1, -2));

        TextView sub =
                a.text(
                        TextUtils.isEmpty(item.episodes) ? "" : item.episodes,
                        10.5f,
                        MainActivity.MUTED);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        // 无更新信息（剧集数）时不再兜底显示「豆瓣榜单」小字：保留占位（INVISIBLE）维持卡片等高
        sub.setVisibility(TextUtils.isEmpty(item.episodes) ? View.INVISIBLE : View.VISIBLE);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
        subLp.topMargin = a.dp(2);
        if (grid) sub.setPadding(a.dp(2), 0, a.dp(2), 0);
        card.addView(sub, subLp);

        focusableTouch(card);
        card.setContentDescription(
                item.title + (TextUtils.isEmpty(item.rate) ? "，豆瓣榜单" : "，豆瓣评分" + item.rate));
        card.setOnClickListener(v -> a.openSearch(item.title));
        card.setOnFocusChangeListener(
                (v, f) -> {
                    // 与 HomeScreen 原分类卡片同款焦点样式：透明底 + 白色描边 + 轻微放大
                    FocusMotion.style(
                            a,
                            card,
                            f,
                            Color.TRANSPARENT,
                            Color.TRANSPARENT,
                            13,
                            MainActivity.WHITE,
                            MainActivity.WHITE,
                            true,
                            1.035f);
                    name.setTextColor(
                            f ? MainActivity.WHITE : (grid ? 0xffc8c8d0 : MainActivity.WHITE));
                    if (f)
                        v.requestRectangleOnScreen(
                                new Rect(0, -a.dp(8), v.getWidth(), v.getHeight() + a.dp(10)),
                                false);
                });
        FocusMotion.style(
                a,
                card,
                false,
                Color.TRANSPARENT,
                Color.TRANSPARENT,
                13,
                MainActivity.WHITE,
                MainActivity.WHITE,
                true,
                1.035f);

        if (!grid) {
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(a.dp(92), -2);
            cardLp.rightMargin = a.dp(10);
            card.setLayoutParams(cardLp);
        }
        if (grid)
            MainActivity.flicker(
                    "CARD_NEW index="
                            + index
                            + " id="
                            + item.id
                            + " view="
                            + System.identityHashCode(card)
                            + " generation="
                            + a.generation);
        return card;
    }

    /**
     * TV 首页 DPad 焦点连线：大类条 ↔ 细分条 ↔ 网格 ↔ 「加载更多」。
     * 由 HomeScreen.wireNavigation 与 drawGrid 调用（切大类重建细分条后也会重连）。
     */
    void wire() {
        if (!grid()) return;
        View activeMajor =
                majorSwitcher != null
                        ? majorSwitcher
                        : (majorChips.isEmpty()
                                ? null
                                : majorChips.get(Math.min(major, majorChips.size() - 1)));
        View activeSub =
                subChips.isEmpty() ? null : subChips.get(Math.min(sub, subChips.size() - 1));
        linkHorizontal(majorChips);
        for (View chip : majorChips) {
            chip.setNextFocusDownId(
                    activeSub != null
                            ? activeSub.getId()
                            : (gridMore != null && gridMore.isShown()
                                    ? gridMore.getId()
                                    : chip.getId()));
        }
        linkHorizontal(subChips);
        for (int i = 0; i < subChips.size(); i++) {
            View chip = subChips.get(i);
            chip.setNextFocusUpId(activeMajor != null ? activeMajor.getId() : chip.getId());
            chip.setNextFocusDownId(
                    gridRows.isEmpty()
                            ? (gridMore != null && gridMore.isShown()
                                    ? gridMore.getId()
                                    : chip.getId())
                            : gridRows.get(0).get(Math.min(i, gridRows.get(0).size() - 1)).getId());
        }
        for (int r = 0; r < gridRows.size(); r++) {
            List<View> row = gridRows.get(r);
            linkHorizontal(row);
            for (int c = 0; c < row.size(); c++) {
                View card = row.get(c);
                if (r == 0) {
                    card.setNextFocusUpId(activeSub != null ? activeSub.getId() : card.getId());
                } else {
                    List<View> up = gridRows.get(r - 1);
                    card.setNextFocusUpId(up.get(Math.min(c, up.size() - 1)).getId());
                }
                if (r + 1 < gridRows.size()) {
                    List<View> down = gridRows.get(r + 1);
                    card.setNextFocusDownId(down.get(Math.min(c, down.size() - 1)).getId());
                } else {
                    card.setNextFocusDownId(
                            gridMore != null && gridMore.isShown()
                                    ? gridMore.getId()
                                    : card.getId());
                }
            }
        }
        if (gridMore != null) {
            gridMore.setNextFocusUpId(
                    gridRows.isEmpty()
                            ? (activeSub != null
                                    ? activeSub.getId()
                                    : (activeMajor != null
                                            ? activeMajor.getId()
                                            : gridMore.getId()))
                            : gridRows.get(gridRows.size() - 1).get(0).getId());
        }
    }

    /**
     * 告诉右侧停靠栏哪些视图是「行末焦点」，避免焦点飘进导航。
     *
     * <p>注意：分类 chips（大类/细分）在横向滚动条内，永远不链接到 Dock。此前对每个 chip
     * 都调 {@code rightEdge}，把所有 chips 的 nextFocusRightId 改写成了 Dock 首项，导致
     * 遥控器在分类条上按右直接跳出、无法横向移动。chips 的横向移动由 {@link #linkHorizontal}
     * 的自钳制 + 焦点回调里的 {@link #scrollChipIntoView} 滚动露出处理。
     */
    void dockRight(HomeDock dock) {
        if (dock == null || !grid()) return;
        for (List<View> row : gridRows) if (!row.isEmpty()) dock.rightEdge(row.get(row.size() - 1));
        if (gridMore != null) dock.rightEdge(gridMore);
    }

    private void linkHorizontal(List<? extends View> line) {
        for (int i = 0; i < line.size(); i++) {
            line.get(i).setNextFocusLeftId(line.get(Math.max(0, i - 1)).getId());
            line.get(i).setNextFocusRightId(line.get(Math.min(line.size() - 1, i + 1)).getId());
        }
    }

    // ---------------------------------------------------------------- 通用

    /**
     * 触摸/遥控两用的可点击视图：
     *
     * <ul>
     *   <li>{@code focusable=true} —— 真电视 DPad 能聚焦，OK 键直接发 click；
     *   <li>{@code focusableInTouchMode=false} —— 触摸屏（手机跑 TV 布局）单击立即 click，
     *       不会出现「第一次触摸只抢焦点」的双触问题（SettingsScreen 修复同款配置）；
     *   <li>按压透明度反馈 —— 保留手机上 bindTouchFeedback 的手感。
     * </ul>
     */
    private void focusableTouch(View v) {
        v.setId(View.generateViewId());
        v.setFocusable(true);
        v.setFocusableInTouchMode(false);
        v.setClickable(true);
        v.setOnTouchListener(
                (view, event) -> {
                    int action = event.getActionMasked();
                    if (action == MotionEvent.ACTION_DOWN) view.setAlpha(0.75f);
                    else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
                        view.setAlpha(1.0f);
                    return false;
                });
    }

    /** 封面地址 + 内联请求头（Referer 防防盗链、UA 防「非浏览器 403」）。 */
    private static String coverAddress(String poster) {
        return poster + "@Referer=" + REFERER + "@User-Agent=" + UA;
    }

    private static List<Item> fetch(String type, String tag, int pageStart) throws Exception {
        String url =
                ENDPOINT
                        + "?type="
                        + type
                        + "&tag="
                        + URLEncoder.encode(tag, "UTF-8")
                        + "&sort=recommend&page_limit="
                        + PAGE
                        + "&page_start="
                        + pageStart;
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", UA);
        headers.put("Referer", REFERER);
        headers.put("Accept", "application/json");
        String raw = Cms.fetch(url, headers);

        JSONObject root = new JSONObject(raw);
        JSONArray list = root.optJSONArray("subjects");
        if (list == null) throw new java.io.IOException("豆瓣返回格式变了");
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            if (o == null) continue;
            String name = o.optString("title", "").trim();
            if (name.isEmpty()) continue;
            Item item = new Item();
            item.id = o.optString("id", "");
            item.title = name;
            item.poster = o.optString("cover", "");
            item.rate = o.optString("rate", "").trim();
            item.episodes = o.optString("episodes_info", "").trim();
            items.add(item);
        }
        return items;
    }

    private List<Item> cacheGet(String key) {
        String day = today();
        synchronized (DoubanHot.class) {
            Cached c = CACHE.get(key);
            if (c != null) {
                if (day.equals(c.day)) return new ArrayList<>(c.items);
                CACHE.remove(key);
            }
        }
        String raw = a.prefs.getString(CACHE_PREF_PREFIX + key, "");
        if (raw.isEmpty()) return null;
        try {
            JSONObject root = new JSONObject(raw);
            if (!day.equals(root.optString("day"))) return null;
            List<Item> items = readItems(root.optJSONArray("items"));
            if (items.isEmpty()) return null;
            Cached c = new Cached();
            c.day = day;
            c.items = items;
            synchronized (DoubanHot.class) {
                CACHE.put(key, c);
            }
            return new ArrayList<>(items);
        } catch (Throwable t) {
            AppLog.w("DoubanHot", "daily cache unreadable: " + key, t);
            return null;
        }
    }

    private void cacheRemove(String key) {
        synchronized (DoubanHot.class) {
            CACHE.remove(key);
        }
        a.prefs.edit().remove(CACHE_PREF_PREFIX + key).apply();
    }

    private void cachePut(String key, List<Item> items) {
        Cached c = new Cached();
        c.day = today();
        c.items = new ArrayList<>(items);
        synchronized (DoubanHot.class) {
            CACHE.put(key, c);
        }
        try {
            JSONObject root = new JSONObject();
            root.put("day", c.day);
            root.put("items", writeItems(items));
            a.prefs.edit().putString(CACHE_PREF_PREFIX + key, root.toString()).apply();
        } catch (Throwable t) {
            AppLog.w("DoubanHot", "daily cache write failed: " + key, t);
        }
    }

    private static List<Item> readItems(JSONArray array) {
        List<Item> items = new ArrayList<>();
        if (array == null) return items;
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.optJSONObject(i);
            if (o == null) continue;
            Item item = new Item();
            item.id = o.optString("id", "");
            item.title = o.optString("title", "");
            item.poster = o.optString("poster", "");
            item.rate = o.optString("rate", "");
            item.episodes = o.optString("episodes", "");
            if (!item.title.isEmpty()) items.add(item);
        }
        return items;
    }

    private static JSONArray writeItems(List<Item> items) {
        JSONArray array = new JSONArray();
        for (Item item : items) {
            JSONObject o = new JSONObject();
            try {
                o.put("id", item.id);
                o.put("title", item.title);
                o.put("poster", item.poster);
                o.put("rate", item.rate);
                o.put("episodes", item.episodes);
                array.put(o);
            } catch (Throwable ignored) {
            }
        }
        return array;
    }
}
