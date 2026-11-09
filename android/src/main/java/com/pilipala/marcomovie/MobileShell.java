package com.pilipala.marcomovie;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import com.pilipala.marcomovie.adskip.AdSkipConfig;
import com.pilipala.marcomovie.safety.ContentFilterConfig;
import com.pilipala.marcomovie.safety.ContentGate;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Dedicated Android Phone & Tablet application shell styled 1:1 after the iPhone Portrait Liquid
 * Glass UI (`AdaptiveShell` + `AppleLiquidGlassDock`):
 *
 * <ul>
 *   <li>Edge-to-edge transparent status bar over an emerald-obsidian ambient canvas
 *   <li>Floating iOS 27 Liquid Glass 4-Tab Pill Dock (`主页` · `直播` · `片库` · `设置`) with a translucent
 *       selection lens
 *   <li>Scrollable Home header with brand logo, source capsule, netdisk capsule, and search button
 *   <li>3-column `2:3` portrait poster grid with bottom gradient `remarks` overlay and two-line
 *       `title` + `year · area` metadata below
 * </ul>
 */
final class MobileShell {
    static int activeTab = 0;
    static String pendingSearchKeyword = "";

    /** Persistent search session state so returning from Detail keeps search results resident. */
    static final class CachedSearchHit {
        final Cms.Site site;
        final Cms.Video video;

        CachedSearchHit(Cms.Site site, Cms.Video video) {
            this.site = site;
            this.video = video;
        }
    }

    static String cachedSearchKeyword = "";
    static String cachedSearchStatus = "";
    static final List<CachedSearchHit> cachedSearchHits = new ArrayList<>();
    static int cachedSearchScrollY = 0;

    /** Sub-page of the 片库 tab: 0 history, 1 favorites, 2 downloads. */
    static int mySubTab = 0;

    /**
     * Sub-page of the 设置 tab:
     * 0 = Main 5-card index, 1 = 片源与直播配置, 2 = 播放、解码与字幕, 3 = 界面外观与偏好, 4 = 备份、更新与诊断.
     */
    static int settingsSubPage = 0;

    private final MainActivity a;
    private FrameLayout contentContainer;
    private final View[] navTabs = new View[5];
    private final TextView[] navLabels = new TextView[5];
    private final NavGlyph[] navIcons = new NavGlyph[5];
    private static final String[] TAB_NAMES = {"主页", "搜索", "直播", "片库", "设置"};
    private static final int[] DOCK_TAB_INDICES = {0, 2, 3, 4};

    // Home tab state
    private Cms.Site currentSite;
    private String selectedCategoryId = "";
    private int currentPage = 1;
    private int totalPages = 1;
    private final List<Cms.Video> currentVideos = new ArrayList<>();
    private List<Cms.Category> currentCategories = new ArrayList<>();

    MobileShell(MainActivity activity) {
        this.a = activity;
    }

    static void show(MainActivity a) {
        new MobileShell(a).render();
    }

    static void openSearchTab(MainActivity a, String initialKeyword) {
        activeTab = 1;
        String kw = initialKeyword == null ? "" : initialKeyword.trim();
        if (!kw.isEmpty()) {
            pendingSearchKeyword = kw;
        }
        if (a.current == null || !a.current.home) {
            boolean pushBack = a.current != null && !a.current.redirect;
            a.open(
                    new MainActivity.Screen(
                            () -> {
                                activeTab = 1;
                                new MobileShell(a).render();
                            }),
                    pushBack);
        } else {
            new MobileShell(a).render();
        }
    }

    private int statusBarHeight() {
        try {
            int resId = a.getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (resId > 0) {
                int h = a.getResources().getDimensionPixelSize(resId);
                if (h > 0) return h;
            }
        } catch (Exception ignored) {
        }
        return a.dp(28);
    }

