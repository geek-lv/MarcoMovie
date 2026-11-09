package com.pilipala.marcomovie;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.media.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.view.animation.DecelerateInterpolator;
import android.widget.*;
import android.util.LruCache;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** Independent first native build. No original APK code or artwork is embedded. */
public final class MainActivity extends Activity {
    static final int WHITE = 0xfff5f5f7, MUTED = 0xffa1a1aa, DARK = 0xff101013;
    final Handler ui = new Handler(Looper.getMainLooper());
    final ExecutorService worker = Executors.newFixedThreadPool(4);
    final ExecutorService details = Executors.newFixedThreadPool(4);
    final DetailCache detailCache = new DetailCache();
    final SingleFlight<String> detailFlights = new SingleFlight<>();
    final List<Future<?>> pageRequests = new ArrayList<>();
    final ExecutorService artwork = Executors.newFixedThreadPool(8);
    final List<Future<?>> artworkRequests = new ArrayList<>();
    final ExecutorService phoneticWork = Executors.newSingleThreadExecutor();
    final LinkedHashSet<String> pendingTitles = new LinkedHashSet<>();
    final Runnable learnPendingTitles = this::learnPendingTitles;

    void learnPendingTitles() {
        SourceController.learnPendingTitles(this);
    }

    UpdateScreen updateScreen;
    final Runnable automaticUpdate =
            new Runnable() {
                public void run() {
                    if (!foreground) return;
                    if (current != null && current.home && appDialog == null) {
                        UpdateManager updates = UpdateManager.get(MainActivity.this);
                        updates.automatic();
                        if (updates.consumeNotice()) toast("发现新版，可在设置 → 软件更新查看");
                    }
                    ui.postDelayed(this, 60000);
                }
            };
    PlaybackLoading playbackLoading;
    View playbackLoadingFocus;
    int playbackResolveSerial;
    GlobalSearchScreen searchScreen;
    Dialog sourceDialog, accountDialog;
    MagnetStream magnetStream;
    static volatile PhoneticSearchIndex sharedPhonetic;
    volatile PhoneticSearchIndex phonetic;
    final CountDownLatch phoneticReady = new CountDownLatch(1);
    ConfigStore configStore;
    PhoneConfigDialog phoneConfig;
    LiveSession liveSession;
    final Runnable tuneLiveNumber = this::commitLiveNumber;
    LocalProxy localProxy;
    WebResolver webResolver;
    PlaybackControls playbackControls;
    Cms.Line activeLine;
    Cms.Episode activeEpisode;
    int resumeAt;
    WatchHistory.Entry playingBookmark;
    long lastBookmarkSave;
    final ArrayDeque<Screen> back = new ArrayDeque<>();
    final LruCache<String, Bitmap> images =
            new LruCache<String, Bitmap>(10 * 1024 * 1024) {
                protected int sizeOf(String k, Bitmap b) {
                    return b.getByteCount();
                }
            };
    final HomeKeyTracker homeKeys = new HomeKeyTracker();
    Screen shortcutScreen;
    StartupIntro startupIntro;
    boolean introSkipKey;
    HomeAmbient homeAmbient;
    HomeDock homeDock;
    FrameLayout root;
    LinearLayout body;
    ScrollView scroll;
    Screen current;
    Cms.Config config;
    android.content.SharedPreferences prefs;
    boolean privateMode, switchingPrivacy;

    @Override
    public File getFilesDir() {
        File base = super.getFilesDir();
        if (!privateMode) return base;
        File scoped = new File(base, "private-space");
        scoped.mkdirs();
        return scoped;
    }

    @Override
    public File getCacheDir() {
        File base = super.getCacheDir();
        if (!privateMode) return base;
        File scoped = new File(base, "private-space");
        scoped.mkdirs();
        return scoped;
    }

    boolean reduced, prepared, playing, foreground;
    int generation;
    static final int MAX_HOME_SNAPSHOTS = 8;
    final Map<String, SourceEngine> engines = new HashMap<>();
    final SingleFlight<SourceEngine> engineInitializations = new SingleFlight<>();
    final Map<String, HomeScreen.Snapshot> homeData =
            Collections.synchronizedMap(
                    new LinkedHashMap<String, HomeScreen.Snapshot>(16, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(
                                Map.Entry<String, HomeScreen.Snapshot> eldest) {
                            return size() > MAX_HOME_SNAPSHOTS;
                        }
                    });
    AppDialog appDialog;
    FrameLayout playbackLayer;
    MiniDetails miniDetails;
    boolean miniMode;
    boolean changingMiniEpisode;
    FrameLayout playerOverlay;
    boolean controlsVisible = true;
    Runnable hideControls;
    PlayerChrome playerChrome;
    MoviePlayer player;
    com.pilipala.emby.EmbyLibrary.Reporter embyReporter;
    String configOrigin = "";
    TextView playStatus, playClock;
    final SeekGesture seekGesture = new SeekGesture();
    String seekStatus = "";
    final Runnable seekPulse =
            new Runnable() {
                public void run() {
                    if (!seekGesture.active) return;
                    if (player == null
                            || !prepared
                            || current == null
                            || !current.player
                            || miniMode
                            || !foreground) {
                        finishSeek(false);
                        return;
                    }
                    seekGesture.advance(SystemClock.uptimeMillis());
                    previewSeek();
                    scheduleHide();
                    ui.postDelayed(this, 100);
                }
            };

    static final class Screen {
        MobileShell mobileHome;
        Runnable render;
        String focus, homeReturnFocus;
        int scroll;
        int episodeFocus = -1;
        String episodeFocusLine = "";
        final EpisodeSelection selection = new EpisodeSelection();
        boolean player, home;
        HomeScreen.State homeState;
        HomeScreen retainedHome;

        /** True while a listing redirect (folder / action / cross-search) has not drawn a page. */
        boolean redirect;

        Screen(Runnable r) {
            render = r;
        }
    }

    /** Foreground activity that source extensions may need for dialogs; cleared on destroy. */
    static volatile MainActivity activeActivity;

    /**
     * Extension background threads must never take the whole app down. Main-thread errors and
     * crashes without extension frames still reach the system handler unchanged.
     */
    static boolean isPluginBackgroundCrash(Thread thread, Throwable error, Thread mainThread) {
        return ListingRoute.isPluginBackgroundCrash(thread, error, mainThread);
    }

    private static volatile boolean crashGuardInstalled;

