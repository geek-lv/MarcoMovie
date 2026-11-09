package com.pilipala.marcomovie;

import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 首页「豆瓣热播」榜单货架。
 *
 * <p>片源里的 {@code home()} 只返回「那一个站自己的片单」，所以电视剧经常是空的、也不跟热播榜走。
 * 这里在首页顶部单独拉一条豆瓣实时热榜（电视剧 / 电影），点某部片直接拿片名去当前片源搜索，
 * 不改变原有片单逻辑，也不引入新的播放链路。
 *
 * <p>数据源是豆瓣的电影搜索接口 {@code /j/search_subjects}，需要带浏览器 UA 与 Referer，
 * 否则会被判成爬虫。图床（doubanio）有防盗链，所以封面地址追加
 * {@code @Referer=https://movie.douban.com/}，交给 {@link ImageLoader} 的内联头机制处理。
 */
final class DoubanHot {

    /** 一条榜单条目。豆瓣只给标题/评分/封面，没有剧集结构，所以不做成本地 Cms.Video。 */
    static final class Item {
        String id, title, poster, rate, episodes;
    }

    private static final String[] TYPES = {"tv", "movie"};
    private static final String[] LABELS = {"电视剧", "电影"};
    private static final String ENDPOINT = "https://movie.douban.com/j/search_subjects";
    private static final String REFERER = "https://movie.douban.com/";
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                    + " (KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    /** 榜单缓存：热门榜变化慢，10 分钟足够，避免每次回首页都打豆瓣 */
    private static final long TTL_MS = 10 * 60 * 1000L;
    private static final Map<String, Cached> CACHE = new LinkedHashMap<>();

    private static final class Cached {
        long time;
        List<Item> items;
    }

    private final MainActivity a;
    private final LinearLayout rowBox;
    private final TextView hint;
    private final TextView[] chips = new TextView[LABELS.length];

    private int tab;
    /** 切 tab 时作废旧请求，防止返回顺序错乱把旧榜单画上去 */
    private int token;

    private DoubanHot(MainActivity activity, LinearLayout rowBox, TextView hint) {
        this.a = activity;
        this.rowBox = rowBox;
        this.hint = hint;
    }

    /**
     * 在给定容器尾部追加「豆瓣热播」货架。
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

        final HorizontalScrollView scroll = new HorizontalScrollView(a);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(a.dp(16), a.dp(2), a.dp(16), a.dp(2));
        final LinearLayout row = a.row();
        scroll.addView(row);

        final TextView hint = a.text("正在获取豆瓣榜单…", 11.5f, MainActivity.MUTED);
        hint.setPadding(a.dp(16), a.dp(6), a.dp(16), a.dp(2));

        final DoubanHot shelf = new DoubanHot(a, row, hint);

        LinearLayout tabs = a.row();
        tabs.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < LABELS.length; i++) {
            final int index = i;
            TextView chip = a.text(LABELS[i], 12.5f, MainActivity.MUTED);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(a.dp(12), a.dp(5), a.dp(12), a.dp(5));
            chip.setTag("douban-tab:" + i);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            if (i > 0) lp.leftMargin = a.dp(6);
            chip.setOnClickListener(v -> shelf.select(index));
            MobileDetailScreen.bindTouchFeedback(chip);
            shelf.chips[i] = chip;
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

    private void select(int index) {
        tab = index;
        token++;
        for (int i = 0; i < chips.length; i++) styleChip(chips[i], i == index);
        List<Item> cached = cacheGet(TYPES[index]);
        if (cached != null) {
            render(cached);
            return;
        }
        rowBox.removeAllViews();
        hint.setText("正在获取" + LABELS[index] + "热榜…");
        hint.setOnClickListener(null);
        final int mine = token;
        a.submitPage(
                () -> {
                    List<Item> items = null;
                    String error = null;
                    try {
                        items = fetch(TYPES[index]);
                    } catch (Throwable t) {
                        error = a.message(t);
                        AppLog.w("DoubanHot", "fetch failed: " + TYPES[index], t);
                    }
                    final List<Item> result = items;
                    final String failure = error;
                    a.ui.post(
                            () -> {
                                if (mine != token) return;
                                if (result == null || result.isEmpty()) {
                                    rowBox.removeAllViews();
                                    hint.setText(
                                            "豆瓣热榜暂时取不到"
                                                    + (failure == null ? "" : "（" + failure + "）")
                                                    + " · 点此重试");
                                    hint.setOnClickListener(v -> select(tab));
                                    return;
                                }
                                cachePut(TYPES[index], result);
                                render(result);
                            });
                });
    }

    private void styleChip(TextView chip, boolean on) {
        chip.setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
        chip.setTextColor(on ? 0xff101413 : 0xffb9c4c0);
        chip.setBackground(a.bg(on ? 0xffe9efec : 0x1fffffff, 999));
    }

    private void render(List<Item> items) {
        rowBox.removeAllViews();
        int limit = Math.min(items.size(), 20);
        for (int i = 0; i < limit; i++) rowBox.addView(card(items.get(i)));
        hint.setText("数据来自豆瓣 · 点封面用片名在当前片源搜索");
    }

    private View card(final Item item) {
        LinearLayout card = a.vertical();
        card.setTag("douban:" + item.id);

        FrameLayout box = new FrameLayout(a);
        ImageView poster = new ImageView(a);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setBackground(a.bg(0xff232b28, 10));
        poster.setClipToOutline(true);
        box.addView(poster, new FrameLayout.LayoutParams(-1, -1));

        if (!TextUtils.isEmpty(item.poster)) {
            // 豆瓣图床有防盗链，追加 Referer 走 ImageLoader 的内联头解析
            a.poster(item.poster + "@Referer=" + REFERER, poster);
        }

        if (!TextUtils.isEmpty(item.rate)) {
            TextView rate = a.text(item.rate, 10, 0xffffffff);
            rate.setTypeface(null, Typeface.BOLD);
            rate.setBackground(a.bg(0xb3000000, 7));
            rate.setPadding(a.dp(5), a.dp(1), a.dp(5), a.dp(1));
            FrameLayout.LayoutParams lp =
                    new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
            lp.topMargin = a.dp(5);
            lp.rightMargin = a.dp(5);
            box.addView(rate, lp);
        }

        LinearLayout.LayoutParams boxLp =
                new LinearLayout.LayoutParams(a.dp(92), a.dp(128));
        card.addView(box, boxLp);

        TextView name = a.text(item.title, 12.5f, MainActivity.WHITE);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setLineSpacing(0, 0.95f);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(-1, -2);
        nameLp.topMargin = a.dp(6);
        card.addView(name, nameLp);

        TextView sub = a.text(TextUtils.isEmpty(item.episodes) ? "豆瓣热榜" : item.episodes,
                10.5f, MainActivity.MUTED);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
        subLp.topMargin = a.dp(2);
        card.addView(sub, subLp);

        MobileDetailScreen.bindTouchFeedback(card);
        card.setOnClickListener(v -> a.openSearch(item.title));

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(a.dp(92), -2);
        cardLp.rightMargin = a.dp(10);
        card.setLayoutParams(cardLp);
        return card;
    }

    // ---------------------------------------------------------------- 网络与缓存

    private static List<Item> fetch(String type) throws Exception {
        String url =
                ENDPOINT
                        + "?type="
                        + type
                        + "&tag="
                        + URLEncoder.encode("热门", "UTF-8")
                        + "&sort=recommend&page_limit=20&page_start=0";
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

    private static synchronized List<Item> cacheGet(String type) {
        Cached c = CACHE.get(type);
        if (c == null) return null;
        if (System.currentTimeMillis() - c.time > TTL_MS) {
            CACHE.remove(type);
            return null;
        }
        return c.items;
    }

    private static synchronized void cachePut(String type, List<Item> items) {
        Cached c = new Cached();
        c.time = System.currentTimeMillis();
        c.items = items;
        CACHE.put(type, c);
    }
}