    static GradientDrawable glassBg(
            int topColor, int bottomColor, float radiusPx, float strokePx, int strokeColor) {
        GradientDrawable gd =
                new GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM, new int[] {topColor, bottomColor});
        gd.setCornerRadius(radiusPx);
        if (strokePx > 0) {
            gd.setStroke(Math.max(1, Math.round(strokePx)), strokeColor);
        }
        return gd;
    }

    static void showMobileImportDialog(MainActivity a) {
        LinearLayout panel = a.vertical();
        panel.setPadding(a.dp(4), a.dp(6), a.dp(4), a.dp(4));

        TextView tip =
                a.text("输入或粘贴配置网址（JSON / JS / PY / TXT / M3U / ZIP）或源码文本：", 13, MainActivity.MUTED);
        panel.addView(tip);
        a.gap(panel, 10);

        final EditText input = new EditText(a);
        input.setHint("https://…/config.json 或 .js / .py / .txt / .zip");
        input.setHintTextColor(0xff6b7280);
        input.setTextColor(MainActivity.WHITE);
        input.setTextSize(14);
        input.setSingleLine(true);
        input.setShowSoftInputOnFocus(true);
        input.setLongClickable(true);
        input.setTextIsSelectable(true);
        input.setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10));
        input.setBackground(a.bg(0xff1e2623, 12));
        String savedOrigin = a.prefs.getString(Prefs.ORIGIN, "");
        if (!savedOrigin.isEmpty()) {
            input.setText(savedOrigin);
            input.setSelection(savedOrigin.length());
        }
        panel.addView(input, new LinearLayout.LayoutParams(-1, -2));
        a.gap(panel, 12);

        TextView pasteBtn = a.text("一键读取手机剪贴板并直接导入", 13, Color.WHITE);
        pasteBtn.setTypeface(null, Typeface.BOLD);
        pasteBtn.setGravity(Gravity.CENTER);
        pasteBtn.setPadding(a.dp(14), a.dp(11), a.dp(14), a.dp(11));
        pasteBtn.setBackground(a.bg(0xff0a84ff, 999));
        MobileDetailScreen.bindTouchFeedback(pasteBtn);
        panel.addView(pasteBtn, new LinearLayout.LayoutParams(-1, -2));
        a.gap(panel, 8);

        TextView fileBtn = a.text("选择本地配置或本地包 (.json/.js/.py/.txt/.zip)", 13, MainActivity.WHITE);
        fileBtn.setGravity(Gravity.CENTER);
        fileBtn.setPadding(a.dp(14), a.dp(11), a.dp(14), a.dp(11));
        fileBtn.setBackground(a.bg(0xff1e2623, 999));
        MobileDetailScreen.bindTouchFeedback(fileBtn);
        panel.addView(fileBtn, new LinearLayout.LayoutParams(-1, -2));
        a.gap(panel, 8);

        TextView liveBtn = a.text("导入或管理电视直播源 (M3U / TXT / JSON)", 13, MainActivity.WHITE);
        liveBtn.setGravity(Gravity.CENTER);
        liveBtn.setPadding(a.dp(14), a.dp(11), a.dp(14), a.dp(11));
        liveBtn.setBackground(a.bg(0xff1e2623, 999));
        MobileDetailScreen.bindTouchFeedback(liveBtn);
        panel.addView(liveBtn, new LinearLayout.LayoutParams(-1, -2));

        final AppDialog dialog =
                new AppDialog.Builder(a)
                        .setTitle("导入或更新配置")
                        .setView(panel)
                        .setNegativeButton("取消", null)
                        .setNeutralButton("连接 Emby", (d, w) -> EmbyEngine.connect(a))
                        .setPositiveButton(
                                "导入输入内容",
                                (d, w) -> {
                                    String raw = input.getText().toString().trim();
                                    if (raw.isEmpty()) {
                                        a.toast("请输入配置网址或点击剪贴板导入");
                                        return;
                                    }
                                    importTextOrUrl(a, raw);
                                })
                        .create();

        pasteBtn.setOnClickListener(
                v -> {
                    dialog.dismiss();
                    pasteConfigFromClipboard(a);
                });
        fileBtn.setOnClickListener(
                v -> {
                    dialog.dismiss();
                    a.pickConfig();
                });
        liveBtn.setOnClickListener(
                v -> {
                    dialog.dismiss();
                    SourceController.showLiveImportDialog(a);
                });
        dialog.show();
    }

    static void pasteConfigFromClipboard(MainActivity a) {
        ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
        String text = "";
        if (cm != null && cm.hasPrimaryClip()) {
            ClipData clip = cm.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0 && clip.getItemAt(0).getText() != null) {
                text = clip.getItemAt(0).getText().toString().trim();
            }
        }
        if (text.isEmpty()) {
            a.toast("剪贴板为空，请先复制配置网址或源码内容");
            return;
        }
        importTextOrUrl(a, text);
    }

    static void importTextOrUrl(MainActivity a, String rawInput) {
        final String pasted = rawInput == null ? "" : rawInput.trim();
        if (pasted.isEmpty()) {
            a.toast("请输入有效的配置网址或源码内容");
            return;
        }
        a.toast("正在读取并解析配置…");
        a.worker.execute(
                () -> {
                    try {
                        String origin = a.configOrigin;
                        String rawJson = pasted;
                        if (Cms.isHttp(pasted)) {
                            origin = Cms.normalizeHttp(pasted);
                            String lowerOrigin = origin.toLowerCase();
                            if (lowerOrigin.endsWith(".zip") || lowerOrigin.contains(".zip?")) {
                                java.net.HttpURLConnection conn =
                                        (java.net.HttpURLConnection)
                                                new java.net.URL(origin).openConnection();
                                conn.setConnectTimeout(15000);
                                conn.setReadTimeout(25000);
                                conn.setRequestProperty("User-Agent", Cms.USER_AGENT);
                                byte[] bytes;
                                try (java.io.InputStream in = conn.getInputStream();
                                        java.io.ByteArrayOutputStream bos =
                                                new java.io.ByteArrayOutputStream()) {
                                    byte[] buf = new byte[8192];
                                    int n;
                                    while ((n = in.read(buf)) != -1) {
                                        if (bos.size() + n > 28 * 1024 * 1024)
                                            throw new IOException("远程包超过 28 MB 限制");
                                        bos.write(buf, 0, n);
                                    }
                                    bytes = bos.toByteArray();
                                } finally {
                                    conn.disconnect();
                                }
                                LocalSourcePackage pkg =
                                        LocalSourcePackage.open(a.getFilesDir(), bytes);
                                a.ui.post(
                                        () -> {
                                            try {
                                                a.saveConfig(pkg.config, pkg.origin);
                                                a.toast(
                                                        "压缩包配置导入成功（共 "
                                                                + pkg.config.sites.size()
                                                                + " 个片源）");
                                            } catch (Exception ex) {
                                                a.toast("保存配置失败：" + a.message(ex));
                                            }
                                        });
                                return;
                            } else {
                                rawJson = SourceController.fetchImport(a, origin);
                                origin = SourceController.importOrigin(origin);
                            }
                        }
                        try {
                            Cms.Config parsed = Cms.parseConfig(rawJson, origin);
                            final String savedOrigin = origin;
                            a.ui.post(
                                    () -> {
                                        try {
                                            a.saveConfig(parsed, savedOrigin);
                                            a.toast("配置导入成功（共 " + parsed.sites.size() + " 个片源）");
                                        } catch (Exception ex) {
                                            a.toast("保存配置失败：" + a.message(ex));
                                        }
                                    });
                        } catch (Exception parseEx) {
                            String trimmed = rawJson == null ? "" : rawJson.trim();
                            if (trimmed.startsWith("#EXTM3U")
                                    || trimmed.contains("#genre#")
                                    || trimmed.contains(",http://")
                                    || trimmed.contains(",https://")
                                    || trimmed.contains(",rtmp://")
                                    || trimmed.contains(",rtsp://")) {
                                SourceController.importLiveTextOrUrl(a, pasted);
                                return;
                            }
                            throw parseEx;
                        }
                    } catch (Exception e) {
                        a.ui.post(() -> a.toast("配置读取失败：" + a.message(e)));
                    }
                });
    }

    void render() {
        a.clearPlaybackLoading();
        a.cancelArtwork();
        a.cancelPageRequests();
        if (a.current != null) a.current.home = true;
        if (a.searchScreen != null) {
            a.searchScreen.close();
            a.searchScreen = null;
        }
        a.stopWeb();
        a.stopMagnet();
        a.releasePlayer();
        a.generation++;

        // Edge-to-edge transparent status bar over the iPhone emerald-obsidian ambient canvas
        a.applyMobileSystemBars(false);

        resolveActiveSite();

        a.root = new FrameLayout(a);
        a.root.setBackgroundColor(0xff070c0a);
        a.setContentView(a.root);

        // Layer 0: iOS 27 Emerald-Obsidian Ambient Background
        a.root.addView(new AmbientCanvasView(a), new FrameLayout.LayoutParams(-1, -1));

        float widthDp = a.windowWidth() / a.getResources().getDisplayMetrics().density;
        boolean tabletSideRail = widthDp >= 640 && !a.isPortrait();

        if (tabletSideRail) {
            LinearLayout mainRow = a.row();
            mainRow.setGravity(Gravity.TOP);
            a.root.addView(mainRow, new FrameLayout.LayoutParams(-1, -1));

            contentContainer = new FrameLayout(a);
            mainRow.addView(contentContainer, new LinearLayout.LayoutParams(0, -1, 1));

            FrameLayout railWrap = new FrameLayout(a);
            railWrap.setPadding(a.dp(8), statusBarHeight() + a.dp(12), a.dp(12), a.dp(12));
            LinearLayout sideRail = buildNavigationBar(true);
            FrameLayout.LayoutParams railLp =
                    new FrameLayout.LayoutParams(a.dp(72), a.dp(296), Gravity.CENTER_VERTICAL);
            railWrap.addView(sideRail, railLp);
            mainRow.addView(railWrap, new LinearLayout.LayoutParams(-2, -1));
        } else {
            // Layer 1: Full-bleed page container
            contentContainer = new FrameLayout(a);
            a.root.addView(contentContainer, new FrameLayout.LayoutParams(-1, -1));

            // Layer 2: Soft top status-bar edge fade (matching iPhone ShaderMask)
            TopEdgeFadeView topFade = new TopEdgeFadeView(a);
            topFade.setClickable(false);
            topFade.setFocusable(false);
            a.root.addView(
                    topFade,
                    new FrameLayout.LayoutParams(-1, statusBarHeight() + a.dp(24), Gravity.TOP));

            // Layer 3: Bottom edge subtle vignette behind the floating dock
            BottomEdgeFadeView bottomFade = new BottomEdgeFadeView(a);
            bottomFade.setClickable(false);
            bottomFade.setFocusable(false);
            a.root.addView(bottomFade, new FrameLayout.LayoutParams(-1, a.dp(92), Gravity.BOTTOM));

            // Layer 4: iPhone Floating Liquid Glass 4-Tab Pill Dock
            LinearLayout bottomDock = buildNavigationBar(false);
            int dockWidthPx = Math.min(a.dp(286), Math.max(a.dp(244), a.windowWidth() - a.dp(40)));
            FrameLayout.LayoutParams dockLp =
                    new FrameLayout.LayoutParams(
                            dockWidthPx, a.dp(64), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            dockLp.bottomMargin = a.dp(16);
            a.root.addView(bottomDock, dockLp);
        }

        switchTab(activeTab);
    }

    private void resolveActiveSite() {
        currentSite = null;
        if (a.config == null || a.config.sites.isEmpty()) return;
        String wanted = a.prefs.getString(Prefs.SELECTED_SITE, "");
        for (Cms.Site s : a.config.sites) {
            if (s.key.equals(wanted) && a.available(s)) {
                currentSite = s;
                return;
            }
        }
        for (Cms.Site s : a.config.sites) {
            if (a.available(s)) {
                currentSite = s;
                a.prefs.edit().putString(Prefs.SELECTED_SITE, s.key).apply();
                return;
            }
        }
    }

    /**
     * Builds the iPhone Portrait Header Row inside the scrollable Home tab (or standalone top bar):
     * Left brand logo + title, right Source selector capsule, Netdisk capsule, and circular Search
     * icon button.
     */
    private View buildHomeTopHeaderRow() {
        LinearLayout bar = a.row();
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(a.dp(16), a.dp(6), a.dp(16), a.dp(6));

        // Left: Brand Logo + Title
        LinearLayout brandRow = a.row();
        brandRow.setTag("home-brand");
        brandRow.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(a);
        logo.setImageResource(R.drawable.logo_mark);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(a.dp(28), a.dp(28));
        logoLp.rightMargin = a.dp(8);
        brandRow.addView(logo, logoLp);

        TextView brandTitle = a.text("Marco", 16, Color.WHITE);
        brandTitle.setTypeface(null, Typeface.BOLD);
        brandRow.addView(brandTitle);
        bar.addView(brandRow, new LinearLayout.LayoutParams(-2, -2));

        // Flexible middle slot: keeps `sitePill` right-aligned and guarantees it shrinks/truncates
        // before ever pushing `netdiskPill` or `searchCircle` off the right edge of the screen.
        FrameLayout siteSlot = new FrameLayout(a);
        LinearLayout.LayoutParams slotLp = new LinearLayout.LayoutParams(0, a.dp(34), 1f);
        slotLp.leftMargin = a.dp(8);
        slotLp.rightMargin = a.dp(6);
        bar.addView(siteSlot, slotLp);

        // Right 1: Site selector Liquid Glass capsule (`source-switcher`)
        String siteLabel = currentSite != null ? currentSite.name + " ▾" : "选择片源 ▾";
        TextView sitePill = a.text(siteLabel, 12.5f, Color.WHITE);
        sitePill.setTag("source-switcher");
        sitePill.setTypeface(null, Typeface.BOLD);
        sitePill.setSingleLine(true);
        sitePill.setEllipsize(TextUtils.TruncateAt.END);
        int maxSiteWidth = Math.max(a.dp(72), Math.min(a.dp(136), a.windowWidth() - a.dp(232)));
        sitePill.setMaxWidth(maxSiteWidth);
        sitePill.setGravity(Gravity.CENTER);
        sitePill.setPadding(a.dp(11), a.dp(7), a.dp(11), a.dp(7));
        a.glass(sitePill, 999);
        MobileDetailScreen.bindTouchFeedback(sitePill);
        sitePill.setOnClickListener(
                v -> {
                    if (a.config == null) showMobileImportDialog(a);
                    else a.chooseSources();
                });
        siteSlot.addView(
                sitePill,
                new FrameLayout.LayoutParams(-2, a.dp(34), Gravity.END | Gravity.CENTER_VERTICAL));

        // Right 2: Netdisk / Config Liquid Glass capsule
        TextView netdiskPill = a.text(a.config == null ? "＋配置" : "网盘", 12.5f, Color.WHITE);
        netdiskPill.setTag("home-netdisk-button");
        netdiskPill.setTypeface(null, Typeface.BOLD);
        netdiskPill.setGravity(Gravity.CENTER);
        netdiskPill.setPadding(a.dp(11), a.dp(7), a.dp(11), a.dp(7));
        a.glass(netdiskPill, 999);
        MobileDetailScreen.bindTouchFeedback(netdiskPill);
        netdiskPill.setOnClickListener(
                v -> {
                    if (a.config == null) showMobileImportDialog(a);
                    else AccountPanel.show(a, null);
                });
        LinearLayout.LayoutParams ndLp = new LinearLayout.LayoutParams(-2, a.dp(34));
        ndLp.rightMargin = a.dp(6);
        bar.addView(netdiskPill, ndLp);

        // Right 3: Circular Liquid Glass Search button (`nav-search`)
        FrameLayout searchCircle = new FrameLayout(a);
        searchCircle.setTag("nav-search");
        a.glass(searchCircle, 999);
        MobileDetailScreen.bindTouchFeedback(searchCircle);
        searchCircle.setOnClickListener(v -> switchTab(1));
        NavGlyph searchGlyph = new NavGlyph(a, 1);
        searchGlyph.setIconTint(0xfff3f4f6);
        searchCircle.addView(
                searchGlyph, new FrameLayout.LayoutParams(a.dp(17), a.dp(17), Gravity.CENTER));
        bar.addView(searchCircle, new LinearLayout.LayoutParams(a.dp(34), a.dp(34)));

        return bar;
    }

    /**
     * Builds the iOS 27 Floating Liquid Glass 4-Tab Pill Dock (`主页` · `直播` · `片库` · `设置`), with an
     * inner translucent glass lens on the active tab and continuous press-and-slide tab switching.
     */
    private LinearLayout buildNavigationBar(final boolean verticalRail) {
        final int touchSlop = a.dp(7);
        LinearLayout bar =
                new LinearLayout(a) {
                    private float downX;
                    private float downY;
                    private boolean sliding;
                    private int pendingTab = -1;

                    @Override
                    public boolean onInterceptTouchEvent(android.view.MotionEvent ev) {
                        int action = ev.getActionMasked();
                        if (action == android.view.MotionEvent.ACTION_DOWN) {
                            downX = ev.getX();
                            downY = ev.getY();
                            sliding = false;
                            pendingTab = -1;
                        } else if (action == android.view.MotionEvent.ACTION_MOVE) {
                            float delta =
                                    verticalRail
                                            ? Math.abs(ev.getY() - downY)
                                            : Math.abs(ev.getX() - downX);
                            if (delta > touchSlop) {
                                sliding = true;
                                return true;
                            }
                        }
                        return super.onInterceptTouchEvent(ev);
                    }

                    @Override
                    public boolean onTouchEvent(android.view.MotionEvent ev) {
                        int action = ev.getActionMasked();
                        if (action == android.view.MotionEvent.ACTION_DOWN
                                || action == android.view.MotionEvent.ACTION_MOVE) {
                            int count = DOCK_TAB_INDICES.length;
                            float pad = a.dp(6);
                            float span = (verticalRail ? getHeight() : getWidth()) - pad * 2f;
                            float coord = (verticalRail ? ev.getY() : ev.getX()) - pad;
                            if (span > 0 && count > 0) {
                                int slot =
                                        Math.max(
                                                0,
                                                Math.min(
                                                        count - 1,
                                                        (int) Math.floor((coord / span) * count)));
                                int targetTab = DOCK_TAB_INDICES[slot];
                                int currentDockTab =
                                        pendingTab >= 0
                                                ? pendingTab
                                                : (activeTab == 1 ? 0 : activeTab);
                                if (targetTab != currentDockTab) {
                                    performHapticFeedback(
                                            android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                                    pendingTab = targetTab;
                                }
                            }
                            return true;
                        }
                        if (action == android.view.MotionEvent.ACTION_UP
                                || action == android.view.MotionEvent.ACTION_CANCEL) {
                            sliding = false;
                            if (action == android.view.MotionEvent.ACTION_UP && pendingTab >= 0)
                                switchTab(pendingTab);
                            pendingTab = -1;
                            return true;
                        }
                        return super.onTouchEvent(ev);
                    }
                };
        bar.setOrientation(verticalRail ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        bar.setTag("home-dock");
        bar.setGravity(Gravity.CENTER);
        bar.setPadding(a.dp(6), a.dp(6), a.dp(6), a.dp(6));
        a.glass(bar, 26);
        bar.setElevation(a.dp(18));

        String[] navKeys = {"nav-home", "nav-search", "nav-live", "nav-favorites", "nav-settings"};

        for (int pos = 0; pos < DOCK_TAB_INDICES.length; pos++) {
            final int tabIdx = DOCK_TAB_INDICES[pos];
            LinearLayout tab = a.vertical();
            tab.setTag(navKeys[tabIdx]);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(a.dp(4), a.dp(4), a.dp(4), a.dp(4));
            MobileDetailScreen.bindTouchFeedback(tab);

            NavGlyph glyph = new NavGlyph(a, tabIdx);
            navIcons[tabIdx] = glyph;
            tab.addView(glyph, new LinearLayout.LayoutParams(a.dp(22), a.dp(22)));

            TextView label = a.text(TAB_NAMES[tabIdx], 10.5f, 0xffe5e7eb);
            label.setGravity(Gravity.CENTER);
            label.setPadding(0, a.dp(2), 0, 0);
            navLabels[tabIdx] = label;
            tab.addView(label, new LinearLayout.LayoutParams(-2, -2));

            tab.setOnClickListener(v -> switchTab(tabIdx));
            navTabs[tabIdx] = tab;

            LinearLayout.LayoutParams lp =
                    verticalRail
                            ? new LinearLayout.LayoutParams(-1, 0, 1f)
                            : new LinearLayout.LayoutParams(0, -1, 1f);
            bar.addView(tab, lp);
        }
        return bar;
    }

    private int homeScrollY;
    private ScrollView homeScroll;

    void switchTab(int index) {
        if (activeTab == 0 && homeScroll != null) homeScrollY = homeScroll.getScrollY();
        activeTab = Math.max(0, Math.min(4, index));
        a.cancelPageRequests();
        // When on Search tab (1), highlight Home (0) in the 4-tab dock
        int highlightedDockTab = activeTab == 1 ? 0 : activeTab;
        for (int pos = 0; pos < DOCK_TAB_INDICES.length; pos++) {
            int i = DOCK_TAB_INDICES[pos];
            boolean selected = i == highlightedDockTab;
            if (navTabs[i] != null) {
                if (selected) {
                    navTabs[i].setBackground(
                            glassBg(0x38ffffff, 0x1effffff, a.dp(20), a.dp(0.8f), 0x52ffffff));
                } else {
                    navTabs[i].setBackground(null);
                }
            }
            if (navLabels[i] != null) {
                navLabels[i].setTextColor(selected ? 0xff0a84ff : 0xffe5e7eb);
                navLabels[i].setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
            }
            if (navIcons[i] != null) {
                navIcons[i].setSelectedState(selected);
            }
        }
        contentContainer.removeAllViews();
        switch (activeTab) {
            case 0:
                renderHomeTab();
                break;
            case 1:
                renderSearchTab();
                break;
            case 2:
                renderLiveTab();
                break;
            case 3:
                renderMyTab();
                break;
            case 4:
                renderSettingsTab();
                break;
        }
    }

    // =========================================================================
    // TAB 0: IPHONE PORTRAIT HOME (Scrollable Header + Category Pills + 3-Col Grid)
    // =========================================================================
    private void renderHomeTab() {
        if (a.config == null || currentSite == null) {
            renderEmptyWelcome();
            return;
        }

        final ScrollView scrollView = new ScrollView(a);
        homeScroll = scrollView;
        scrollView.post(() -> scrollView.scrollTo(0, homeScrollY));
        scrollView.setFillViewport(true);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.setClipToPadding(false);
        a.scroll = scrollView;
        contentContainer.addView(scrollView, new FrameLayout.LayoutParams(-1, -1));

        final LinearLayout body = a.vertical();
        body.setPadding(0, statusBarHeight() + a.dp(8), 0, a.dp(106));
        scrollView.addView(body, new ScrollView.LayoutParams(-1, -2));

        // 1. Scrollable Top Header Row (Brand + Site Capsule + Netdisk Capsule + Search Icon)
        body.addView(buildHomeTopHeaderRow(), new LinearLayout.LayoutParams(-1, -2));

        // 2. Continue Watching Shelf ("继续观看")
        List<WatchHistory.Entry> history = a.watchHistory();
        if (!history.isEmpty()) {
            LinearLayout histHeader = a.row();
            histHeader.setGravity(Gravity.CENTER_VERTICAL);
            histHeader.setPadding(a.dp(16), a.dp(14), a.dp(16), a.dp(8));
            TextView hTitle = a.text("继续观看", 18, MainActivity.WHITE);
            hTitle.setTypeface(null, Typeface.BOLD);
            histHeader.addView(hTitle, new LinearLayout.LayoutParams(0, -2, 1));
            TextView moreHist = a.text("查看全部", 13, 0xff0a84ff);
            moreHist.setTypeface(null, Typeface.BOLD);
            MobileDetailScreen.bindTouchFeedback(moreHist);
            moreHist.setOnClickListener(
                    v -> {
                        mySubTab = 0;
                        switchTab(3);
                    });
            histHeader.addView(moreHist);
            body.addView(histHeader, new LinearLayout.LayoutParams(-1, -2));

            HorizontalScrollView histScroll = new HorizontalScrollView(a);
            histScroll.setHorizontalScrollBarEnabled(false);
            histScroll.setClipToPadding(false);
            histScroll.setPadding(a.dp(16), a.dp(2), a.dp(16), a.dp(2));
            LinearLayout histRow = a.row();
            histScroll.addView(histRow);
            for (int i = 0; i < Math.min(8, history.size()); i++) {
                final WatchHistory.Entry e = history.get(i);
                LinearLayout card = a.row();
                card.setTag("home-history:" + i);
                card.setGravity(Gravity.CENTER_VERTICAL);
                card.setPadding(a.dp(10), a.dp(10), a.dp(12), a.dp(10));
                card.setBackground(
                        glassBg(0xb81e2724, 0xb8151b19, a.dp(18), a.dp(0.8f), 0x2effffff));
                MobileDetailScreen.bindTouchFeedback(card);
                card.setOnClickListener(v -> a.resumeHistory(e));

                ImageView thumb = new ImageView(a);
                thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
                thumb.setBackground(a.bg(0xff232b28, 10));
                thumb.setClipToOutline(true);
                LinearLayout.LayoutParams thumbLp =
                        new LinearLayout.LayoutParams(a.dp(46), a.dp(64));
                thumbLp.rightMargin = a.dp(10);
                card.addView(thumb, thumbLp);
                if (e.poster != null && !e.poster.isEmpty()) a.poster(e.poster, thumb);

                LinearLayout meta = a.vertical();
                TextView t = a.text(e.title, 13.5f, MainActivity.WHITE);
                t.setTypeface(null, Typeface.BOLD);
                t.setSingleLine(true);
                t.setEllipsize(TextUtils.TruncateAt.END);
                meta.addView(t);

                TextView sub = a.text(a.historyLabel(e), 11.5f, 0xff9ca3af);
                sub.setSingleLine(true);
                sub.setEllipsize(TextUtils.TruncateAt.END);
                sub.setPadding(0, a.dp(3), 0, a.dp(7));
                meta.addView(sub);

                // Progress bar matching iPhone Continue Watching card
                FrameLayout progTrack = new FrameLayout(a);
                progTrack.setBackground(a.bg(0x33ffffff, 2));
                float frac =
                        e.duration > 0
                                ? Math.max(0.12f, Math.min(1f, (float) e.position / e.duration))
                                : 0.2f;
                View progFill = new View(a);
                progFill.setBackground(a.bg(0xff0a84ff, 2));
                progTrack.addView(
                        progFill,
                        new FrameLayout.LayoutParams(
                                a.dp(Math.round(126 * frac)), a.dp(3), Gravity.START));
                meta.addView(progTrack, new LinearLayout.LayoutParams(a.dp(126), a.dp(3)));

                card.addView(meta, new LinearLayout.LayoutParams(0, -2, 1));

                LinearLayout.LayoutParams cardLp =
                        new LinearLayout.LayoutParams(a.dp(212), a.dp(84));
                cardLp.rightMargin = a.dp(12);
                histRow.addView(card, cardLp);
            }
            body.addView(histScroll, new LinearLayout.LayoutParams(-1, -2));
        }

        // 3. 豆瓣热播榜货架（独立于当前片源，占据首页首屏原「今日首推」的位置）
        DoubanHot.attach(a, body);

        // 3. Horizontal Scrollable Category Pill Bar
        HorizontalScrollView catScroll = new HorizontalScrollView(a);
        catScroll.setHorizontalScrollBarEnabled(false);
        catScroll.setClipToPadding(false);
        catScroll.setPadding(a.dp(16), a.dp(14), a.dp(16), a.dp(10));
        final LinearLayout catRow = a.row();
        catRow.setGravity(Gravity.CENTER_VERTICAL);
        catScroll.addView(catRow);
        body.addView(catScroll, new LinearLayout.LayoutParams(-1, -2));

        // 5. Status & 3-Column Poster Grid Container
        final TextView statusText = a.text("正在加载片单…", 13, MainActivity.MUTED);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(a.dp(16), a.dp(24), a.dp(16), a.dp(24));
        body.addView(statusText, new LinearLayout.LayoutParams(-1, -2));

        final LinearLayout gridContainer = a.vertical();
        gridContainer.setPadding(a.dp(16), a.dp(2), a.dp(16), 0);
        body.addView(gridContainer, new LinearLayout.LayoutParams(-1, -2));

        final TextView loadMoreBtn = a.text("加载更多影片", 13.5f, MainActivity.WHITE);
        loadMoreBtn.setTag("home-load-more");
        loadMoreBtn.setTypeface(null, Typeface.BOLD);
        loadMoreBtn.setGravity(Gravity.CENTER);
        loadMoreBtn.setPadding(a.dp(20), a.dp(11), a.dp(20), a.dp(11));
        loadMoreBtn.setBackground(
                glassBg(0xcc262e2b, 0xb81a211f, a.dp(999), a.dp(0.8f), 0x33ffffff));
        loadMoreBtn.setVisibility(View.GONE);
        MobileDetailScreen.bindTouchFeedback(loadMoreBtn);
        LinearLayout.LayoutParams moreLp = new LinearLayout.LayoutParams(-1, -2);
        moreLp.setMargins(a.dp(16), a.dp(14), a.dp(16), a.dp(8));
        body.addView(loadMoreBtn, moreLp);

        final boolean[] loadingPage = {false};
        final Runnable[] fetchCategoryPage = new Runnable[1];
        final Runnable renderCategoryChips =
                new Runnable() {
                    @Override
                    public void run() {
                        catRow.removeAllViews();
                        addCategoryChip(catRow, "推荐", "", this, fetchCategoryPage[0]);
                        for (Cms.Category c : currentCategories) {
                            addCategoryChip(catRow, c.name, c.id, this, fetchCategoryPage[0]);
                        }
                    }
                };

        fetchCategoryPage[0] =
                () -> {
                    loadingPage[0] = true;
                    if (currentPage == 1) {
                        statusText.setVisibility(View.VISIBLE);
                        statusText.setText("正在加载…");
                        loadMoreBtn.setVisibility(View.GONE);
                    } else {
                        loadMoreBtn.setVisibility(View.VISIBLE);
                        loadMoreBtn.setText("正在加载更多…");
                    }
                    final Cms.Site siteSnapshot = currentSite;
                    final String catSnapshot = selectedCategoryId;
                    final int pageSnapshot = currentPage;
                    final int ticket = a.generation;
                    if (catSnapshot.isEmpty() && pageSnapshot == 1 && siteSnapshot != null) {
                        a.prefs.edit().putString(Prefs.LOADING_SITE, siteSnapshot.key).commit();
                    }
                    a.submitPage(
                            () -> {
                                try {
                                    SourceEngine eng = a.engine(siteSnapshot);
                                    List<Cms.Category> cats = currentCategories;
                                    Cms.Listing list;
                                    if (catSnapshot.isEmpty() && pageSnapshot == 1) {
                                        String raw = eng.home();
                                        cats = Cms.categories(raw);
                                        list =
                                                new JSONObject(raw).has("list")
                                                        ? Cms.listing(raw, siteSnapshot)
                                                        : new Cms.Listing();
                                        if (list.videos.isEmpty()) {
                                            try {
                                                list = Cms.listing(eng.homeVideos(), siteSnapshot);
                                            } catch (Throwable ignored) {
                                            }
                                        }
                                        if (list.videos.isEmpty() && !cats.isEmpty()) {
                                            list = Cms.listing(
                                                    eng.category(cats.get(0).id, 1), siteSnapshot);
                                        }
                                    } else {
                                        String targetCat =
                                                catSnapshot.isEmpty() && !cats.isEmpty()
                                                        ? cats.get(0).id
                                                        : catSnapshot;
                                        list = Cms.listing(
                                                eng.category(targetCat, pageSnapshot), siteSnapshot);
                                    }
                                    final List<Cms.Category> finalCats = cats;
                                    final Cms.Listing finalList = list;
                                    a.learnTitles(finalList.videos);
                                    a.ui.post(
                                            () -> {
                                                a.prefs
                                                        .edit()
                                                        .putString(Prefs.LOADING_SITE, "")
                                                        .apply();
                                                if (ticket != a.generation || a.isFinishing())
                                                    return;
                                                loadingPage[0] = false;
                                                currentCategories = finalCats;
                                                renderCategoryChips.run();
                                                if (pageSnapshot == 1) currentVideos.clear();
                                                for (Cms.Video v : finalList.videos) {
                                                    if (v.action == null || v.action.isEmpty()) {
                                                        currentVideos.add(v);
                                                    }
                                                }
                                                totalPages = Math.max(1, finalList.pageCount);
                                                statusText.setVisibility(
                                                        currentVideos.isEmpty()
                                                                ? View.VISIBLE
                                                                : View.GONE);
                                                if (currentVideos.isEmpty()) {
                                                    statusText.setText("当前分类暂无影片");
                                                }
                                                // 首页顶部固定位已由「豆瓣热播」货架承担，
                                                // 不再渲染「今日首推」大卡（避免重复占位）。
                                                renderVideoGrid(
                                                        gridContainer, siteSnapshot, currentVideos);
                                                if (currentPage < totalPages) {
                                                    loadMoreBtn.setVisibility(View.VISIBLE);
                                                    loadMoreBtn.setText(
                                                            "加载更多影片 ("
                                                                    + currentPage
                                                                    + "/"
                                                                    + totalPages
                                                                    + ")");
                                                } else {
                                                    loadMoreBtn.setVisibility(View.GONE);
                                                }
                                            });
                                } catch (Throwable e) {
                                    AppLog.w("MobileShell", "fetchCategoryPage failed", e);
                                    a.ui.post(
                                            () -> {
                                                a.prefs
                                                        .edit()
                                                        .putString(Prefs.LOADING_SITE, "")
                                                        .apply();
                                                if (ticket != a.generation || a.isFinishing())
                                                    return;
                                                loadingPage[0] = false;
                                                statusText.setVisibility(View.VISIBLE);
                                                statusText.setText("加载失败：" + a.message(e));
                                                loadMoreBtn.setVisibility(View.VISIBLE);
                                                loadMoreBtn.setText("点击重试");
                                            });
                                }
                            });
                };

        loadMoreBtn.setOnClickListener(
                v -> {
                    if (loadingPage[0]) return;
                    if (currentPage < totalPages) currentPage++;
                    fetchCategoryPage[0].run();
                });

        // Auto-load next page when scrolling near bottom (matching iPhone infinite scroll)
        scrollView
                .getViewTreeObserver()
                .addOnScrollChangedListener(
                        () -> {
                            if (loadingPage[0] || currentPage >= totalPages) return;
                            View child = scrollView.getChildAt(0);
                            if (child == null) return;
                            int diff =
                                    child.getBottom()
                                            - (scrollView.getHeight() + scrollView.getScrollY());
                            if (diff <= a.dp(240)) {
                                currentPage++;
                                fetchCategoryPage[0].run();
                            }
                        });

        renderCategoryChips.run();
        fetchCategoryPage[0].run();
    }

    /**
     * Builds a category pill 1:1 with iPhone `_buildCategoryPill`: white stadium pill with black
     * bold text when selected, translucent dark glass pill with white text when unselected.
     */
    private void addCategoryChip(
            LinearLayout row,
            String label,
            String catId,
            Runnable refreshChips,
            Runnable loadPage) {
        boolean active = selectedCategoryId.equals(catId);
        TextView chip = a.text(label, 13, active ? 0xff000000 : 0xfff3f4f6);
        chip.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(a.dp(15), a.dp(7), a.dp(15), a.dp(7));
        if (active) {
            chip.setBackground(glassBg(0xffffffff, 0xffeaeaf0, a.dp(999), a.dp(0.7f), 0xffffffff));
        } else {
            chip.setBackground(glassBg(0xcc262e2b, 0xb81a211f, a.dp(999), a.dp(0.7f), 0x2effffff));
        }
        MobileDetailScreen.bindTouchFeedback(chip);
        chip.setOnClickListener(
                v -> {
                    if (selectedCategoryId.equals(catId)) return;
                    selectedCategoryId = catId;
                    currentPage = 1;
                    refreshChips.run();
                    loadPage.run();
                });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = a.dp(8);
        row.addView(chip, lp);
    }

    /**
     * Renders the 3-column portrait poster grid matching the iPhone screenshot (`_buildVideoCard`):
     * `12dp` rounded `2:3` poster with a smooth bottom gradient scrim and right-aligned `remarks`
     * inside the poster bottom edge, plus two-line left-aligned `title` + `year · area` below.
     */
    private void renderVideoGrid(LinearLayout container, Cms.Site site, List<Cms.Video> videos) {
        container.removeAllViews();
        float widthDp = a.windowWidth() / a.getResources().getDisplayMetrics().density;
        int cols = widthDp >= 720 ? 5 : (widthDp >= 520 ? 4 : 3);
        int horizontalPaddingDp = widthDp >= 640 && !a.isPortrait() ? 116 : 32;
        int cardWidthDp =
                Math.max(92, (int) ((widthDp - horizontalPaddingDp - (cols - 1) * 12) / cols));
        int posterHeightDp = Math.round(cardWidthDp * 1.48f);

        LinearLayout currentRow = null;
        for (int i = 0; i < videos.size(); i++) {
            if (i % cols == 0) {
                currentRow = a.row();
                currentRow.setGravity(Gravity.TOP);
                container.addView(currentRow, new LinearLayout.LayoutParams(-1, -2));
            }
            final Cms.Video v = videos.get(i);
            LinearLayout card = a.vertical();
            card.setTag(i == 0 ? "primary" : "video:" + v.id);
            MobileDetailScreen.bindTouchFeedback(card);
            card.setOnClickListener(view -> a.openListing(site, v));

            FrameLayout art = new FrameLayout(a);
            art.setBackground(glassBg(0xff1c2421, 0xff141a18, a.dp(12), a.dp(0.6f), 0x26ffffff));
            art.setClipToOutline(true);

            ImageView img = new ImageView(a);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            art.addView(img, new FrameLayout.LayoutParams(-1, -1));
            if (v.poster != null && !v.poster.isEmpty()) a.poster(v.poster, img);

            // Bottom gradient scrim + bottom-right remarks label inside poster (1:1 with iPhone)
            if (v.remarks != null && !v.remarks.trim().isEmpty()) {
                FrameLayout bottomScrim = new FrameLayout(a);
                bottomScrim.setPadding(a.dp(7), a.dp(16), a.dp(7), a.dp(5));
                bottomScrim.setBackground(
                        new GradientDrawable(
                                GradientDrawable.Orientation.TOP_BOTTOM,
                                new int[] {0x00000000, 0xc8000000}));
                TextView badge = a.text(v.remarks.trim(), 10, 0xfff5f7fa);
                badge.setTypeface(null, Typeface.BOLD);
                badge.setSingleLine(true);
                badge.setEllipsize(TextUtils.TruncateAt.END);
                badge.setGravity(Gravity.END);
                bottomScrim.addView(
                        badge, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.END));
                art.addView(bottomScrim, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
            }

            card.addView(art, new LinearLayout.LayoutParams(-1, a.dp(posterHeightDp)));

            // Line 1: Title (bold 13sp white)
            TextView title = a.text(v.title, 13, 0xfff5f7fa);
            title.setTypeface(null, Typeface.BOLD);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setPadding(a.dp(1), a.dp(7), a.dp(1), 0);
            card.addView(title);

            // Line 2: Subtitle (`year · area` or fallback to `remarks` / `site.name`, 11sp muted)
            String subtitleText = formatCardSubtitle(v, site);
            TextView sub = a.text(subtitleText, 11, 0xff8e959e);
            sub.setSingleLine(true);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            sub.setPadding(a.dp(1), a.dp(2), a.dp(1), a.dp(2));
            card.addView(sub);

            LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(0, -2, 1);
            int colIdx = i % cols;
            cellLp.leftMargin = colIdx == 0 ? 0 : a.dp(5);
            cellLp.rightMargin = colIdx == cols - 1 ? 0 : a.dp(5);
            cellLp.topMargin = a.dp(4);
            cellLp.bottomMargin = a.dp(14);
            currentRow.addView(card, cellLp);
        }
        if (currentRow != null) {
            int rem = videos.size() % cols;
            if (rem > 0) {
                for (int i = rem; i < cols; i++) {
                    LinearLayout.LayoutParams blankLp = new LinearLayout.LayoutParams(0, 1, 1);
                    blankLp.leftMargin = a.dp(5);
                    blankLp.rightMargin = i == cols - 1 ? 0 : a.dp(5);
                    currentRow.addView(new View(a), blankLp);
                }
            }
        }
    }

    private static String formatCardSubtitle(Cms.Video v, Cms.Site site) {
        String year = v.year == null ? "" : v.year.trim();
        String area = v.area == null ? "" : v.area.trim();
        String tag = v.tag == null ? "" : v.tag.trim();
        StringBuilder sb = new StringBuilder();
        if (!year.isEmpty()) sb.append(year);
        if (!area.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(area);
        } else if (!tag.isEmpty() && !"folder".equalsIgnoreCase(tag)) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(tag);
        }
        if (sb.length() > 0) return sb.toString();
        if (v.remarks != null && !v.remarks.trim().isEmpty()) return v.remarks.trim();
        return site != null && site.name != null ? site.name : "";
    }

    private void renderEmptyWelcome() {
        ScrollView sv = new ScrollView(a);
        sv.setFillViewport(true);
        sv.setClipToPadding(false);
        contentContainer.addView(sv, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout wrap = a.vertical();
        wrap.setGravity(Gravity.CENTER_VERTICAL);
        wrap.setPadding(a.dp(22), statusBarHeight() + a.dp(20), a.dp(22), a.dp(110));
        sv.addView(wrap, new ScrollView.LayoutParams(-1, -2));

        LinearLayout card = a.vertical();
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(a.dp(24), a.dp(26), a.dp(24), a.dp(26));
        card.setBackground(glassBg(0xd818221e, 0xd8101715, a.dp(26), a.dp(0.8f), 0x33ffffff));
        wrap.addView(card, new LinearLayout.LayoutParams(-1, -2));

        ImageView logo = new ImageView(a);
        logo.setImageResource(R.drawable.logo_mark);
        card.addView(logo, new LinearLayout.LayoutParams(a.dp(64), a.dp(64)));
        a.gap(card, 12);

        TextView heading = a.text("把今晚，留给好故事。", 23, MainActivity.WHITE);
        heading.setTypeface(null, Typeface.BOLD);
        heading.setGravity(Gravity.CENTER);
        card.addView(heading);
        a.gap(card, 10);

        TextView desc =
                a.text("导入你自己合法获取的影视配置，支持单仓/多仓配置地址、JSON 文本及手机剪贴板一键导入。", 13.5f, MainActivity.MUTED);
        desc.setGravity(Gravity.CENTER);
        desc.setLineSpacing(a.dp(4), 1f);
        card.addView(desc);
        a.gap(card, 22);

        LinearLayout urlRow = a.row();
        final EditText urlInput = new EditText(a);
        urlInput.setHint("输入或长按粘贴配置网址 https://…");
        urlInput.setHintTextColor(0xff6b7280);
        urlInput.setTextColor(MainActivity.WHITE);
        urlInput.setTextSize(13);
        urlInput.setSingleLine(true);
        urlInput.setShowSoftInputOnFocus(true);
        urlInput.setLongClickable(true);
        urlInput.setTextIsSelectable(true);
        urlInput.setPadding(a.dp(14), a.dp(10), a.dp(14), a.dp(10));
        urlInput.setBackground(a.bg(0xff1e2724, 999));
        LinearLayout.LayoutParams urlLp = new LinearLayout.LayoutParams(0, -2, 1);
        urlLp.rightMargin = a.dp(8);
        urlRow.addView(urlInput, urlLp);

        TextView goBtn = a.text("导入", 13, Color.WHITE);
        goBtn.setTypeface(null, Typeface.BOLD);
        goBtn.setGravity(Gravity.CENTER);
        goBtn.setPadding(a.dp(18), a.dp(10), a.dp(18), a.dp(10));
        goBtn.setBackground(a.bg(0xff0a84ff, 999));
        MobileDetailScreen.bindTouchFeedback(goBtn);
        goBtn.setOnClickListener(v -> importTextOrUrl(a, urlInput.getText().toString()));
        urlRow.addView(goBtn);
        card.addView(urlRow, new LinearLayout.LayoutParams(-1, -2));
        a.gap(card, 12);

        addMobileActionBtn(
                card, "一键粘贴剪贴板并导入", 0xff0a84ff, Color.WHITE, () -> pasteConfigFromClipboard(a));
        a.gap(card, 10);
        addMobileActionBtn(
                card,
                "选择本地配置或本地包 (.json/.js/.py/.zip)",
                0xff1e2724,
                MainActivity.WHITE,
                a::pickConfig);
        a.gap(card, 10);
        addMobileActionBtn(
                card,
                "导入电视直播源 (M3U / TXT / JSON)",
                0xff1e2724,
                MainActivity.WHITE,
                () -> SourceController.showLiveImportDialog(a));

        a.gap(wrap, 18);
        TextView legal = a.text("本产品仅为播放工具，不内置、不提供、不托管任何影视资源或配置地址。", 11.5f, 0xff6b7280);
        legal.setGravity(Gravity.CENTER);
        legal.setLineSpacing(a.dp(3), 1f);
        wrap.addView(legal);
    }

    private void addMobileActionBtn(
            LinearLayout parent, String text, int bgColor, int textColor, Runnable onClick) {
        TextView btn = a.text(text, 14, textColor);
        btn.setTypeface(null, Typeface.BOLD);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(a.dp(16), a.dp(12), a.dp(16), a.dp(12));
        btn.setBackground(a.bg(bgColor, 999));
        MobileDetailScreen.bindTouchFeedback(btn);
        btn.setOnClickListener(v -> onClick.run());
        parent.addView(btn, new LinearLayout.LayoutParams(-1, -2));
    }

    // =========================================================================
    // TAB 1: MOBILE SEARCH (System Soft Keyboard + Multi-Site Live Results)
    // =========================================================================
    private void renderSearchTab() {
        LinearLayout page = a.vertical();
        page.setPadding(a.dp(16), statusBarHeight() + a.dp(10), a.dp(16), 0);
        contentContainer.addView(page, new FrameLayout.LayoutParams(-1, -1));

        // Top row: back circle + search capsule
        LinearLayout topBar = a.row();
        topBar.setGravity(Gravity.CENTER_VERTICAL);

        TextView backBtn = a.text("‹", 22, Color.WHITE);
        backBtn.setGravity(Gravity.CENTER);
        backBtn.setBackground(glassBg(0xb034343a, 0xc01e1e22, a.dp(999), a.dp(0.8f), 0x38ffffff));
        MobileDetailScreen.bindTouchFeedback(backBtn);
        backBtn.setOnClickListener(v -> switchTab(0));
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(a.dp(38), a.dp(38));
        backLp.rightMargin = a.dp(10);
        topBar.addView(backBtn, backLp);

        LinearLayout inputRow = a.row();
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        inputRow.setPadding(a.dp(14), a.dp(3), a.dp(5), a.dp(3));
        inputRow.setBackground(glassBg(0xd4222a27, 0xc4181e1c, a.dp(999), a.dp(0.8f), 0x33ffffff));

        final EditText input = new EditText(a);
        input.setHint("搜索片名、演员或拼音首字母…");
        input.setHintTextColor(0xff7c858e);
        input.setTextColor(MainActivity.WHITE);
        input.setTextSize(14);
        input.setSingleLine(true);
        input.setBackgroundColor(Color.TRANSPARENT);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setPadding(a.dp(2), a.dp(7), a.dp(8), a.dp(7));
        if (!pendingSearchKeyword.isEmpty()) {
            input.setText(pendingSearchKeyword);
            input.setSelection(pendingSearchKeyword.length());
        }
        inputRow.addView(input, new LinearLayout.LayoutParams(0, -2, 1));

        TextView clearBtn = a.text("✕", 13, MainActivity.MUTED);
        clearBtn.setGravity(Gravity.CENTER);
        clearBtn.setPadding(a.dp(8), a.dp(6), a.dp(8), a.dp(6));
        MobileDetailScreen.bindTouchFeedback(clearBtn);
        inputRow.addView(clearBtn);

        TextView searchBtn = a.text("搜索", 13, Color.WHITE);
        searchBtn.setTypeface(null, Typeface.BOLD);
        searchBtn.setGravity(Gravity.CENTER);
        searchBtn.setPadding(a.dp(14), a.dp(7), a.dp(14), a.dp(7));
        searchBtn.setBackground(a.bg(0xff0a84ff, 999));
        MobileDetailScreen.bindTouchFeedback(searchBtn);
        inputRow.addView(searchBtn);

        topBar.addView(inputRow, new LinearLayout.LayoutParams(0, -2, 1));
        page.addView(topBar, new LinearLayout.LayoutParams(-1, -2));

        // Search history chips
        final LinearLayout historyWrap = a.vertical();
        page.addView(historyWrap, new LinearLayout.LayoutParams(-1, -2));

        final TextView searchStatus = a.text("输入关键词搜索全部已启用的片源", 12, MainActivity.MUTED);
        searchStatus.setPadding(a.dp(4), a.dp(10), a.dp(4), a.dp(8));
        page.addView(searchStatus);

        final ScrollView resultScroll = new ScrollView(a);
        resultScroll.setFillViewport(true);
        resultScroll.setVerticalScrollBarEnabled(false);
        resultScroll.setClipToPadding(false);
        resultScroll
                .getViewTreeObserver()
                .addOnScrollChangedListener(
                        () -> {
                            cachedSearchScrollY = resultScroll.getScrollY();
                        });
        page.addView(resultScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        final LinearLayout resultList = a.vertical();
        resultList.setPadding(0, 0, 0, a.dp(106));
        resultScroll.addView(resultList, new ScrollView.LayoutParams(-1, -2));

        clearBtn.setOnClickListener(
                v -> {
                    input.setText("");
                    cachedSearchKeyword = "";
                    cachedSearchStatus = "";
                    cachedSearchHits.clear();
                    cachedSearchScrollY = 0;
                    resultList.removeAllViews();
                    searchStatus.setText("输入关键词搜索全部已启用的片源");
                });

        final Runnable[] triggerSearch = new Runnable[1];
        final Runnable renderHistoryChips =
                () -> {
                    historyWrap.removeAllViews();
                    try {
                        JSONArray arr =
                                new JSONArray(a.prefs.getString(Prefs.GLOBAL_SEARCH_HISTORY, "[]"));
                        if (arr.length() == 0) return;
                        LinearLayout hRow = a.row();
                        hRow.setPadding(a.dp(2), a.dp(10), a.dp(2), a.dp(4));
                        TextView hLabel = a.text("历史搜索", 12, MainActivity.MUTED);
                        hRow.addView(hLabel, new LinearLayout.LayoutParams(0, -2, 1));
                        TextView clearHist = a.text("清空", 12, 0xff0a84ff);
                        MobileDetailScreen.bindTouchFeedback(clearHist);
                        clearHist.setOnClickListener(
                                v -> {
                                    a.prefs
                                            .edit()
                                            .putString(Prefs.GLOBAL_SEARCH_HISTORY, "[]")
                                            .apply();
                                    historyWrap.removeAllViews();
                                });
                        hRow.addView(clearHist);
                        historyWrap.addView(hRow);

                        HorizontalScrollView chipScroll = new HorizontalScrollView(a);
                        chipScroll.setHorizontalScrollBarEnabled(false);
                        LinearLayout chipRow = a.row();
                        chipScroll.addView(chipRow);
                        for (int i = 0; i < Math.min(12, arr.length()); i++) {
                            final String kw = arr.optString(i);
                            if (kw.isEmpty()) continue;
                            TextView chip = a.text(kw, 12, 0xffe5e7eb);
                            chip.setPadding(a.dp(13), a.dp(6), a.dp(13), a.dp(6));
                            chip.setBackground(
                                    glassBg(
                                            0xcc262e2b,
                                            0xb81a211f,
                                            a.dp(999),
                                            a.dp(0.7f),
                                            0x2effffff));
                            MobileDetailScreen.bindTouchFeedback(chip);
                            chip.setOnClickListener(
                                    v -> {
                                        input.setText(kw);
                                        input.setSelection(kw.length());
                                        triggerSearch[0].run();
                                    });
                            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                            lp.rightMargin = a.dp(8);
                            chipRow.addView(chip, lp);
                        }
                        historyWrap.addView(chipScroll);
                    } catch (Exception ignored) {
                    }
                };

        triggerSearch[0] =
                () -> {
                    String kw = input.getText().toString().trim();
                    if (kw.isEmpty()) {
                        a.toast("请输入搜索关键词");
                        return;
                    }
                    if (a.config == null || a.config.sites.isEmpty()) {
                        showMobileImportDialog(a);
                        return;
                    }
                    // Hide keyboard
                    try {
                        InputMethodManager imm =
                                (InputMethodManager)
                                        a.getSystemService(Context.INPUT_METHOD_SERVICE);
                        if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
                    } catch (Exception ignored) {
                    }

                    // Save to search history
                    try {
                        JSONArray old =
                                new JSONArray(a.prefs.getString(Prefs.GLOBAL_SEARCH_HISTORY, "[]"));
                        JSONArray updated = new JSONArray();
                        updated.put(kw);
                        for (int i = 0; i < old.length() && updated.length() < 16; i++) {
                            String item = old.optString(i);
                            if (!item.isEmpty() && !item.equals(kw)) updated.put(item);
                        }
                        a.prefs
                                .edit()
                                .putString(Prefs.GLOBAL_SEARCH_HISTORY, updated.toString())
                                .apply();
                    } catch (Exception ignored) {
                    }
                    renderHistoryChips.run();

                    a.cancelPageRequests();
                    resultList.removeAllViews();
                    cachedSearchKeyword = kw;
                    cachedSearchHits.clear();
                    cachedSearchScrollY = 0;

                    List<Cms.Site> searchableSites = new ArrayList<>();
                    for (Cms.Site s : SourcePreferences.prioritized(a, a.config.sites)) {
                        if (a.available(s) && s.searchable) searchableSites.add(s);
                    }
                    if (searchableSites.isEmpty()) {
                        cachedSearchStatus = "当前没有可搜索的片源";
                        searchStatus.setText(cachedSearchStatus);
                        return;
                    }
                    cachedSearchStatus = "正在并发搜索 " + searchableSites.size() + " 个片源…";
                    searchStatus.setText(cachedSearchStatus);
                    final int ticket = a.generation;
                    final int[] completed = {0};
                    final int[] hitCount = {0};

                    for (final Cms.Site site : searchableSites) {
                        Future<?> f =
                                RequestScope.submit(
                                        a.worker,
                                        () -> {
                                            List<Cms.Video> matches = new ArrayList<>();
                                            try {
                                                Cms.Listing l =
                                                        Cms.listing(
                                                                a.engine(site).search(kw, 1), site);
                                                for (Cms.Video v : l.videos) {
                                                    if (v.action == null || v.action.isEmpty()) {
                                                        matches.add(v);
                                                    }
                                                }
                                            } catch (Exception ignored) {
                                            }
                                            a.ui.post(
                                                    () -> {
                                                        if (ticket != a.generation
                                                                || a.isFinishing()) return;
                                                        completed[0]++;
                                                        for (final Cms.Video v : matches) {
                                                            hitCount[0]++;
                                                            if (kw.equals(cachedSearchKeyword)) {
                                                                cachedSearchHits.add(
                                                                        new CachedSearchHit(
                                                                                site, v));
                                                            }
                                                            resultList.addView(
                                                                    buildSearchResultRow(site, v));
                                                        }
                                                        if (completed[0] < searchableSites.size()) {
                                                            cachedSearchStatus =
                                                                    "已找到 "
                                                                            + hitCount[0]
                                                                            + " 条结果（已完成 "
                                                                            + completed[0]
                                                                            + "/"
                                                                            + searchableSites.size()
                                                                            + " 个片源）";
                                                        } else {
                                                            cachedSearchStatus =
                                                                    hitCount[0] == 0
                                                                            ? "未找到与“" + kw
                                                                                    + "”相关的影片"
                                                                            : "搜索完成，共找到 "
                                                                                    + hitCount[0]
                                                                                    + " 条结果（点击任意线路进入，返回可继续切换）";
                                                        }
                                                        searchStatus.setText(cachedSearchStatus);
                                                    });
                                        });
                        a.pageRequests.add(f);
                    }
                };

        searchBtn.setOnClickListener(v -> triggerSearch[0].run());
        input.setOnEditorActionListener(
                (v, actionId, event) -> {
                    if (actionId == EditorInfo.IME_ACTION_SEARCH
                            || (event != null
                                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                                    && event.getAction() == KeyEvent.ACTION_DOWN)) {
                        triggerSearch[0].run();
                        return true;
                    }
                    return false;
                });

        renderHistoryChips.run();
        if (!pendingSearchKeyword.isEmpty()) {
            String wantedKw = pendingSearchKeyword;
            pendingSearchKeyword = "";
            input.setText(wantedKw);
            input.setSelection(wantedKw.length());
            if (wantedKw.equals(cachedSearchKeyword) && !cachedSearchHits.isEmpty()) {
                for (CachedSearchHit hit : cachedSearchHits) {
                    resultList.addView(buildSearchResultRow(hit.site, hit.video));
                }
                searchStatus.setText(
                        cachedSearchStatus.isEmpty()
                                ? "已保留搜索结果，共 " + cachedSearchHits.size() + " 条"
                                : cachedSearchStatus);
                final int savedScroll = cachedSearchScrollY;
                if (savedScroll > 0) {
                    resultScroll.post(() -> resultScroll.scrollTo(0, savedScroll));
                }
            } else {
                triggerSearch[0].run();
            }
        } else if (!cachedSearchKeyword.isEmpty()) {
            input.setText(cachedSearchKeyword);
            input.setSelection(cachedSearchKeyword.length());
            for (CachedSearchHit hit : cachedSearchHits) {
                resultList.addView(buildSearchResultRow(hit.site, hit.video));
            }
            searchStatus.setText(
                    cachedSearchStatus.isEmpty()
                            ? "已保留搜索结果，共 " + cachedSearchHits.size() + " 条"
                            : cachedSearchStatus);
            final int savedScroll = cachedSearchScrollY;
            if (savedScroll > 0) {
                resultScroll.post(() -> resultScroll.scrollTo(0, savedScroll));
            }
        }
    }

    private View buildSearchResultRow(final Cms.Site site, final Cms.Video v) {
        LinearLayout row = a.row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(a.dp(10), a.dp(10), a.dp(12), a.dp(10));
        row.setBackground(glassBg(0xb81b2320, 0xb8131917, a.dp(16), a.dp(0.7f), 0x26ffffff));
        MobileDetailScreen.bindTouchFeedback(row);
        row.setOnClickListener(
                view -> {
                    activeTab = 1;
                    a.openListing(site, v);
                });

        ImageView poster = new ImageView(a);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setBackground(a.bg(0xff232b28, 10));
        poster.setClipToOutline(true);
        LinearLayout.LayoutParams posterLp = new LinearLayout.LayoutParams(a.dp(60), a.dp(86));
        posterLp.rightMargin = a.dp(12);
        row.addView(poster, posterLp);
        if (v.poster != null && !v.poster.isEmpty()) a.poster(v.poster, poster);

        LinearLayout info = a.vertical();
        row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));

        TextView title = a.text(v.title, 15, MainActivity.WHITE);
        title.setTypeface(null, Typeface.BOLD);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(title);

        TextView badge = a.text("来源：" + site.name, 12, 0xff0a84ff);
        badge.setTypeface(null, Typeface.BOLD);
        badge.setPadding(0, a.dp(4), 0, 0);
        info.addView(badge);

        String sub =
                ((v.year == null ? "" : v.year)
                                + " "
                                + (v.area == null ? "" : v.area)
                                + " "
                                + (v.remarks == null ? "" : v.remarks))
                        .trim();
        if (!sub.isEmpty()) {
            TextView subView = a.text(sub, 12, MainActivity.MUTED);
            subView.setPadding(0, a.dp(4), 0, 0);
            subView.setSingleLine(true);
            subView.setEllipsize(TextUtils.TruncateAt.END);
            info.addView(subView);
        }

        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
        rowLp.bottomMargin = a.dp(10);
        row.setLayoutParams(rowLp);
        return row;
    }

    // =========================================================================
    // TAB 2: MOBILE LIVE TV (Two-Tier Group Pills + Multi-Column Card Grid)
    // =========================================================================
    private void renderLiveTab() {
        LinearLayout page = a.vertical();
        contentContainer.addView(page, new FrameLayout.LayoutParams(-1, -1));

        final List<LiveCatalog.Source> sources = LiveController.sourcesFor(a);
        if (sources.isEmpty()) {
            page.setPadding(a.dp(24), statusBarHeight() + a.dp(24), a.dp(24), 0);
            renderCenteredHint(page, "当前未配置直播源（支持 M3U / TXT / JSON）");
            addMobileActionBtn(
                    page,
                    "导入电视直播源 (M3U / TXT / JSON)",
                    0xff0a84ff,
                    Color.WHITE,
                    () -> SourceController.showLiveImportDialog(a));
            return;
        }

        // Row 1: Top Header with Live Source Selector Pills + Quick Channel Filter
        LinearLayout topHeader = a.vertical();
        topHeader.setPadding(a.dp(16), statusBarHeight() + a.dp(10), a.dp(16), a.dp(8));
        page.addView(topHeader, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout titleSearchRow = a.row();
        titleSearchRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView titleView = a.text("电视直播", 22, Color.WHITE);
        titleView.setTypeface(null, Typeface.BOLD);
        titleSearchRow.addView(titleView, new LinearLayout.LayoutParams(0, -2, 1));

        final EditText searchInput = new EditText(a);
        searchInput.setHint("搜索频道…");
        searchInput.setHintTextColor(0xff7c858e);
        searchInput.setTextColor(Color.WHITE);
        searchInput.setTextSize(12.5f);
        searchInput.setSingleLine(true);
        searchInput.setPadding(a.dp(13), a.dp(6), a.dp(13), a.dp(6));
        searchInput.setBackground(
                glassBg(0xd4222a27, 0xc4181e1c, a.dp(999), a.dp(0.8f), 0x33ffffff));
        titleSearchRow.addView(searchInput, new LinearLayout.LayoutParams(a.dp(168), -2));
        topHeader.addView(titleSearchRow, new LinearLayout.LayoutParams(-1, -2));

        HorizontalScrollView srcScroll = new HorizontalScrollView(a);
        srcScroll.setHorizontalScrollBarEnabled(false);
        srcScroll.setPadding(0, a.dp(10), 0, a.dp(2));
        final LinearLayout srcRow = a.row();
        srcScroll.addView(srcRow);
        topHeader.addView(srcScroll, new LinearLayout.LayoutParams(-1, -2));

        // Row 2: Horizontal Channel Group Selector Pills
        HorizontalScrollView groupScroll = new HorizontalScrollView(a);
        groupScroll.setHorizontalScrollBarEnabled(false);
        groupScroll.setPadding(0, a.dp(6), 0, a.dp(2));
        final LinearLayout groupRow = a.row();
        groupScroll.addView(groupRow);
        topHeader.addView(groupScroll, new LinearLayout.LayoutParams(-1, -2));

        final TextView stateView = a.text("正在加载直播频道…", 13, MainActivity.MUTED);
        stateView.setPadding(a.dp(16), a.dp(16), a.dp(16), a.dp(16));
        page.addView(stateView);

        // Main Multi-Column Channel Card Grid Area
        ScrollView channelScroll = new ScrollView(a);
        channelScroll.setVerticalScrollBarEnabled(false);
        channelScroll.setClipToPadding(false);
        final LinearLayout channelGridCol = a.vertical();
        channelGridCol.setPadding(a.dp(16), a.dp(10), a.dp(16), a.dp(106));
        channelScroll.addView(channelGridCol);
        page.addView(channelScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        final int[] activeSrcIdx = {0};
        final int[] activeGroupIdx = {0};
        final LiveCatalog.Catalog[] activeCatalog = {null};
        final Runnable[] loadSource = new Runnable[1];

        searchInput.addTextChangedListener(
                new android.text.TextWatcher() {
                    @Override
                    public void beforeTextChanged(
                            CharSequence s, int start, int count, int after) {}

                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {}

                    @Override
                    public void afterTextChanged(android.text.Editable s) {
                        if (activeCatalog[0] != null) {
                            renderLiveGroupsAndChannels(
                                    sources.get(activeSrcIdx[0]),
                                    activeCatalog[0],
                                    activeGroupIdx,
                                    s.toString().trim(),
                                    groupRow,
                                    channelGridCol);
                        }
                    }
                });

        loadSource[0] =
                () -> {
                    srcRow.removeAllViews();
                    for (int i = 0; i < sources.size(); i++) {
                        final int idx = i;
                        boolean active = i == activeSrcIdx[0];
                        TextView chip =
                                a.text(sources.get(i).name, 12, active ? Color.WHITE : 0xffd1d5db);
                        chip.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
                        chip.setPadding(a.dp(13), a.dp(5), a.dp(13), a.dp(5));
                        chip.setBackground(
                                active
                                        ? a.bg(0xff0a84ff, 999)
                                        : glassBg(
                                                0xcc262e2b,
                                                0xb81a211f,
                                                a.dp(999),
                                                a.dp(0.7f),
                                                0x2effffff));
                        MobileDetailScreen.bindTouchFeedback(chip);
                        chip.setOnClickListener(
                                v -> {
                                    activeSrcIdx[0] = idx;
                                    activeGroupIdx[0] = 0;
                                    loadSource[0].run();
                                });
                        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                        lp.rightMargin = a.dp(8);
                        srcRow.addView(chip, lp);
                    }
                    TextView importLiveChip = a.text("＋ 导入直播", 12, 0xff5ac8fa);
                    importLiveChip.setTypeface(null, Typeface.BOLD);
                    importLiveChip.setPadding(a.dp(13), a.dp(5), a.dp(13), a.dp(5));
                    importLiveChip.setBackground(
                            glassBg(0xcc262e2b, 0xb81a211f, a.dp(999), a.dp(0.7f), 0x2effffff));
                    MobileDetailScreen.bindTouchFeedback(importLiveChip);
                    importLiveChip.setOnClickListener(
                            v -> SourceController.showLiveImportDialog(a));
                    srcRow.addView(importLiveChip, new LinearLayout.LayoutParams(-2, -2));

                    groupRow.removeAllViews();
                    channelGridCol.removeAllViews();
                    stateView.setVisibility(View.VISIBLE);
                    stateView.setText("正在加载频道列表…");
                    final LiveCatalog.Source selectedSource = sources.get(activeSrcIdx[0]);
                    final int ticket = a.generation;
                    a.submitPage(
                            () -> {
                                try {
                                    if (selectedSource.isLocalProxy()) {
                                        if (!a.prefs.getBoolean(Prefs.EXPERIMENTAL_CSP, true))
                                            throw new IOException("请先在设置中开启实验性扩展支持");
                                        if (a.config != null) a.ensureProxy(a.config);
                                        selectedSource.url = LocalProxy.rewrite(selectedSource.url);
                                    }
                                    final LiveCatalog.Catalog catalog =
                                            LiveCatalog.load(selectedSource);
                                    a.ui.post(
                                            () -> {
                                                if (ticket != a.generation || a.isFinishing())
                                                    return;
                                                activeCatalog[0] = catalog;
                                                a.liveSession =
                                                        new LiveSession(selectedSource, catalog);
                                                stateView.setVisibility(View.GONE);
                                                renderLiveGroupsAndChannels(
                                                        selectedSource,
                                                        catalog,
                                                        activeGroupIdx,
                                                        searchInput.getText().toString().trim(),
                                                        groupRow,
                                                        channelGridCol);
                                            });
                                } catch (Exception e) {
                                    a.ui.post(
                                            () -> {
                                                if (ticket != a.generation || a.isFinishing())
                                                    return;
                                                stateView.setVisibility(View.VISIBLE);
                                                stateView.setText("读取直播源失败：" + a.message(e));
                                            });
                                }
                            });
                };

        loadSource[0].run();
    }

    private void renderLiveGroupsAndChannels(
            final LiveCatalog.Source source,
            final LiveCatalog.Catalog catalog,
            final int[] selectedGroupHolder,
            final String searchQuery,
            final LinearLayout groupRow,
            final LinearLayout channelGridCol) {
        groupRow.removeAllViews();
        channelGridCol.removeAllViews();
        if (catalog.groups.isEmpty()) return;

        final int selectedGroupIdx = selectedGroupHolder[0];
        final String qLower = searchQuery == null ? "" : searchQuery.toLowerCase();

        // Pill 0: All Channels
        boolean allActive = qLower.isEmpty() && selectedGroupIdx == -1;
        TextView allBtn =
                a.text(
                        "全部 (" + catalog.channelCount() + ")",
                        12,
                        allActive ? 0xff000000 : 0xffe5e7eb);
        allBtn.setTypeface(null, allActive ? Typeface.BOLD : Typeface.NORMAL);
        allBtn.setPadding(a.dp(13), a.dp(5), a.dp(13), a.dp(5));
        allBtn.setBackground(
                allActive
                        ? glassBg(0xffffffff, 0xffeaeaf0, a.dp(999), 0, 0)
                        : glassBg(0xcc262e2b, 0xb81a211f, a.dp(999), a.dp(0.7f), 0x2effffff));
        MobileDetailScreen.bindTouchFeedback(allBtn);
        allBtn.setOnClickListener(
                v -> {
                    selectedGroupHolder[0] = -1;
                    renderLiveGroupsAndChannels(
                            source, catalog, selectedGroupHolder, "", groupRow, channelGridCol);
                });
        LinearLayout.LayoutParams allLp = new LinearLayout.LayoutParams(-2, -2);
        allLp.rightMargin = a.dp(7);
        groupRow.addView(allBtn, allLp);

        for (int i = 0; i < catalog.groups.size(); i++) {
            final int gIdx = i;
            boolean active = qLower.isEmpty() && i == selectedGroupIdx;
            LiveCatalog.Group g = catalog.groups.get(i);
            TextView gBtn =
                    a.text(
                            g.name + " (" + g.channels.size() + ")",
                            12,
                            active ? 0xff000000 : 0xffe5e7eb);
            gBtn.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
            gBtn.setSingleLine(true);
            gBtn.setPadding(a.dp(13), a.dp(5), a.dp(13), a.dp(5));
            gBtn.setBackground(
                    active
                            ? glassBg(0xffffffff, 0xffeaeaf0, a.dp(999), 0, 0)
                            : glassBg(0xcc262e2b, 0xb81a211f, a.dp(999), a.dp(0.7f), 0x2effffff));
            MobileDetailScreen.bindTouchFeedback(gBtn);
            gBtn.setOnClickListener(
                    v -> {
                        selectedGroupHolder[0] = gIdx;
                        renderLiveGroupsAndChannels(
                                source, catalog, selectedGroupHolder, "", groupRow, channelGridCol);
                    });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = a.dp(7);
            groupRow.addView(gBtn, lp);
        }

        // Collect channels to display
        List<LiveCatalog.Channel> displayList = new ArrayList<>();
        List<String> displayGroups = new ArrayList<>();
        if (!qLower.isEmpty()) {
            for (LiveCatalog.Group g : catalog.groups) {
                for (LiveCatalog.Channel ch : g.channels) {
                    if (ch.name.toLowerCase().contains(qLower)
                            || g.name.toLowerCase().contains(qLower)) {
                        displayList.add(ch);
                        displayGroups.add(g.name);
                    }
                }
            }
        } else if (selectedGroupIdx == -1) {
            for (LiveCatalog.Group g : catalog.groups) {
                for (LiveCatalog.Channel ch : g.channels) {
                    displayList.add(ch);
                    displayGroups.add(g.name);
                }
            }
        } else {
            int safeIdx = Math.max(0, Math.min(selectedGroupIdx, catalog.groups.size() - 1));
            LiveCatalog.Group g = catalog.groups.get(safeIdx);
            for (LiveCatalog.Channel ch : g.channels) {
                displayList.add(ch);
                displayGroups.add(g.name);
            }
        }

        if (displayList.isEmpty()) {
            TextView empty = a.text("未找到匹配的直播频道", 13, MainActivity.MUTED);
            empty.setPadding(a.dp(8), a.dp(24), a.dp(8), a.dp(24));
            channelGridCol.addView(empty);
            return;
        }

        int swDp = a.getResources().getConfiguration().screenWidthDp;
        final int cols = swDp >= 880 ? 4 : (swDp >= 580 ? 3 : 2);
        LinearLayout currentRow = null;

        for (int i = 0; i < displayList.size(); i++) {
            if (i % cols == 0) {
                currentRow = a.row();
                currentRow.setBaselineAligned(false);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
                rowLp.bottomMargin = a.dp(10);
                channelGridCol.addView(currentRow, rowLp);
            }
            final LiveCatalog.Channel ch = displayList.get(i);
            final String groupName = displayGroups.get(i);
            LinearLayout card = buildMobileLiveChannelCard(source, ch, groupName);
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(0, -2, 1f);
            if (i % cols != cols - 1) {
                cardLp.rightMargin = a.dp(10);
            }
            currentRow.addView(card, cardLp);
        }
        int remainder = displayList.size() % cols;
        if (remainder != 0 && currentRow != null) {
            for (int pad = remainder; pad < cols; pad++) {
                View spacer = new View(a);
                LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(0, 1, 1f);
                if (pad != cols - 1) {
                    spLp.rightMargin = a.dp(10);
                }
                currentRow.addView(spacer, spLp);
            }
        }
    }

    private LinearLayout buildMobileLiveChannelCard(
            final LiveCatalog.Source source, final LiveCatalog.Channel ch, final String groupName) {
        LinearLayout card = a.row();
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(a.dp(10), a.dp(10), a.dp(10), a.dp(10));
        card.setBackground(glassBg(0xb81c2522, 0xb8141a18, a.dp(16), a.dp(0.8f), 0x28ffffff));
        MobileDetailScreen.bindTouchFeedback(card);

        // Left: Smart Callsign Badge
        TextView badge = buildSmartChannelBadgeView(a, ch.name);
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(a.dp(40), a.dp(40));
        badgeLp.rightMargin = a.dp(9);
        card.addView(badge, badgeLp);

        // Middle: Two-Line Info
        LinearLayout infoCol = a.vertical();
        TextView name = a.text(ch.name, 13, MainActivity.WHITE);
        name.setTypeface(null, Typeface.BOLD);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        infoCol.addView(name);

        LinearLayout subRow = a.row();
        subRow.setGravity(Gravity.CENTER_VERTICAL);
        subRow.setPadding(0, a.dp(3), 0, 0);
        TextView sub =
                a.text(
                        groupName
                                + " · "
                                + (ch.streams.size() > 1 ? ch.streams.size() + "源" : "直连"),
                        11,
                        0xff9ca3af);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        subRow.addView(sub, new LinearLayout.LayoutParams(0, -2, 1f));
        infoCol.addView(subRow);

        card.addView(infoCol, new LinearLayout.LayoutParams(0, -2, 1f));

        card.setOnClickListener(
                v -> {
                    if (ch.streams.isEmpty()) return;
                    a.playLive(source, ch, ch.streams.get(0));
                });
        if (ch.streams.size() > 1) {
            card.setOnLongClickListener(
                    v -> {
                        String[] names = new String[ch.streams.size()];
                        for (int n = 0; n < names.length; n++) names[n] = "线路 " + (n + 1);
                        new AppDialog.Builder(a)
                                .setTitle(ch.name + " · 选择线路")
                                .setItems(
                                        names,
                                        (dialog, n) -> a.playLive(source, ch, ch.streams.get(n)))
                                .show();
                        return true;
                    });
        }
        return card;
    }

    static TextView buildSmartChannelBadgeView(MainActivity a, String rawName) {
        String name = rawName == null ? "TV" : rawName.trim();
        String upper = name.toUpperCase();
        int bgColor = 0xff2563eb;
        String label;
        if (upper.startsWith("CCTV")) {
            bgColor = 0xffe11d48;
            String num = upper.replaceFirst("^CCTV[-\\s]*", "").replaceAll("[^0-9A-Z+]", "");
            if (num.length() > 3) num = num.substring(0, 3);
            label = num.isEmpty() ? "CCTV" : "CC\n" + num;
        } else if (name.contains("卫视")) {
            bgColor = 0xffd97706;
            String prefix = name.replaceAll("卫视.*$", "").trim();
            label = prefix.length() >= 2 ? prefix.substring(0, 2) : "卫视";
        } else if (name.contains("翡翠")
                || name.contains("明珠")
                || name.contains("凤凰")
                || upper.contains("TVB")) {
            bgColor = 0xff059669;
            label = name.contains("凤凰") ? "凤凰" : "TVB";
        } else if (upper.contains("4K") || upper.contains("8K")) {
            bgColor = 0xff7c3aed;
            label = upper.contains("8K") ? "8K" : "4K";
        } else {
            String clean = name.replaceAll("[\\[\\(（【].*?[\\]\\)）】]", "").trim();
            label = clean.length() >= 2 ? clean.substring(0, 2) : (clean.isEmpty() ? "TV" : clean);
        }
        TextView tv = a.text(label, 11, Color.WHITE);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setBackground(a.bg(bgColor, 10));
        return tv;
    }

    // =========================================================================
    // TAB 3: LIBRARY ("片库": Watch History, Favorites & Downloads)
    // =========================================================================
    private void renderMyTab() {
        LinearLayout page = a.vertical();
        page.setPadding(a.dp(16), statusBarHeight() + a.dp(10), a.dp(16), 0);
        contentContainer.addView(page, new FrameLayout.LayoutParams(-1, -1));

        TextView pageTitle = a.text("片库", 22, Color.WHITE);
        pageTitle.setTypeface(null, Typeface.BOLD);
        pageTitle.setPadding(0, 0, 0, a.dp(10));
        page.addView(pageTitle);

        final boolean[] showFavorites = {mySubTab == 1};
        final boolean[] showDownloads = {mySubTab == 2};
        final LinearLayout headerRow = a.row();
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        final TextView tabHist = a.text("观看历史", 13.5f, Color.WHITE);
        final TextView tabFav = a.text("我的收藏", 13.5f, MainActivity.MUTED);
        final TextView tabDown = a.text("我的下载", 13.5f, MainActivity.MUTED);
        tabDown.setTag("my-downloads");
        final TextView clearBtn = a.text("清空历史", 12, 0xffef4444);

        HorizontalScrollView tabScroll = new HorizontalScrollView(a);
        tabScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tabRow = a.row();
        tabScroll.addView(tabRow);
        tabRow.addView(tabHist);
        tabRow.addView(new View(a), new LinearLayout.LayoutParams(a.dp(8), 1));
        tabRow.addView(tabFav);
        tabRow.addView(new View(a), new LinearLayout.LayoutParams(a.dp(8), 1));
        tabRow.addView(tabDown);
        headerRow.addView(tabScroll, new LinearLayout.LayoutParams(0, -2, 1));
        headerRow.addView(clearBtn);
        page.addView(headerRow, new LinearLayout.LayoutParams(-1, -2));
        a.gap(page, 12);

        ScrollView sv = new ScrollView(a);
        sv.setFillViewport(true);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipToPadding(false);
        page.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        final LinearLayout listBody = a.vertical();
        listBody.setPadding(0, 0, 0, a.dp(106));
        sv.addView(listBody, new ScrollView.LayoutParams(-1, -2));

        final Runnable renderSubTab =
                new Runnable() {
                    @Override
                    public void run() {
                        boolean favMode = showFavorites[0];
                        boolean downMode = showDownloads[0];
                        boolean histMode = !favMode && !downMode;
                        mySubTab = downMode ? 2 : favMode ? 1 : 0;
                        styleLibraryPill(tabHist, histMode);
                        styleLibraryPill(tabFav, favMode);
                        styleLibraryPill(tabDown, downMode);

                        clearBtn.setVisibility(histMode ? View.VISIBLE : View.GONE);
                        listBody.removeAllViews();

                        if (downMode) {
                            MobileDownloads.render(a, listBody);
                            return;
                        }
                        if (!favMode) {
                            List<WatchHistory.Entry> items = a.watchHistory();
                            if (items.isEmpty()) {
                                renderCenteredHint(listBody, "暂无观看历史记录");
                                return;
                            }
                            for (final WatchHistory.Entry e : items) {
                                LinearLayout row = a.row();
                                row.setGravity(Gravity.CENTER_VERTICAL);
                                row.setPadding(a.dp(10), a.dp(10), a.dp(12), a.dp(10));
                                row.setBackground(
                                        glassBg(
                                                0xb81b2320,
                                                0xb8131917,
                                                a.dp(16),
                                                a.dp(0.7f),
                                                0x26ffffff));
                                MobileDetailScreen.bindTouchFeedback(row);
                                row.setOnClickListener(v -> a.resumeHistory(e));

                                ImageView thumb = new ImageView(a);
                                thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
                                thumb.setBackground(a.bg(0xff232b28, 10));
                                thumb.setClipToOutline(true);
                                LinearLayout.LayoutParams thumbLp =
                                        new LinearLayout.LayoutParams(a.dp(52), a.dp(74));
                                thumbLp.rightMargin = a.dp(12);
                                row.addView(thumb, thumbLp);
                                if (e.poster != null && !e.poster.isEmpty())
                                    a.poster(e.poster, thumb);

                                LinearLayout info = a.vertical();
                                TextView t = a.text(e.title, 15, MainActivity.WHITE);
                                t.setTypeface(null, Typeface.BOLD);
                                t.setSingleLine(true);
                                t.setEllipsize(TextUtils.TruncateAt.END);
                                info.addView(t);

                                TextView sub = a.text(a.historyLabel(e), 12, MainActivity.MUTED);
                                sub.setPadding(0, a.dp(5), 0, 0);
                                info.addView(sub);
                                row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));

                                TextView del = a.text("删除", 12, 0xffef4444);
                                del.setPadding(a.dp(10), a.dp(6), a.dp(10), a.dp(6));
                                MobileDetailScreen.bindTouchFeedback(del);
                                del.setOnClickListener(
                                        v -> {
                                            a.prefs
                                                    .edit()
                                                    .putString(
                                                            Prefs.WATCH_HISTORY,
                                                            WatchHistory.remove(
                                                                    a.prefs.getString(
                                                                            Prefs.WATCH_HISTORY,
                                                                            "[]"),
                                                                    e.key()))
                                                    .apply();
                                            run();
                                        });
                                row.addView(del);

                                LinearLayout.LayoutParams lp =
                                        new LinearLayout.LayoutParams(-1, -2);
                                lp.bottomMargin = a.dp(8);
                                listBody.addView(row, lp);
                            }
                        } else {
                            List<Favorites.Entry> favs =
                                    Favorites.read(a.prefs.getString(Prefs.FAVORITES, "[]"));
                            if (favs.isEmpty()) {
                                renderCenteredHint(listBody, "还没有收藏任何影片");
                                return;
                            }
                            for (final Favorites.Entry f : favs) {
                                LinearLayout row = a.row();
                                row.setGravity(Gravity.CENTER_VERTICAL);
                                row.setPadding(a.dp(10), a.dp(10), a.dp(12), a.dp(10));
                                row.setBackground(
                                        glassBg(
                                                0xb81b2320,
                                                0xb8131917,
                                                a.dp(16),
                                                a.dp(0.7f),
                                                0x26ffffff));
                                MobileDetailScreen.bindTouchFeedback(row);

                                ImageView thumb = new ImageView(a);
                                thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
                                thumb.setBackground(a.bg(0xff232b28, 10));
                                thumb.setClipToOutline(true);
                                LinearLayout.LayoutParams thumbLp =
                                        new LinearLayout.LayoutParams(a.dp(52), a.dp(74));
                                thumbLp.rightMargin = a.dp(12);
                                row.addView(thumb, thumbLp);
                                if (f.poster != null && !f.poster.isEmpty())
                                    a.poster(f.poster, thumb);

                                LinearLayout info = a.vertical();
                                TextView t = a.text(f.title, 15, MainActivity.WHITE);
                                t.setTypeface(null, Typeface.BOLD);
                                t.setSingleLine(true);
                                t.setEllipsize(TextUtils.TruncateAt.END);
                                info.addView(t);

                                TextView sub = a.text("已收藏 · 点击查看详情", 12, MainActivity.MUTED);
                                sub.setPadding(0, a.dp(5), 0, 0);
                                info.addView(sub);
                                row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));

                                row.setOnClickListener(
                                        v -> {
                                            Cms.Site targetSite = null;
                                            if (a.config != null) {
                                                for (Cms.Site s : a.config.sites) {
                                                    if (s.key.equals(f.site)) {
                                                        targetSite = s;
                                                        break;
                                                    }
                                                }
                                            }
                                            if (targetSite == null) targetSite = currentSite;
                                            if (targetSite == null) {
                                                a.toast("该收藏所属的片源不可用");
                                                return;
                                            }
                                            final Cms.Site resolvedSite = targetSite;
                                            Cms.Video video = f.video();
                                            a.openListing(resolvedSite, video);
                                        });

                                LinearLayout.LayoutParams lp =
                                        new LinearLayout.LayoutParams(-1, -2);
                                lp.bottomMargin = a.dp(8);
                                listBody.addView(row, lp);
                            }
                        }
                    }
                };

        MobileDetailScreen.bindTouchFeedback(tabHist);
        MobileDetailScreen.bindTouchFeedback(tabFav);
        MobileDetailScreen.bindTouchFeedback(tabDown);
        MobileDetailScreen.bindTouchFeedback(clearBtn);
        tabHist.setOnClickListener(
                v -> {
                    showFavorites[0] = false;
                    showDownloads[0] = false;
                    renderSubTab.run();
                });
        tabFav.setOnClickListener(
                v -> {
                    showFavorites[0] = true;
                    showDownloads[0] = false;
                    renderSubTab.run();
                });
        tabDown.setOnClickListener(
                v -> {
                    showFavorites[0] = false;
                    showDownloads[0] = true;
                    renderSubTab.run();
                });
        clearBtn.setOnClickListener(
                v -> {
                    a.prefs.edit().putString(Prefs.WATCH_HISTORY, "[]").apply();
                    renderSubTab.run();
                });

        renderSubTab.run();
    }

    private void styleLibraryPill(TextView pill, boolean active) {
        pill.setTextColor(active ? 0xff000000 : 0xffe5e7eb);
        pill.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
        pill.setPadding(a.dp(15), a.dp(7), a.dp(15), a.dp(7));
        if (active) {
            pill.setBackground(glassBg(0xffffffff, 0xffeaeaf0, a.dp(999), 0, 0));
        } else {
            pill.setBackground(glassBg(0xcc262e2b, 0xb81a211f, a.dp(999), a.dp(0.7f), 0x2effffff));
        }
    }

    // =========================================================================
    // TAB 4: MOBILE SETTINGS (2-Level Clean Architecture, Zero Emojis, Zero Ghosting)
    // =========================================================================
    private void renderSettingsTab() {
        contentContainer.removeAllViews();

        final ScrollView sv = new ScrollView(a);
        sv.setFillViewport(true);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipToPadding(false);
        contentContainer.addView(sv, new FrameLayout.LayoutParams(-1, -1));

        final LinearLayout body = a.vertical();
        body.setPadding(a.dp(16), statusBarHeight() + a.dp(10), a.dp(16), a.dp(108));
        sv.addView(body, new ScrollView.LayoutParams(-1, -2));

        populateSettingsBody(body, sv);
    }

    private void populateSettingsBody(final LinearLayout body, final ScrollView sv) {
        final int savedScrollY = sv.getScrollY();
        body.removeAllViews();

        final Runnable refreshInPlace =
                () -> {
                    populateSettingsBody(body, sv);
                    body.invalidate();
                    sv.invalidate();
                    if (contentContainer != null) contentContainer.invalidate();
                    if (a.root != null) a.root.invalidate();
                };

        if (settingsSubPage != 0) {
            LinearLayout topNav = a.row();
            topNav.setGravity(Gravity.CENTER_VERTICAL);
            topNav.setPadding(0, 0, 0, a.dp(14));

            TextView backBtn = a.text("‹", 22, Color.WHITE);
            backBtn.setGravity(Gravity.CENTER);
            backBtn.setBackground(
                    glassBg(0xb034343a, 0xc01e1e22, a.dp(999), a.dp(0.8f), 0x38ffffff));
            MobileDetailScreen.bindTouchFeedback(backBtn);
            backBtn.setOnClickListener(
                    v -> {
                        settingsSubPage = 0;
                        sv.scrollTo(0, 0);
                        refreshInPlace.run();
                    });
            LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(a.dp(38), a.dp(38));
            backLp.rightMargin = a.dp(10);
            topNav.addView(backBtn, backLp);

            String subTitleText =
                    settingsSubPage == 1
                            ? "片源与直播配置"
                            : settingsSubPage == 2
                                    ? "播放、解码与字幕"
                                    : settingsSubPage == 3
                                            ? "界面外观与偏好"
                                            : settingsSubPage == 6
                                                    ? "智能跳过与内容安全"
                                                    : "备份、更新与诊断";
            TextView subTitle = a.text(subTitleText, 20, Color.WHITE);
            subTitle.setTypeface(null, Typeface.BOLD);
            topNav.addView(subTitle, new LinearLayout.LayoutParams(0, -2, 1));
            body.addView(topNav, new LinearLayout.LayoutParams(-1, -2));

            if (settingsSubPage == 1) {
                addSectionHeader(body, "配置与直播源导入");
                addSettingCard(
                        body,
                        1,
                        "从剪贴板一键粘贴配置",
                        "直接读取手机剪贴板中的 JSON、JS、PY、TXT/M3U 或配置网址",
                        () -> pasteConfigFromClipboard(a));
                addSettingCard(
                        body,
                        1,
                        "输入或修改配置网址与源码",
                        a.configOrigin.isEmpty()
                                ? "支持 JSON / JS / PY / TXT / M3U / ZIP 地址"
                                : a.configOrigin,
                        () -> showMobileImportDialog(a));
                addSettingCard(
                        body,
                        1,
                        "选择本地配置或本地包",
                        "支持导入 .json / .js / .py / .txt / .m3u / .zip 本地文件",
                        a::pickConfig);
                String savedLiveUrl = a.prefs.getString(Prefs.LIVE_URL, "");
                addSettingCard(
                        body,
                        1,
                        "导入或管理电视直播源",
                        savedLiveUrl.isEmpty()
                                ? "支持 M3U、TXT (#genre#)、JSON 及本地直播文件"
                                : "当前直播源：" + savedLiveUrl,
                        () -> SourceController.showLiveImportDialog(a));

                a.gap(body, 12);
                addSectionHeader(body, "片源选择与扩展引擎");
                addSettingCard(
                        body,
                        1,
                        "切换默认片源",
                        currentSite == null ? "未选择" : "当前：" + currentSite.name,
                        a::chooseSources);
                final boolean cspEnabled = a.prefs.getBoolean(Prefs.EXPERIMENTAL_CSP, true);
                addSettingCard(
                        body,
                        1,
                        "启用第三方扩展片源 (JAR / JS / PY)",
                        cspEnabled ? "当前状态：已开启" : "当前状态：已关闭（点击开启）",
                        () -> {
                            a.prefs.edit().putBoolean(Prefs.EXPERIMENTAL_CSP, !cspEnabled).apply();
                            a.closeEngines();
                            refreshInPlace.run();
                            a.toast(!cspEnabled ? "已开启扩展片源支持" : "已关闭扩展片源支持");
                        });
            } else if (settingsSubPage == 2) {
                addSectionHeader(body, "播放器与解码器");
                final int engine = a.prefs.getInt(Prefs.PLAYER_ENGINE, 0);
                addSettingCard(
                        body,
                        3,
                        "播放器内核",
                        engine == 0 ? "Media3（自带软解与字幕增强）" : "Android 系统播放器",
                        () -> {
                            int next = engine == 0 ? 1 : 0;
                            a.prefs.edit().putInt(Prefs.PLAYER_ENGINE, next).apply();
                            refreshInPlace.run();
                        });
                final int decoder = a.prefs.getInt(Prefs.DECODER_MODE, 0);
                final String[] decNames = {"自动选择（推荐）", "硬件解码优先", "软件解码优先"};
                addSettingCard(
                        body,
                        3,
                        "视频解码方式",
                        decNames[Math.max(0, Math.min(2, decoder))],
                        () -> {
                            int next = (decoder + 1) % 3;
                            a.prefs.edit().putInt(Prefs.DECODER_MODE, next).apply();
                            refreshInPlace.run();
                        });
                final boolean tunnel = a.prefs.getBoolean(Prefs.TUNNELED_PLAYBACK, false);
                addSettingCard(
                        body,
                        3,
                        "4K HDR 隧道模式",
                        tunnel ? "已开启（部分设备可降低高码率掉帧）" : "已关闭（兼容模式）",
                        () -> {
                            a.prefs.edit().putBoolean(Prefs.TUNNELED_PLAYBACK, !tunnel).apply();
                            refreshInPlace.run();
                        });
                final boolean autoNext = a.prefs.getBoolean(Prefs.AUTO_NEXT, true);
                addSettingCard(
                        body,
                        3,
                        "自动连播下一集",
                        autoNext ? "已开启" : "已关闭",
                        () -> {
                            a.prefs.edit().putBoolean(Prefs.AUTO_NEXT, !autoNext).apply();
                            refreshInPlace.run();
                        });

                a.gap(body, 12);
                addSectionHeader(body, "片头片尾与字幕样式");
                int introSec = a.prefs.getInt(Prefs.SKIP_INTRO_SECONDS, 0);
                int outroSec = a.prefs.getInt(Prefs.SKIP_OUTRO_SECONDS, 0);
                String skipDesc =
                        (introSec > 0
                                        ? "片头 " + PlaybackController.formatTime(introSec * 1000)
                                        : "片头关闭")
                                + " · "
                                + (outroSec > 0
                                        ? "片尾 " + PlaybackController.formatTime(outroSec * 1000)
                                        : "片尾关闭");
                addSettingCard(
                        body,
                        3,
                        "片头片尾跳过时间节点",
                        skipDesc + "（支持手动拖动滑块精准设定）",
                        () ->
                                PlaybackControls.showSkipAdjuster(
                                        a, true, introSec, 0, refreshInPlace));

                int subSize = Prefs.clampSubtitleSize(a.prefs.getInt(Prefs.SUBTITLE_SIZE_SP, 40));
                int subBg = Prefs.clampSubtitleBgStyle(a.prefs.getInt(Prefs.SUBTITLE_BG_STYLE, 0));
                addSettingCard(
                        body,
                        3,
                        "字幕大小、位置与样式",
                        "字号 "
                                + subSize
                                + " · "
                                + SubtitleOverlayView.bgStyleLabel(subBg)
                                + "（支持方向键与触控调节）",
                        () -> PlaybackControls.showSubtitleAdjuster(a, null, refreshInPlace));
            } else if (settingsSubPage == 3) {
                addSectionHeader(body, "界面显示与交互");
                final int scale = a.prefs.getInt(Prefs.UI_SCALE_PERCENT, 100);
                addSettingCard(
                        body,
                        4,
                        "界面整体缩放比例",
                        "当前：" + scale + "%（点击切换 90% / 100% / 110%）",
                        () -> {
                            int next = scale == 100 ? 110 : (scale == 110 ? 90 : 100);
                            a.prefs.edit().putInt(Prefs.UI_SCALE_PERCENT, next).apply();
                            a.recreate();
                        });
                final boolean reduced = a.prefs.getBoolean(Prefs.REDUCED, false);
                final boolean reducedTransparency =
                        a.prefs.getBoolean(Prefs.REDUCED_TRANSPARENCY, false);
                addSettingCard(
                        body,
                        4,
                        "减少透明效果",
                        reducedTransparency ? "已开启（使用实色面板）" : "已关闭（背景玻璃材质）",
                        () -> {
                            a.prefs
                                    .edit()
                                    .putBoolean(Prefs.REDUCED_TRANSPARENCY, !reducedTransparency)
                                    .apply();
                            com.pilipala.glass.GlassworkSurface.invalidateBackdrop(a.root);
                            refreshInPlace.run();
                        });
                addSettingCard(
                        body,
                        4,
                        "精简界面动画",
                        reduced ? "已开启（减少过渡动画，提升响应速度）" : "已关闭（保留完整流畅动效）",
                        () -> {
                            a.reduced = !reduced;
                            a.prefs.edit().putBoolean(Prefs.REDUCED, !reduced).apply();
                            refreshInPlace.run();
                        });
                addSettingCard(
                        body,
                        4,
                        "清空搜索历史记录",
                        "清除搜索页保存的全部历史关键词",
                        () -> {
                            a.prefs.edit().putString(Prefs.GLOBAL_SEARCH_HISTORY, "[]").apply();
                            cachedSearchKeyword = "";
                            cachedSearchStatus = "";
                            cachedSearchHits.clear();
                            a.toast("已清空搜索历史记录");
                        });
            } else if (settingsSubPage == 4) {
                addSectionHeader(body, "更新、备份与诊断");
                addSettingCard(
                        body,
                        5,
                        "检查软件更新",
                        "当前版本：" + UpdateManager.installedVersion(a) + " · 正式版",
                        () ->
                                a.open(
                                        new MainActivity.Screen(() -> new UpdateScreen(a).show()),
                                        true));
                addSettingCard(
                        body,
                        5,
                        "查看运行诊断日志",
                        "排查片源网络请求、代理与播放解码日志",
                        () -> new SettingsScreen(a).diagnostics());
                addSettingCard(
                        body,
                        5,
                        "完整高级设置与备份恢复",
                        "配置备份导出、恢复及完整系统参数页面",
                        () ->
                                a.open(
                                        new MainActivity.Screen(() -> new SettingsScreen(a).show()),
                                        true));
            } else if (settingsSubPage == 6) {
                addSectionHeader(body, "智能跳过与内容安全");
                final boolean adOn = AdSkipConfig.isEnabled();
                addSettingCard(
                        body,
                        6,
                        "口播广告跳过",
                        adOn
                                ? ("开启 · "
                                        + AdSkipConfig.getKeywordCount()
                                        + " 个关键词 · 跳 "
                                        + AdSkipConfig.getSkipSeconds()
                                        + " 秒 · "
                                        + AdSkipConfig.getSensitivityLabel())
                                : "已关闭",
                        () ->
                                a.open(
                                        new MainActivity.Screen(
                                                () -> new SettingsScreen(a).adSkip()),
                                        true));
                addSettingCard(
                        body,
                        6,
                        "内容安全（成人资源过滤）",
                        ContentGate.locked()
                                ? "过滤开启"
                                : (ContentFilterConfig.unlocked() ? "临时解锁中" : "过滤关闭"),
                        () ->
                                a.open(
                                        new MainActivity.Screen(
                                                () -> new SettingsScreen(a).contentSafety()),
                                        true));
            }
            if (savedScrollY > 0) {
                sv.scrollTo(0, savedScrollY);
            }
            return;
        }

        // =====================================================================
        // LEVEL 1: CLEAN 5-CARD PRIMARY SETTINGS MENU (Zero Emojis)
        // =====================================================================
        TextView pageTitle = a.text("设置", 24, Color.WHITE);
        pageTitle.setTypeface(null, Typeface.BOLD);
        body.addView(pageTitle);

        a.gap(body, 14);
        final String[] categories = {"片源与直播配置", "播放、解码与字幕", "界面外观与偏好", "备份、更新与诊断"};
        final int[] icons = {1, 3, 4, 5};
        for (int i = 0; i < categories.length; i++) {
            final int page = i + 1;
            addSettingCard(
                    body,
                    icons[i],
                    categories[i],
                    "",
                    () -> {
                        settingsSubPage = page;
                        sv.scrollTo(0, 0);
                        refreshInPlace.run();
                    });
        }
        addSettingCard(body, 2, "网盘与账号授权", "", () -> AccountPanel.show(a, null));
        addSettingCard(
                body,
                6,
                "智能跳过与内容安全",
                "口播广告自动跳过 · 成人资源过滤",
                () -> {
                    settingsSubPage = 6;
                    sv.scrollTo(0, 0);
                    refreshInPlace.run();
                });
    }

    private void addSectionHeader(LinearLayout parent, String title) {
        TextView h = a.text(title, 12.5f, 0xff5ac8fa);
        h.setTypeface(null, Typeface.BOLD);
        h.setPadding(a.dp(4), a.dp(6), a.dp(4), a.dp(6));
        parent.addView(h);
    }

    private void addSettingCard(
            LinearLayout parent, int iconKind, String title, String subtitle, Runnable action) {
        LinearLayout row = a.row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(a.dp(14), a.dp(13), a.dp(14), a.dp(13));
        row.setBackground(glassBg(0xb81b2320, 0xb8131917, a.dp(18), a.dp(0.7f), 0x26ffffff));
        MobileDetailScreen.bindTouchFeedback(row);
        row.setOnClickListener(v -> action.run());

        SettingIconView icon = new SettingIconView(a, iconKind);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(a.dp(36), a.dp(36));
        iconLp.rightMargin = a.dp(12);
        row.addView(icon, iconLp);

        LinearLayout textCol = a.vertical();
        TextView t = a.text(title, 14.5f, MainActivity.WHITE);
        t.setTypeface(null, Typeface.BOLD);
        textCol.addView(t);

        TextView s = a.text(subtitle, 12, MainActivity.MUTED);
        s.setPadding(0, a.dp(3), 0, 0);
        s.setSingleLine(true);
        s.setEllipsize(TextUtils.TruncateAt.END);
        if (!subtitle.isEmpty()) textCol.addView(s);

        row.addView(textCol, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(a.text("›", 20, MainActivity.MUTED));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = a.dp(10);
        parent.addView(row, lp);
    }

    /** Clean vector icon badge for Settings cards (replaces emojis). */
    static final class SettingIconView extends View {
        private final MainActivity a;
        private final int kind;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        SettingIconView(MainActivity activity, int kind) {
            super(activity);
            this.a = activity;
            this.kind = kind;
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float cx = w / 2f, cy = h / 2f;
            int badgeBg;
            int strokeColor;
            switch (kind) {
                case 1:
                    badgeBg = 0x260a84ff;
                    strokeColor = 0xff5ac8fa;
                    break;
                case 2:
                    badgeBg = 0x2630d158;
                    strokeColor = 0xff34d399;
                    break;
                case 3:
                    badgeBg = 0x26ff9f0a;
                    strokeColor = 0xfffbbf24;
                    break;
                case 4:
                    badgeBg = 0x26bf5af2;
                    strokeColor = 0xffc084fc;
                    break;
                case 6:
                    badgeBg = 0x26ef4444;
                    strokeColor = 0xfff87171;
                    break;
                default:
                    badgeBg = 0x265ac8fa;
                    strokeColor = 0xff60a5fa;
                    break;
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(badgeBg);
            c.drawRoundRect(new RectF(0, 0, w, h), a.dp(10), a.dp(10), paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(strokeColor);
            paint.setStrokeWidth(a.dp(1.6f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);

            if (kind == 1) {
                // Layers / source cube icon
                Path top = new Path();
                top.moveTo(cx, a.dp(8.5f));
                top.lineTo(w - a.dp(8.5f), cy - a.dp(2f));
                top.lineTo(cx, cy + a.dp(2.5f));
                top.lineTo(a.dp(8.5f), cy - a.dp(2f));
                top.close();
                c.drawPath(top, paint);
                c.drawLine(a.dp(8.5f), cy + a.dp(2.5f), cx, h - a.dp(8.5f), paint);
                c.drawLine(w - a.dp(8.5f), cy + a.dp(2.5f), cx, h - a.dp(8.5f), paint);
            } else if (kind == 2) {
                // Cloud / netdisk icon
                RectF base = new RectF(a.dp(8.5f), cy - a.dp(2f), w - a.dp(8.5f), h - a.dp(9.5f));
                c.drawRoundRect(base, a.dp(4.5f), a.dp(4.5f), paint);
                c.drawArc(
                        new RectF(cx - a.dp(5f), a.dp(9f), cx + a.dp(5f), cy + a.dp(3f)),
                        190,
                        160,
                        false,
                        paint);
            } else if (kind == 3) {
                // Play circle + sliders icon
                c.drawCircle(cx, cy, a.dp(8.5f), paint);
                paint.setStyle(Paint.Style.FILL);
                Path tri = new Path();
                tri.moveTo(cx - a.dp(2.2f), cy - a.dp(3.4f));
                tri.lineTo(cx + a.dp(3.6f), cy);
                tri.lineTo(cx - a.dp(2.2f), cy + a.dp(3.4f));
                tri.close();
                c.drawPath(tri, paint);
            } else if (kind == 4) {
                // Sliders / tuning icon
                float x1 = a.dp(9.5f), x2 = w - a.dp(9.5f);
                c.drawLine(x1, cy - a.dp(5f), x2, cy - a.dp(5f), paint);
                c.drawLine(x1, cy + a.dp(5f), x2, cy + a.dp(5f), paint);
                paint.setStyle(Paint.Style.FILL);
                c.drawCircle(cx - a.dp(3f), cy - a.dp(5f), a.dp(2.3f), paint);
                c.drawCircle(cx + a.dp(3f), cy + a.dp(5f), a.dp(2.3f), paint);
            } else if (kind == 6) {
                // Shield with inner forward-skip double chevron
                Path shield = new Path();
                float stop = a.dp(7f);
                float sbot = h - a.dp(7f);
                shield.moveTo(cx, stop);
                shield.lineTo(w - a.dp(9f), cy - a.dp(4f));
                shield.lineTo(cx, sbot);
                shield.lineTo(a.dp(9f), cy - a.dp(4f));
                shield.close();
                c.drawPath(shield, paint);
                float iy = cy + a.dp(0.5f);
                c.drawLine(cx - a.dp(4f), iy - a.dp(3f), cx - a.dp(1f), iy, paint);
                c.drawLine(cx - a.dp(4f), iy + a.dp(3f), cx - a.dp(1f), iy, paint);
                c.drawLine(cx + a.dp(0.5f), iy - a.dp(3f), cx + a.dp(3.5f), iy, paint);
                c.drawLine(cx + a.dp(0.5f), iy + a.dp(3f), cx + a.dp(3.5f), iy, paint);
            } else {
                // Shield / update arrow icon
                c.drawArc(
                        new RectF(a.dp(9.5f), a.dp(9.5f), w - a.dp(9.5f), h - a.dp(9.5f)),
                        -30,
                        300,
                        false,
                        paint);
                c.drawLine(cx, a.dp(13f), cx, cy + a.dp(3.5f), paint);
                c.drawLine(cx - a.dp(2.8f), cy + a.dp(0.8f), cx, cy + a.dp(3.6f), paint);
                c.drawLine(cx + a.dp(2.8f), cy + a.dp(0.8f), cx, cy + a.dp(3.6f), paint);
            }
        }
    }

    private void renderCenteredHint(LinearLayout parent, String message) {
        TextView t = a.text(message, 14, MainActivity.MUTED);
        t.setGravity(Gravity.CENTER);
        t.setPadding(a.dp(20), a.dp(48), a.dp(20), a.dp(48));
        parent.addView(t, new LinearLayout.LayoutParams(-1, -2));
    }

    /**
     * Deep emerald-obsidian ambient canvas matching the iPhone `AppleAmbientBackground` preset.
     */
    private static final class AmbientCanvasView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        AmbientCanvasView(Context context) {
            super(context);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;

            paint.setShader(
                    new LinearGradient(
                            0,
                            0,
                            0,
                            h,
                            new int[] {0xff11241c, 0xff0b1612, 0xff060a08},
                            new float[] {0f, 0.52f, 1f},
                            Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);

            float radius = Math.max(w, h) * 0.65f;
            glowPaint.setShader(
                    new RadialGradient(
                            w * 0.5f,
                            h * 0.14f,
                            radius,
                            new int[] {0x38205c44, 0x14123628, 0x00070c0a},
                            new float[] {0f, 0.55f, 1f},
                            Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, glowPaint);
        }
    }

    /** Soft top-edge gradient veil so scrolling content dissolves gently under the status bar. */
    private static final class TopEdgeFadeView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        TopEdgeFadeView(Context context) {
            super(context);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;
            paint.setShader(
                    new LinearGradient(
                            0,
                            0,
                            0,
                            h,
                            new int[] {0xd80d1c16, 0x780d1c16, 0x000d1c16},
                            new float[] {0f, 0.58f, 1f},
                            Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
        }
    }

    /** Soft bottom-edge vignette behind the floating Liquid Glass dock. */
    private static final class BottomEdgeFadeView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        BottomEdgeFadeView(Context context) {
            super(context);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;
            paint.setShader(
                    new LinearGradient(
                            0,
                            0,
                            0,
                            h,
                            new int[] {0x00060a08, 0x90060a08, 0xe6060a08},
                            new float[] {0f, 0.55f, 1f},
                            Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
        }
    }

    /**
     * Custom vector icons matching the iPhone Cupertino dock icons:
     *
     * <ul>
     *   <li>`kind == 0`: `play_rectangle_fill` / `play_rectangle` (`主页`)
     *   <li>`kind == 1`: `search` magnifying glass (`搜索`)
     *   <li>`kind == 2`: `dot_radiowaves_left_right` (`直播`)
     *   <li>`kind == 3`: `square_favorites_alt` (`片库`)
     *   <li>`kind == 4`: `gear_alt` (`设置`)
     * </ul>
     */
    static final class NavGlyph extends View {
        private final MainActivity a;
        private final int kind;
        private boolean selected;
        private int customTint = 0;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        NavGlyph(MainActivity activity, int kind) {
            super(activity);
            this.a = activity;
            this.kind = kind;
        }

        void setSelectedState(boolean value) {
            this.selected = value;
            invalidate();
        }

        void setIconTint(int color) {
            this.customTint = color;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float cx = w / 2f, cy = h / 2f;
            int color = customTint != 0 ? customTint : (selected ? 0xff0a84ff : 0xffe5e7eb);
            paint.setColor(color);
            paint.setStrokeWidth(a.dp(1.75f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);

            if (kind == 0) {
                // iPhone `play_rectangle` / `play_rectangle_fill`
                RectF rect = new RectF(a.dp(2.5f), a.dp(4f), w - a.dp(2.5f), h - a.dp(4f));
                if (selected) {
                    paint.setStyle(Paint.Style.FILL);
                    c.drawRoundRect(rect, a.dp(4.5f), a.dp(4.5f), paint);
                    paint.setColor(0xff101820);
                } else {
                    paint.setStyle(Paint.Style.STROKE);
                    c.drawRoundRect(rect, a.dp(4.5f), a.dp(4.5f), paint);
                    paint.setStyle(Paint.Style.FILL);
                }
                Path tri = new Path();
                tri.moveTo(cx - a.dp(2.2f), cy - a.dp(3.2f));
                tri.lineTo(cx + a.dp(3.4f), cy);
                tri.lineTo(cx - a.dp(2.2f), cy + a.dp(3.2f));
                tri.close();
                c.drawPath(tri, paint);
            } else if (kind == 1) {
                // Search magnifying glass
                paint.setStyle(Paint.Style.STROKE);
                float r = a.dp(5.5f);
                c.drawCircle(cx - a.dp(1.2f), cy - a.dp(1.2f), r, paint);
                c.drawLine(cx + a.dp(2.8f), cy + a.dp(2.8f), w - a.dp(2.5f), h - a.dp(2.5f), paint);
            } else if (kind == 2) {
                // iPhone `dot_radiowaves_left_right` (`((•))`)
                paint.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, cy, a.dp(2f), paint);
                paint.setStyle(Paint.Style.STROKE);
                float r1 = a.dp(5.2f);
                RectF inner = new RectF(cx - r1, cy - r1, cx + r1, cy + r1);
                c.drawArc(inner, -48, 96, false, paint);
                c.drawArc(inner, 132, 96, false, paint);
                float r2 = a.dp(8.6f);
                RectF outer = new RectF(cx - r2, cy - r2, cx + r2, cy + r2);
                c.drawArc(outer, -52, 104, false, paint);
                c.drawArc(outer, 128, 104, false, paint);
            } else if (kind == 3) {
                // iPhone `square_favorites_alt` (rounded square card with heart/lines)
                paint.setStyle(Paint.Style.STROKE);
                RectF box = new RectF(a.dp(3.5f), a.dp(3f), w - a.dp(3.5f), h - a.dp(3f));
                c.drawRoundRect(box, a.dp(4f), a.dp(4f), paint);
                c.drawLine(a.dp(6.8f), h - a.dp(6.5f), w - a.dp(6.8f), h - a.dp(6.5f), paint);
                paint.setStyle(Paint.Style.FILL);
                c.drawCircle(cx - a.dp(1.6f), cy - a.dp(2.2f), a.dp(1.6f), paint);
                c.drawCircle(cx + a.dp(1.6f), cy - a.dp(2.2f), a.dp(1.6f), paint);
                Path heartTip = new Path();
                heartTip.moveTo(cx - a.dp(3.1f), cy - a.dp(1.7f));
                heartTip.lineTo(cx + a.dp(3.1f), cy - a.dp(1.7f));
                heartTip.lineTo(cx, cy + a.dp(1.8f));
                heartTip.close();
                c.drawPath(heartTip, paint);
            } else {
                // iPhone `gear_alt` (gear with 8 teeth + center circle)
                paint.setStyle(Paint.Style.STROKE);
                c.drawCircle(cx, cy, a.dp(3.1f), paint);
                c.drawCircle(cx, cy, a.dp(6.2f), paint);
                for (int deg = 0; deg < 360; deg += 45) {
                    double rad = Math.toRadians(deg);
                    float x1 = cx + (float) Math.cos(rad) * a.dp(6.2f);
                    float y1 = cy + (float) Math.sin(rad) * a.dp(6.2f);
                    float x2 = cx + (float) Math.cos(rad) * a.dp(8.3f);
                    float y2 = cy + (float) Math.sin(rad) * a.dp(8.3f);
                    c.drawLine(x1, y1, x2, y2, paint);
                }
            }
        }
    }
}