    static void installPluginCrashGuard() {
        if (crashGuardInstalled) return;
        crashGuardInstalled = true;
        final Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();
        final Thread main = android.os.Looper.getMainLooper().getThread();
        Thread.setDefaultUncaughtExceptionHandler(
                (thread, error) -> {
                    boolean pluginTrace =
                            ListingRoute.hasPluginFrame(error) || hasDexClassLoaderFrame(error);
                    boolean bgPlugin =
                            isPluginBackgroundCrash(thread, error, main)
                                    || (thread != null
                                            && thread != main
                                            && (pluginTrace
                                                    || thread.getContextClassLoader()
                                                            instanceof
                                                            dalvik.system.DexClassLoader));
                    if (bgPlugin) {
                        AppLog.w(
                                "PluginGuard",
                                "extension thread failed: " + thread.getName(),
                                error);
                        MainActivity activity = activeActivity;
                        if (activity != null)
                            activity.ui.post(
                                    () -> {
                                        if (!activity.isFinishing())
                                            activity.toast("来源扩展出错，已拦截，应用继续运行");
                                    });
                        return;
                    }
                    if (thread == main && pluginTrace) {
                        AppLog.w("PluginGuard", "extension main-thread callback failed", error);
                        MainActivity activity = activeActivity;
                        if (activity != null && activity.prefs != null) {
                            activity.prefs.edit().putString(Prefs.LOADING_SITE, "").commit();
                            activity.ui.post(
                                    () -> {
                                        if (!activity.isFinishing()) activity.toast("扩展操作未能完成，请重试");
                                    });
                        }
                        while (true) {
                            try {
                                android.os.Looper.loop();
                                return;
                            } catch (Throwable nextError) {
                                if (!ListingRoute.hasPluginFrame(nextError)
                                        && !hasDexClassLoaderFrame(nextError)) {
                                    if (previous != null)
                                        previous.uncaughtException(thread, nextError);
                                    return;
                                }
                                AppLog.w(
                                        "PluginGuard",
                                        "extension main-thread follow-up error",
                                        nextError);
                            }
                        }
                    }
                    MainActivity activity = activeActivity;
                    if (activity != null && activity.prefs != null) {
                        String currentSite = activity.prefs.getString(Prefs.SELECTED_SITE, "");
                        if (currentSite != null && !currentSite.isEmpty()) {
                            activity.prefs
                                    .edit()
                                    .putString(Prefs.LOADING_SITE, currentSite)
                                    .commit();
                        }
                    }
                    if (previous != null) previous.uncaughtException(thread, error);
                });
    }

