package com.pilipala.marcomovie;

import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;

/**
 * Category listing, paginated video grid browsing, and watch history management extracted from
 * {@link MainActivity}.
 */
final class CatalogScreen {
    private CatalogScreen() {}

    /** Visible back entry so remote and touch users can always leave a library page. */
    static void addBack(MainActivity a, String tag) {
        a.body.addView(
                a.navButton("‹ 返回", tag, a::onBackPressed),
                new LinearLayout.LayoutParams(a.dp(82), a.dp(32)));
        a.gap(a.body, 8);
    }

    static void categories(MainActivity a, Cms.Site s) {
        a.prefs.edit().putString(Prefs.SELECTED_SITE, s.key).apply();
        a.renderPage(s.name, "CHOOSE A CATEGORY");
        addBack(a, "catalog-back");
        LinearLayout actions = a.row();
        a.addButton(actions, a.button("全局搜索", "search", a::globalSearchDialog));
        a.addButton(
                actions,
                a.button(
                        "最新内容",
                        "latest",
                        () ->
                                a.open(
                                        new MainActivity.Screen(
                                                () -> videos(a, s, "", "最新内容", 1, null)),
                                        true)));
        a.body.addView(actions);
        a.load(
                "正在读取分类…",
                () -> Cms.categories(a.engine(s).home()),
                cats -> {
                    if (cats.isEmpty()) {
                        a.paragraph("来源没有返回分类，可尝试“最新内容”或搜索。");
                        return;
                    }
                    for (Cms.Category c : cats)
                        a.addButton(
                                a.body,
                                a.button(
                                        c.name,
                                        "category:" + c.id,
                                        () ->
                                                a.open(
                                                        new MainActivity.Screen(
                                                                () ->
                                                                        videos(
                                                                                a, s, c.id, c.name,
                                                                                1, null)),
                                                        true)));
                });
    }

    static void videos(
            MainActivity a, Cms.Site s, String cat, String title, int page, String search) {
        a.renderPage(title, "EXPLORE THE LIBRARY");
        addBack(a, "catalog-back");
        a.paragraph(s.name + " · 第 " + page + " 页");
        a.load(
                "正在读取影片…",
                () -> {
                    SourceEngine selected = a.engine(s);
                    return Cms.listing(
                            search != null
                                    ? selected.search(search, page)
                                    : selected.category(cat, page),
                            s);
                },
                list -> {
                    if (list.videos.isEmpty()) a.paragraph("这一页没有影片。");
                    int cols =
                            TvLayoutPolicy.catalogColumns(
                                    a.windowWidth() / a.getResources().getDisplayMetrics().density,
                                    a.formFactor,
                                    a.isPortrait());
                    LinearLayout cards = null;
                    for (int i = 0; i < list.videos.size(); i++) {
                        if (i % cols == 0) {
                            cards = a.row();
                            cards.setGravity(Gravity.TOP);
                            cards.setClipChildren(false);
                            cards.setClipToPadding(false);
                            a.body.addView(cards);
                        }
                        Cms.Video v = list.videos.get(i);
                        LinearLayout card = a.vertical();
                        card.setPadding(a.dp(5), a.dp(5), a.dp(5), a.dp(10));
                        card.setClipChildren(false);
                        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
                        lp.setMargins(a.dp(5), a.dp(5), a.dp(14), a.dp(19));
                        cards.addView(card, lp);
                        FrameLayout art = new FrameLayout(a);
                        art.setBackground(
                                new GradientDrawable(
                                        GradientDrawable.Orientation.TL_BR,
                                        new int[] {0xff304c70, 0xff141c2c}));
                        ImageView img = new ImageView(a);
                        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                        art.addView(img, new FrameLayout.LayoutParams(-1, -1));
                        card.addView(art, new LinearLayout.LayoutParams(-1, a.dp(185)));
                        a.poster(v.poster, img);
                        TextView label = a.text(v.title, 16, MainActivity.WHITE);
                        label.setPadding(a.dp(5), a.dp(10), a.dp(5), 0);
                        label.setLines(2);
                        label.setEllipsize(TextUtils.TruncateAt.END);
                        card.addView(label);
                        TextView remark = a.text(v.remarks, 12, MainActivity.MUTED);
                        remark.setMaxLines(1);
                        remark.setEllipsize(TextUtils.TruncateAt.END);
                        card.addView(remark);
                        card.setFocusable(true);
                        card.setFocusableInTouchMode(true);
                        card.setClickable(true);
                        card.setTag(i == 0 ? "primary" : "video:" + v.id);
                        card.setOnClickListener(view -> a.openListing(s, v));
                        card.setOnFocusChangeListener(
                                (view, focus) ->
                                        FocusMotion.style(
                                                a,
                                                card,
                                                focus,
                                                0x00101c30,
                                                0xff435c7c,
                                                10,
                                                MainActivity.WHITE,
                                                MainActivity.WHITE,
                                                true,
                                                1.035f));
                    }
                    if (cards != null) {
                        int remainder = list.videos.size() % cols;
                        if (remainder > 0)
                            for (int i = remainder; i < cols; i++) {
                                LinearLayout.LayoutParams blank =
                                        new LinearLayout.LayoutParams(0, 1, 1);
                                blank.setMargins(a.dp(5), a.dp(5), a.dp(14), a.dp(19));
                                cards.addView(new View(a), blank);
                            }
                    }
                    LinearLayout paging = a.row();
                    if (page > 1)
                        a.addButton(
                                paging,
                                a.button(
                                        "上一页",
                                        "previous",
                                        () ->
                                                a.open(
                                                        new MainActivity.Screen(
                                                                () ->
                                                                        videos(
                                                                                a, s, cat, title,
                                                                                page - 1, search)),
                                                        false)));
                    if (page < list.pageCount)
                        a.addButton(
                                paging,
                                a.button(
                                        "下一页",
                                        "next",
                                        () ->
                                                a.open(
                                                        new MainActivity.Screen(
                                                                () ->
                                                                        videos(
                                                                                a, s, cat, title,
                                                                                page + 1, search)),
                                                        false)));
                    a.body.addView(paging);
                    a.restore(a.current);
                });
    }

