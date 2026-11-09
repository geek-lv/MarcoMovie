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
    static String cachedSearchSiteFilter = "";
    static final List<CachedSearchHit> cachedSearchHits = new ArrayList<>();
    static int cachedSearchScrollY = 0;
    static int cachedSiteFilterScrollX = 0;
    static SearchCoordinator activeSearchCoordinator;

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

    // Home tab state (persisted across MobileShell re-renders for the same active site)
    private Cms.Site currentSite;
    static String cachedCategorySiteKey = "";
    static String selectedCategoryId = "";
    private int currentPage = 1;
    private int totalPages = 1;
    private final List<Cms.Video> currentVideos = new ArrayList<>();
    static List<Cms.Category> currentCategories = new ArrayList<>();
    static String filterSchema = "{}";
    static final java.util.Map<String, String> selectedFilters = new java.util.LinkedHashMap<>();
    static int cachedCatScrollX = 0;
    private int homeRequest;
    private boolean homeReady;
    private Runnable resumeHome;
    Runnable reloadCategory;
    private FrameLayout retainedRoot;
    private int retainedWidth, retainedHeight, retainedEpoch;
    private boolean renderedPortrait = true;

    boolean resume() {
        if (!homeReady
                || activeTab != 0
                || retainedRoot == null
                || retainedEpoch != a.engineEpoch
                || retainedWidth != a.windowWidth()
                || retainedHeight != a.windowHeight()
                || renderedPortrait != a.isPortrait()) return false;
        a.cancelPageRequests();
        a.cancelArtwork();
        a.releasePlayer();
        a.generation++;
        ++homeRequest;
        if (resumeHome != null) resumeHome.run();
        a.root = retainedRoot;
        a.setContentView(retainedRoot);
        a.watchRootLayout(retainedRoot);
        a.applyMobileSystemBars(false);
        a.scroll = homeScroll;
        a.body = homeBody;
        if (a.current != null) a.current.home = true;
        return true;
    }

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
        return a.statusBarHeight();
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

    /**
     * Bottom-sheet drawer selector for all customizable settings on mobile (replaces tap-to-toggle
     * and modal dialogs with a unified slide-up drawer).
     */
    static void showDrawerPicker(
            MainActivity a,
            String title,
            String subtitle,
            String[] options,
            int selectedIndex,
            PlayerBubble.IntAction onSelect) {
        final android.app.Dialog dlg = new android.app.Dialog(a);
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        if (dlg.getWindow() != null) {
            dlg.getWindow()
                    .setBackgroundDrawable(
                            new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            dlg.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            dlg.getWindow()
                    .setLayout(
                            android.view.WindowManager.LayoutParams.MATCH_PARENT,
                            android.view.WindowManager.LayoutParams.MATCH_PARENT);
        }

        FrameLayout backdrop = new FrameLayout(a);
        backdrop.setMinimumWidth(a.windowWidth());
        backdrop.setMinimumHeight(a.windowHeight());
        backdrop.setBackgroundColor(0x88000000);
        backdrop.setOnClickListener(v -> dlg.dismiss());

        int sheetWidth =
                a.isPortrait() ? a.windowWidth() : Math.min(a.windowWidth() - a.dp(32), a.dp(540));
        LinearLayout sheet = a.vertical();
        sheet.setClickable(true);
        sheet.setMinimumWidth(sheetWidth);
        sheet.setPadding(a.dp(20), a.dp(12), a.dp(20), a.dp(28));
        GradientDrawable sheetBg =
                new GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[] {0xf619221f, 0xfa101614});
        float r = a.dp(26);
        sheetBg.setCornerRadii(new float[] {r, r, r, r, 0, 0, 0, 0});
        sheetBg.setStroke(Math.max(1, a.dp(1)), 0x33ffffff);
        sheet.setBackground(sheetBg);

        // Top drag handle pill
        View handle = new View(a);
        handle.setBackground(a.bg(0x48ffffff, 999));
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(a.dp(40), a.dp(4));
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.bottomMargin = a.dp(14);
        sheet.addView(handle, handleLp);

        LinearLayout headerRow = a.row();
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titleCol = a.vertical();
        TextView titleView = a.text(title, 18, Color.WHITE);
        titleView.setTypeface(null, Typeface.BOLD);
        titleCol.addView(titleView);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView subView = a.text(subtitle, 12.5f, MainActivity.MUTED);
            subView.setPadding(0, a.dp(4), 0, 0);
            titleCol.addView(subView);
        }
        headerRow.addView(titleCol, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView closeBtn = a.text("关闭", 12.5f, 0xffe5e7eb);
        closeBtn.setGravity(Gravity.CENTER);
        closeBtn.setPadding(a.dp(13), a.dp(6), a.dp(13), a.dp(6));
        closeBtn.setBackground(a.bg(0x24ffffff, 999));
        MobileDetailScreen.bindTouchFeedback(closeBtn);
        closeBtn.setOnClickListener(v -> dlg.dismiss());
        headerRow.addView(closeBtn, new LinearLayout.LayoutParams(-2, -2));
        sheet.addView(headerRow, new LinearLayout.LayoutParams(-1, -2));

        a.gap(sheet, 16);

        ScrollView scroll = new ScrollView(a);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout list = a.vertical();
        for (int i = 0; i < options.length; i++) {
            final int idx = i;
            boolean active = (i == selectedIndex);
            LinearLayout optRow = a.row();
            optRow.setGravity(Gravity.CENTER_VERTICAL);
            optRow.setPadding(a.dp(16), a.dp(15), a.dp(16), a.dp(15));
            optRow.setBackground(
                    active
                            ? glassBg(0x380a84ff, 0x260a84ff, a.dp(16), a.dp(1f), 0x885ac8fa)
                            : glassBg(0xb8202926, 0xb818201d, a.dp(16), a.dp(0.7f), 0x22ffffff));
            MobileDetailScreen.bindTouchFeedback(optRow);

            TextView label = a.text(options[i], 14.5f, active ? Color.WHITE : 0xffe5e7eb);
            label.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
            optRow.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));

            TextView check = a.text(active ? "✓" : "", 16, 0xff5ac8fa);
            check.setTypeface(null, Typeface.BOLD);
            optRow.addView(check, new LinearLayout.LayoutParams(-2, -2));

            optRow.setOnClickListener(
                    v -> {
                        dlg.dismiss();
                        if (onSelect != null) onSelect.accept(idx);
                    });

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = a.dp(10);
            list.addView(optRow, lp);
        }
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        int maxSheetHeight = Math.round(a.windowHeight() * 0.72f);
        sheet.addView(scroll, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout.LayoutParams sheetLp =
                new FrameLayout.LayoutParams(
                        sheetWidth, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        sheetLp.topMargin = Math.max(a.dp(80), a.windowHeight() - maxSheetHeight);
        backdrop.addView(sheet, sheetLp);

        dlg.setContentView(
                backdrop,
                new android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        dlg.show();
        if (dlg.getWindow() != null) {
            dlg.getWindow()
                    .setLayout(
                            android.view.WindowManager.LayoutParams.MATCH_PARENT,
                            android.view.WindowManager.LayoutParams.MATCH_PARENT);
            dlg.getWindow().setGravity(Gravity.BOTTOM | Gravity.FILL_HORIZONTAL);
        }
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
                                                SourceController.applyLocalPackage(a, pkg);
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
        a.watchRootLayout(a.root);
        renderedPortrait = a.isPortrait();
        final boolean builtPortrait = renderedPortrait;
        final FrameLayout shellRoot = a.root;
        shellRoot.addOnLayoutChangeListener(
                (view, l, t, r, b, ol, ot, or, ob) -> {
                    int w = r - l, h = b - t;
                    if (w <= 0 || h <= 0 || a.root != shellRoot) return;
                    a.usableWidth = w;
                    a.usableHeight = h;
                    if ((w < h) != builtPortrait) {
                        a.ui.post(
                                () -> {
                                    if (!a.isFinishing()
                                            && !a.isDestroyed()
                                            && a.root == shellRoot
                                            && a.current != null
                                            && a.current.home) {
                                        MobileShell.show(a);
                                    }
                                });
                    }
                });

        // Layer 0: iOS 27 Emerald-Obsidian Ambient Background
        a.root.addView(new AmbientCanvasView(a), new FrameLayout.LayoutParams(-1, -1));

        float widthDp = a.windowWidth() / a.getResources().getDisplayMetrics().density;
        boolean tabletSideRail = widthDp >= 640 && !a.isPortrait();
        float navScale = Prefs.clampNavScale(a.prefs.getInt(Prefs.NAV_SCALE_PERCENT, 100)) / 100f;

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
                    new FrameLayout.LayoutParams(
                            a.dp(72 * navScale), a.dp(296 * navScale), Gravity.CENTER_VERTICAL);
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
            a.root.addView(
                    bottomFade,
                    new FrameLayout.LayoutParams(-1, a.dp(92 * navScale), Gravity.BOTTOM));

            // Layer 4: iPhone Floating Liquid Glass 4-Tab Pill Dock
            LinearLayout bottomDock = buildNavigationBar(false);
            int dockWidthPx =
                    Math.min(a.dp(286 * navScale), Math.max(a.dp(244), a.windowWidth() - a.dp(32)));
            FrameLayout.LayoutParams dockLp =
                    new FrameLayout.LayoutParams(
                            dockWidthPx,
                            a.dp(64 * navScale),
                            Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            dockLp.bottomMargin = a.dp(16);
            a.root.addView(bottomDock, dockLp);
        }

        switchTab(activeTab);
        retainedRoot = a.root;
        retainedWidth = a.windowWidth();
        retainedHeight = a.windowHeight();
        retainedEpoch = a.engineEpoch;
        if (a.current != null) a.current.mobileHome = this;
    }

    private void resolveActiveSite() {
        currentSite = null;
        if (a.config != null && !a.config.sites.isEmpty()) {
            String wanted = a.prefs.getString(Prefs.SELECTED_SITE, "");
            for (Cms.Site s : a.config.sites) {
                if (s.key.equals(wanted) && a.available(s)) {
                    currentSite = s;
                    break;
                }
            }
            if (currentSite == null) {
                for (Cms.Site s : a.config.sites) {
                    if (a.available(s)) {
                        currentSite = s;
                        a.prefs.edit().putString(Prefs.SELECTED_SITE, s.key).apply();
                        break;
                    }
                }
            }
        }
        String nextSiteKey = currentSite != null ? currentSite.key : "";
        if (!nextSiteKey.equals(cachedCategorySiteKey)) {
            cachedCategorySiteKey = nextSiteKey;
            selectedCategoryId = "";
            currentCategories = new ArrayList<>();
            filterSchema = "{}";
            selectedFilters.clear();
            cachedCatScrollX = 0;
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

        boolean compactBrand =
                a.windowWidth() < a.dp(360) || a.getResources().getConfiguration().fontScale > 1.2f;
        TextView brandTitle =
                a.text(
                        a.privateMode ? "隐私 · 退出" : compactBrand ? "Marco\nMovie" : "Marco Movie",
                        compactBrand ? 12 : 14,
                        Color.WHITE);
        brandTitle.setTypeface(null, Typeface.BOLD);
        brandTitle.setSingleLine(!compactBrand);
        brandTitle.setMaxLines(compactBrand ? 2 : 1);
        brandTitle.setContentDescription(a.privateMode ? "退出隐私空间" : "Marco Movie");
        brandRow.addView(brandTitle);
        brandRow.setMinimumHeight(a.dp(44));
        brandRow.setOnClickListener(
                v -> {
                    if (a.privateMode) PrivacySpace.switchSpace(a, false);
                    else PrivacySpace.logoTap(a);
                });
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
        int maxSiteWidth = Math.max(a.dp(48), Math.min(a.dp(136), a.windowWidth() - a.dp(250)));
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
        float navScale = Prefs.clampNavScale(a.prefs.getInt(Prefs.NAV_SCALE_PERCENT, 100)) / 100f;

        for (int pos = 0; pos < DOCK_TAB_INDICES.length; pos++) {
            final int tabIdx = DOCK_TAB_INDICES[pos];
            LinearLayout tab = a.vertical();
            tab.setTag(navKeys[tabIdx]);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(a.dp(4), a.dp(4), a.dp(4), a.dp(4));
            MobileDetailScreen.bindTouchFeedback(tab);

            NavGlyph glyph = new NavGlyph(a, tabIdx);
            navIcons[tabIdx] = glyph;
            tab.addView(
                    glyph, new LinearLayout.LayoutParams(a.dp(22 * navScale), a.dp(22 * navScale)));

            TextView label = a.text(TAB_NAMES[tabIdx], 10.5f * navScale, 0xffe5e7eb);
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
    private LinearLayout homeBody;

    void switchTab(int index) {
        ++homeRequest;
        homeReady = false;
        ++a.generation;
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

        final Runnable[] triggerPullRefresh = new Runnable[1];
        final TextView pullRefreshBadge = a.text("↓ 下拉刷新当前内容", 12.5f, MainActivity.WHITE);
        pullRefreshBadge.setTypeface(null, Typeface.BOLD);
        pullRefreshBadge.setGravity(Gravity.CENTER);
        pullRefreshBadge.setPadding(a.dp(16), a.dp(8), a.dp(16), a.dp(8));
        pullRefreshBadge.setBackground(
                glassBg(0xd81e2824, 0xd8141b18, a.dp(999), a.dp(0.8f), 0x40ffffff));
        pullRefreshBadge.setElevation(a.dp(12));
        pullRefreshBadge.setVisibility(View.GONE);

        final LinearLayout body = a.vertical();
        final int pullSlop = a.dp(10);
        final int pullThreshold = a.dp(44);
        final int pullMaxOffset = a.dp(76);
        final ScrollView scrollView =
                new ScrollView(a) {
                    private float startX;
                    private float startY;
                    private boolean trackingTopPull;
                    private boolean pullingDown;
                    private float currentPullOffset;

                    @Override
                    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
                        int action = ev.getActionMasked();
                        if (action == android.view.MotionEvent.ACTION_DOWN) {
                            startX = ev.getRawX();
                            startY = ev.getRawY();
                            trackingTopPull = getScrollY() <= 0;
                            pullingDown = false;
                            currentPullOffset = 0f;
                        } else if (action == android.view.MotionEvent.ACTION_MOVE) {
                            if (!trackingTopPull && getScrollY() <= 0) {
                                startX = ev.getRawX();
                                startY = ev.getRawY();
                                trackingTopPull = true;
                            } else if (getScrollY() > 0 && !pullingDown) {
                                trackingTopPull = false;
                            }
                            if (trackingTopPull) {
                                float dx = ev.getRawX() - startX;
                                float dy = ev.getRawY() - startY;
                                if (!pullingDown
                                        && dy > pullSlop
                                        && dy > Math.abs(dx) * 1.2f
                                        && getScrollY() <= 0) {
                                    pullingDown = true;
                                }
                                if (pullingDown) {
                                    currentPullOffset =
                                            Math.max(
                                                    0f,
                                                    Math.min(
                                                            pullMaxOffset,
                                                            (dy - pullSlop) * 0.42f));
                                    body.setTranslationY(currentPullOffset);
                                    pullRefreshBadge.setVisibility(View.VISIBLE);
                                    pullRefreshBadge.setAlpha(
                                            Math.min(1f, currentPullOffset / pullThreshold));
                                    pullRefreshBadge.setText(
                                            currentPullOffset >= pullThreshold
                                                    ? "↑ 松开刷新当前内容"
                                                    : "↓ 下拉刷新当前内容");
                                    return true;
                                }
                            }
                        } else if (action == android.view.MotionEvent.ACTION_UP
                                || action == android.view.MotionEvent.ACTION_CANCEL) {
                            if (pullingDown) {
                                boolean shouldRefresh =
                                        action == android.view.MotionEvent.ACTION_UP
                                                && currentPullOffset >= pullThreshold;
                                pullingDown = false;
                                trackingTopPull = false;
                                currentPullOffset = 0f;
                                body.animate().translationY(0f).setDuration(180).start();
                                if (shouldRefresh && triggerPullRefresh[0] != null) {
                                    pullRefreshBadge.setVisibility(View.VISIBLE);
                                    pullRefreshBadge.setAlpha(1f);
                                    pullRefreshBadge.setText("正在刷新…");
                                    triggerPullRefresh[0].run();
                                } else {
                                    pullRefreshBadge.setVisibility(View.GONE);
                                }
                                return true;
                            }
                            trackingTopPull = false;
                        }
                        return super.dispatchTouchEvent(ev);
                    }
                };
        homeScroll = scrollView;
        scrollView.post(() -> scrollView.scrollTo(0, homeScrollY));
        scrollView.setFillViewport(true);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.setClipToPadding(false);
        a.scroll = scrollView;
        contentContainer.addView(scrollView, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout.LayoutParams badgeLp =
                new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        badgeLp.topMargin = statusBarHeight() + a.dp(10);
        contentContainer.addView(pullRefreshBadge, badgeLp);

        a.body = body;
        homeBody = body;
        body.setPadding(0, statusBarHeight() + a.dp(8), 0, a.dp(106));
        scrollView.addView(body, new ScrollView.LayoutParams(-1, -2));

        // 1. Scrollable Top Header Row (Brand + Site Capsule + Netdisk Capsule + Search Icon)
        body.addView(buildHomeTopHeaderRow(), new LinearLayout.LayoutParams(-1, -2));

        // 2. History shelf; the newest entry remains first and resumes on tap.
        List<WatchHistory.Entry> history = a.watchHistory();
        if (!history.isEmpty()) {
            LinearLayout histHeader = a.row();
            histHeader.setGravity(Gravity.CENTER_VERTICAL);
            histHeader.setPadding(a.dp(16), a.dp(14), a.dp(16), a.dp(8));
            TextView hTitle = a.text("历史记录", 18, MainActivity.WHITE);
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

        // 2.5 豆瓣热播榜货架（独立于当前片源，解决片源无电视剧/片单不跟热播的问题）
        DoubanHot.attach(a, body);

        // 3. Featured Hero Showcase Card Container ("今日首推")
        final FrameLayout heroContainer = new FrameLayout(a);
        heroContainer.setPadding(a.dp(16), a.dp(14), a.dp(16), 0);
        heroContainer.setVisibility(View.GONE);
        body.addView(heroContainer, new LinearLayout.LayoutParams(-1, -2));

        // 4. Horizontal Scrollable Category Pill Bar
        HorizontalScrollView catScroll =
                new HorizontalScrollView(a) {
                    @Override
                    protected void dispatchDraw(Canvas canvas) {
                        int save = canvas.save();
                        canvas.clipRect(getScrollX(), 0, getScrollX() + getWidth(), getHeight());
                        super.dispatchDraw(canvas);
                        canvas.restoreToCount(save);
                    }
                };
        catScroll.setHorizontalScrollBarEnabled(false);
        catScroll.setClipChildren(true);
        catScroll.setClipToPadding(false);
        catScroll.setPadding(a.dp(16), a.dp(14), a.dp(8), a.dp(10));
        catScroll
                .getViewTreeObserver()
                .addOnScrollChangedListener(
                        () -> {
                            if (catScroll.getWidth() > 0) {
                                cachedCatScrollX = catScroll.getScrollX();
                            }
                        });
        final LinearLayout catRow = a.row();
        catRow.setGravity(Gravity.CENTER_VERTICAL);
        catScroll.addView(catRow);
        LinearLayout categoryBar = a.row();
        categoryBar.setClipChildren(true);
        categoryBar.setGravity(Gravity.CENTER_VERTICAL);
        categoryBar.addView(catScroll, new LinearLayout.LayoutParams(0, -2, 1));
        float navScale = Prefs.clampNavScale(a.prefs.getInt(Prefs.NAV_SCALE_PERCENT, 100)) / 100f;
        TextView filterButton = a.text("筛选", 13 * navScale, MainActivity.WHITE);
        filterButton.setTag("home-filters");
        filterButton.setSingleLine(true);
        filterButton.setGravity(Gravity.CENTER);
        filterButton.setPadding(
                a.dp(14 * navScale), a.dp(9 * navScale), a.dp(14 * navScale), a.dp(9 * navScale));
        a.glass(filterButton, 999);
        LinearLayout.LayoutParams filterSize = new LinearLayout.LayoutParams(-2, -2);
        filterSize.leftMargin = a.dp(8);
        filterSize.rightMargin = a.dp(16);
        filterSize.topMargin = a.dp(2);
        categoryBar.addView(filterButton, filterSize);
        body.addView(categoryBar, new LinearLayout.LayoutParams(-1, -2));

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
        final boolean[] pageFailed = {false};
        final int[] completedPage = {0};
        resumeHome =
                () -> {
                    boolean showHero =
                            !SourceController.isConfigOrNetdiskSite(currentSite)
                                    && a.prefs.getBoolean(Prefs.SHOW_HERO_BANNER, true)
                                    && selectedCategoryId.isEmpty()
                                    && !currentVideos.isEmpty();
                    if (showHero) {
                        if (heroContainer.getChildCount() == 0) {
                            heroContainer.addView(
                                    buildHeroShowcaseCard(currentSite, currentVideos.get(0)));
                        }
                        heroContainer.setVisibility(View.VISIBLE);
                    } else {
                        heroContainer.removeAllViews();
                        heroContainer.setVisibility(View.GONE);
                    }
                    if (!loadingPage[0]) return;
                    loadingPage[0] = false;
                    pageFailed[0] = false;
                    currentPage = Math.max(1, completedPage[0]);
                    loadMoreBtn.setVisibility(currentPage < totalPages ? View.VISIBLE : View.GONE);
                    loadMoreBtn.setText("加载更多影片 (" + currentPage + "/" + totalPages + ")");
                };
        final Runnable[] fetchCategoryPage = new Runnable[1];
        reloadCategory =
                () -> {
                    if (currentSite != null && selectedCategoryId.isEmpty()) {
                        a.homeData.remove(currentSite.key);
                    }
                    currentPage = 1;
                    if (fetchCategoryPage[0] != null) fetchCategoryPage[0].run();
                };
        triggerPullRefresh[0] =
                () -> {
                    if (loadingPage[0]) {
                        pullRefreshBadge.setVisibility(View.GONE);
                        return;
                    }
                    if (currentSite != null && selectedCategoryId.isEmpty()) {
                        a.homeData.remove(currentSite.key);
                    }
                    currentPage = 1;
                    if (fetchCategoryPage[0] != null) fetchCategoryPage[0].run();
                };
        final Runnable renderCategoryChips =
                new Runnable() {
                    @Override
                    public void run() {
                        final int savedCatX =
                                catScroll.getScrollX() > 0
                                        ? catScroll.getScrollX()
                                        : cachedCatScrollX;
                        boolean configSite = SourceController.isConfigOrNetdiskSite(currentSite);
                        filterButton.setVisibility(configSite ? View.GONE : View.VISIBLE);
                        filterButton.setText(
                                selectedFilters.isEmpty()
                                        ? "筛选"
                                        : "筛选 · " + selectedFilters.size());
                        if (configSite
                                && selectedCategoryId.isEmpty()
                                && !currentCategories.isEmpty()) {
                            selectedCategoryId = currentCategories.get(0).id;
                        }
                        catRow.removeAllViews();
                        if (!configSite || currentCategories.isEmpty()) {
                            addCategoryChip(catRow, "推荐", "", this, fetchCategoryPage[0]);
                        }
                        for (Cms.Category c : currentCategories) {
                            addCategoryChip(catRow, c.name, c.id, this, fetchCategoryPage[0]);
                        }
                        if (savedCatX > 0) {
                            catScroll.scrollTo(savedCatX, 0);
                            catScroll.post(() -> catScroll.scrollTo(savedCatX, 0));
                        }
                    }
                };

        filterButton.setOnClickListener(
                v ->
                        VideoFilterDialog.show(
                                a,
                                mobileFilterDefinitions(),
                                selectedFilters,
                                () -> {
                                    if (selectedCategoryId.isEmpty()
                                            && !currentCategories.isEmpty()) {
                                        selectedCategoryId = currentCategories.get(0).id;
                                        renderCategoryChips.run();
                                    }
                                    currentPage = 1;
                                    filterButton.setText(
                                            selectedFilters.isEmpty()
                                                    ? "筛选"
                                                    : "筛选 · " + selectedFilters.size());
                                    fetchCategoryPage[0].run();
                                }));
        fetchCategoryPage[0] =
                () -> {
                    final int request = ++homeRequest;
                    loadingPage[0] = true;
                    if (currentPage == 1) {
                        if (!selectedCategoryId.isEmpty()) {
                            heroContainer.removeAllViews();
                            heroContainer.setVisibility(View.GONE);
                        }
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
                    final java.util.Map<String, String> filterSnapshot =
                            new java.util.LinkedHashMap<>(selectedFilters);
                    final List<VideoFilters.Definition> definitions = mobileFilterDefinitions();
                    final int ticket = a.generation;
                    final HomeScreen.Snapshot cached =
                            siteSnapshot == null ? null : a.homeData.get(siteSnapshot.key);
                    if (catSnapshot.isEmpty() && pageSnapshot == 1 && siteSnapshot != null) {
                        a.prefs.edit().putString(Prefs.LOADING_SITE, siteSnapshot.key).commit();
                    }
                    a.submitPage(
                            () -> {
                                try {
                                    SourceEngine eng = a.engine(siteSnapshot);
                                    List<Cms.Category> cats = currentCategories;
                                    Cms.Listing list;
                                    HomeScreen.Snapshot homeSnapshot = null;
                                    if (catSnapshot.isEmpty() && pageSnapshot == 1) {
                                        String raw =
                                                cached != null
                                                                && System.currentTimeMillis()
                                                                                - cached.time
                                                                        < 300000
                                                        ? cached.filterSchema
                                                        : eng.home();
                                        cats = Cms.categories(raw);
                                        list =
                                                new JSONObject(raw).has("list")
                                                        ? Cms.listing(raw, siteSnapshot)
                                                        : new Cms.Listing();
                                        if (list.videos.isEmpty()
                                                && cached != null
                                                && System.currentTimeMillis() - cached.time
                                                        < 300000) {
                                            list.videos.addAll(cached.videos);
                                            list.pageCount = cached.pages;
                                        }
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
                                        HomeScreen.Snapshot saved =
                                                new HomeScreen.Snapshot(
                                                        list.videos, cats, "", list.pageCount);
                                        saved.filterSchema = raw;
                                        homeSnapshot = saved;
                                    } else {
                                        if (cats.isEmpty()) {
                                            if (cached != null
                                                    && cached.categories != null
                                                    && !cached.categories.isEmpty()) {
                                                cats = new ArrayList<>(cached.categories);
                                            } else {
                                                try {
                                                    String rawHome = eng.home();
                                                    cats = Cms.categories(rawHome);
                                                    if (filterSchema == null
                                                            || filterSchema.isEmpty()
                                                            || "{}".equals(filterSchema)) {
                                                        filterSchema = rawHome;
                                                    }
                                                } catch (Throwable ignored) {
                                                }
                                            }
                                        }
                                        String targetCat =
                                                catSnapshot.isEmpty() && !cats.isEmpty()
                                                        ? cats.get(0).id
                                                        : catSnapshot;
                                        list =
                                                Cms.listing(
                                                        eng.category(
                                                                targetCat,
                                                                pageSnapshot,
                                                                VideoFilters.remoteValues(
                                                                        definitions,
                                                                        filterSnapshot)),
                                                        siteSnapshot);
                                    }
                                    final HomeScreen.Snapshot finalHomeSnapshot = homeSnapshot;
                                    final List<Cms.Category> finalCats = cats;
                                    final Cms.Listing finalList = list;
                                    final List<Cms.Video> filtered =
                                            VideoFilters.apply(
                                                    list.videos, definitions, filterSnapshot);
                                    a.learnTitles(finalList.videos);
                                    a.ui.post(
                                            () -> {
                                                a.prefs
                                                        .edit()
                                                        .putString(Prefs.LOADING_SITE, "")
                                                        .apply();
                                                if (ticket != a.generation
                                                        || a.isFinishing()
                                                        || request != homeRequest) return;
                                                loadingPage[0] = false;
                                                pageFailed[0] = false;
                                                completedPage[0] = pageSnapshot;
                                                homeReady = true;
                                                pullRefreshBadge.setVisibility(View.GONE);
                                                if (finalHomeSnapshot != null) {
                                                    filterSchema = finalHomeSnapshot.filterSchema;
                                                    a.homeData.put(
                                                            siteSnapshot.key, finalHomeSnapshot);
                                                }
                                                currentCategories = finalCats;
                                                if (pageSnapshot == 1) {
                                                    renderCategoryChips.run();
                                                }
                                                boolean configOrNetdisk =
                                                        SourceController.isConfigOrNetdiskSite(
                                                                siteSnapshot);
                                                if (pageSnapshot == 1) currentVideos.clear();
                                                for (Cms.Video v : filtered) {
                                                    if (configOrNetdisk
                                                            || v.action == null
                                                            || v.action.isEmpty()) {
                                                        currentVideos.add(v);
                                                    }
                                                }
                                                totalPages = Math.max(1, finalList.pageCount);
                                                statusText.setVisibility(
                                                        currentVideos.isEmpty()
                                                                ? View.VISIBLE
                                                                : View.GONE);
                                                if (currentVideos.isEmpty()) {
                                                    statusText.setText(
                                                            selectedFilters.isEmpty()
                                                                    ? "当前分类暂无影片"
                                                                    : "当前筛选暂无匹配，可继续加载更多影片");
                                                }
                                                // Render Featured Hero Showcase on Recommend tab
                                                if (!configOrNetdisk
                                                        && a.prefs.getBoolean(
                                                                Prefs.SHOW_HERO_BANNER, true)
                                                        && catSnapshot.isEmpty()
                                                        && !currentVideos.isEmpty()) {
                                                    heroContainer.removeAllViews();
                                                    heroContainer.addView(
                                                            buildHeroShowcaseCard(
                                                                    siteSnapshot,
                                                                    currentVideos.get(0)));
                                                    heroContainer.setVisibility(View.VISIBLE);
                                                } else {
                                                    heroContainer.removeAllViews();
                                                    heroContainer.setVisibility(View.GONE);
                                                }
                                                final int savedScrollY = scrollView.getScrollY();
                                                renderVideoGrid(
                                                        gridContainer, siteSnapshot, currentVideos);
                                                if (pageSnapshot > 1 && savedScrollY > 0) {
                                                    scrollView.scrollTo(0, savedScrollY);
                                                    scrollView.post(
                                                            () ->
                                                                    scrollView.scrollTo(
                                                                            0, savedScrollY));
                                                }
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
                                                if (ticket != a.generation
                                                        || a.isFinishing()
                                                        || request != homeRequest) return;
                                                a.prefs
                                                        .edit()
                                                        .putString(Prefs.LOADING_SITE, "")
                                                        .apply();
                                                if (ticket != a.generation
                                                        || a.isFinishing()
                                                        || request != homeRequest) return;
                                                loadingPage[0] = false;
                                                pageFailed[0] = true;
                                                homeReady = !currentVideos.isEmpty();
                                                pullRefreshBadge.setVisibility(View.GONE);
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
                    if (!pageFailed[0] && currentPage < totalPages) currentPage++;
                    fetchCategoryPage[0].run();
                });

        // Auto-load next page when scrolling near bottom (matching iPhone infinite scroll)
        scrollView
                .getViewTreeObserver()
                .addOnScrollChangedListener(
                        () -> {
                            if (loadingPage[0] || pageFailed[0] || currentPage >= totalPages)
                                return;
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
     * Builds the iPhone Featured Hero Showcase card (`_buildHeroShowcaseCard` in
     * `adaptive_shell.dart`): rounded `24dp` glass container with dimmed backdrop artwork, left
     * `2:3` poster, right `今日首推` pill, bold title, metadata chips, and bottom-right white `▶ 立即观看`
     * capsule button.
     */
    private View buildHeroShowcaseCard(final Cms.Site site, final Cms.Video featured) {
        FrameLayout card = new FrameLayout(a);
        card.setTag("hero-watch-now-button");
        card.setBackground(glassBg(0xff141c19, 0xff0d1311, a.dp(24), a.dp(0.8f), 0x38ffffff));
        card.setClipToOutline(true);
        MobileDetailScreen.bindTouchFeedback(card);
        card.setOnClickListener(v -> a.openListing(site, featured));

        // Ambient backdrop image
        ImageView bgImg = new ImageView(a);
        bgImg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        bgImg.setAlpha(0.32f);
        card.addView(bgImg, new FrameLayout.LayoutParams(-1, -1));
        if (featured.poster != null && !featured.poster.isEmpty()) a.poster(featured.poster, bgImg);

        // Dark gradient scrim
        View scrim = new View(a);
        scrim.setBackground(
                new GradientDrawable(
                        GradientDrawable.Orientation.TL_BR,
                        new int[] {0x44000000, 0xb8080d0b, 0xeb060908}));
        card.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout contentRow = a.row();
        contentRow.setGravity(Gravity.CENTER_VERTICAL);
        contentRow.setPadding(a.dp(16), a.dp(16), a.dp(16), a.dp(16));
        card.addView(contentRow, new FrameLayout.LayoutParams(-1, a.dp(198)));

        // Left 2:3 Poster
        ImageView poster = new ImageView(a);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setBackground(a.bg(0xff1e2623, 14));
        poster.setClipToOutline(true);
        LinearLayout.LayoutParams posterLp = new LinearLayout.LayoutParams(a.dp(110), a.dp(166));
        posterLp.rightMargin = a.dp(14);
        contentRow.addView(poster, posterLp);
        if (featured.poster != null && !featured.poster.isEmpty())
            a.poster(featured.poster, poster);

        // Right Column
        LinearLayout rightCol = a.vertical();
        contentRow.addView(rightCol, new LinearLayout.LayoutParams(0, -1, 1f));

        TextView topBadge = a.text("✦ 今日首推 · " + site.name, 11, Color.WHITE);
        topBadge.setTypeface(null, Typeface.BOLD);
        topBadge.setSingleLine(true);
        topBadge.setEllipsize(TextUtils.TruncateAt.END);
        topBadge.setPadding(a.dp(9), a.dp(3), a.dp(9), a.dp(3));
        topBadge.setBackground(glassBg(0x38ffffff, 0x24ffffff, a.dp(999), a.dp(0.6f), 0x33ffffff));
        rightCol.addView(topBadge, new LinearLayout.LayoutParams(-2, -2));

        TextView title = a.text(featured.title, 18, Color.WHITE);
        title.setTypeface(null, Typeface.BOLD);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(0, a.dp(7), 0, a.dp(5));
        rightCol.addView(title);

        LinearLayout chipsRow = a.row();
        String[] tags = {featured.year, featured.area, featured.remarks};
        boolean hasChip = false;
        for (String rawTag : tags) {
            if (rawTag == null || rawTag.trim().isEmpty()) continue;
            TextView chip = a.text(rawTag.trim(), 10.5f, 0xffd1d5db);
            chip.setTypeface(null, Typeface.BOLD);
            chip.setSingleLine(true);
            chip.setEllipsize(TextUtils.TruncateAt.END);
            chip.setMaxWidth(a.dp(110));
            chip.setPadding(a.dp(7), a.dp(2), a.dp(7), a.dp(2));
            chip.setBackground(a.bg(0x29ffffff, 6));
            LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(-2, -2);
            chipLp.rightMargin = a.dp(6);
            chipsRow.addView(chip, chipLp);
            hasChip = true;
        }
        if (hasChip) {
            rightCol.addView(chipsRow, new LinearLayout.LayoutParams(-1, -2));
        }

        View flexSpacer = new View(a);
        rightCol.addView(flexSpacer, new LinearLayout.LayoutParams(1, 0, 1f));

        // Bottom-right Watch Now white capsule button
        LinearLayout bottomRow = a.row();
        bottomRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        TextView watchBtn = a.text("▶ 立即观看", 12.5f, 0xff0b0f0d);
        watchBtn.setTypeface(null, Typeface.BOLD);
        watchBtn.setGravity(Gravity.CENTER);
        watchBtn.setPadding(a.dp(14), a.dp(7), a.dp(14), a.dp(7));
        watchBtn.setBackground(glassBg(0xffffffff, 0xffeaeaf0, a.dp(999), 0, 0));
        bottomRow.addView(watchBtn, new LinearLayout.LayoutParams(-2, -2));
        rightCol.addView(bottomRow, new LinearLayout.LayoutParams(-1, -2));

        return card;
    }

    private List<VideoFilters.Definition> mobileFilterDefinitions() {
        String category =
                selectedCategoryId.isEmpty() && !currentCategories.isEmpty()
                        ? currentCategories.get(0).id
                        : selectedCategoryId;
        List<VideoFilters.Definition> defs =
                VideoFilters.definitions(filterSchema, category, currentVideos);
        if (currentSite != null && currentSite.supported())
            for (VideoFilters.Definition def : defs)
                if (!VideoFilters.allowedKey(def.key)) def.remote = false;
        return defs;
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
        float navScale = Prefs.clampNavScale(a.prefs.getInt(Prefs.NAV_SCALE_PERCENT, 100)) / 100f;
        TextView chip = a.text(label, 13 * navScale, active ? 0xff000000 : 0xfff3f4f6);
        chip.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(
                a.dp(15 * navScale), a.dp(7 * navScale), a.dp(15 * navScale), a.dp(7 * navScale));
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
                    selectedFilters.clear();
                    currentPage = 1;
                    homeScrollY = 0;
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
        logo.setContentDescription(a.privateMode ? "退出隐私空间" : "Marco Movie");
        logo.setOnClickListener(
                v -> {
                    if (a.privateMode) PrivacySpace.switchSpace(a, false);
                    else PrivacySpace.logoTap(a);
                });
        card.addView(logo, new LinearLayout.LayoutParams(a.dp(64), a.dp(64)));
        a.gap(card, 12);

        if (a.privateMode) {
            addMobileActionBtn(
                    card,
                    "退出隐私空间",
                    0xff1e2724,
                    MainActivity.WHITE,
                    () -> PrivacySpace.switchSpace(a, false));
            a.gap(card, 12);
        }

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
        searchStatus.setMaxLines(2);
        searchStatus.setEllipsize(TextUtils.TruncateAt.END);
        searchStatus.setPadding(a.dp(4), a.dp(10), a.dp(4), a.dp(6));
        page.addView(searchStatus);

        final HorizontalScrollView siteFilterScroll = new HorizontalScrollView(a);
        siteFilterScroll.setHorizontalScrollBarEnabled(false);
        siteFilterScroll.setPadding(0, a.dp(2), 0, a.dp(8));
        siteFilterScroll.setVisibility(View.GONE);
        siteFilterScroll
                .getViewTreeObserver()
                .addOnScrollChangedListener(
                        () -> {
                            cachedSiteFilterScrollX = siteFilterScroll.getScrollX();
                        });
        final LinearLayout siteFilterRow = a.row();
        siteFilterRow.setGravity(Gravity.CENTER_VERTICAL);
        siteFilterScroll.addView(siteFilterRow);
        page.addView(siteFilterScroll, new LinearLayout.LayoutParams(-1, -2));

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

        final int[] searchSerial = {0};
        final Runnable renderSearchStatus =
                () -> {
                    int separator = cachedSearchStatus.indexOf('\n');
                    String mainLine =
                            separator < 0
                                    ? cachedSearchStatus
                                    : cachedSearchStatus.substring(0, separator);
                    if (!cachedSearchSiteFilter.isEmpty() && !cachedSearchHits.isEmpty()) {
                        String filterName = cachedSearchSiteFilter;
                        int filteredCount = 0;
                        for (CachedSearchHit hit : cachedSearchHits) {
                            if (hit.site != null && cachedSearchSiteFilter.equals(hit.site.key)) {
                                filterName = hit.site.name;
                                filteredCount++;
                            }
                        }
                        mainLine =
                                "已筛选「"
                                        + filterName
                                        + "」："
                                        + filteredCount
                                        + " 条（共 "
                                        + cachedSearchHits.size()
                                        + " 条）";
                    }
                    searchStatus.setText(mainLine);
                };

        final Runnable[] refreshSiteFilterBar = new Runnable[1];
        final Runnable rebuildFilteredResults =
                () -> {
                    resultList.removeAllViews();
                    for (CachedSearchHit hit : cachedSearchHits) {
                        if (cachedSearchSiteFilter.isEmpty()
                                || (hit.site != null
                                        && cachedSearchSiteFilter.equals(hit.site.key))) {
                            resultList.addView(buildSearchResultRow(hit.site, hit.video));
                        }
                    }
                    renderSearchStatus.run();
                    if (refreshSiteFilterBar[0] != null) refreshSiteFilterBar[0].run();
                };

        refreshSiteFilterBar[0] =
                () -> {
                    siteFilterRow.removeAllViews();
                    if (cachedSearchHits.isEmpty()) {
                        siteFilterScroll.setVisibility(View.GONE);
                        return;
                    }
                    final java.util.Map<String, Cms.Site> hitSites =
                            new java.util.LinkedHashMap<>();
                    final java.util.Map<String, Integer> siteCounts =
                            new java.util.LinkedHashMap<>();
                    for (CachedSearchHit hit : cachedSearchHits) {
                        if (hit.site == null || hit.site.key == null) continue;
                        hitSites.put(hit.site.key, hit.site);
                        Integer prev = siteCounts.get(hit.site.key);
                        siteCounts.put(hit.site.key, prev == null ? 1 : prev + 1);
                    }
                    if (!cachedSearchSiteFilter.isEmpty()
                            && !siteCounts.containsKey(cachedSearchSiteFilter)) {
                        cachedSearchSiteFilter = "";
                    }
                    siteFilterScroll.setVisibility(View.VISIBLE);

                    if (hitSites.size() >= 2) {
                        TextView drawerBtn = a.text("筛选线路 ▾", 12, 0xff5ac8fa);
                        drawerBtn.setTypeface(null, Typeface.BOLD);
                        drawerBtn.setSingleLine(true);
                        drawerBtn.setPadding(a.dp(12), a.dp(5), a.dp(12), a.dp(5));
                        drawerBtn.setBackground(
                                glassBg(0xd81b2a34, 0xc8131f28, a.dp(999), a.dp(0.8f), 0x405ac8fa));
                        MobileDetailScreen.bindTouchFeedback(drawerBtn);
                        drawerBtn.setOnClickListener(
                                v -> {
                                    final List<String> keys = new ArrayList<>();
                                    final List<String> labels = new ArrayList<>();
                                    keys.add("");
                                    labels.add("全部线路（" + cachedSearchHits.size() + "）");
                                    int currentIdx = 0;
                                    for (java.util.Map.Entry<String, Cms.Site> entry :
                                            hitSites.entrySet()) {
                                        String k = entry.getKey();
                                        Cms.Site s = entry.getValue();
                                        int c = siteCounts.containsKey(k) ? siteCounts.get(k) : 0;
                                        if (k.equals(cachedSearchSiteFilter)) {
                                            currentIdx = keys.size();
                                        }
                                        keys.add(k);
                                        labels.add(s.name + "（" + c + "）");
                                    }
                                    showDrawerPicker(
                                            a,
                                            "筛选搜索线路",
                                            "选择仅展示指定片源的搜索结果，或恢复查看全部线路",
                                            labels.toArray(new String[0]),
                                            currentIdx,
                                            which -> {
                                                if (which >= 0 && which < keys.size()) {
                                                    cachedSearchSiteFilter = keys.get(which);
                                                    cachedSearchScrollY = 0;
                                                    resultScroll.scrollTo(0, 0);
                                                    rebuildFilteredResults.run();
                                                }
                                            });
                                });
                        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
                        dlp.rightMargin = a.dp(7);
                        siteFilterRow.addView(drawerBtn, dlp);
                    }

                    boolean allActive = cachedSearchSiteFilter.isEmpty();
                    TextView allChip =
                            a.text(
                                    "全部 (" + cachedSearchHits.size() + ")",
                                    12,
                                    allActive ? 0xff000000 : 0xffe5e7eb);
                    allChip.setTypeface(null, allActive ? Typeface.BOLD : Typeface.NORMAL);
                    allChip.setSingleLine(true);
                    allChip.setPadding(a.dp(13), a.dp(5), a.dp(13), a.dp(5));
                    allChip.setBackground(
                            allActive
                                    ? glassBg(0xffffffff, 0xffeaeaf0, a.dp(999), 0, 0)
                                    : glassBg(
                                            0xcc262e2b,
                                            0xb81a211f,
                                            a.dp(999),
                                            a.dp(0.7f),
                                            0x2effffff));
                    MobileDetailScreen.bindTouchFeedback(allChip);
                    allChip.setOnClickListener(
                            v -> {
                                if (!cachedSearchSiteFilter.isEmpty()) {
                                    cachedSearchSiteFilter = "";
                                    cachedSearchScrollY = 0;
                                    resultScroll.scrollTo(0, 0);
                                    rebuildFilteredResults.run();
                                }
                            });
                    LinearLayout.LayoutParams allLp = new LinearLayout.LayoutParams(-2, -2);
                    allLp.rightMargin = a.dp(7);
                    siteFilterRow.addView(allChip, allLp);

                    for (java.util.Map.Entry<String, Cms.Site> entry : hitSites.entrySet()) {
                        final String siteKey = entry.getKey();
                        final Cms.Site siteObj = entry.getValue();
                        int count = siteCounts.containsKey(siteKey) ? siteCounts.get(siteKey) : 0;
                        final boolean active = siteKey.equals(cachedSearchSiteFilter);
                        TextView chip =
                                a.text(
                                        siteObj.name + " (" + count + ")",
                                        12,
                                        active ? 0xff000000 : 0xffe5e7eb);
                        chip.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
                        chip.setSingleLine(true);
                        chip.setPadding(a.dp(13), a.dp(5), a.dp(13), a.dp(5));
                        chip.setBackground(
                                active
                                        ? glassBg(0xffffffff, 0xffeaeaf0, a.dp(999), 0, 0)
                                        : glassBg(
                                                0xcc262e2b,
                                                0xb81a211f,
                                                a.dp(999),
                                                a.dp(0.7f),
                                                0x2effffff));
                        MobileDetailScreen.bindTouchFeedback(chip);
                        chip.setOnClickListener(
                                v -> {
                                    cachedSearchSiteFilter = active ? "" : siteKey;
                                    cachedSearchScrollY = 0;
                                    resultScroll.scrollTo(0, 0);
                                    rebuildFilteredResults.run();
                                });
                        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                        lp.rightMargin = a.dp(7);
                        siteFilterRow.addView(chip, lp);
                    }
                };

        clearBtn.setOnClickListener(
                v -> {
                    searchSerial[0]++;
                    if (activeSearchCoordinator != null) {
                        activeSearchCoordinator.close();
                        activeSearchCoordinator = null;
                    }
                    a.cancelPageRequests();
                    input.setText("");
                    cachedSearchKeyword = "";
                    cachedSearchStatus = "";
                    cachedSearchSiteFilter = "";
                    cachedSearchHits.clear();
                    cachedSearchScrollY = 0;
                    cachedSiteFilterScrollX = 0;
                    siteFilterRow.removeAllViews();
                    siteFilterScroll.setVisibility(View.GONE);
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

                    // Private queries never enter history or the shared phonetic dictionary.
                    if (!a.privateMode)
                        try {
                            JSONArray old =
                                    new JSONArray(
                                            a.prefs.getString(Prefs.GLOBAL_SEARCH_HISTORY, "[]"));
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

                    final int searchTicket = ++searchSerial[0];
                    if (activeSearchCoordinator != null) {
                        activeSearchCoordinator.close();
                        activeSearchCoordinator = null;
                    }
                    a.cancelPageRequests();
                    resultList.removeAllViews();
                    cachedSearchKeyword = kw;
                    cachedSearchSiteFilter = "";
                    cachedSearchHits.clear();
                    cachedSearchScrollY = 0;
                    cachedSiteFilterScrollX = 0;
                    siteFilterRow.removeAllViews();
                    siteFilterScroll.setVisibility(View.GONE);

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
                    final int[] completed = {0};
                    final int[] hitCount = {0};

                    SearchCoordinator.configureThreads(a.prefs.getInt(Prefs.SEARCH_THREADS, 6));
                    activeSearchCoordinator =
                            new SearchCoordinator(
                                    (site, keyword) -> {
                                        List<Cms.Video> matches = new ArrayList<>();
                                        try {
                                            Cms.Listing l =
                                                    Cms.listing(
                                                            a.engine(site).search(keyword, 1),
                                                            site);
                                            for (Cms.Video v : l.videos) {
                                                if (v.action == null || v.action.isEmpty()) {
                                                    matches.add(v);
                                                }
                                            }
                                        } catch (
                                                com.pilipala.catpaw.CatPawHost.DrpySourceException
                                                        error) {
                                            if ("drpy_verification_required".equals(error.code)) {
                                                throw new SearchCoordinator
                                                        .VerificationRequiredException();
                                            }
                                            throw error;
                                        }
                                        return matches;
                                    },
                                    (site, matches, outcome) ->
                                            a.ui.post(
                                                    () -> {
                                                        if (searchTicket != searchSerial[0]
                                                                || a.isFinishing()
                                                                || a.isDestroyed()) return;
                                                        completed[0]++;
                                                        if (matches != null) {
                                                            for (final Cms.Video v : matches) {
                                                                hitCount[0]++;
                                                                if (kw.equals(
                                                                        cachedSearchKeyword)) {
                                                                    cachedSearchHits.add(
                                                                            new CachedSearchHit(
                                                                                    site, v));
                                                                }
                                                                if (cachedSearchSiteFilter.isEmpty()
                                                                        || cachedSearchSiteFilter
                                                                                .equals(site.key)) {
                                                                    resultList.addView(
                                                                            buildSearchResultRow(
                                                                                    site, v));
                                                                }
                                                            }
                                                            if (!matches.isEmpty()) {
                                                                refreshSiteFilterBar[0].run();
                                                            }
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
                                                                                    + " 条结果（点击上方片源标签可筛选线路）";
                                                        }
                                                        renderSearchStatus.run();
                                                    }),
                                    Prefs.clampSearchTimeoutSeconds(
                                                    a.prefs.getInt(Prefs.SEARCH_TIMEOUT, 20))
                                            * 1000L,
                                    90000L);
                    activeSearchCoordinator.start(searchableSites, kw);
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
                final int savedScroll = cachedSearchScrollY;
                final int savedFilterX = cachedSiteFilterScrollX;
                if (cachedSearchStatus.isEmpty())
                    cachedSearchStatus = "已保留搜索结果，共 " + cachedSearchHits.size() + " 条";
                rebuildFilteredResults.run();
                if (savedFilterX > 0) {
                    siteFilterScroll.post(() -> siteFilterScroll.scrollTo(savedFilterX, 0));
                }
                if (savedScroll > 0) {
                    resultScroll.post(() -> resultScroll.scrollTo(0, savedScroll));
                }
            } else {
                triggerSearch[0].run();
            }
        } else if (!cachedSearchKeyword.isEmpty()) {
            input.setText(cachedSearchKeyword);
            input.setSelection(cachedSearchKeyword.length());
            final int savedScroll = cachedSearchScrollY;
            final int savedFilterX = cachedSiteFilterScrollX;
            if (cachedSearchStatus.isEmpty())
                cachedSearchStatus = "已保留搜索结果，共 " + cachedSearchHits.size() + " 条";
            rebuildFilteredResults.run();
            if (savedFilterX > 0) {
                siteFilterScroll.post(() -> siteFilterScroll.scrollTo(savedFilterX, 0));
            }
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
        final int[] liveRequest = {0};

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
                    final int request = ++liveRequest[0];
                    a.cancelPageRequests();
                    activeCatalog[0] = null;
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
                                    final LiveCatalog.Catalog catalog =
                                            LiveController.loadCatalog(a, selectedSource);
                                    a.ui.post(
                                            () -> {
                                                if (ticket != a.generation
                                                        || a.isFinishing()
                                                        || request != liveRequest[0]) return;
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
                                                if (ticket != a.generation
                                                        || a.isFinishing()
                                                        || request != liveRequest[0]) return;
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
                        for (int n = 0; n < names.length; n++) {
                            String lbl = ch.streams.get(n).label;
                            names[n] =
                                    "线路 "
                                            + (n + 1)
                                            + (lbl != null && !lbl.trim().isEmpty()
                                                    ? " · " + lbl.trim()
                                                    : "");
                        }
                        showDrawerPicker(
                                a,
                                ch.name + " · 选择直播线路",
                                "长按频道可手动切换不同直播源线路",
                                names,
                                0,
                                n -> a.playLive(source, ch, ch.streams.get(n)));
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
        body.setPadding(a.dp(16), statusBarHeight() + a.dp(14), a.dp(16), a.dp(108));
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
                        SettingIconView.ICON_CLIPBOARD,
                        "从剪贴板一键粘贴配置",
                        "直接读取手机剪贴板中的 JSON、JS、PY、TXT/M3U 或配置网址",
                        () -> pasteConfigFromClipboard(a));
                addSettingCard(
                        body,
                        SettingIconView.ICON_LINK_CODE,
                        "输入或修改配置网址与源码",
                        a.configOrigin.isEmpty()
                                ? "支持 JSON / JS / PY / TXT / M3U / ZIP 地址"
                                : a.configOrigin,
                        () -> showMobileImportDialog(a));
                addSettingCard(
                        body,
                        SettingIconView.ICON_FOLDER_FILE,
                        "选择本地配置或本地包",
                        "支持导入 .json / .js / .py / .txt / .m3u / .zip 本地文件",
                        a::pickConfig);
                String savedLiveUrl = a.prefs.getString(Prefs.LIVE_URL, "");
                addSettingCard(
                        body,
                        SettingIconView.ICON_LIVE_TV,
                        "导入或管理电视直播源",
                        savedLiveUrl.isEmpty()
                                ? "支持 M3U、TXT (#genre#)、JSON 及本地直播文件"
                                : "当前直播源：" + savedLiveUrl,
                        () -> SourceController.showLiveImportDialog(a));

                a.gap(body, 18);
                addSectionHeader(body, "片源选择与扩展引擎");
                addSettingCard(
                        body,
                        SettingIconView.ICON_SWITCH_SOURCE,
                        "切换默认片源",
                        currentSite == null ? "未选择" : "当前：" + currentSite.name,
                        a::chooseSources);
                final boolean cspEnabled = a.prefs.getBoolean(Prefs.EXPERIMENTAL_CSP, true);
                addSettingToggleCard(
                        body,
                        SettingIconView.ICON_PLUGIN_EXT,
                        "启用第三方扩展片源 (JAR / JS / PY)",
                        "已开启 · 允许运行配置源中的 JAR、JS 与 PY 扩展",
                        "已关闭 · 仅使用内置标准解析",
                        cspEnabled,
                        next -> {
                            a.prefs.edit().putBoolean(Prefs.EXPERIMENTAL_CSP, next).apply();
                            a.closeEngines();
                        });
            } else if (settingsSubPage == 2) {
                addSectionHeader(body, "播放器与解码器");
                final int engine = a.prefs.getInt(Prefs.PLAYER_ENGINE, 0);
                final String[] engineNames = {
                    "Exo 播放器（Media3 · 4K HDR 与字幕增强）", "IJK 播放器（FFmpeg · 直播与特殊封装兼容）", "Android 系统播放器"
                };
                final int[] engineVals = {0, 2, 1};
                final int curEngIdx = engine == 2 ? 1 : (engine == 1 ? 2 : 0);
                addSettingCard(
                        body,
                        SettingIconView.ICON_ENGINE_CORE,
                        "播放器内核",
                        engineNames[curEngIdx],
                        () ->
                                showDrawerPicker(
                                        a,
                                        "播放器内核",
                                        "作为后续播放的默认内核，播放页内也可随时切换",
                                        engineNames,
                                        curEngIdx,
                                        idx -> {
                                            if (idx >= 0 && idx < engineVals.length) {
                                                a.prefs
                                                        .edit()
                                                        .putInt(
                                                                Prefs.PLAYER_ENGINE,
                                                                engineVals[idx])
                                                        .apply();
                                                refreshInPlace.run();
                                            }
                                        }));
                final int decoder = a.prefs.getInt(Prefs.DECODER_MODE, 0);
                final String[] decNames = {"自动选择（推荐）", "硬件解码优先 (HW)", "软件解码优先 (SW)"};
                addSettingCard(
                        body,
                        SettingIconView.ICON_DECODER_CHIP,
                        "视频解码方式",
                        decNames[Math.max(0, Math.min(2, decoder))],
                        () ->
                                showDrawerPicker(
                                        a,
                                        "视频解码方式",
                                        "高码率 4K 视频建议使用自动或硬件解码",
                                        decNames,
                                        Math.max(0, Math.min(2, decoder)),
                                        idx -> {
                                            a.prefs.edit().putInt(Prefs.DECODER_MODE, idx).apply();
                                            refreshInPlace.run();
                                        }));
                final boolean tunnel = a.prefs.getBoolean(Prefs.TUNNELED_PLAYBACK, false);
                addSettingToggleCard(
                        body,
                        SettingIconView.ICON_HDR_TUNNEL,
                        "4K HDR 隧道模式",
                        "已开启 · 硬件音视频隧道直通",
                        "已关闭 · 默认兼容渲染模式",
                        tunnel,
                        next -> a.prefs.edit().putBoolean(Prefs.TUNNELED_PLAYBACK, next).apply());
                final boolean autoNext = a.prefs.getBoolean(Prefs.AUTO_NEXT, true);
                addSettingToggleCard(
                        body,
                        SettingIconView.ICON_AUTO_NEXT,
                        "自动连播下一集",
                        "已开启 · 当前剧集播放完毕自动切换下一集",
                        "已关闭 · 单集播放完毕后停止",
                        autoNext,
                        next -> a.prefs.edit().putBoolean(Prefs.AUTO_NEXT, next).apply());

                a.gap(body, 18);
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
                        SettingIconView.ICON_SKIP_TIMELINE,
                        "片头片尾跳过时间节点",
                        skipDesc + "（支持手动拖动滑块精准设定）",
                        () ->
                                PlaybackControls.showSkipAdjuster(
                                        a, true, introSec, 0, refreshInPlace));

                int subSize = Prefs.clampSubtitleSize(a.prefs.getInt(Prefs.SUBTITLE_SIZE_SP, 45));
                int subWeight =
                        Prefs.clampSubtitleWeight(a.prefs.getInt(Prefs.SUBTITLE_WEIGHT, 50));
                int subBg = Prefs.clampSubtitleBgStyle(a.prefs.getInt(Prefs.SUBTITLE_BG_STYLE, 0));
                addSettingCard(
                        body,
                        SettingIconView.ICON_SUBTITLE,
                        "全屏字幕触控调试（大小、粗细、位置与样式）",
                        "字号 "
                                + subSize
                                + " · 粗细 "
                                + subWeight
                                + " · "
                                + SubtitleOverlayView.bgStyleLabel(subBg)
                                + "（双指缩放 · 按住拖拽 · 中轴线指引）",
                        () -> PlaybackControls.showSubtitleAdjuster(a, null, refreshInPlace));
            } else if (settingsSubPage == 3) {
                addSectionHeader(body, "界面显示与交互");
                final boolean showHeroBanner = a.prefs.getBoolean(Prefs.SHOW_HERO_BANNER, true);
                addSettingToggleCard(
                        body,
                        SettingIconView.ICON_HERO_CARD,
                        "首页推荐卡片",
                        "已开启 · 在首页顶部展示今日首推巨幕卡片",
                        "已关闭 · 隐藏首页顶部推荐卡片，直接展示海报墙",
                        showHeroBanner,
                        next -> a.prefs.edit().putBoolean(Prefs.SHOW_HERO_BANNER, next).apply());
                final int scale = a.prefs.getInt(Prefs.UI_SCALE_PERCENT, 100);
                final int[] scaleVals = {90, 100, 110, 120};
                final String[] scaleLabels = {
                    "紧凑 · 90%", "标准 · 100%（默认）", "放大 · 110%", "特大 · 120%"
                };
                int curScaleIdx = 1;
                for (int i = 0; i < scaleVals.length; i++) {
                    if (scaleVals[i] == scale) curScaleIdx = i;
                }
                final int finalScaleIdx = curScaleIdx;
                addSettingCard(
                        body,
                        SettingIconView.ICON_UI_SCALE,
                        "界面整体缩放比例",
                        "当前：" + scaleLabels[finalScaleIdx],
                        () ->
                                showDrawerPicker(
                                        a,
                                        "界面整体缩放比例",
                                        "调整按钮、文字与卡片整体显示比例",
                                        scaleLabels,
                                        finalScaleIdx,
                                        idx -> {
                                            a.prefs
                                                    .edit()
                                                    .putInt(Prefs.UI_SCALE_PERCENT, scaleVals[idx])
                                                    .apply();
                                            a.recreate();
                                        }));
                final int navScalePct =
                        Prefs.clampNavScale(a.prefs.getInt(Prefs.NAV_SCALE_PERCENT, 100));
                final int[] navVals = {85, 100, 115, 130, 140};
                final String[] navLabels = {
                    "紧凑 · 85%", "标准 · 100%（默认）", "偏大 · 115%", "大号 · 130%", "特大 · 140%"
                };
                int curNavIdx = 1;
                for (int i = 0; i < navVals.length; i++) {
                    if (navVals[i] == navScalePct) curNavIdx = i;
                }
                final int finalNavIdx = curNavIdx;
                addSettingCard(
                        body,
                        SettingIconView.ICON_NAV_FONT,
                        "导航栏字体与按钮大小",
                        "当前：" + navLabels[finalNavIdx] + "（自适应排版）",
                        () ->
                                showDrawerPicker(
                                        a,
                                        "导航栏字体与按钮大小",
                                        "调整导航栏文字与按钮尺寸，排版自动适应",
                                        navLabels,
                                        finalNavIdx,
                                        idx -> {
                                            a.prefs
                                                    .edit()
                                                    .putInt(Prefs.NAV_SCALE_PERCENT, navVals[idx])
                                                    .apply();
                                            a.recreate();
                                        }));
                final boolean reduced = a.prefs.getBoolean(Prefs.REDUCED, false);
                final boolean reducedTransparency =
                        a.prefs.getBoolean(Prefs.REDUCED_TRANSPARENCY, false);
                addSettingToggleCard(
                        body,
                        SettingIconView.ICON_GLASS_BLUR,
                        "减少透明效果",
                        "已开启 · 使用高对比实色面板",
                        "已关闭 · 使用背景液态毛玻璃材质",
                        reducedTransparency,
                        next -> {
                            a.prefs.edit().putBoolean(Prefs.REDUCED_TRANSPARENCY, next).apply();
                            com.pilipala.glass.GlassworkSurface.invalidateBackdrop(a.root);
                        });
                addSettingToggleCard(
                        body,
                        SettingIconView.ICON_MOTION_SPARK,
                        "精简界面动画",
                        "已开启 · 减少过渡动画，提升响应速度",
                        "已关闭 · 保留完整流畅动效",
                        reduced,
                        next -> {
                            a.reduced = next;
                            a.prefs.edit().putBoolean(Prefs.REDUCED, next).apply();
                        });
                addSettingCard(
                        body,
                        SettingIconView.ICON_TRASH_CLEAR,
                        "清空搜索历史记录",
                        "清除搜索页保存的全部历史关键词",
                        () ->
                                showDrawerPicker(
                                        a,
                                        "清空搜索历史记录",
                                        "确认清除搜索页保存的全部历史关键词？",
                                        new String[] {"确认清空全部搜索历史", "取消"},
                                        1,
                                        idx -> {
                                            if (idx == 0) {
                                                a.prefs
                                                        .edit()
                                                        .putString(
                                                                Prefs.GLOBAL_SEARCH_HISTORY, "[]")
                                                        .apply();
                                                cachedSearchKeyword = "";
                                                cachedSearchStatus = "";
                                                cachedSearchHits.clear();
                                                a.toast("已清空搜索历史记录");
                                            }
                                        }));
            } else if (settingsSubPage == 4) {
                addSectionHeader(body, "更新、备份与诊断");
                addSettingCard(
                        body,
                        SettingIconView.ICON_UPDATE_CLOUD,
                        "检查软件更新",
                        "当前版本：" + UpdateManager.installedVersion(a) + " · 正式版",
                        () ->
                                a.open(
                                        new MainActivity.Screen(() -> new UpdateScreen(a).show()),
                                        true));
                addSettingCard(
                        body,
                        SettingIconView.ICON_DIAGNOSTIC_LOG,
                        "查看运行诊断日志",
                        "排查片源网络请求、代理与播放解码日志",
                        () -> new SettingsScreen(a).diagnostics());
                addSettingCard(
                        body,
                        SettingIconView.ICON_BACKUP_SAFE,
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

        a.gap(body, 18);
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
        h.setPadding(a.dp(6), a.dp(10), a.dp(6), a.dp(10));
        parent.addView(h);
    }

    interface BooleanAction {
        void onToggle(boolean checked);
    }

    static void bindCardTouchFeedback(
            MainActivity a,
            View card,
            android.graphics.drawable.Drawable normalBg,
            android.graphics.drawable.Drawable pressedBg) {
        if (card == null) return;
        card.setOnTouchListener(
                (v, ev) -> {
                    int act = ev.getActionMasked();
                    if (act == android.view.MotionEvent.ACTION_DOWN) {
                        if (pressedBg != null) v.setBackground(pressedBg);
                        if (!a.reduced) {
                            v.animate().cancel();
                            v.animate().scaleX(0.986f).scaleY(0.986f).setDuration(80).start();
                        }
                    } else if (act == android.view.MotionEvent.ACTION_UP
                            || act == android.view.MotionEvent.ACTION_CANCEL) {
                        if (normalBg != null) v.setBackground(normalBg);
                        if (!a.reduced) {
                            v.animate().cancel();
                            v.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                        }
                    }
                    return false;
                });
    }

    private void addSettingCard(
            LinearLayout parent, int iconKind, String title, String subtitle, Runnable action) {
        LinearLayout row = a.row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(a.dp(18), a.dp(16), a.dp(18), a.dp(16));
        GradientDrawable normalBg =
                glassBg(0xb81b2320, 0xb8131917, a.dp(20), a.dp(0.7f), 0x26ffffff);
        GradientDrawable pressedBg =
                glassBg(0xd825322d, 0xd81b2420, a.dp(20), a.dp(0.9f), 0x48ffffff);
        row.setBackground(normalBg);
        bindCardTouchFeedback(a, row, normalBg, pressedBg);
        row.setOnClickListener(v -> action.run());

        SettingIconView icon = new SettingIconView(a, iconKind);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(a.dp(38), a.dp(38));
        iconLp.rightMargin = a.dp(14);
        row.addView(icon, iconLp);

        LinearLayout textCol = a.vertical();
        TextView t = a.text(title, 15f, MainActivity.WHITE);
        t.setTypeface(null, Typeface.BOLD);
        textCol.addView(t);

        TextView s = a.text(subtitle, 12.5f, MainActivity.MUTED);
        s.setPadding(0, a.dp(4), 0, 0);
        s.setSingleLine(true);
        s.setEllipsize(TextUtils.TruncateAt.END);
        if (!subtitle.isEmpty()) textCol.addView(s);

        row.addView(textCol, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(a.text("›", 20, MainActivity.MUTED));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = a.dp(15);
        parent.addView(row, lp);
    }

    private void addSettingToggleCard(
            LinearLayout parent,
            int iconKind,
            String title,
            final String onSubtitle,
            final String offSubtitle,
            boolean initialChecked,
            final BooleanAction onToggle) {
        LinearLayout row = a.row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(a.dp(18), a.dp(16), a.dp(18), a.dp(16));
        GradientDrawable normalBg =
                glassBg(0xb81b2320, 0xb8131917, a.dp(20), a.dp(0.7f), 0x26ffffff);
        GradientDrawable pressedBg =
                glassBg(0xd825322d, 0xd81b2420, a.dp(20), a.dp(0.9f), 0x48ffffff);
        row.setBackground(normalBg);
        bindCardTouchFeedback(a, row, normalBg, pressedBg);

        SettingIconView icon = new SettingIconView(a, iconKind);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(a.dp(38), a.dp(38));
        iconLp.rightMargin = a.dp(14);
        row.addView(icon, iconLp);

        LinearLayout textCol = a.vertical();
        TextView t = a.text(title, 15f, MainActivity.WHITE);
        t.setTypeface(null, Typeface.BOLD);
        textCol.addView(t);

        final TextView s =
                a.text(initialChecked ? onSubtitle : offSubtitle, 12.5f, MainActivity.MUTED);
        s.setPadding(0, a.dp(4), 0, 0);
        s.setSingleLine(true);
        s.setEllipsize(TextUtils.TruncateAt.END);
        textCol.addView(s);

        row.addView(textCol, new LinearLayout.LayoutParams(0, -2, 1));

        final IosSwitchView toggleSwitch = new IosSwitchView(a, initialChecked);
        LinearLayout.LayoutParams switchLp = new LinearLayout.LayoutParams(a.dp(50), a.dp(30));
        switchLp.leftMargin = a.dp(12);
        row.addView(toggleSwitch, switchLp);

        row.setOnClickListener(
                v -> {
                    boolean next = !toggleSwitch.isChecked();
                    toggleSwitch.setChecked(next, !a.reduced);
                    s.setText(next ? onSubtitle : offSubtitle);
                    if (onToggle != null) onToggle.onToggle(next);
                });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = a.dp(15);
        parent.addView(row, lp);
    }

    /** iOS-style capsule toggle switch (`50dp × 30dp`, emerald/iOS-green track + sliding white thumb). */
    static final class IosSwitchView extends View {
        private final MainActivity a;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean checked;
        private float progress;
        private android.animation.ValueAnimator animator;

        IosSwitchView(MainActivity activity, boolean initialChecked) {
            super(activity);
            this.a = activity;
            this.checked = initialChecked;
            this.progress = initialChecked ? 1f : 0f;
            setClickable(false);
            setFocusable(false);
        }

        boolean isChecked() {
            return checked;
        }

        void setChecked(boolean next, boolean animate) {
            this.checked = next;
            float target = next ? 1f : 0f;
            if (animator != null) {
                animator.cancel();
                animator = null;
            }
            if (!animate || Math.abs(progress - target) < 0.01f) {
                progress = target;
                invalidate();
                return;
            }
            animator = android.animation.ValueAnimator.ofFloat(progress, target);
            animator.setDuration(160);
            animator.setInterpolator(new android.view.animation.DecelerateInterpolator());
            animator.addUpdateListener(
                    anim -> {
                        progress = (Float) anim.getAnimatedValue();
                        invalidate();
                    });
            animator.start();
        }

        private static int blendColor(int c1, int c2, float ratio) {
            float r = Math.max(0f, Math.min(1f, ratio));
            int a1 = (c1 >>> 24) & 0xff,
                    r1 = (c1 >> 16) & 0xff,
                    g1 = (c1 >> 8) & 0xff,
                    b1 = c1 & 0xff;
            int a2 = (c2 >>> 24) & 0xff,
                    r2 = (c2 >> 16) & 0xff,
                    g2 = (c2 >> 8) & 0xff,
                    b2 = c2 & 0xff;
            int oa = Math.round(a1 + (a2 - a1) * r);
            int or = Math.round(r1 + (r2 - r1) * r);
            int og = Math.round(g1 + (g2 - g1) * r);
            int ob = Math.round(b1 + (b2 - b1) * r);
            return (oa << 24) | (or << 16) | (og << 8) | ob;
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float radius = h / 2f;
            int trackColor = blendColor(0x58787880, 0xff30d158, progress);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(trackColor);
            RectF trackRect = new RectF(0, 0, w, h);
            c.drawRoundRect(trackRect, radius, radius, paint);

            if (progress < 0.95f) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(1f, a.dp(0.8f)));
                paint.setColor(blendColor(0x2effffff, 0x00ffffff, progress));
                float inset = paint.getStrokeWidth() / 2f;
                c.drawRoundRect(
                        new RectF(inset, inset, w - inset, h - inset), radius, radius, paint);
            }

            float pad = a.dp(2f);
            float thumbRadius = (h - pad * 2f) / 2f;
            float minCx = pad + thumbRadius;
            float maxCx = w - pad - thumbRadius;
            float cx = minCx + (maxCx - minCx) * progress;
            float cy = h / 2f;

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x33000000);
            c.drawCircle(cx, cy + a.dp(0.8f), thumbRadius, paint);

            paint.setColor(Color.WHITE);
            c.drawCircle(cx, cy, thumbRadius, paint);
        }
    }

    /**
     * Semantic vector icon badge for Settings cards (`38dp × 38dp` rounded-square tinted badge +
     * bespoke vector glyph per setting item).
     */
    static final class SettingIconView extends View {
        // Level-1 Primary Categories
        static final int ICON_SOURCES = 1;
        static final int ICON_NETDISK = 2;
        static final int ICON_PLAYBACK = 3;
        static final int ICON_APPEARANCE = 4;
        static final int ICON_SYSTEM = 5;

        // Subpage 1: 片源与直播配置
        static final int ICON_CLIPBOARD = 11;
        static final int ICON_LINK_CODE = 12;
        static final int ICON_FOLDER_FILE = 13;
        static final int ICON_LIVE_TV = 14;
        static final int ICON_SWITCH_SOURCE = 15;
        static final int ICON_PLUGIN_EXT = 16;

        // Subpage 2: 播放、解码与字幕
        static final int ICON_ENGINE_CORE = 21;
        static final int ICON_DECODER_CHIP = 22;
        static final int ICON_HDR_TUNNEL = 23;
        static final int ICON_AUTO_NEXT = 24;
        static final int ICON_SKIP_TIMELINE = 25;
        static final int ICON_SUBTITLE = 26;

        // Subpage 3: 界面外观与偏好
        static final int ICON_UI_SCALE = 31;
        static final int ICON_NAV_FONT = 32;
        static final int ICON_GLASS_BLUR = 33;
        static final int ICON_MOTION_SPARK = 34;
        static final int ICON_TRASH_CLEAR = 35;
        static final int ICON_HERO_CARD = 36;

        // Subpage 4: 备份、更新与诊断
        static final int ICON_UPDATE_CLOUD = 41;
        static final int ICON_DIAGNOSTIC_LOG = 42;
        static final int ICON_BACKUP_SAFE = 43;

        // Additional TV / Advanced Settings Icons
        static final int ICON_WALLPAPER = 51;
        static final int ICON_GRID_COLS = 52;
        static final int ICON_AUDIO_WAVE = 53;
        static final int ICON_ASPECT_RATIO = 54;
        static final int ICON_SPEED_GAUGE = 55;
        static final int ICON_REPEAT_LOOP = 56;

        private final MainActivity a;
        private final int kind;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        SettingIconView(MainActivity activity, int kind) {
            super(activity);
            this.a = activity;
            this.kind = kind;
        }

        static int kindForSetting(String key, String title) {
            String k = key == null ? "" : key;
            String t = title == null ? "" : title;
            if (k.contains("settings-appearance")) return ICON_APPEARANCE;
            if (k.contains("settings-playback")) return ICON_PLAYBACK;
            if (k.contains("settings-data")) return ICON_SOURCES;
            if (k.contains("settings-updates")) return ICON_UPDATE_CLOUD;
            if (k.contains("settings-accounts") || t.contains("网盘")) return ICON_NETDISK;
            if (k.contains("settings-live-import") || t.contains("直播")) return ICON_LIVE_TV;
            if (k.contains("settings-import") || t.contains("配置源")) return ICON_LINK_CODE;
            if (k.contains("settings-depots") || t.contains("线路") || t.contains("切换"))
                return ICON_SWITCH_SOURCE;
            if (k.contains(Prefs.EXPERIMENTAL_CSP) || t.contains("扩展")) return ICON_PLUGIN_EXT;
            if (k.contains("settings-backup")
                    || k.contains("settings-restore")
                    || t.contains("备份")
                    || t.contains("还原")) return ICON_BACKUP_SAFE;
            if (k.contains("settings-logs") || t.contains("日志")) return ICON_DIAGNOSTIC_LOG;
            if (k.contains("clear-history") || t.contains("清除") || t.contains("清空"))
                return ICON_TRASH_CLEAR;
            if (k.contains("settings-history")
                    || k.contains("clear-images")
                    || k.contains("settings-reset")
                    || k.contains(Prefs.REPEAT_MODE)
                    || t.contains("循环")
                    || t.contains("历史")
                    || t.contains("恢复")) return ICON_REPEAT_LOOP;
            if (k.contains(Prefs.SHOW_HERO_BANNER)
                    || t.contains("推荐卡片")
                    || t.contains("首推")
                    || t.contains("巨幕")) return ICON_HERO_CARD;
            if (k.contains(Prefs.UI_SCALE_PERCENT) || t.contains("界面大小") || t.contains("缩放"))
                return ICON_UI_SCALE;
            if (k.contains(Prefs.NAV_SCALE_PERCENT) || t.contains("导航栏")) return ICON_NAV_FONT;
            if (k.contains(Prefs.HOME_COLUMNS) || t.contains("每行海报")) return ICON_GRID_COLS;
            if (k.contains("wallpaper")
                    || k.contains(Prefs.HOME_AMBIENT)
                    || t.contains("壁纸")
                    || t.contains("图片")
                    || t.contains("柔光")
                    || t.contains("压暗")) return ICON_WALLPAPER;
            if (k.contains(Prefs.REDUCED_TRANSPARENCY) || t.contains("透明")) return ICON_GLASS_BLUR;
            if (k.contains(Prefs.REDUCED)
                    || k.contains(Prefs.STARTUP_ANIMATION)
                    || t.contains("动画")
                    || t.contains("动态")) return ICON_MOTION_SPARK;
            if (k.contains(Prefs.VIDEO_OUTPUT)
                    || k.contains(Prefs.TUNNELED_PLAYBACK)
                    || t.contains("HDR")
                    || t.contains("画面输出")) return ICON_HDR_TUNNEL;
            if (k.contains(Prefs.PLAYER_ENGINE) || t.contains("播放器")) return ICON_ENGINE_CORE;
            if (k.contains(Prefs.DECODER_MODE) || k.contains("decoders") || t.contains("解码"))
                return ICON_DECODER_CHIP;
            if (k.contains(Prefs.SOFTWARE_AUDIO)
                    || k.contains(Prefs.PREFER_AAC)
                    || k.contains(Prefs.AUDIO_EFFECT)
                    || t.contains("音轨")
                    || t.contains("音效")
                    || t.contains("音频")) return ICON_AUDIO_WAVE;
            if (k.contains(Prefs.ASPECT)
                    || k.contains(Prefs.PICTURE_EFFECT)
                    || t.contains("画面比例")
                    || t.contains("色彩")) return ICON_ASPECT_RATIO;
            if (k.contains(Prefs.PLAY_SPEED)
                    || k.contains(Prefs.BUFFER_SECONDS)
                    || k.contains(Prefs.SEARCH_THREADS)
                    || k.contains(Prefs.SEARCH_TIMEOUT)
                    || t.contains("速度")
                    || t.contains("缓冲")) return ICON_SPEED_GAUGE;
            if (k.contains(Prefs.AUTO_NEXT) || t.contains("下一集")) return ICON_AUTO_NEXT;
            if (k.contains(Prefs.SKIP_INTRO) || k.contains(Prefs.SKIP_OUTRO) || t.contains("跳过片"))
                return ICON_SKIP_TIMELINE;
            if (k.contains(Prefs.SUBTITLE_SIZE)
                    || k.contains(Prefs.SUBTITLE_STYLE)
                    || k.contains(Prefs.DANMAKU_DEFAULT)
                    || t.contains("字幕")
                    || t.contains("弹幕")) return ICON_SUBTITLE;
            return ICON_SOURCES;
        }

        int iconKind() {
            return kind;
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float cx = w / 2f, cy = h / 2f;
            int badgeBg;
            int strokeColor;
            switch (kind) {
                case ICON_SOURCES:
                case ICON_CLIPBOARD:
                case ICON_DECODER_CHIP:
                case ICON_GLASS_BLUR:
                    badgeBg = 0x2606b6d4;
                    strokeColor = 0xff22d3ee;
                    break;
                case ICON_NETDISK:
                case ICON_PLUGIN_EXT:
                case ICON_AUTO_NEXT:
                case ICON_REPEAT_LOOP:
                case ICON_HERO_CARD:
                    badgeBg = 0x2610b981;
                    strokeColor = 0xff34d399;
                    break;
                case ICON_PLAYBACK:
                case ICON_ENGINE_CORE:
                case ICON_SPEED_GAUGE:
                    badgeBg = 0x26f59e0b;
                    strokeColor = 0xfffbbf24;
                    break;
                case ICON_FOLDER_FILE:
                case ICON_SKIP_TIMELINE:
                    badgeBg = 0x26f97316;
                    strokeColor = 0xfffb923c;
                    break;
                case ICON_APPEARANCE:
                case ICON_HDR_TUNNEL:
                case ICON_UI_SCALE:
                case ICON_GRID_COLS:
                    badgeBg = 0x26a855f7;
                    strokeColor = 0xffc084fc;
                    break;
                case ICON_NAV_FONT:
                    badgeBg = 0x26ec4899;
                    strokeColor = 0xfff472b6;
                    break;
                case ICON_LIVE_TV:
                case ICON_AUDIO_WAVE:
                    badgeBg = 0x26f43f5e;
                    strokeColor = 0xfffb7185;
                    break;
                case ICON_TRASH_CLEAR:
                    badgeBg = 0x26ef4444;
                    strokeColor = 0xfff87171;
                    break;
                case ICON_MOTION_SPARK:
                    badgeBg = 0x26eab308;
                    strokeColor = 0xfffacc15;
                    break;
                case ICON_DIAGNOSTIC_LOG:
                case ICON_WALLPAPER:
                    badgeBg = 0x2614b8a6;
                    strokeColor = 0xff2dd4bf;
                    break;
                case ICON_SWITCH_SOURCE:
                case ICON_BACKUP_SAFE:
                    badgeBg = 0x266366f1;
                    strokeColor = 0xff818cf8;
                    break;
                case ICON_SUBTITLE:
                case ICON_ASPECT_RATIO:
                    badgeBg = 0x260ea5e9;
                    strokeColor = 0xff38bdf8;
                    break;
                case ICON_SYSTEM:
                case ICON_LINK_CODE:
                case ICON_UPDATE_CLOUD:
                case 6:
                    badgeBg = 0x26ef4444;
                    strokeColor = 0xfff87171;
                    break;
                default:
                    badgeBg = 0x263b82f6;
                    strokeColor = 0xff60a5fa;
                    break;
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(badgeBg);
            c.drawRoundRect(new RectF(0, 0, w, h), a.dp(10), a.dp(10), paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(strokeColor);
            paint.setStrokeWidth(a.dp(1.65f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);

            switch (kind) {
                case ICON_SOURCES:
                    {
                        // 3D stacked layers
                        Path top = new Path();
                        top.moveTo(cx, cy - a.dp(8f));
                        top.lineTo(cx + a.dp(8f), cy - a.dp(3.2f));
                        top.lineTo(cx, cy + a.dp(1.6f));
                        top.lineTo(cx - a.dp(8f), cy - a.dp(3.2f));
                        top.close();
                        c.drawPath(top, paint);
                        c.drawLine(cx - a.dp(8f), cy + a.dp(0.8f), cx, cy + a.dp(5.2f), paint);
                        c.drawLine(cx + a.dp(8f), cy + a.dp(0.8f), cx, cy + a.dp(5.2f), paint);
                        c.drawLine(cx - a.dp(8f), cy + a.dp(4.4f), cx, cy + a.dp(8.8f), paint);
                        c.drawLine(cx + a.dp(8f), cy + a.dp(4.4f), cx, cy + a.dp(8.8f), paint);
                        break;
                    }
                case ICON_NETDISK:
                    {
                        // Cloud drive with upward sync arrow
                        RectF base =
                                new RectF(
                                        cx - a.dp(8.2f),
                                        cy - a.dp(1.5f),
                                        cx + a.dp(8.2f),
                                        cy + a.dp(6.8f));
                        c.drawRoundRect(base, a.dp(4.2f), a.dp(4.2f), paint);
                        c.drawArc(
                                new RectF(
                                        cx - a.dp(5.2f),
                                        cy - a.dp(7.5f),
                                        cx + a.dp(5.2f),
                                        cy + a.dp(2.5f)),
                                185,
                                170,
                                false,
                                paint);
                        c.drawLine(cx, cy + a.dp(4.2f), cx, cy - a.dp(1.2f), paint);
                        c.drawLine(cx - a.dp(2.3f), cy + a.dp(1f), cx, cy - a.dp(1.4f), paint);
                        c.drawLine(cx + a.dp(2.3f), cy + a.dp(1f), cx, cy - a.dp(1.4f), paint);
                        break;
                    }
                case ICON_PLAYBACK:
                    {
                        // Cinema play screen
                        RectF scr =
                                new RectF(
                                        cx - a.dp(8.5f),
                                        cy - a.dp(6.5f),
                                        cx + a.dp(8.5f),
                                        cy + a.dp(6.5f));
                        c.drawRoundRect(scr, a.dp(2.8f), a.dp(2.8f), paint);
                        paint.setStyle(Paint.Style.FILL);
                        Path tri = new Path();
                        tri.moveTo(cx - a.dp(2.2f), cy - a.dp(3.2f));
                        tri.lineTo(cx + a.dp(3.4f), cy);
                        tri.lineTo(cx - a.dp(2.2f), cy + a.dp(3.2f));
                        tri.close();
                        c.drawPath(tri, paint);
                        break;
                    }
                case ICON_APPEARANCE:
                    {
                        // Dual tuning sliders
                        float x1 = cx - a.dp(8f), x2 = cx + a.dp(8f);
                        c.drawLine(x1, cy - a.dp(4.5f), x2, cy - a.dp(4.5f), paint);
                        c.drawLine(x1, cy + a.dp(4.5f), x2, cy + a.dp(4.5f), paint);
                        paint.setStyle(Paint.Style.FILL);
                        c.drawCircle(cx - a.dp(2.8f), cy - a.dp(4.5f), a.dp(2.4f), paint);
                        c.drawCircle(cx + a.dp(2.8f), cy + a.dp(4.5f), a.dp(2.4f), paint);
                        break;
                    }
                case ICON_CLIPBOARD:
                    {
                        // Clipboard with top clip & lines
                        RectF board =
                                new RectF(
                                        cx - a.dp(6.8f),
                                        cy - a.dp(6.2f),
                                        cx + a.dp(6.8f),
                                        cy + a.dp(8.2f));
                        c.drawRoundRect(board, a.dp(2.2f), a.dp(2.2f), paint);
                        RectF clip =
                                new RectF(
                                        cx - a.dp(3.2f),
                                        cy - a.dp(8.4f),
                                        cx + a.dp(3.2f),
                                        cy - a.dp(4.8f));
                        paint.setStyle(Paint.Style.FILL);
                        c.drawRoundRect(clip, a.dp(1.4f), a.dp(1.4f), paint);
                        paint.setStyle(Paint.Style.STROKE);
                        c.drawLine(
                                cx - a.dp(3.6f),
                                cy - a.dp(0.8f),
                                cx + a.dp(3.6f),
                                cy - a.dp(0.8f),
                                paint);
                        c.drawLine(
                                cx - a.dp(3.6f),
                                cy + a.dp(3.4f),
                                cx + a.dp(2.2f),
                                cy + a.dp(3.4f),
                                paint);
                        break;
                    }
                case ICON_LINK_CODE:
                    {
                        // Code brackets </>
                        c.drawLine(cx - a.dp(3.8f), cy - a.dp(4.5f), cx - a.dp(8.2f), cy, paint);
                        c.drawLine(cx - a.dp(8.2f), cy, cx - a.dp(3.8f), cy + a.dp(4.5f), paint);
                        c.drawLine(cx + a.dp(3.8f), cy - a.dp(4.5f), cx + a.dp(8.2f), cy, paint);
                        c.drawLine(cx + a.dp(8.2f), cy, cx + a.dp(3.8f), cy + a.dp(4.5f), paint);
                        c.drawLine(
                                cx + a.dp(1.8f),
                                cy - a.dp(6.2f),
                                cx - a.dp(1.8f),
                                cy + a.dp(6.2f),
                                paint);
                        break;
                    }
                case ICON_FOLDER_FILE:
                    {
                        // Folder with top tab
                        Path folder = new Path();
                        folder.moveTo(cx - a.dp(8.2f), cy - a.dp(6.8f));
                        folder.lineTo(cx - a.dp(2.2f), cy - a.dp(6.8f));
                        folder.lineTo(cx + a.dp(0.2f), cy - a.dp(4.2f));
                        folder.lineTo(cx + a.dp(8.2f), cy - a.dp(4.2f));
                        folder.lineTo(cx + a.dp(8.2f), cy + a.dp(7.2f));
                        folder.lineTo(cx - a.dp(8.2f), cy + a.dp(7.2f));
                        folder.close();
                        c.drawPath(folder, paint);
                        c.drawLine(
                                cx - a.dp(8f),
                                cy - a.dp(0.8f),
                                cx + a.dp(8f),
                                cy - a.dp(0.8f),
                                paint);
                        break;
                    }
                case ICON_LIVE_TV:
                    {
                        // TV monitor with dual antenna rabbit ears & live dot
                        RectF tv =
                                new RectF(
                                        cx - a.dp(8f),
                                        cy - a.dp(3.8f),
                                        cx + a.dp(8f),
                                        cy + a.dp(7.5f));
                        c.drawRoundRect(tv, a.dp(2.4f), a.dp(2.4f), paint);
                        c.drawLine(cx - a.dp(4.5f), cy - a.dp(8.5f), cx, cy - a.dp(3.8f), paint);
                        c.drawLine(cx + a.dp(4.5f), cy - a.dp(8.5f), cx, cy - a.dp(3.8f), paint);
                        paint.setStyle(Paint.Style.FILL);
                        c.drawCircle(cx, cy + a.dp(1.8f), a.dp(2f), paint);
                        break;
                    }
                case ICON_SWITCH_SOURCE:
                    {
                        // Two opposite horizontal swap arrows
                        float lx = cx - a.dp(7.5f), rx = cx + a.dp(7.5f);
                        float ty = cy - a.dp(3.5f), by = cy + a.dp(3.5f);
                        c.drawLine(lx, ty, rx, ty, paint);
                        c.drawLine(rx - a.dp(3f), ty - a.dp(2.6f), rx, ty, paint);
                        c.drawLine(rx - a.dp(3f), ty + a.dp(2.6f), rx, ty, paint);
                        c.drawLine(rx, by, lx, by, paint);
                        c.drawLine(lx + a.dp(3f), by - a.dp(2.6f), lx, by, paint);
                        c.drawLine(lx + a.dp(3f), by + a.dp(2.6f), lx, by, paint);
                        break;
                    }
                case ICON_PLUGIN_EXT:
                    {
                        // 2x2 modular plugin grid with popped top-right module
                        float s = a.dp(5.6f), g = a.dp(1.4f);
                        c.drawRoundRect(
                                new RectF(cx - g - s, cy - g - s, cx - g, cy - g),
                                a.dp(1.5f),
                                a.dp(1.5f),
                                paint);
                        c.drawRoundRect(
                                new RectF(cx - g - s, cy + g, cx - g, cy + g + s),
                                a.dp(1.5f),
                                a.dp(1.5f),
                                paint);
                        c.drawRoundRect(
                                new RectF(cx + g, cy + g, cx + g + s, cy + g + s),
                                a.dp(1.5f),
                                a.dp(1.5f),
                                paint);
                        paint.setStyle(Paint.Style.FILL);
                        c.drawRoundRect(
                                new RectF(
                                        cx + g + a.dp(0.8f),
                                        cy - g - s - a.dp(0.8f),
                                        cx + g + s + a.dp(0.8f),
                                        cy - g - a.dp(0.8f)),
                                a.dp(1.5f),
                                a.dp(1.5f),
                                paint);
                        break;
                    }
                case ICON_ENGINE_CORE:
                    {
                        // Play circle core
                        c.drawCircle(cx, cy, a.dp(8.5f), paint);
                        paint.setStyle(Paint.Style.FILL);
                        Path tri = new Path();
                        tri.moveTo(cx - a.dp(2.2f), cy - a.dp(3.4f));
                        tri.lineTo(cx + a.dp(3.6f), cy);
                        tri.lineTo(cx - a.dp(2.2f), cy + a.dp(3.4f));
                        tri.close();
                        c.drawPath(tri, paint);
                        break;
                    }
                case ICON_DECODER_CHIP:
                    {
                        // Hardware CPU/GPU decoder chip with pins
                        RectF chip =
                                new RectF(
                                        cx - a.dp(5.5f),
                                        cy - a.dp(5.5f),
                                        cx + a.dp(5.5f),
                                        cy + a.dp(5.5f));
                        c.drawRoundRect(chip, a.dp(1.8f), a.dp(1.8f), paint);
                        float p = a.dp(2.4f), in = a.dp(5.5f), out = a.dp(8.5f);
                        c.drawLine(cx - p, cy - out, cx - p, cy - in, paint);
                        c.drawLine(cx + p, cy - out, cx + p, cy - in, paint);
                        c.drawLine(cx - p, cy + in, cx - p, cy + out, paint);
                        c.drawLine(cx + p, cy + in, cx + p, cy + out, paint);
                        c.drawLine(cx - out, cy - p, cx - in, cy - p, paint);
                        c.drawLine(cx - out, cy + p, cx - in, cy + p, paint);
                        c.drawLine(cx + in, cy - p, cx + out, cy - p, paint);
                        c.drawLine(cx + in, cy + p, cx + out, cy + p, paint);
                        paint.setStyle(Paint.Style.FILL);
                        c.drawRoundRect(
                                new RectF(
                                        cx - a.dp(2.2f),
                                        cy - a.dp(2.2f),
                                        cx + a.dp(2.2f),
                                        cy + a.dp(2.2f)),
                                a.dp(0.8f),
                                a.dp(0.8f),
                                paint);
                        break;
                    }
                case ICON_HDR_TUNNEL:
                    {
                        // HDR display with sun & rays
                        RectF scr =
                                new RectF(
                                        cx - a.dp(8.5f),
                                        cy - a.dp(6.5f),
                                        cx + a.dp(8.5f),
                                        cy + a.dp(6.5f));
                        c.drawRoundRect(scr, a.dp(2.5f), a.dp(2.5f), paint);
                        c.drawCircle(cx, cy, a.dp(2.4f), paint);
                        c.drawLine(cx, cy - a.dp(4.6f), cx, cy - a.dp(3.8f), paint);
                        c.drawLine(cx, cy + a.dp(3.8f), cx, cy + a.dp(4.6f), paint);
                        c.drawLine(cx - a.dp(4.8f), cy, cx - a.dp(3.8f), cy, paint);
                        c.drawLine(cx + a.dp(3.8f), cy, cx + a.dp(4.8f), cy, paint);
                        break;
                    }
                case ICON_AUTO_NEXT:
                    {
                        // Fast-forward double-triangle + vertical bar (⏭)
                        paint.setStyle(Paint.Style.FILL);
                        Path t1 = new Path();
                        t1.moveTo(cx - a.dp(7.5f), cy - a.dp(4.5f));
                        t1.lineTo(cx - a.dp(1.2f), cy);
                        t1.lineTo(cx - a.dp(7.5f), cy + a.dp(4.5f));
                        t1.close();
                        c.drawPath(t1, paint);
                        Path t2 = new Path();
                        t2.moveTo(cx - a.dp(1.2f), cy - a.dp(4.5f));
                        t2.lineTo(cx + a.dp(5.2f), cy);
                        t2.lineTo(cx - a.dp(1.2f), cy + a.dp(4.5f));
                        t2.close();
                        c.drawPath(t2, paint);
                        paint.setStyle(Paint.Style.STROKE);
                        c.drawLine(
                                cx + a.dp(6.8f),
                                cy - a.dp(4.5f),
                                cx + a.dp(6.8f),
                                cy + a.dp(4.5f),
                                paint);
                        break;
                    }
                case ICON_SKIP_TIMELINE:
                    {
                        // Timeline bar with curved skip-over arc arrow
                        c.drawLine(
                                cx - a.dp(8f), cy + a.dp(5f), cx + a.dp(8f), cy + a.dp(5f), paint);
                        c.drawLine(
                                cx - a.dp(5f),
                                cy + a.dp(2.5f),
                                cx - a.dp(5f),
                                cy + a.dp(7.2f),
                                paint);
                        c.drawLine(
                                cx + a.dp(5f),
                                cy + a.dp(2.5f),
                                cx + a.dp(5f),
                                cy + a.dp(7.2f),
                                paint);
                        c.drawArc(
                                new RectF(
                                        cx - a.dp(6.5f),
                                        cy - a.dp(6.5f),
                                        cx + a.dp(6.5f),
                                        cy + a.dp(3.5f)),
                                195,
                                150,
                                false,
                                paint);
                        c.drawLine(
                                cx + a.dp(3.6f),
                                cy - a.dp(3.2f),
                                cx + a.dp(6.5f),
                                cy - a.dp(1f),
                                paint);
                        c.drawLine(
                                cx + a.dp(3.2f),
                                cy - a.dp(0.4f),
                                cx + a.dp(6.5f),
                                cy - a.dp(1f),
                                paint);
                        break;
                    }
                case ICON_SUBTITLE:
                    {
                        // Subtitle caption frame with bottom caption bars
                        RectF box =
                                new RectF(
                                        cx - a.dp(8.5f),
                                        cy - a.dp(6.5f),
                                        cx + a.dp(8.5f),
                                        cy + a.dp(6.5f));
                        c.drawRoundRect(box, a.dp(2.5f), a.dp(2.5f), paint);
                        c.drawLine(
                                cx - a.dp(5.2f),
                                cy + a.dp(0.5f),
                                cx + a.dp(5.2f),
                                cy + a.dp(0.5f),
                                paint);
                        c.drawLine(
                                cx - a.dp(3.6f),
                                cy + a.dp(3.5f),
                                cx + a.dp(3.6f),
                                cy + a.dp(3.5f),
                                paint);
                        break;
                    }
                case ICON_HERO_CARD:
                    {
                        // Featured showcase card with left poster thumbnail & right title lines
                        RectF card =
                                new RectF(
                                        cx - a.dp(8.5f),
                                        cy - a.dp(6.5f),
                                        cx + a.dp(8.5f),
                                        cy + a.dp(6.5f));
                        c.drawRoundRect(card, a.dp(2.4f), a.dp(2.4f), paint);
                        paint.setStyle(Paint.Style.FILL);
                        c.drawRoundRect(
                                new RectF(
                                        cx - a.dp(6f),
                                        cy - a.dp(4f),
                                        cx - a.dp(1.2f),
                                        cy + a.dp(4f)),
                                a.dp(1.2f),
                                a.dp(1.2f),
                                paint);
                        paint.setStyle(Paint.Style.STROKE);
                        c.drawLine(
                                cx + a.dp(1.2f),
                                cy - a.dp(2.2f),
                                cx + a.dp(5.8f),
                                cy - a.dp(2.2f),
                                paint);
                        c.drawLine(
                                cx + a.dp(1.2f),
                                cy + a.dp(1.8f),
                                cx + a.dp(4.4f),
                                cy + a.dp(1.8f),
                                paint);
                        break;
                    }
                case ICON_UI_SCALE:
                    {
                        // Four outward expansion corners
                        float d = a.dp(7.5f), s = a.dp(3.4f);
                        c.drawLine(cx - d, cy - d, cx - d + s, cy - d, paint);
                        c.drawLine(cx - d, cy - d, cx - d, cy - d + s, paint);
                        c.drawLine(cx + d, cy - d, cx + d - s, cy - d, paint);
                        c.drawLine(cx + d, cy - d, cx + d, cy - d + s, paint);
                        c.drawLine(cx - d, cy + d, cx - d + s, cy + d, paint);
                        c.drawLine(cx - d, cy + d, cx - d, cy + d - s, paint);
                        c.drawLine(cx + d, cy + d, cx + d - s, cy + d, paint);
                        c.drawLine(cx + d, cy + d, cx + d, cy + d - s, paint);
                        c.drawLine(
                                cx - a.dp(3f),
                                cy - a.dp(3f),
                                cx - d + a.dp(0.6f),
                                cy - d + a.dp(0.6f),
                                paint);
                        c.drawLine(
                                cx + a.dp(3f),
                                cy + a.dp(3f),
                                cx + d - a.dp(0.6f),
                                cy + d - a.dp(0.6f),
                                paint);
                        break;
                    }
                case ICON_NAV_FONT:
                    {
                        // Typography 'A' + nav pill bar
                        c.drawLine(
                                cx - a.dp(3.5f),
                                cy - a.dp(6.5f),
                                cx - a.dp(7.2f),
                                cy + a.dp(3f),
                                paint);
                        c.drawLine(
                                cx - a.dp(3.5f),
                                cy - a.dp(6.5f),
                                cx + a.dp(0.2f),
                                cy + a.dp(3f),
                                paint);
                        c.drawLine(
                                cx - a.dp(6f),
                                cy - a.dp(0.2f),
                                cx - a.dp(1f),
                                cy - a.dp(0.2f),
                                paint);
                        c.drawCircle(cx + a.dp(4.8f), cy + a.dp(0.2f), a.dp(2.6f), paint);
                        c.drawLine(
                                cx + a.dp(7.4f),
                                cy - a.dp(2.4f),
                                cx + a.dp(7.4f),
                                cy + a.dp(3f),
                                paint);
                        c.drawLine(
                                cx - a.dp(7.5f),
                                cy + a.dp(7f),
                                cx + a.dp(7.5f),
                                cy + a.dp(7f),
                                paint);
                        break;
                    }
                case ICON_GLASS_BLUR:
                    {
                        // Overlapping translucent glass circles
                        c.drawCircle(cx - a.dp(2.6f), cy - a.dp(2.2f), a.dp(5.4f), paint);
                        paint.setAlpha(110);
                        paint.setStyle(Paint.Style.FILL);
                        c.drawCircle(cx + a.dp(2.8f), cy + a.dp(2.4f), a.dp(5.4f), paint);
                        paint.setAlpha(255);
                        paint.setStyle(Paint.Style.STROKE);
                        c.drawCircle(cx + a.dp(2.8f), cy + a.dp(2.4f), a.dp(5.4f), paint);
                        break;
                    }
                case ICON_MOTION_SPARK:
                    {
                        // Lightning flash / dynamic animation
                        Path bolt = new Path();
                        bolt.moveTo(cx + a.dp(1.2f), cy - a.dp(8.2f));
                        bolt.lineTo(cx - a.dp(5.2f), cy + a.dp(0.6f));
                        bolt.lineTo(cx + a.dp(0.2f), cy + a.dp(0.6f));
                        bolt.lineTo(cx - a.dp(1.2f), cy + a.dp(8.2f));
                        bolt.lineTo(cx + a.dp(5.2f), cy - a.dp(0.6f));
                        bolt.lineTo(cx - a.dp(0.2f), cy - a.dp(0.6f));
                        bolt.close();
                        c.drawPath(bolt, paint);
                        break;
                    }
                case ICON_TRASH_CLEAR:
                    {
                        // Trash can
                        c.drawLine(
                                cx - a.dp(7.2f),
                                cy - a.dp(5.2f),
                                cx + a.dp(7.2f),
                                cy - a.dp(5.2f),
                                paint);
                        c.drawLine(
                                cx - a.dp(2.4f),
                                cy - a.dp(7.5f),
                                cx + a.dp(2.4f),
                                cy - a.dp(7.5f),
                                paint);
                        RectF bin =
                                new RectF(
                                        cx - a.dp(5.2f),
                                        cy - a.dp(5.2f),
                                        cx + a.dp(5.2f),
                                        cy + a.dp(7.8f));
                        c.drawRoundRect(bin, a.dp(1.8f), a.dp(1.8f), paint);
                        c.drawLine(
                                cx - a.dp(1.8f),
                                cy - a.dp(1.8f),
                                cx - a.dp(1.8f),
                                cy + a.dp(4.5f),
                                paint);
                        c.drawLine(
                                cx + a.dp(1.8f),
                                cy - a.dp(1.8f),
                                cx + a.dp(1.8f),
                                cy + a.dp(4.5f),
                                paint);
                        break;
                    }
                case ICON_DIAGNOSTIC_LOG:
                    {
                        // Diagnostic pulse monitor
                        RectF box =
                                new RectF(
                                        cx - a.dp(8.5f),
                                        cy - a.dp(6.8f),
                                        cx + a.dp(8.5f),
                                        cy + a.dp(6.8f));
                        c.drawRoundRect(box, a.dp(2.4f), a.dp(2.4f), paint);
                        Path pulse = new Path();
                        pulse.moveTo(cx - a.dp(5.8f), cy + a.dp(0.5f));
                        pulse.lineTo(cx - a.dp(2.6f), cy + a.dp(0.5f));
                        pulse.lineTo(cx - a.dp(1f), cy - a.dp(3.8f));
                        pulse.lineTo(cx + a.dp(1.2f), cy + a.dp(3.8f));
                        pulse.lineTo(cx + a.dp(2.8f), cy + a.dp(0.5f));
                        pulse.lineTo(cx + a.dp(5.8f), cy + a.dp(0.5f));
                        c.drawPath(pulse, paint);
                        break;
                    }
                case ICON_BACKUP_SAFE:
                    {
                        // Archive / backup box
                        RectF lid =
                                new RectF(
                                        cx - a.dp(8.2f),
                                        cy - a.dp(6.5f),
                                        cx + a.dp(8.2f),
                                        cy - a.dp(2.2f));
                        c.drawRoundRect(lid, a.dp(1.5f), a.dp(1.5f), paint);
                        RectF body =
                                new RectF(
                                        cx - a.dp(7f),
                                        cy - a.dp(2.2f),
                                        cx + a.dp(7f),
                                        cy + a.dp(7.5f));
                        c.drawRoundRect(body, a.dp(2f), a.dp(2f), paint);
                        c.drawLine(
                                cx - a.dp(2.4f),
                                cy + a.dp(1.8f),
                                cx + a.dp(2.4f),
                                cy + a.dp(1.8f),
                                paint);
                        break;
                    }
                case ICON_WALLPAPER:
                    {
                        // Landscape image frame with sun & mountains
                        RectF frame =
                                new RectF(
                                        cx - a.dp(8.5f),
                                        cy - a.dp(6.5f),
                                        cx + a.dp(8.5f),
                                        cy + a.dp(6.5f));
                        c.drawRoundRect(frame, a.dp(2.4f), a.dp(2.4f), paint);
                        paint.setStyle(Paint.Style.FILL);
                        c.drawCircle(cx - a.dp(3.8f), cy - a.dp(2.2f), a.dp(1.6f), paint);
                        paint.setStyle(Paint.Style.STROKE);
                        Path mtn = new Path();
                        mtn.moveTo(cx - a.dp(6.5f), cy + a.dp(5.2f));
                        mtn.lineTo(cx - a.dp(1.5f), cy + a.dp(0.2f));
                        mtn.lineTo(cx + a.dp(1.8f), cy + a.dp(3.2f));
                        mtn.lineTo(cx + a.dp(4.2f), cy + a.dp(1f));
                        mtn.lineTo(cx + a.dp(6.8f), cy + a.dp(5.2f));
                        c.drawPath(mtn, paint);
                        break;
                    }
                case ICON_GRID_COLS:
                    {
                        // 2x2 poster grid
                        float s = a.dp(5.8f), g = a.dp(1.4f);
                        c.drawRoundRect(
                                new RectF(cx - g - s, cy - g - s, cx - g, cy - g),
                                a.dp(1.4f),
                                a.dp(1.4f),
                                paint);
                        c.drawRoundRect(
                                new RectF(cx + g, cy - g - s, cx + g + s, cy - g),
                                a.dp(1.4f),
                                a.dp(1.4f),
                                paint);
                        c.drawRoundRect(
                                new RectF(cx - g - s, cy + g, cx - g, cy + g + s),
                                a.dp(1.4f),
                                a.dp(1.4f),
                                paint);
                        c.drawRoundRect(
                                new RectF(cx + g, cy + g, cx + g + s, cy + g + s),
                                a.dp(1.4f),
                                a.dp(1.4f),
                                paint);
                        break;
                    }
                case ICON_AUDIO_WAVE:
                    {
                        // Equalizer sound wave bars
                        c.drawLine(
                                cx - a.dp(6.8f),
                                cy - a.dp(3f),
                                cx - a.dp(6.8f),
                                cy + a.dp(3f),
                                paint);
                        c.drawLine(
                                cx - a.dp(3.4f),
                                cy - a.dp(7f),
                                cx - a.dp(3.4f),
                                cy + a.dp(7f),
                                paint);
                        c.drawLine(cx, cy - a.dp(4.5f), cx, cy + a.dp(4.5f), paint);
                        c.drawLine(
                                cx + a.dp(3.4f),
                                cy - a.dp(8f),
                                cx + a.dp(3.4f),
                                cy + a.dp(8f),
                                paint);
                        c.drawLine(
                                cx + a.dp(6.8f),
                                cy - a.dp(3.5f),
                                cx + a.dp(6.8f),
                                cy + a.dp(3.5f),
                                paint);
                        break;
                    }
                case ICON_ASPECT_RATIO:
                    {
                        // 16:9 frame with inner corner brackets
                        RectF frame =
                                new RectF(
                                        cx - a.dp(8.8f),
                                        cy - a.dp(6f),
                                        cx + a.dp(8.8f),
                                        cy + a.dp(6f));
                        c.drawRoundRect(frame, a.dp(2.2f), a.dp(2.2f), paint);
                        c.drawLine(
                                cx - a.dp(5.8f),
                                cy - a.dp(3.2f),
                                cx - a.dp(2.8f),
                                cy - a.dp(3.2f),
                                paint);
                        c.drawLine(
                                cx - a.dp(5.8f),
                                cy - a.dp(3.2f),
                                cx - a.dp(5.8f),
                                cy - a.dp(0.4f),
                                paint);
                        c.drawLine(
                                cx + a.dp(5.8f),
                                cy + a.dp(3.2f),
                                cx + a.dp(2.8f),
                                cy + a.dp(3.2f),
                                paint);
                        c.drawLine(
                                cx + a.dp(5.8f),
                                cy + a.dp(3.2f),
                                cx + a.dp(5.8f),
                                cy + a.dp(0.4f),
                                paint);
                        break;
                    }
                case ICON_SPEED_GAUGE:
                    {
                        // Speedometer gauge
                        c.drawArc(
                                new RectF(
                                        cx - a.dp(8f),
                                        cy - a.dp(6.5f),
                                        cx + a.dp(8f),
                                        cy + a.dp(9.5f)),
                                155,
                                230,
                                false,
                                paint);
                        c.drawLine(cx, cy + a.dp(2f), cx + a.dp(4.2f), cy - a.dp(3.2f), paint);
                        paint.setStyle(Paint.Style.FILL);
                        c.drawCircle(cx, cy + a.dp(2f), a.dp(1.6f), paint);
                        break;
                    }
                case ICON_REPEAT_LOOP:
                    {
                        // Circular history / repeat arrow
                        c.drawArc(
                                new RectF(
                                        cx - a.dp(7.5f),
                                        cy - a.dp(7.5f),
                                        cx + a.dp(7.5f),
                                        cy + a.dp(7.5f)),
                                -45,
                                290,
                                false,
                                paint);
                        c.drawLine(
                                cx + a.dp(2.5f),
                                cy - a.dp(7.5f),
                                cx + a.dp(6f),
                                cy - a.dp(4.5f),
                                paint);
                        c.drawLine(
                                cx + a.dp(6.5f),
                                cy - a.dp(8.2f),
                                cx + a.dp(6f),
                                cy - a.dp(4.5f),
                                paint);
                        c.drawLine(cx, cy - a.dp(3.5f), cx, cy + a.dp(0.5f), paint);
                        c.drawLine(cx, cy + a.dp(0.5f), cx + a.dp(2.8f), cy + a.dp(2.2f), paint);
                        break;
                    }
                case ICON_SYSTEM:
                case ICON_UPDATE_CLOUD:
                case 6:
                    // Shield with inner forward-skip double chevron
                    {
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
                    }
                    break;
                default:
                    {
                        // Circular update ring + arrow
                        c.drawArc(
                                new RectF(a.dp(9.5f), a.dp(9.5f), w - a.dp(9.5f), h - a.dp(9.5f)),
                                -30,
                                300,
                                false,
                                paint);
                        c.drawLine(cx, a.dp(13f), cx, cy + a.dp(3.5f), paint);
                        c.drawLine(cx - a.dp(2.8f), cy + a.dp(0.8f), cx, cy + a.dp(3.6f), paint);
                        c.drawLine(cx + a.dp(2.8f), cy + a.dp(0.8f), cx, cy + a.dp(3.6f), paint);
                        break;
                    }
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