    static boolean hasDexClassLoaderFrame(Throwable error) {
        if (error == null) return false;
        LinkedHashSet<ClassLoader> loaders = new LinkedHashSet<>(CspEngine.LOADERS.values());
        synchronized (CspEngine.ALL_PLUGIN_LOADERS) {
            loaders.addAll(CspEngine.ALL_PLUGIN_LOADERS);
        }
        if (loaders.isEmpty()) return false;
        int depth = 0;
        for (Throwable t = error; t != null && depth < 12; t = t.getCause(), depth++) {
            for (StackTraceElement e : t.getStackTrace()) {
                String cls = e.getClassName();
                if (cls == null
                        || cls.startsWith("android.")
                        || cls.startsWith("java.")
                        || cls.startsWith("javax.")
                        || cls.startsWith("dalvik.")
                        || cls.startsWith("com.android.")
                        || cls.startsWith("com.pilipala.")) continue;
                for (ClassLoader loader : loaders) {
                    try {
                        Class<?> loaded = Class.forName(cls, false, loader);
                        ClassLoader loadedCl = loaded.getClassLoader();
                        if (loadedCl == loader
                                || (loadedCl instanceof dalvik.system.BaseDexClassLoader
                                        && loadedCl != MainActivity.class.getClassLoader())) {
                            return true;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (t.getCause() == t) break;
        }
        return false;
    }

    /** Opens a list card without leaving an invisible redirect page in the back stack. */
    void openListing(Cms.Site s, Cms.Video v) {
        if (v == null) return;
        // 详情入口兜底：外部跳转 / 历史 / 收藏等不经过 Cms.listing 的路径也拦住
        if (Safety.hideItem(v)) {
            toast("该内容已被内容安全过滤");
            return;
        }
        if (v.action != null && !v.action.isEmpty()) {
            sourceAction(s, v.action);
            return;
        }
        if ("action".equalsIgnoreCase(v.tag) && v.id != null && !v.id.isEmpty()) {
            sourceAction(s, v.id);
            return;
        }
        if ("folder".equalsIgnoreCase(v.tag)) {
            open(new Screen(() -> videos(s, v.id, v.title, 1, null)), true);
            return;
        }
        if (v.id != null && v.id.startsWith("msearch:")) {
            crossSearch(Cms.searchTitle(v));
            return;
        }
        if (SourceController.isConfigOrNetdiskActionCard(s, v)) {
            sourceAction(s, v.id);
            return;
        }
        open(new Screen(() -> detail(s, v)), true);
    }

    /**
     * Called by a detail redirect that will not draw its own page. Restores the page underneath
     * so Back never re-runs the redirect forever.
     */
    void dropRedirect() {
        if (current != null && current.redirect && !back.isEmpty()) current = back.pop();
    }

    static boolean isRedirectListing(Cms.Video v) {
        return v != null && ListingRoute.isRedirect(v.tag, v.action, v.id);
    }

    /**
     * Detail pages reached for a folder, action or cross-search card hand over to the real page.
     * The invisible detail screen is removed first, so Back returns to the list instead of
     * re-opening the folder in a loop.
     */
    boolean redirectListing(Cms.Site s, Cms.Video v) {
        if (!isRedirectListing(v)) return false;
        if (current != null) current.redirect = true;
        if (back.isEmpty() && "folder".equalsIgnoreCase(v.tag)) {
            open(new Screen(() -> videos(s, v.id, v.title, 1, null)), false);
            return true;
        }
        dropRedirect();
        openListing(s, v);
        return true;
    }

    interface Work<T> {
        T run() throws Exception;
    }

    interface Done<T> {
        void accept(T value) throws Exception;
    }

    static int[] viewportHint;
    boolean layoutReady, layoutIntro;
    int usableWidth, usableHeight;
    int formFactor = TvLayoutPolicy.FORM_TV;

    static int detectFormFactor(Context base, int widthPixels, int heightPixels, int densityDpi) {
        int smallestDp =
                densityDpi > 0
                        ? Math.round(Math.min(widthPixels, heightPixels) * 160f / densityDpi)
                        : 360;
        if (viewportHint == null) {
            try {
                android.content.pm.ApplicationInfo appInfo =
                        base.getPackageManager()
                                .getApplicationInfo(
                                        base.getPackageName(),
                                        android.content.pm.PackageManager.GET_META_DATA);
                if (appInfo != null && appInfo.metaData != null) {
                    String edition = appInfo.metaData.getString("com.pilipala.marcomovie.EDITION");
                    if ("mobile".equals(edition)) {
                        return smallestDp >= 600
                                ? TvLayoutPolicy.FORM_TABLET
                                : TvLayoutPolicy.FORM_PHONE;
                    }
                    if ("tv".equals(edition)) {
                        return TvLayoutPolicy.FORM_TV;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        boolean tvMode = false;
        try {
            UiModeManager uiMode = (UiModeManager) base.getSystemService(Context.UI_MODE_SERVICE);
            tvMode =
                    uiMode != null
                            && uiMode.getCurrentModeType()
                                    == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION;
        } catch (Exception ignored) {
        }
        boolean hasTouch = false;
        try {
            hasTouch =
                    base.getPackageManager()
                            .hasSystemFeature(
                                    android.content.pm.PackageManager.FEATURE_TOUCHSCREEN);
        } catch (Exception ignored) {
        }
        return TvLayoutPolicy.formFactor(tvMode, hasTouch, smallestDp);
    }

    boolean isTvDevice() {
        return formFactor == TvLayoutPolicy.FORM_TV;
    }

    boolean isPortrait() {
        return TvLayoutPolicy.isPortrait(windowWidth(), windowHeight());
    }

    boolean useBottomDock() {
        return TvLayoutPolicy.useBottomDock(windowWidth(), windowHeight(), formFactor);
    }

    void applyMobileSystemBars(boolean fullscreenPlayer) {
        if (isTvDevice()) return;
        try {
            Window w = getWindow();
            if (w == null) return;
            WindowManager.LayoutParams lp = w.getAttributes();
            try {
                java.lang.reflect.Field f = lp.getClass().getField("layoutInDisplayCutoutMode");
                // 1 == WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                if (f.getInt(lp) != 1) {
                    f.setInt(lp, 1);
                    w.setAttributes(lp);
                }
            } catch (Throwable ignored) {
            }
            if (fullscreenPlayer) {
                w.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                w.getDecorView().setSystemUiVisibility(5894);
            } else {
                w.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                w.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
                w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
                w.getDecorView()
                        .setSystemUiVisibility(
                                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
                w.setStatusBarColor(Color.TRANSPARENT);
                w.setNavigationBarColor(0xff060a08);
            }
        } catch (Throwable ignored) {
        }
    }

    void togglePlayerOrientation() {
        if (isTvDevice()) return;
        if (isPortrait()) {
            setRequestedOrientation(
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        } else {
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }
        if (playerChrome != null) playerChrome.relayout();
    }

    int windowWidth() {
        return usableWidth > 0 ? usableWidth : getResources().getDisplayMetrics().widthPixels;
    }

    int windowHeight() {
        return usableHeight > 0 ? usableHeight : getResources().getDisplayMetrics().heightPixels;
    }

    @Override
    protected void attachBaseContext(Context base) {
        android.util.DisplayMetrics screen = new android.util.DisplayMetrics();
        ((WindowManager) base.getSystemService(Context.WINDOW_SERVICE))
                .getDefaultDisplay()
                .getMetrics(screen);
        int detectedForm =
                detectFormFactor(base, screen.widthPixels, screen.heightPixels, screen.densityDpi);
        int percent = base.getSharedPreferences(Prefs.FILE, 0).getInt(Prefs.UI_SCALE_PERCENT, 100);
        int[] hint = viewportHint;
        if (hint != null && hint[0] == screen.widthPixels && hint[1] == screen.heightPixels) {
            screen.widthPixels = hint[2];
            screen.heightPixels = hint[3];
        }
        android.content.res.Configuration layout =
                new android.content.res.Configuration(base.getResources().getConfiguration());
        layout.densityDpi =
                TvLayoutPolicy.deviceDensityDpi(
                        screen.widthPixels, screen.heightPixels, percent, detectedForm);
        float density = layout.densityDpi / 160f;
        if (detectedForm == TvLayoutPolicy.FORM_TV) {
            layout.screenWidthDp =
                    Math.round(Math.max(screen.widthPixels, screen.heightPixels) / density);
            layout.screenHeightDp =
                    Math.round(Math.min(screen.widthPixels, screen.heightPixels) / density);
        } else {
            layout.screenWidthDp = Math.round(screen.widthPixels / density);
            layout.screenHeightDp = Math.round(screen.heightPixels / density);
        }
        layout.smallestScreenWidthDp = Math.min(layout.screenWidthDp, layout.screenHeightDp);
        super.attachBaseContext(base.createConfigurationContext(layout));
    }

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);
        privateMode = PrivacySpace.active;
        if (privateMode) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        activeActivity = this;
        installPluginCrashGuard();
        android.util.DisplayMetrics rawScreen = new android.util.DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(rawScreen);
        formFactor =
                detectFormFactor(
                        this, rawScreen.widthPixels, rawScreen.heightPixels, rawScreen.densityDpi);
        setRequestedOrientation(
                isTvDevice()
                        ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER);
        configStore = new ConfigStore(getFilesDir());
        if (isTvDevice()) {
            getWindow().getDecorView().setSystemUiVisibility(5894);
        } else {
            applyMobileSystemBars(false);
        }
        prefs = getSharedPreferences(privateMode ? "movie-private" : Prefs.FILE, MODE_PRIVATE);
        reduced = prefs.getBoolean(Prefs.REDUCED, false);
        configOrigin = prefs.getString("origin", "");
        // ---- 移植功能初始化 ----
        // 内容过滤只读 SharedPreferences，主线程初始化即可；KWS 模型加载要数秒，必须放后台。
        com.pilipala.marcomovie.safety.ContentFilterConfig.init(this);
        com.pilipala.marcomovie.safety.AdultSourceStats.init(this);
        final com.pilipala.marcomovie.adskip.AdSkipController adSkip =
                com.pilipala.marcomovie.adskip.AdSkipController.get();
        new Thread(() -> adSkip.init(MainActivity.this), "adskip-engine-init").start();
        // 命中后回主线程 seek；播放器实例会随换集更换，因此每次动态取 player 字段。
        adSkip.setSeekHandler(
                new com.pilipala.marcomovie.adskip.AdSkipController.SeekHandler() {
                    public void seekTo(long positionMs) {
                        ui.post(
                                () -> {
                                    MoviePlayer p = player;
                                    if (p != null && prepared)
                                        p.seekToFromAdSkip((int) positionMs);
                                });
                    }

                    public void onAdSkipped(long fromMs, long toMs, String keyword) {
                        ui.post(
                                () ->
                                        toast(
                                                "识别到口播「"
                                                        + keyword
                                                        + "」，跳过 "
                                                        + (toMs - fromMs) / 1000
                                                        + " 秒"));
                    }
                });
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) images.resize(ImageDecodePolicy.cacheBytes(am.getMemoryClass()));
        } catch (Exception ignored) {
        }
        try {
            File f = new File(getFilesDir(), "config.json");
            if (f.exists())
                try (InputStream i = new FileInputStream(f)) {
                    config = Cms.parseConfig(Cms.read(i, Cms.MAX_JSON), configOrigin);
                    SourcePicker.applyEdits(this, config, configOrigin);
                    SourcePreferences.restoreDefault(this);
                }
        } catch (Exception e) {
            AppLog.w("MainActivity", "load saved config failed", e);
            config = null;
        }
        // Phone / tablet only: restore the offline download queue (never started on TV).
        if (!isTvDevice()) DownloadManager.get(this);
        if (sharedPhonetic != null) {
            phonetic = sharedPhonetic;
            phoneticReady.countDown();
        } else {
            worker.execute(
                    () -> {
                        try {
                            PhoneticSearchIndex index =
                                    new PhoneticSearchIndex(
                                            getAssets().open("search/pinyin-characters.tsv"),
                                            getAssets().open("search/pinyin-phrases.dict.yaml"),
                                            getAssets().open("search/title-phrases.txt"));
                            try {
                                org.json.JSONArray old =
                                        new org.json.JSONArray(
                                                prefs.getString(Prefs.PHONETIC_TITLES, "[]"));
                                for (int i = 0; i < old.length(); i++)
                                    index.learn(old.optString(i));
                                org.json.JSONArray history =
                                        new org.json.JSONArray(
                                                prefs.getString(Prefs.GLOBAL_SEARCH_HISTORY, "[]"));
                                for (int i = history.length() - 1; i >= 0; i--)
                                    index.prefer(history.optString(i));
                            } catch (Throwable ignored) {
                            }
                            sharedPhonetic = index;
                            phonetic = index;
                        } catch (Throwable ignored) {
                        } finally {
                            phoneticReady.countDown();
                        }
                    });
        }
        layoutIntro = b == null || b.getBoolean("layout_initial_launch", false);
        root = new FrameLayout(this);
        root.setBackgroundColor(DARK);
        setContentView(root);
        root.addOnLayoutChangeListener(
                new View.OnLayoutChangeListener() {
                    public void onLayoutChange(
                            View view,
                            int l,
                            int t,
                            int r,
                            int bottom,
                            int ol,
                            int ot,
                            int or,
                            int ob) {
                        int width = r - l, height = bottom - t;
                        if (width <= 0 || height <= 0) return;
                        view.removeOnLayoutChangeListener(this);
                        usableWidth = width;
                        usableHeight = height;
                        int expected =
                                TvLayoutPolicy.deviceDensityDpi(
                                        width,
                                        height,
                                        prefs.getInt(Prefs.UI_SCALE_PERCENT, 100),
                                        formFactor);
                        if (viewportHint == null
                                && expected != getResources().getDisplayMetrics().densityDpi) {
                            android.util.DisplayMetrics display = new android.util.DisplayMetrics();
                            getWindowManager().getDefaultDisplay().getMetrics(display);
                            viewportHint =
                                    new int[] {
                                        display.widthPixels, display.heightPixels, width, height
                                    };
                            recreate();
                            return;
                        }
                        ui.post(
                                () -> {
                                    if (isFinishing() || isDestroyed()) return;
                                    String crashedSite = prefs.getString(Prefs.LOADING_SITE, "");
                                    boolean recoveredFromCrash =
                                            crashedSite != null && !crashedSite.isEmpty();
                                    if (recoveredFromCrash) {
                                        prefs.edit()
                                                .putString(Prefs.LOADING_SITE, "")
                                                .putString(Prefs.SELECTED_SITE, "")
                                                .commit();
                                    }
                                    if (!reduced && config != null) {
                                        final String savedKey =
                                                prefs.getString(Prefs.SELECTED_SITE, "");
                                        if (savedKey != null && !savedKey.isEmpty()) {
                                            for (final Cms.Site s : config.sites) {
                                                if (s.key.equals(savedKey) && available(s)) {
                                                    worker.execute(
                                                            () -> {
                                                                try {
                                                                    engine(s);
                                                                } catch (Throwable ignored) {
                                                                }
                                                            });
                                                    break;
                                                }
                                            }
                                        }
                                    }
                                    open(new Screen(() -> home()), false);
                                    layoutReady = true;
                                    if (recoveredFromCrash) {
                                        toast("上次加载该片源时异常退出，已自动重置片源选择");
                                    } else if (layoutIntro && isTvDevice()) {
                                        StartupIntro.show(MainActivity.this);
                                    }
                                });
                    }
                });
    }

    /**
     * 旋转/分屏引起的窗口尺寸变化，统一由 decorView 的布局回调驱动。
     *
     * <p>不能直接在这里读 root 的宽高：onConfigurationChanged 触发时窗口还没重新布局，
     * root.getWidth() 仍是旋转前的尺寸，与缓存的 usableWidth 相等，判断就会漏掉这次翻转
     * —— 表现就是「横屏后导航栏停靠右侧，转回竖屏不会回到底部」。
     */
    private boolean windowSizeWatcherAttached;
    private boolean lastPortraitKnown;
    private boolean lastPortraitLayout;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        attachWindowSizeWatcher();
    }

    private void attachWindowSizeWatcher() {
        if (windowSizeWatcherAttached) return;
        Window w = getWindow();
        View decor = w == null ? null : w.peekDecorView();
        if (decor == null) return;
        windowSizeWatcherAttached = true;
        decor.addOnLayoutChangeListener(
                (v, l, t, r, b, ol, ot, or, ob) -> onWindowSizeChanged(r - l, b - t));
    }

    private void onWindowSizeChanged(final int w, final int h) {
        if (w <= 0 || h <= 0) return;
        final int oldW = usableWidth, oldH = usableHeight;
        usableWidth = w;
        usableHeight = h;
        final boolean portrait = TvLayoutPolicy.isPortrait(w, h);
        if (!lastPortraitKnown) {
            lastPortraitKnown = true;
            lastPortraitLayout = portrait;
            return;
        }
        if (portrait == lastPortraitLayout) {
            // 同一方向下的窗口变化（分屏拖拽、缩放手势）也重新排版，但避免首帧抖动的微小差值
            if (oldW > 0 && oldH > 0 && (Math.abs(oldW - w) > 16 || Math.abs(oldH - h) > 16))
                scheduleOrientationRelayout();
            return;
        }
        lastPortraitLayout = portrait;
        scheduleOrientationRelayout();
    }

    private void scheduleOrientationRelayout() {
        if (!layoutReady || root == null) return;
        final View anchor = root;
        anchor.post(
                () -> {
                    if (isFinishing() || isDestroyed() || root == null) return;
                    if (current != null && current.player) {
                        if (playerChrome != null) playerChrome.relayout();
                        if (playbackControls != null)
                            playbackControls.resize(
                                    playbackControls.videoWidth, playbackControls.videoHeight);
                    } else if (current != null) {
                        // 整体重建：底部 Dock / 右侧导航栏、首页栅格列数都由方向决定
                        refresh();
                    }
                });
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        attachWindowSizeWatcher();
        if (!layoutReady || root == null) return;
        if (!isTvDevice() && usableWidth > 0 && usableHeight > 0 && newConfig != null) {
            boolean configPortrait =
                    newConfig.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT;
            boolean configLandscape =
                    newConfig.orientation
                            == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
            if ((configPortrait && usableWidth > usableHeight)
                    || (configLandscape && usableWidth < usableHeight)) {
                int tmp = usableWidth;
                usableWidth = usableHeight;
                usableHeight = tmp;
                if (current != null && current.player) {
                    if (playerChrome != null) playerChrome.scheduleLayout();
                } else if (current != null) {
                    refresh();
                }
            }
        }
        root.post(this::syncRootDimensions);
        ui.postDelayed(this::syncRootDimensions, 140);
    }

    void syncRootDimensions() {
        if (isFinishing() || isDestroyed() || root == null || !layoutReady) return;
        int w = root.getWidth(), h = root.getHeight();
        if (w <= 0 || h <= 0) return;
        boolean orientationFlipped = (w < h) != (usableWidth < usableHeight);
        if (w != usableWidth || h != usableHeight) {
            usableWidth = w;
            usableHeight = h;
            if (current != null && current.player) {
                if (playerChrome != null) playerChrome.relayout();
                if (playbackControls != null)
                    playbackControls.resize(
                            playbackControls.videoWidth, playbackControls.videoHeight);
            } else if (current != null && orientationFlipped) {
                refresh();
            }
        }
    }

    void watchRootLayout(FrameLayout targetRoot) {
        if (targetRoot == null) return;
        targetRoot.addOnLayoutChangeListener(
                (view, l, t, r, b, ol, ot, or, ob) -> {
                    int w = r - l, h = b - t;
                    if (w <= 0 || h <= 0 || !layoutReady || view != root) return;
                    if (w != usableWidth || h != usableHeight) {
                        ui.post(this::syncRootDimensions);
                    }
                });

    @Override
    protected void onSaveInstanceState(Bundle out) {
        out.putBoolean("layout_initial_launch", !layoutReady && layoutIntro);
        super.onSaveInstanceState(out);
    }

    int dp(float x) {
        return Math.round(x * getResources().getDisplayMetrics().density);
    }

    void glass(View view, float radius) {
        com.pilipala.glass.GlassworkSurface.attach(
                view, root, dp(radius), () -> prefs.getBoolean(Prefs.REDUCED_TRANSPARENCY, false));
    }

    GradientDrawable bg(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }

    TextView text(String s, float size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(size);
        t.setFontFeatureSettings("kern");
        return t;
    }

    LinearLayout vertical() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        return v;
    }

    LinearLayout row() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.HORIZONTAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }

    void gap(LinearLayout p, int n) {
        View v = new View(this);
        p.addView(v, new LinearLayout.LayoutParams(1, dp(n)));
    }

    void paragraph(String s) {
        TextView t = text(s, 16, MUTED);
        t.setLineSpacing(dp(5), 1);
        body.addView(t);
        gap(body, 14);
    }

    Button button(String label, String key, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(15);
        b.setTextColor(WHITE);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setIncludeFontPadding(false);
        b.setStateListAnimator(null);
        b.setOutlineProvider(null);
        b.setMinHeight(dp(48));
        b.setMinimumHeight(dp(48));
        b.setPadding(dp(20), dp(9), dp(20), dp(9));
        b.setBackground(bg(0xff242429, 12));
        b.setTag(key);
        b.setFocusable(true);
        b.setFocusableInTouchMode(isTvDevice());
        b.setOnClickListener(v -> action.run());
        pressFeedback(b);
        b.setOnFocusChangeListener(
                (v, f) ->
                        FocusMotion.style(
                                this, b, f, 0xff242429, WHITE, 12, WHITE, DARK, false, 1.025f));
        return b;
    }

    void pressFeedback(View view) {
        view.setOnTouchListener(
                (v, event) -> {
                    if (!isTvDevice()) {
                        if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                            v.setAlpha(0.75f);
                        } else if (event.getAction() == android.view.MotionEvent.ACTION_UP
                                || event.getAction() == android.view.MotionEvent.ACTION_CANCEL) {
                            v.setAlpha(1.0f);
                        }
                        return false;
                    }
                    if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                        v.requestFocus();
                        FocusMotion.press(this, v, true);
                    } else if (event.getAction() == android.view.MotionEvent.ACTION_UP
                            || event.getAction() == android.view.MotionEvent.ACTION_CANCEL)
                        FocusMotion.press(this, v, false);
                    return false;
                });
        view.setOnKeyListener(
                (v, key, event) -> {
                    FocusMotion.key(this, v, key, event);
                    return false;
                });
    }

    Button navButton(String label, String key, Runnable action) {
        Button b = button(label, key, action);
        b.setTextSize(13.5f);
        b.setSingleLine(true);
        b.setEllipsize(android.text.TextUtils.TruncateAt.END);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(14), dp(6), dp(14), dp(6));
        b.setMinHeight(dp(38));
        b.setMinimumHeight(dp(38));
        GradientDrawable pill = bg(0xff282832, 19);
        pill.setStroke(Math.max(1, dp(1)), 0x26ffffff);
        b.setBackground(pill);
        b.setTextColor(0xffe6e6ee);
        b.setOnFocusChangeListener(
                (v, f) ->
                        FocusMotion.style(
                                this, b, f, 0xff282832, WHITE, 19, 0xffe6e6ee, DARK, false, 1.02f));
        return b;
    }