    static String historyLabel(WatchHistory.Entry e) {
        return e.episode
                + " · "
                + (e.complete()
                        ? "已看完"
                        : PlaybackController.formatTime(e.position)
                                + (e.duration > 0
                                        ? " / " + PlaybackController.formatTime(e.duration)
                                        : ""));
    }

    static void resumeHistory(MainActivity a, WatchHistory.Entry e) {
        Cms.Site site = null;
        if (a.config != null) for (Cms.Site s : a.config.sites) if (s.key.equals(e.site)) site = s;
        if (site == null || !a.available(site)) {
            a.notice("这条记录的来源已不可用，记录仍保留在本机。");
            return;
        }
        final Cms.Site selected = site;
        Cms.Video listing = new Cms.Video();
        listing.id = e.id;
        listing.title = e.title;
        listing.poster = e.poster;
        a.open(new MainActivity.Screen(() -> a.detail(selected, listing)), true);
    }

    static void playBookmark(
            MainActivity a, Cms.Site site, Cms.Video video, WatchHistory.Entry bookmark) {
        Cms.Line line = null;
        for (Cms.Line candidate : video.lines)
            if (candidate.name.equals(bookmark.line)) {
                line = candidate;
                break;
            }
        if (line == null && !video.lines.isEmpty()) {
            line = video.lines.get(0);
        }
        Cms.Episode episode = null;
        if (line != null) {
            for (Cms.Episode candidate : line.episodes)
                if (candidate.name.equals(bookmark.episode)) {
                    episode = candidate;
                    break;
                }
            if (episode == null && bookmark.index >= 0 && bookmark.index < line.episodes.size()) {
                episode = line.episodes.get(bookmark.index);
            }
        }
        if (episode == null) {
            a.notice("上次的线路或集数已变更，请在下方重新选集。");
            return;
        }
        a.resumeAt = bookmark.resume();
        a.resolvePlayer(site, video, line, episode);
    }

    static void historyPage(MainActivity a) {
        a.renderPage("观看历史", "CONTINUE WATCHING");
        List<WatchHistory.Entry> entries = a.watchHistory();
        if (entries.isEmpty()) {
            a.paragraph("播放后会在这里记录集数和进度。");
            return;
        }
        a.addButton(
                a.body,
                a.navButton(
                        "清空观看历史",
                        "history-clear",
                        () ->
                                new AppDialog.Builder(a)
                                        .setTitle("清空观看历史？")
                                        .setMessage("只清除本机观看记录，保留配置和账号。")
                                        .setNegativeButton("取消", null)
                                        .setPositiveButton(
                                                "清空",
                                                (d, n) -> {
                                                    a.prefs
                                                            .edit()
                                                            .remove(Prefs.WATCH_HISTORY)
                                                            .apply();
                                                    a.refresh();
                                                })
                                        .show()));
        for (WatchHistory.Entry e : entries) {
            LinearLayout line = a.row();
            line.setClipChildren(false);
            line.setClipToPadding(false);
            line.setPadding(0, a.dp(6), 0, a.dp(6));
            Button item =
                    a.button(
                            e.title + " · " + historyLabel(e),
                            "history:" + e.key(),
                            () -> resumeHistory(a, e));
            item.setSingleLine(true);
            item.setEllipsize(TextUtils.TruncateAt.END);
            line.addView(item, new LinearLayout.LayoutParams(0, a.dp(52), 1));
            a.addButton(
                    line,
                    a.navButton(
                            "移除",
                            "history-remove:" + e.key(),
                            () -> {
                                a.prefs
                                        .edit()
                                        .putString(
                                                Prefs.WATCH_HISTORY,
                                                WatchHistory.remove(
                                                        a.prefs.getString(
                                                                Prefs.WATCH_HISTORY, "[]"),
                                                        e.key()))
                                        .apply();
                                a.refresh();
                            }));
            a.body.addView(line);
            a.gap(a.body, 8);
        }
    }
}