    void chooseOrImport() {
        if (config == null) importDialog();
        else chooseSources();
    }

    void addButton(LinearLayout p, Button b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(dp(4), dp(5), dp(10), dp(8));
        p.addView(b, lp);
    }

    int statusBarHeight() {
        int best = isTvDevice() ? 0 : dp(38);
        try {
            if (getWindow() != null && getWindow().getDecorView() != null) {
                WindowInsets insets = getWindow().getDecorView().getRootWindowInsets();
                if (insets != null) {
                    best = Math.max(best, insets.getSystemWindowInsetTop());
                    if (Build.VERSION.SDK_INT >= 28 && insets.getDisplayCutout() != null) {
                        best = Math.max(best, insets.getDisplayCutout().getSafeInsetTop());
                    }
                }
            }
        } catch (Exception ignored) {
        }
        try {
            int resId =
                    android.content.res.Resources.getSystem()
                            .getIdentifier("status_bar_height", "dimen", "android");
            if (resId > 0) {
                best =
                        Math.max(
                                best,
                                android.content.res.Resources.getSystem()
                                        .getDimensionPixelSize(resId));
            }
        } catch (Exception ignored) {
        }
        try {
            int resId = getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (resId > 0) {
                best = Math.max(best, getResources().getDimensionPixelSize(resId));
            }
        } catch (Exception ignored) {
        }
        return best;
    }

    void renderPage(String title, String eyebrow) {
        renderPage(title, eyebrow, false);
    }

    void preparePage(boolean navigation) {
        clearPlaybackLoading();
        cancelArtwork();
        cancelPageRequests();
        if (current != null) current.home = navigation;
        if (searchScreen != null) {
            searchScreen.close();
            searchScreen = null;
        }
        stopWeb();
        stopMagnet();
        releasePlayer();
        if (!isTvDevice()) {
            applyMobileSystemBars(false);
        }
        generation++;
    }

    void renderPage(String title, String eyebrow, boolean navigation) {
        preparePage(navigation);
        root = new FrameLayout(this);
        root.setClipChildren(false);
        root.setClipToPadding(false);
        root.setBackgroundColor(DARK);
        root.addView(ImageLoader.createAtmosphere(this), new FrameLayout.LayoutParams(-1, -1));
        homeAmbient = null;
        if (navigation
                && prefs.getBoolean(Prefs.HOME_AMBIENT, true)
                && !prefs.getBoolean(Prefs.WALLPAPER_ENABLED, false)) {
            homeAmbient = new HomeAmbient(this);
            root.addView(homeAmbient, new FrameLayout.LayoutParams(-1, -1));
        }
        if (navigation) root.addView(homeGradient(), new FrameLayout.LayoutParams(-1, -1));
        scroll = new TvScrollView(this);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setFillViewport(true);
        body = vertical();
        body.setClipChildren(false);
        body.setClipToPadding(false);
        if (!isTvDevice()) {
            int sidePad = isPortrait() ? dp(18) : dp(28);
            body.setPadding(sidePad, statusBarHeight() + dp(16), sidePad, dp(48));
        } else {
            body.setPadding(dp(46), dp(26), dp(46), dp(44));
        }
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        watchRootLayout(root);
        homeDock = null;
        if (navigation) {
            if (useBottomDock()) {
                body.setPadding(dp(18), statusBarHeight() + dp(14), dp(18), dp(86));
            } else {
                body.setPadding(dp(34), dp(18), dp(100), dp(36));
            }
            LinearLayout brandRow = row();
            brandRow.setTag("home-brand");
            ImageView brandMark = new ImageView(this);
            brandMark.setImageResource(R.drawable.logo_mark);
            LinearLayout.LayoutParams brandSize = new LinearLayout.LayoutParams(dp(30), dp(30));
            brandSize.rightMargin = dp(10);
            brandRow.addView(brandMark, brandSize);
            TextView brand = text("MARCO MOVIE", 14, WHITE);
            brand.setLetterSpacing(.10f);
            brand.setTypeface(null, Typeface.BOLD);
            brandRow.addView(brand);
            body.addView(brandRow);
            gap(body, title.isEmpty() ? 14 : 22);
            homeDock = new HomeDock(this);
        }
        if (!title.isEmpty()) {
            TextView eye = text(eyebrow, 12, 0xffa9c9f3);
            eye.setLetterSpacing(.15f);
            body.addView(eye);
            gap(body, 10);
            TextView heading = text(title, 36, WHITE);
            heading.setTypeface(null, Typeface.BOLD);
            body.addView(heading);
            gap(body, 16);
        }
        if (!reduced) {
            body.setTranslationY(dp(4));
            body.animate()
                    .translationY(0)
                    .setDuration(140)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }
    }

    /**
     * Soft home backdrop: a cool light from the top, deepening toward the bottom, with gentle
     * side shading. Sits above the poster colors or wallpaper and below all content.
     */
    View homeGradient() {
        GradientDrawable vertical =
                new GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[] {0x3a3b5a80, 0x0d1a2233, 0x00101013, 0x8c0a0a0d, 0xe60a0a0d});
        GradientDrawable sides =
                new GradientDrawable(
                        GradientDrawable.Orientation.LEFT_RIGHT,
                        new int[] {0x47000000, 0x00000000, 0x00000000, 0x38000000});
        View scrim = new View(this);
        scrim.setTag("home-gradient");
        scrim.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        scrim.setBackground(
                new android.graphics.drawable.LayerDrawable(
                        new android.graphics.drawable.Drawable[] {vertical, sides}));
        return scrim;
    }

    boolean available(Cms.Site s) {
        return SourceController.available(this, s);
    }

    int engineEpoch;

    SourceEngine engine(Cms.Site s) throws Exception {
        return SourceController.engine(this, s);
    }

    synchronized void ensureProxy(Cms.Config snapshot) throws IOException {
        SourceController.ensureProxy(this, snapshot);
    }

    void closeEngines() {
        SourceController.closeEngines(this);
    }

    void open(Screen s, boolean history) {
        capture();
        if (history && current != null) back.push(current);
        current = s;
        s.render.run();
        restore(s);
    }

    void capture() {
        if (current != null) {
            View f = getCurrentFocus();
            if (f != null && f.getTag() instanceof String) current.focus = (String) f.getTag();
            if (scroll != null) current.scroll = scroll.getScrollY();
        }
    }

    /** Last focus placed by the app rather than the user; late content may replace only this. */
    View autoFocus;

    void restore(Screen s) {
        ui.post(
                () -> {
                    if (current != s) return;
                    if (scroll != null) scroll.scrollTo(0, s.scroll);
                    if (!isTvDevice()) return;
                    View target = s.focus == null ? null : root.findViewWithTag(s.focus);
                    if (target != null) {
                        target.requestFocus();
                        autoFocus = target;
                    } else {
                        View first = root.findViewWithTag("primary");
                        if (first == null) first = root.findViewWithTag("detail-play");
                        if (first == null && s.home) {
                            first = root.findViewWithTag("home-tab:0");
                            if (first == null) first = root.findViewWithTag("source-switcher");
                        }
                        if (first == null) first = root.findViewWithTag("nav-home");
                        if (first == null) {
                            ArrayList<View> available = new ArrayList<>();
                            body.addFocusables(available, View.FOCUS_FORWARD);
                            if (!available.isEmpty()) first = available.get(0);
                        }
                        if (first != null) first.requestFocus();
                        autoFocus = first;
                    }
                });
    }

    void refresh() {
        capture();
        Screen s = current;
        s.render.run();
        restore(s);
    }

    void renderReturn(Screen screen) {
        if (!isTvDevice() && screen.mobileHome != null && screen.mobileHome.resume()) return;
        if (isTvDevice() && screen.retainedHome != null && screen.retainedHome.resume()) return;
        screen.render.run();
    }

    @Override
    public void onBackPressed() {
        if (playbackLoading != null) {
            changingMiniEpisode = false;
            clearPlaybackLoading();
            cancelPageRequests();
            stopWeb();
            if (current != null && current.player && !prepared) {
                if (playerChrome != null) playerChrome.setLoading(false);
                if (playStatus != null) playStatus.setText("已取消加载");
                showControls(true);
            }
            return;
        }
        if (current != null && current.player) {
            if (!miniMode && playbackControls != null) {
                if (controlsVisible) showControls(false);
                else if (isLive() || !isTvDevice()) leavePlayback();
                else minimizePlayback();
                return;
            }
            if (miniMode) {
                releasePlayer();
                if (!back.isEmpty()) back.pop();
                if (!back.isEmpty()) {
                    current = back.pop();
                    renderReturn(current);
                    restore(current);
                } else {
                    current = new Screen(this::home);
                    renderReturn(current);
                }
                return;
            }
        }
        if (current != null && current.home) {
            if (!isTvDevice() && MobileShell.activeTab == 4 && MobileShell.settingsSubPage != 0) {
                MobileShell.settingsSubPage = 0;
                MobileShell.show(this);
                return;
            }
            if (!isTvDevice() && !back.isEmpty()) {
                capture();
                current = back.pop();
                renderReturn(current);
                restore(current);
                return;
            }
            if (!isTvDevice() && MobileShell.activeTab != 0) {
                MobileShell.activeTab = 0;
                MobileShell.show(this);
                return;
            }
            releasePlayer();
            finish();
            return;
        }
        if (!back.isEmpty()) {
            capture();
            current = back.pop();
            renderReturn(current);
            restore(current);
        } else {
            releasePlayer();
            finish();
        }
    }

    void minimizePlayback() {
        PlaybackController.minimizePlayback(this);
    }

    void expandPlayback() {
        PlaybackController.expandPlayback(this);
    }

    void toast(String message) {
        final FrameLayout parent = root;
        if (parent == null) return;
        View previous = parent.findViewWithTag("app-toast");
        if (previous != null && previous.getParent() == parent) parent.removeView(previous);
        TextView tip = text(message, 14, WHITE);
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(dp(22), dp(12), dp(22), dp(12));
        glass(tip, 22);
        tip.setTag("app-toast");
        FrameLayout.LayoutParams size =
                new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        size.bottomMargin = dp(24);
        View dock = parent.findViewWithTag("home-dock");
        if (dock != null
                && dock.getVisibility() == View.VISIBLE
                && dock.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams rail = (FrameLayout.LayoutParams) dock.getLayoutParams();
            if ((rail.gravity & Gravity.VERTICAL_GRAVITY_MASK) == Gravity.BOTTOM) {
                size.bottomMargin =
                        Math.max(
                                size.bottomMargin,
                                Math.max(dock.getHeight(), rail.height)
                                        + rail.bottomMargin
                                        + dp(10));
            } else {
                size.rightMargin =
                        Math.max(dock.getWidth(), rail.width) + rail.rightMargin + dp(10);
            }
            tip.setElevation(dock.getElevation() + dp(1));
        }
        android.graphics.Rect visible = new android.graphics.Rect();
        parent.getWindowVisibleDisplayFrame(visible);
        int[] xy = new int[2];
        parent.getLocationOnScreen(xy);
        size.bottomMargin =
                Math.max(size.bottomMargin, xy[1] + parent.getHeight() - visible.bottom + dp(10));
        tip.setMaxWidth(Math.max(dp(80), parent.getWidth() - size.rightMargin - dp(32)));
        parent.addView(tip, size);
        ui.postDelayed(() -> parent.removeView(tip), 2600);
    }

    void cancelPageRequests() {
        for (Future<?> request : pageRequests) request.cancel(true);
        pageRequests.clear();
        ((ThreadPoolExecutor) worker).purge();
        ((ThreadPoolExecutor) details).purge();
    }

    Future<?> submitPage(Runnable task) {
        Future<?> request = RequestScope.submit(worker, task);
        for (int i = pageRequests.size() - 1; i >= 0; i--) {
            if (pageRequests.get(i).isDone()) pageRequests.remove(i);
        }
        pageRequests.add(request);
        return request;
    }

    void loadDetail(Cms.Site site, String id, Done<Cms.Video> done) {
        SourceController.loadDetail(this, site, id, done);
    }

    <T> void load(String label, Work<T> task, Done<T> done) {
        SourceController.load(this, label, task, done);
    }

    String message(Throwable e) {
        return SourceController.message(e);
    }

    void notice(String s) {
        new AppDialog.Builder(this).setMessage(s).setPositiveButton("知道了", null).show();
    }

    void learnTitles(List<Cms.Video> videos) {
        SourceController.learnTitles(this, videos);
    }

    void sourceAction(Cms.Site site, String action) {
        SourceController.sourceAction(this, site, action);
    }

    void pickDanmaku() {
        SourceController.pickDocument(this, 43, "这台电视没有文件选择器。");
    }

    void pickSubtitle() {
        SourceController.pickDocument(this, 42, "这台电视没有文件选择器，可安装文件管理器后再导入字幕。");
    }

    void home() {
        if (current != null) current.home = true;
        if (!isTvDevice()) {
            MobileShell.show(this);
            return;
        }
        Cms.Site selected = null;
        if (config != null)
            for (Cms.Site s : config.sites)
                if (s.key.equals(prefs.getString(Prefs.SELECTED_SITE, "")) && available(s))
                    selected = s;
        if (selected != null) {
            contentHome(selected);
            return;
        }
        renderPage("把今晚，留给好故事。", "YOUR OWN CINEMA", true);
        paragraph("属于你的电视播放器。导入自己合法获取的配置，用遥控器浏览和播放。");
        LinearLayout hero = row();
        addButton(
                hero,
                button(config == null ? "＋ 导入我的配置" : "＋ 更新配置", "primary", () -> importDialog()));
        if (config != null) addButton(hero, button("浏览片库", "primary", () -> chooseSources()));
        addButton(
                hero,
                navButton("观看历史", "history-open", () -> open(new Screen(this::historyPage), true)));
        body.addView(hero);
        gap(body, 30);
        if (config == null) {
            paragraph("本产品仅为播放工具，不内置、不提供、不托管任何影视资源或配置地址。请自行合法获取内容与配置，并确保拥有相应使用权。");
            return;
        }
        TextView t = text("我的来源", 22, WHITE);
        body.addView(t);
        gap(body, 10);
        int supported = 0;
        for (Cms.Site s : config.sites) if (available(s)) supported++;
        paragraph("已保存 " + config.sites.size() + " 个来源。选择一个，开始浏览。还未支持的来源会保留在列表中。");
    }

    void contentHome(Cms.Site site) {
        if (!isTvDevice()) {
            MobileShell.show(this);
            return;
        }
        new HomeScreen(this, site).show();
    }

    void chooseSources() {
        SourcePicker.show(this);
    }

    void importDialog() {
        if (!isTvDevice()) {
            MobileShell.showMobileImportDialog(this);
            return;
        }
        if (phoneConfig != null) phoneConfig.close();
        phoneConfig = new PhoneConfigDialog(this);
        phoneConfig.show();
    }

    void addressImportDialog() {
        SourceController.addressImportDialog(this);
    }

    void pickConfig() {
        SourceController.pickDocument(this, 41, "这台电视没有系统文件选择器。请安装文件管理器，或使用配置地址导入。");
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        SourceController.onActivityResult(this, request, result, data);
    }

    void saveConfig(Cms.Config c, String origin) throws Exception {
        SourceController.saveConfig(this, c, origin);
    }

    void categories(Cms.Site s) {
        CatalogScreen.categories(this, s);
    }

    void searchDialog(Cms.Site s) {
        globalSearchDialog();
    }

    void videos(Cms.Site s, String cat, String title, int page, String search) {
        CatalogScreen.videos(this, s, cat, title, page, search);
    }

    void cancelArtwork() {
        ImageLoader.cancel(this);
    }

    void poster(String address, ImageView image) {
        poster(address, image, false);
    }

    void poster(String address, ImageView image, boolean detail) {
        ImageLoader.poster(this, address, image, detail);
    }

    void detail(Cms.Site s, Cms.Video listing) {
        detail(s, listing, null);
    }

    void detail(Cms.Site s, Cms.Video listing, Cms.Video ready) {
        if (!isTvDevice()) {
            MobileDetailScreen.show(this, s, listing, ready);
            return;
        }
        DetailScreen.show(this, s, listing, ready);
    }

    void renderEpisodes(
            Cms.Site site, Cms.Video video, int lineIndex, int page, LinearLayout panel) {
        DetailScreen.renderEpisodes(this, site, video, lineIndex, page, panel, null);
    }

    void liveSources() {
        if (!isTvDevice()) {
            MobileShell.activeTab = 2;
            open(new Screen(this::home), false);
            return;
        }
        LiveController.liveSources(this);
    }

    void playLive(
            LiveCatalog.Source source, LiveCatalog.Channel channel, LiveCatalog.Stream stream) {
        LiveController.playLive(this, source, channel, stream);
    }

    boolean isLive() {
        return LiveController.isLive(this);
    }

    boolean liveStep(int direction) {
        return LiveController.liveStep(this, direction);
    }

    void chooseLiveChannel() {
        LiveController.chooseLiveChannel(this);
    }

    void chooseLiveStream() {
        LiveController.chooseLiveStream(this);
    }

    void clearLiveNumber() {
        LiveController.clearLiveNumber(this);
    }

    void commitLiveNumber() {
        LiveController.commitLiveNumber(this);
    }

    boolean liveKey(KeyEvent event) {
        return LiveController.liveKey(this, event);
    }

    void globalSearchDialog() {
        openSearch("");
    }

    void openSearch(String initial) {
        if (config == null) {
            importDialog();
            return;
        }
        if (!isTvDevice()) {
            MobileShell.openSearchTab(this, initial);
            return;
        }
        GlobalSearchScreen screen = new GlobalSearchScreen(this, initial);
        open(
                new Screen(
                        () -> {
                            screen.show();
                            searchScreen = screen;
                        }),
                true);
    }

    void crossSearch(String keyword) {
        openSearch(keyword);
    }

    void stopMagnet() {
        PlaybackController.stopMagnet(this);
    }

    void stopWeb() {
        PlaybackController.stopWeb(this);
    }

    void resolvePlayer(Cms.Site s, Cms.Video v, Cms.Line line, Cms.Episode ep) {
        PlaybackController.resolvePlayer(this, s, v, line, ep);
    }

    void clearPlaybackLoading() {
        PlaybackController.clearPlaybackLoading(this);
    }

    void openPlayer(Cms.Site s, Cms.Video v, Cms.Episode e) {
        PlaybackController.openPlayer(this, s, v, e);
    }

    void playerPage(Cms.Site s, Cms.Video v, Cms.Episode ep) {
        PlaybackController.playerPage(this, s, v, ep);
    }

    void changeEpisode(
            Cms.Site site, Cms.Video video, Cms.Line line, Cms.Episode episode, int position) {
        PlaybackController.changeEpisode(this, site, video, line, episode, position);
    }

    void leavePlayback() {
        PlaybackController.leavePlayback(this);
    }

    void showControls(boolean visible) {
        PlaybackController.showControls(this, visible);
    }

    void scheduleHide() {
        PlaybackController.scheduleHide(this);
    }

    String time(int ms) {
        return PlaybackController.formatTime(ms);
    }

    void togglePlayback() {
        PlaybackController.togglePlayback(this);
    }

    void beginSeek(int direction) {
        PlaybackController.beginSeek(this, direction);
    }

    void previewSeek() {
        PlaybackController.previewSeek(this);
    }

    void finishSeek(boolean commit) {
        PlaybackController.finishSeek(this, commit);
    }

    java.util.List<WatchHistory.Entry> watchHistory() {
        return privateMode
                ? java.util.Collections.emptyList()
                : WatchHistory.read(prefs.getString(Prefs.WATCH_HISTORY, "[]"));
    }

    void savePlaybackHistory(boolean force) {
        PlaybackController.savePlaybackHistory(this, force);
    }

    String historyLabel(WatchHistory.Entry e) {
        return CatalogScreen.historyLabel(e);
    }

    void resumeHistory(WatchHistory.Entry e) {
        CatalogScreen.resumeHistory(this, e);
    }

    void playBookmark(Cms.Site site, Cms.Video video, WatchHistory.Entry bookmark) {
        CatalogScreen.playBookmark(this, site, video, bookmark);
    }

    void historyPage() {
        CatalogScreen.historyPage(this);
    }

    void releasePlayer() {
        PlaybackController.releasePlayer(this);
    }

    void smoothHomeScroll(int y) {
        if (scroll instanceof TvScrollView) ((TvScrollView) scroll).moveTo(y);
        else if (reduced) scroll.scrollTo(0, y);
        else scroll.smoothScrollTo(0, y);
    }

    void jumpHomeTop() {
        if (current == null || !current.home || scroll == null) return;
        View target = root.findViewWithTag("home-history:0");
        if (target == null) target = root.findViewWithTag("home-tab:0");
        if (target == null) target = root.findViewWithTag("source-switcher");
        if (target == null) target = root.findViewWithTag("primary");
        if (target != null) target.requestFocus();
        current.scroll = 0;
        smoothHomeScroll(0);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (introSkipKey && event.getAction() == KeyEvent.ACTION_UP) {
            introSkipKey = false;
            return true;
        }
        if (startupIntro != null) {
            int key = event.getKeyCode();
            if (event.getAction() == KeyEvent.ACTION_DOWN
                    && (key == KeyEvent.KEYCODE_DPAD_CENTER
                            || key == KeyEvent.KEYCODE_ENTER
                            || key == KeyEvent.KEYCODE_BACK)) {
                introSkipKey = true;
                startupIntro.finish(true);
            }
            return true;
        }

        if (shortcutScreen != current) {
            homeKeys.reset();
            shortcutScreen = current;
        }
        if (current != null && current.home) {
            int key = event.getKeyCode();
            if (key == KeyEvent.KEYCODE_MENU) {
                homeKeys.reset();
                if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) chooseSources();
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (key == KeyEvent.KEYCODE_DPAD_UP) {
                    if (homeKeys.up(event.getEventTime(), event.getRepeatCount())) {
                        jumpHomeTop();
                        return true;
                    }
                } else homeKeys.reset();
            }
        }
        if (liveKey(event)) return true;
        View feedbackFocus = getCurrentFocus();
        if (feedbackFocus != null) FocusMotion.key(this, feedbackFocus, event.getKeyCode(), event);
        if (current != null && current.home && homeDock != null && homeDock.key(event)) return true;
        if (current != null && current.home && HomeScreen.navigationKey(this, event)) return true;
        if (PlaybackController.dispatchPlaybackKey(this, event)) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (!focus) finishSeek(false);
        SourceController.onActivityFocusChanged(this, focus);
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        ImageLoader.trimMemory(this, level);
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        images.evictAll();
    }

    @Override
    protected void onStart() {
        super.onStart();
        activeActivity = this;
        foreground = true;
        CspEngine.syncAllPluginActivities(this);
        UpdateManager.get(this)
                .listen(
                        () -> {
                            if (updateScreen != null) updateScreen.refresh();
                            if (foreground
                                    && current != null
                                    && current.home
                                    && appDialog == null
                                    && UpdateManager.get(this).consumeNotice())
                                toast("发现新版，可在设置 → 软件更新查看");
                        });
        ui.removeCallbacks(automaticUpdate);
        ui.postDelayed(automaticUpdate, 4000);
        // 回到前台后重新锚定音频时钟（后台期间没有 PCM 输入）
        if (player != null && prepared)
            com.pilipala.marcomovie.adskip.AdSkipController.get()
                    .onPlaybackStart(player.getCurrentPosition());
    }

    @Override
    protected void onResume() {
        super.onResume();
        activeActivity = this;
        foreground = true;
        CspEngine.syncAllPluginActivities(this);
    }

    @Override
    protected void onPause() {
        savePlaybackHistory(true);
        super.onPause();
    }

    @Override
    protected void onStop() {
        PrivacySpace.cancelPrompt(this);
        finishSeek(false);
        if (playbackLoading != null) {
            clearPlaybackLoading();
            cancelPageRequests();
            stopWeb();
        }
        UpdateManager.get(this).listen(null);
        ui.removeCallbacks(automaticUpdate);
        if (phoneConfig != null) {
            PhoneConfigDialog session = phoneConfig;
            session.close();
            if (session.dialog != null) session.dialog.dismiss();
        }
        clearLiveNumber();
        if (startupIntro != null) startupIntro.cancel();
        introSkipKey = false;
        savePlaybackHistory(true);
        com.pilipala.marcomovie.adskip.AdSkipController.get().onPaused();
        foreground = false;
        super.onStop();
        if (player != null)
            try {
                player.pause();
                playing = false;
                if (embyReporter != null) embyReporter.update(player.getCurrentPosition(), true);
                if (playStatus != null) playStatus.setText("已暂停 · 按确认继续");
            } catch (Exception ignored) {
            }
    }

    @Override
    protected void onDestroy() {
        if (privateMode && isFinishing() && !switchingPrivacy) PrivacySpace.active = false;
        if (activeActivity == this) activeActivity = null;
        clearPlaybackLoading();
        if (phoneConfig != null) phoneConfig.close();
        if (startupIntro != null) startupIntro.cancel();
        if (searchScreen != null) searchScreen.close();
        stopWeb();
        stopMagnet();
        generation++;
        releasePlayer();
        com.pilipala.marcomovie.adskip.AdSkipController.get().destroy();
        closeEngines();
        cancelArtwork();
        cancelPageRequests();
        details.shutdownNow();
        worker.shutdownNow();
        artwork.shutdownNow();
        phoneticWork.shutdownNow();
        ui.removeCallbacksAndMessages(null);
        images.evictAll();
        super.onDestroy();
    }

    void settings() {
        new SettingsScreen(this).show();
    }
}
