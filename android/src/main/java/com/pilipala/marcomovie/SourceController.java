package com.pilipala.marcomovie;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.Future;

/**
 * Manages source engine lifecycles, detail loading, phonetic title indexing, and configuration /
 * subtitle / danmaku document imports.
 */
final class SourceController {
    private SourceController() {}

    static String importOrigin(String address) {
        return com.pilipala.emby.CheckedScript.matches(address) ? "" : address;
    }

    static String fetchImport(android.content.Context context, String address) throws Exception {
        if (!com.pilipala.emby.CheckedScript.matches(address)) return Cms.fetch(address, null);
        com.pilipala.emby.CheckedScript script = com.pilipala.emby.CheckedScript.read(address);
        if (com.pilipala.catpaw.CatPawHost.isBundle(script.code))
            return new com.pilipala.catpaw.CatPawHost(context).install(script.code);
        if (!Cms.looksLikeJsSpider(script.code, "")) throw new IOException("已校验的文件没有兼容的来源入口");
        org.json.JSONObject site =
                new org.json.JSONObject()
                        .put("key", "script_" + script.base.hashCode())
                        .put("name", new java.net.URL(script.base).getHost())
                        .put("type", 3)
                        .put("api", script.base)
                        .put("ext", new org.json.JSONObject().put("_mm_entrySource", script.code));
        return new org.json.JSONObject()
                .put("sites", new org.json.JSONArray().put(site))
                .toString();
    }

    static boolean available(MainActivity a, Cms.Site s) {
        return s.api.startsWith(com.pilipala.emby.EmbyLibrary.PREFIX)
                || s.api.startsWith("catpaw://")
                || HttpT4Engine.eligible(s)
                || s.supported()
                || (a.prefs.getBoolean(Prefs.EXPERIMENTAL_CSP, true)
                        && ((CspEngine.eligible(s)
                                        && ((s.jar != null && !s.jar.isEmpty())
                                                || (a.config != null
                                                        && !a.config.spider.isEmpty())))
                                || ((JsEngine.eligible(s) || DrpyEngine.eligible(s))
                                        && a.config != null)));
    }

    static SourceEngine engine(MainActivity a, Cms.Site s) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        final Cms.Config snapshot;
        final int epoch;
        synchronized (a) {
            SourceEngine existing = a.engines.get(s.key);
            if (existing instanceof QueuedSourceEngine
                    && ((QueuedSourceEngine) existing).isWedged()) {
                a.engines.remove(s.key);
                existing = null;
            }
            if (existing != null) return existing;
            snapshot = a.config;
            epoch = a.engineEpoch;
        }
        return a.engineInitializations.get(
                epoch + ":" + s.key,
                () -> {
                    synchronized (a) {
                        if (epoch != a.engineEpoch) throw new IOException("配置已更改，请重新选择来源");
                        SourceEngine existing = a.engines.get(s.key);
                        if (existing != null) return existing;
                    }
                    SourceEngine raw;
                    if (s.api.startsWith(com.pilipala.emby.EmbyLibrary.PREFIX))
                        raw = new EmbyEngine(a, s);
                    else if (s.api.startsWith("catpaw://")) raw = new CatPawEngine(a, s);
                    else if (HttpT4Engine.eligible(s)) raw = new HttpT4Engine(s);
                    else if (s.supported()) raw = new JsonCmsEngine(s);
                    else if (a.prefs.getBoolean(Prefs.EXPERIMENTAL_CSP, true)
                            && DrpyEngine.eligible(s)
                            && snapshot != null) raw = new DrpyEngine(a, snapshot, s);
                    else if (a.prefs.getBoolean(Prefs.EXPERIMENTAL_CSP, true)
                            && GuardEngine.eligible(s)
                            && snapshot != null) {
                        raw =
                                GuardEngine.open(
                                        a,
                                        snapshot,
                                        s,
                                        () -> {
                                            ensureProxy(a, snapshot);
                                            return new CspEngine(a.getApplication(), snapshot, s);
                                        });
                    } else if (a.prefs.getBoolean(Prefs.EXPERIMENTAL_CSP, true)
                            && !JsEngine.eligible(s)
                            && CspEngine.eligible(s)
                            && snapshot != null) {
                        ensureProxy(a, snapshot);
                        raw = new CspEngine(a.getApplication(), snapshot, s);
                    } else if (a.prefs.getBoolean(Prefs.EXPERIMENTAL_CSP, true)
                            && JsEngine.eligible(s)
                            && snapshot != null) {
                        ensureProxy(a, snapshot);
                        try {
                            raw = new JsEngine(a.getApplication(), snapshot, s);
                        } catch (JsEngine.DrpyRuleDetected rule) {
                            raw = new DrpyEngine(a, snapshot, s, rule.source);
                        }
                    } else throw new IOException("来源需要尚未启用的扩展引擎");
                    SourceEngine created = new QueuedSourceEngine(raw);
                    synchronized (a) {
                        if (epoch == a.engineEpoch) {
                            if (raw instanceof JsEngine) ((JsEngine) raw).activate();
                            a.engines.put(s.key, created);
                            return created;
                        }
                    }
                    created.close();
                    throw new IOException("配置已更改，请重新选择来源");
                });
    }

    static void ensureProxy(MainActivity a, Cms.Config snapshot) throws IOException {
        synchronized (a) {
            if (a.localProxy == null) a.localProxy = new LocalProxy(a.getApplication(), snapshot);
        }
    }

    static void closeEngines(MainActivity a) {
        a.liveSession = null;
        a.detailCache.clear();
        a.homeData.clear();
        if (a.localProxy != null) {
            a.localProxy.close();
            a.localProxy = null;
        }
        final List<SourceEngine> old;
        synchronized (a) {
            a.engineEpoch++;
            old = new ArrayList<>(a.engines.values());
            a.engines.clear();
        }
        if (!old.isEmpty())
            new Thread(
                            () -> {
                                for (SourceEngine e : old) e.close();
                            },
                            "close-source-engines")
                    .start();
    }

    static volatile long lastConfigActionAt;
    static volatile boolean pendingConfigRefreshOnFocus;

    static boolean isDedicatedConfigSite(Cms.Site site) {
        if (site == null) return false;
        String api = site.api == null ? "" : site.api.toLowerCase(java.util.Locale.ROOT);
        String key = site.key == null ? "" : site.key.toLowerCase(java.util.Locale.ROOT);
        String name = site.name == null ? "" : site.name;
        return api.contains("mydrive")
                || api.contains("config")
                || key.contains("mydrive")
                || key.contains("config")
                || name.contains("配置中心")
                || name.contains("配置┃中心")
                || (name.contains("云盘") && name.contains("配置"))
                || (name.contains("网盘") && name.contains("配置"));
    }

    static boolean isConfigOrNetdiskSite(Cms.Site site) {
        if (site == null) return false;
        if (isDedicatedConfigSite(site)) return true;
        String api = site.api == null ? "" : site.api.toLowerCase(java.util.Locale.ROOT);
        String key = site.key == null ? "" : site.key.toLowerCase(java.util.Locale.ROOT);
        String name = site.name == null ? "" : site.name;
        return api.contains("mypan")
                || key.contains("mypan")
                || name.contains("我的云盘")
                || name.contains("我的网盘")
                || name.contains("我的┃网盘");
    }

    static boolean isConfigOrNetdiskActionCard(Cms.Site site, Cms.Video v) {
        if (!isConfigOrNetdiskSite(site) || v == null) return false;
        if ("folder".equalsIgnoreCase(v.tag)) return false;
        if (v.lines != null && !v.lines.isEmpty()) return false;
        String id = v.id == null ? "" : v.id.trim();
        if (id.isEmpty() || id.startsWith("msearch:")) return false;
        String api = site.api == null ? "" : site.api.toLowerCase(java.util.Locale.ROOT);
        String key = site.key == null ? "" : site.key.toLowerCase(java.util.Locale.ROOT);
        if ((api.contains("mydrive") || key.contains("mydrive")) && id.startsWith("{")) {
            return false;
        }
        if (isDedicatedConfigSite(site)
                && !id.contains("/")
                && !id.startsWith("http://")
                && !id.startsWith("https://")) {
            return true;
        }
        String title = v.title == null ? "" : v.title;
        if (title.contains("登入")
                || title.contains("登录")
                || title.contains("清除")
                || title.contains("配置")
                || title.contains("顺序")
                || title.contains("设置")
                || title.contains("扫码")
                || title.contains("Cookie")
                || title.contains("token")
                || title.contains("Token")
                || title.contains("刷新")
                || title.contains("注册")
                || title.contains("输入")
                || title.contains("代理")
                || title.contains("加速")
                || title.contains("适配")
                || title.contains("DNS")
                || title.contains("弹幕")
                || title.contains("日志")
                || title.contains("备份")
                || title.contains("还原")
                || title.contains("突破")) {
            return true;
        }
        String lowerId = id.toLowerCase(java.util.Locale.ROOT);
        return id.startsWith("{")
                || lowerId.startsWith("action")
                || lowerId.contains("cookie")
                || lowerId.contains("token")
                || lowerId.contains("login")
                || lowerId.contains("clear")
                || lowerId.contains("manual")
                || lowerId.contains("register");
    }

    static void prefetchDetail(MainActivity a, Cms.Site site, Cms.Video video) {
        if (a == null
                || a.reduced
                || site == null
                || video == null
                || video.id == null
                || video.id.isEmpty()) return;
        if (isConfigOrNetdiskSite(site) || isConfigOrNetdiskActionCard(site, video)) return;
        if (ListingRoute.isRedirect(video.tag, video.action, video.id)) return;
        if (video.lines != null
                && !video.lines.isEmpty()
                && video.description != null
                && !video.description.trim().isEmpty()) return;
        final int epoch = a.engineEpoch;
        final String id = video.id;
        final String key = epoch + ":" + site.key.length() + ":" + site.key + id;
        if (a.detailCache.get(key) != null) return;
        RequestScope.submit(
                a.details,
                () -> {
                    try {
                        if (a.detailCache.get(key) != null) return;
                        String raw =
                                a.detailFlights.get(
                                        key,
                                        () -> {
                                            String cached = a.detailCache.get(key);
                                            if (cached != null) return cached;
                                            SourceEngine source = a.engine(site);
                                            long[] phases = new long[2];
                                            String fetched =
                                                    SourceGate.measured(
                                                            phases, () -> source.detail(id));
                                            if (fetched != null && epoch == a.engineEpoch) {
                                                a.detailCache.put(key, fetched);
                                            }
                                            return fetched;
                                        });
                    } catch (Throwable ignored) {
                    }
                });
    }

    static void loadDetail(
            MainActivity a, Cms.Site site, String id, MainActivity.Done<Cms.Video> done) {
        final int ticket = a.generation, epoch = a.engineEpoch;
        final String key = epoch + ":" + site.key.length() + ":" + site.key + id;
        final DetailRequest timing = new DetailRequest();
        Future<?> request =
                RequestScope.submit(
                        a.details,
                        () -> {
                            try {
                                timing.dispatched = SystemClock.elapsedRealtime();
                                String raw = a.detailCache.get(key);
                                timing.cached = raw != null;
                                if (raw == null) {
                                    final long[] phases = new long[2];
                                    raw =
                                            a.detailFlights.get(
                                                    key,
                                                    () -> {
                                                        String cached = a.detailCache.get(key);
                                                        if (cached != null) return cached;
                                                        SourceEngine source = a.engine(site);
                                                        timing.engineReady =
                                                                SystemClock.elapsedRealtime();
                                                        String fetched =
                                                                SourceGate.measured(
                                                                        phases,
                                                                        () -> source.detail(id));
                                                        if (fetched != null
                                                                && epoch == a.engineEpoch) {
                                                            a.detailCache.put(key, fetched);
                                                        }
                                                        return fetched;
                                                    });
                                    if (timing.engineReady == 0)
                                        timing.engineReady = SystemClock.elapsedRealtime();
                                    timing.gateWaitNanos = phases[0];
                                    timing.delegateNanos = phases[1];
                                    timing.returned = SystemClock.elapsedRealtime();
                                } else
                                    timing.engineReady =
                                            timing.returned = SystemClock.elapsedRealtime();
                                if (Thread.currentThread().isInterrupted()) return;
                                Cms.Listing listing = Cms.listing(raw);
                                if (listing.videos.isEmpty()) throw new IOException("来源没有返回影片详情");
                                timing.parsed = SystemClock.elapsedRealtime();
                                if (!timing.cached && epoch == a.engineEpoch)
                                    a.detailCache.put(key, raw);
                                Cms.Video video = listing.videos.get(0);
                                if ((video.id == null || video.id.trim().isEmpty()) && id != null) {
                                    video.id = id.trim();
                                }
                                a.ui.post(
                                        () -> {
                                            if (a.isFinishing() || ticket != a.generation) return;
                                            try {
                                                done.accept(video);
                                                a.root.postOnAnimation(timing::ready);
                                            } catch (Throwable error) {
                                                a.paragraph("详情数据无法显示，请返回后重试。");
                                            }
                                        });
                            } catch (Throwable error) {
                                if (Thread.currentThread().isInterrupted()) return;
                                a.ui.post(
                                        () -> {
                                            if (a.isFinishing() || ticket != a.generation) return;
                                            View state = a.root.findViewWithTag("detail-loading");
                                            if (state instanceof TextView)
                                                ((TextView) state).setText(a.message(error));
                                            LinearLayout target =
                                                    state != null
                                                                    && state.getParent()
                                                                            instanceof LinearLayout
                                                            ? (LinearLayout) state.getParent()
                                                            : a.body;
                                            a.addButton(
                                                    target, a.button("重试", "primary", a::refresh));
                                        });
                            }
                        });
        for (int i = a.pageRequests.size() - 1; i >= 0; i--) {
            if (a.pageRequests.get(i).isDone()) a.pageRequests.remove(i);
        }
        a.pageRequests.add(request);
    }

    static <T> void load(
            MainActivity a, String label, MainActivity.Work<T> task, MainActivity.Done<T> done) {
        final int ticket = a.generation;
        TextView loading = a.text(label, 17, MainActivity.MUTED);
        a.body.addView(loading);
        a.submitPage(
                () -> {
                    try {
                        T value = task.run();
                        a.ui.post(
                                () -> {
                                    if (a.isFinishing() || ticket != a.generation) return;
                                    a.body.removeView(loading);
                                    try {
                                        done.accept(value);
                                    } catch (Throwable e) {
                                        a.notice("数据格式不符合此版本支持的格式。");
                                    }
                                });
                    } catch (Throwable e) {
                        a.ui.post(
                                () -> {
                                    if (a.isFinishing() || ticket != a.generation) return;
                                    a.body.removeView(loading);
                                    a.paragraph(a.message(e));
                                    a.addButton(a.body, a.button("重试", "primary", a::refresh));
                                });
                    }
                });
    }

    static String message(Throwable e) {
        if (e == null) return "连接超时、来源不可达或数据格式不受支持。请检查配置与网络后重试。";
        String s = e.getMessage();
        if (e instanceof CspEngine.EngineException) return s;
        if (e instanceof OutOfMemoryError) return "设备运行内存不足，已保护性中止加载。建议切换轻量片源。";
        if (e instanceof LinkageError) return "此片源扩展与当前电视系统版本不兼容，请切换其他片源。";
        if (e instanceof java.net.UnknownHostException) return "无法解析来源域名，请检查网络或切换其他来源。";
        if (e instanceof javax.net.ssl.SSLException) return "与来源建立安全连接失败，请检查设备时间或切换其他来源。";
        if (e instanceof java.net.SocketTimeoutException) return "来源响应超时，请重试或切换其他来源。";
        if (e instanceof java.net.ConnectException) return "无法连接来源服务，请重试或切换其他来源。";
        if (s != null
                && (s.startsWith("直播列表")
                        || s.startsWith("配置")
                        || s.startsWith("来源返回 HTTP")
                        || s.startsWith("来源没有")
                        || s.startsWith("来源重定向")
                        || s.startsWith("单页超过")
                        || s.startsWith("响应超过")
                        || s.startsWith("剧集返回")
                        || s.startsWith("媒体地址返回")
                        || s.startsWith("此剧集"))) return s;
        return "连接超时、来源不可达或数据格式不受支持。请检查配置与网络后重试。";
    }

    static void learnTitles(MainActivity a, List<Cms.Video> videos) {
        if (a.privateMode) return;
        if (a.phonetic == null || videos == null || videos.isEmpty()) return;
        for (Cms.Video video : videos)
            if (video.title != null && !video.title.isEmpty()) a.pendingTitles.add(video.title);
        while (a.pendingTitles.size() > 400)
            a.pendingTitles.remove(a.pendingTitles.iterator().next());
        a.ui.removeCallbacks(a.learnPendingTitles);
        a.ui.postDelayed(a.learnPendingTitles, 500);
    }

    static void learnPendingTitles(MainActivity a) {
        List<String> titles = new ArrayList<>(a.pendingTitles);
        a.pendingTitles.clear();
        final PhoneticSearchIndex index = a.phonetic;
        final android.content.SharedPreferences target = a.prefs;
        if (index == null || titles.isEmpty()) return;
        a.phoneticWork.execute(
                () -> {
                    LinkedHashSet<String> values = new LinkedHashSet<>();
                    try {
                        org.json.JSONArray previous =
                                new org.json.JSONArray(
                                        target.getString(Prefs.PHONETIC_TITLES, "[]"));
                        for (int i = 0; i < previous.length(); i++)
                            values.add(previous.optString(i));
                    } catch (Exception ignored) {
                    }
                    for (String title : titles) {
                        index.learn(title);
                        values.add(title);
                    }
                    while (values.size() > 400) values.remove(values.iterator().next());
                    target.edit()
                            .putString(
                                    Prefs.PHONETIC_TITLES,
                                    new org.json.JSONArray(values).toString())
                            .apply();
                });
    }

    static void onActivityFocusChanged(MainActivity a, boolean focus) {
        if (a == null) return;
        if (!focus) {
            if (SystemClock.elapsedRealtime() - lastConfigActionAt < 15000) {
                pendingConfigRefreshOnFocus = true;
            }
            return;
        }
        if (pendingConfigRefreshOnFocus) {
            pendingConfigRefreshOnFocus = false;
            a.ui.postDelayed(() -> refreshCurrentConfigCategory(a, null), 250);
        }
    }

    static void refreshCurrentConfigCategory(MainActivity a, Cms.Site targetSite) {
        if (a == null
                || a.isFinishing()
                || a.current == null
                || !a.current.home
                || a.config == null) return;
        String selKey = a.prefs.getString(Prefs.SELECTED_SITE, "");
        Cms.Site currentSite = null;
        for (Cms.Site s : a.config.sites) {
            if (s.key.equals(selKey)) {
                currentSite = s;
                break;
            }
        }
        if (currentSite == null || !isConfigOrNetdiskSite(currentSite)) return;
        if (targetSite != null
                && targetSite.key != null
                && !targetSite.key.equals(currentSite.key)) {
            return;
        }
        a.detailCache.clear();
        a.homeData.remove(currentSite.key);
        if (a.current.retainedHome != null) {
            a.current.retainedHome.reloadCurrentCategory();
        } else if (a.current.mobileHome != null && a.current.mobileHome.reloadCategory != null) {
            a.current.mobileHome.reloadCategory.run();
        }
    }

    static boolean isInternalActionCode(String s, String action) {
        if (s == null) return true;
        String trimmed = s.trim();
        if (trimmed.isEmpty()) return true;
        if (action != null && trimmed.equalsIgnoreCase(action.trim())) return true;
        return "LoginShow".equalsIgnoreCase(trimmed)
                || "pushCkShow".equalsIgnoreCase(trimmed)
                || "panSortShow".equalsIgnoreCase(trimmed)
                || "panSourceSortShow".equalsIgnoreCase(trimmed)
                || "null".equalsIgnoreCase(trimmed)
                || "{}".equals(trimmed)
                || "[]".equals(trimmed);
    }

    static void invokeConfigPlayerAction(MainActivity a, Cms.Site site, String flag, String id) {
        lastConfigActionAt = SystemClock.elapsedRealtime();
        pendingConfigRefreshOnFocus = false;
        CspEngine.syncAllPluginActivities(a);
        final int ticket = a.generation;
        a.worker.execute(
                () -> {
                    try {
                        CspEngine.syncAllPluginActivities(a);
                        SourceEngine eng = a.engine(site);
                        CspEngine.syncAllPluginActivities(a);
                        final String resp = eng.player(flag, id);
                        a.ui.post(
                                () -> {
                                    if (ticket != a.generation || a.isFinishing()) return;
                                    a.ui.postDelayed(
                                            () -> {
                                                if (a.isFinishing() || ticket != a.generation)
                                                    return;
                                                if (!a.hasWindowFocus()) {
                                                    pendingConfigRefreshOnFocus = true;
                                                } else {
                                                    refreshCurrentConfigCategory(a, site);
                                                }
                                            },
                                            550);
                                    if (resp == null || resp.trim().isEmpty()) return;
                                    try {
                                        org.json.JSONObject obj = new org.json.JSONObject(resp);
                                        String msg =
                                                obj.optString("msg", obj.optString("message", ""));
                                        if (!msg.isEmpty() && !isInternalActionCode(msg, id)) {
                                            a.notice(msg);
                                        }
                                    } catch (Throwable ignored) {
                                    }
                                });
                    } catch (Throwable error) {
                        a.ui.post(
                                () -> {
                                    if (ticket == a.generation) a.notice(a.message(error));
                                });
                    }
                });
    }

    static void sourceAction(MainActivity a, Cms.Site site, String action) {
        sourceAction(a, site, action, false);
    }

    static void sourceAction(MainActivity a, Cms.Site site, String action, boolean preferDetail) {
        a.detailCache.clear();
        lastConfigActionAt = SystemClock.elapsedRealtime();
        pendingConfigRefreshOnFocus = false;
        CspEngine.syncAllPluginActivities(a);
        final int ticket = a.generation;
        a.worker.execute(
                () -> {
                    try {
                        CspEngine.syncAllPluginActivities(a);
                        SourceEngine eng = a.engine(site);
                        CspEngine.syncAllPluginActivities(a);
                        String actResp;
                        boolean detailFallback = false;
                        if (eng.supportsCustomAction()) {
                            actResp = eng.action(action);
                        } else {
                            actResp = eng.detailAction(action);
                            detailFallback = true;
                        }
                        final String response = actResp;
                        final boolean usedDetailFallback = detailFallback;
                        a.ui.post(
                                () -> {
                                    if (ticket != a.generation || a.isFinishing()) return;
                                    a.ui.postDelayed(
                                            () -> {
                                                if (a.isFinishing() || ticket != a.generation)
                                                    return;
                                                if (!a.hasWindowFocus()) {
                                                    pendingConfigRefreshOnFocus = true;
                                                } else if (usedDetailFallback) {
                                                    refreshCurrentConfigCategory(a, site);
                                                }
                                            },
                                            550);
                                    if (response == null || response.trim().isEmpty()) return;
                                    try {
                                        org.json.JSONObject result =
                                                new org.json.JSONObject(response);
                                        if (result.has("list")) {
                                            Cms.Listing list = Cms.listing(response);
                                            if (usedDetailFallback) {
                                                if (list.videos.size() == 1) {
                                                    Cms.Video v = list.videos.get(0);
                                                    for (Cms.Line line : v.lines) {
                                                        if ("Config".equalsIgnoreCase(line.name)
                                                                && !line.episodes.isEmpty()) {
                                                            if (line.episodes.size() == 1) {
                                                                invokeConfigPlayerAction(
                                                                        a,
                                                                        site,
                                                                        line.name,
                                                                        line.episodes.get(0).url);
                                                            } else {
                                                                String[] epNames =
                                                                        new String
                                                                                [line.episodes
                                                                                        .size()];
                                                                for (int i = 0;
                                                                        i < line.episodes.size();
                                                                        i++) {
                                                                    epNames[i] =
                                                                            line.episodes.get(i)
                                                                                    .name;
                                                                }
                                                                new AppDialog.Builder(a)
                                                                        .setTitle(
                                                                                v.title != null
                                                                                                && !v
                                                                                                        .title
                                                                                                        .isEmpty()
                                                                                        ? v.title
                                                                                        : site.name)
                                                                        .setItems(
                                                                                epNames,
                                                                                (d, idx) ->
                                                                                        invokeConfigPlayerAction(
                                                                                                a,
                                                                                                site,
                                                                                                line.name,
                                                                                                line
                                                                                                        .episodes
                                                                                                        .get(
                                                                                                                idx)
                                                                                                        .url))
                                                                        .setNegativeButton(
                                                                                "关闭", null)
                                                                        .show();
                                                            }
                                                            return;
                                                        }
                                                    }
                                                }
                                                return;
                                            }
                                            if (list.videos.size() == 1
                                                    && (list.videos.get(0).title.contains("刷新配置中心")
                                                            || "配置中心"
                                                                    .equals(
                                                                            list.videos.get(0)
                                                                                    .remarks))) {
                                                return;
                                            }
                                            String[] titles = new String[list.videos.size()];
                                            for (int i = 0; i < list.videos.size(); i++)
                                                titles[i] = list.videos.get(i).title;
                                            new AppDialog.Builder(a)
                                                    .setTitle(site.name)
                                                    .setItems(
                                                            titles,
                                                            (dialog, index) ->
                                                                    a.openListing(
                                                                            site,
                                                                            list.videos.get(index)))
                                                    .setNegativeButton("关闭", null)
                                                    .show();
                                        } else {
                                            String msg =
                                                    result.optString(
                                                            "msg", result.optString("message", ""));
                                            if (!msg.isEmpty()
                                                    && !isInternalActionCode(msg, action)) {
                                                a.notice(msg);
                                            } else if (!usedDetailFallback
                                                    && !isInternalActionCode(response, action)) {
                                                a.notice(response);
                                            }
                                        }
                                    } catch (Throwable ignored) {
                                        if (!usedDetailFallback
                                                && !isInternalActionCode(response, action)) {
                                            a.notice(response);
                                        }
                                    }
                                });
                    } catch (Throwable error) {
                        a.ui.post(
                                () -> {
                                    if (ticket == a.generation) a.notice(a.message(error));
                                });
                    }
                });
    }

    static void pickDocument(MainActivity a, int requestCode, String missingPickerNotice) {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            a.startActivityForResult(intent, requestCode);
        } catch (Exception error) {
            a.notice(missingPickerNotice);
        }
    }

    static void addressImportDialog(MainActivity a) {
        AddressInput input =
                new AddressInput(a, "https://…/config.json", a.prefs.getString(Prefs.ORIGIN, ""));
        AppDialog d =
                new AppDialog.Builder(a)
                        .setTitle("导入配置")
                        .setView(input)
                        .setNegativeButton("取消", null)
                        .setNeutralButton("选择本地文件", (x, y) -> a.pickConfig())
                        .setPositiveButton("导入地址", null)
                        .create();
        d.setOnShowListener(
                x ->
                        d.getButton(-1)
                                .setOnClickListener(
                                        view -> {
                                            String address = input.value();
                                            if (!Cms.isHttp(address)) {
                                                input.invalid("请输入 HTTP(S) 配置地址");
                                                return;
                                            }
                                            d.dismiss();
                                            a.open(
                                                    new MainActivity.Screen(
                                                            () -> {
                                                                a.renderPage(
                                                                        "正在导入",
                                                                        "IMPORT CONFIGURATION");
                                                                a.load(
                                                                        "连接配置并读取来源…",
                                                                        () -> {
                                                                            String raw =
                                                                                    fetchImport(
                                                                                            a,
                                                                                            address);
                                                                            return Cms.parseConfig(
                                                                                    raw,
                                                                                    importOrigin(
                                                                                            address));
                                                                        },
                                                                        c ->
                                                                                a.saveConfig(
                                                                                        c,
                                                                                        importOrigin(
                                                                                                address)));
                                                            }),
                                                    true);
                                        }));
        d.show();
    }

    static void onActivityResult(MainActivity a, int request, int result, Intent data) {
        if (request == UpdateScreen.INSTALL) {
            UpdateScreen.result(a);
            return;
        }
        if (request == SettingsScreen.PICK_ADBLUR_IMPORT) {
            // 多选图片走 ClipData（data.getData() 可能为空），须在通用单 URI 路由之前处理
            if (result == Activity.RESULT_OK && data != null) SettingsScreen.importResult(a, data);
            return;
        }
        if (request >= 44 && request <= 46) {
            if (result == Activity.RESULT_OK && data != null && data.getData() != null)
                SettingsScreen.result(a, request, data.getData());
            return;
        }
        if (request == 43
                && result == Activity.RESULT_OK
                && data != null
                && data.getData() != null) {
            final PlaybackControls controls = a.playbackControls;
            if (controls != null) {
                Uri file = data.getData();
                a.worker.execute(
                        () -> {
                            try (InputStream in = a.getContentResolver().openInputStream(file)) {
                                String text = Cms.read(in, 8 * 1024 * 1024);
                                a.ui.post(
                                        () -> {
                                            if (controls == a.playbackControls) {
                                                controls.danmaku.enabled(true);
                                                controls.danmaku.loadText(
                                                        text,
                                                        new DanmakuView.Listener() {
                                                            public void loaded(int count) {
                                                                a.toast("已加载 " + count + " 条弹幕");
                                                            }

                                                            public void error(String message) {
                                                                a.notice(message);
                                                            }
                                                        });
                                            }
                                        });
                            } catch (Exception error) {
                                AppLog.w("SourceController", "load danmaku file failed", error);
                                a.ui.post(() -> a.notice("弹幕文件无法读取。"));
                            }
                        });
            }
            return;
        }
        if (request == 42
                && result == Activity.RESULT_OK
                && data != null
                && data.getData() != null) {
            Uri subtitle = data.getData();
            if (a.player != null && a.prepared) {
                String name = "外挂字幕";
                try (android.database.Cursor cursor =
                        a.getContentResolver()
                                .query(
                                        subtitle,
                                        new String[] {
                                            android.provider.OpenableColumns.DISPLAY_NAME
                                        },
                                        null,
                                        null,
                                        null)) {
                    if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
                } catch (Exception ignored) {
                }
                try {
                    a.player.addExternalSubtitle(
                            subtitle, name, MediaOptions.subtitleMime(name, ""));
                    a.toast("已导入字幕");
                } catch (Exception error) {
                    AppLog.w("SourceController", "load subtitle file failed", error);
                    a.notice("字幕无法读取，请选择 SRT、VTT、ASS 或 TTML 文件。");
                }
            }
            return;
        }
        if ((request == 41 || request == 47)
                && result == Activity.RESULT_OK
                && data != null
                && data.getData() != null) {
            importLocalUri(a, data.getData(), request == 47);
        }
    }

    static void pickLiveSource(MainActivity a) {
        pickDocument(a, 47, "这台设备没有文件选择器，请使用网址或剪贴板导入直播源。");
    }

    static void showLiveImportDialog(MainActivity a) {
        LinearLayout panel = a.vertical();
        panel.setPadding(a.dp(4), a.dp(6), a.dp(4), a.dp(4));

        TextView tip =
                a.text("支持 M3U / M3U8 / TXT (#genre#) / JSON 直播源网址或文本：", 13, MainActivity.MUTED);
        panel.addView(tip);
        a.gap(panel, 10);

        final android.widget.EditText input = new android.widget.EditText(a);
        input.setHint("https://…/live.txt 或 #EXTM3U…");
        input.setHintTextColor(0xff6b7280);
        input.setTextColor(MainActivity.WHITE);
        input.setTextSize(14);
        input.setMaxLines(4);
        input.setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10));
        input.setBackground(a.bg(0xff1e2623, 12));
        String savedLive = a.prefs.getString(Prefs.LIVE_URL, "");
        if (Cms.isHttp(savedLive)) {
            input.setText(savedLive);
            input.setSelection(savedLive.length());
        }
        panel.addView(input, new LinearLayout.LayoutParams(-1, -2));
        a.gap(panel, 12);

        TextView pasteBtn = a.text("📋 从剪贴板一键粘贴直播源", 13, android.graphics.Color.WHITE);
        pasteBtn.setTypeface(null, android.graphics.Typeface.BOLD);
        pasteBtn.setGravity(android.view.Gravity.CENTER);
        pasteBtn.setPadding(a.dp(14), a.dp(11), a.dp(14), a.dp(11));
        pasteBtn.setBackground(a.bg(0xff0a84ff, 999));
        MobileDetailScreen.bindTouchFeedback(pasteBtn);
        panel.addView(pasteBtn, new LinearLayout.LayoutParams(-1, -2));
        a.gap(panel, 8);

        TextView fileBtn = a.text("📂 选择本地直播源文件 (TXT / M3U / JSON)", 13, MainActivity.WHITE);
        fileBtn.setGravity(android.view.Gravity.CENTER);
        fileBtn.setPadding(a.dp(14), a.dp(11), a.dp(14), a.dp(11));
        fileBtn.setBackground(a.bg(0xff1e2623, 999));
        MobileDetailScreen.bindTouchFeedback(fileBtn);
        panel.addView(fileBtn, new LinearLayout.LayoutParams(-1, -2));

        AppDialog.Builder builder =
                new AppDialog.Builder(a)
                        .setTitle("导入或管理直播源")
                        .setView(panel)
                        .setNegativeButton("取消", null)
                        .setPositiveButton(
                                "导入输入内容",
                                (d, w) -> {
                                    String raw = input.getText().toString().trim();
                                    if (raw.isEmpty()) {
                                        a.toast("请输入直播源网址或内容");
                                        return;
                                    }
                                    importLiveTextOrUrl(a, raw);
                                });
        if (!savedLive.isEmpty()) {
            builder.setNeutralButton(
                    "清除自定义直播",
                    (d, w) -> {
                        a.prefs.edit().remove(Prefs.LIVE_URL).remove(Prefs.LIVE_NAME).apply();
                        a.toast("已清除自定义直播源");
                        if (!a.isTvDevice()) MobileShell.show(a);
                        else a.refresh();
                    });
        }
        final AppDialog dialog = builder.create();

        pasteBtn.setOnClickListener(
                v -> {
                    dialog.dismiss();
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager)
                                    a.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                    String clipText = "";
                    if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null) {
                        android.content.ClipData clip = cm.getPrimaryClip();
                        if (clip.getItemCount() > 0 && clip.getItemAt(0).getText() != null) {
                            clipText = clip.getItemAt(0).getText().toString().trim();
                        }
                    }
                    if (clipText.isEmpty()) {
                        a.toast("剪贴板为空，请先复制直播源网址或内容");
                        return;
                    }
                    importLiveTextOrUrl(a, clipText);
                });
        fileBtn.setOnClickListener(
                v -> {
                    dialog.dismiss();
                    pickLiveSource(a);
                });
        dialog.show();
    }

    static void importLiveTextOrUrl(MainActivity a, String rawInput) {
        final String text = rawInput == null ? "" : rawInput.trim();
        if (text.isEmpty()) {
            a.toast("请输入直播源网址或内容");
            return;
        }
        a.toast("正在读取并校验直播源…");
        a.worker.execute(
                () -> {
                    try {
                        final String savedUrl;
                        final String savedName;
                        final int count;
                        if (Cms.isHttp(text)) {
                            String norm = Cms.normalizeHttp(text);
                            LiveCatalog.Source src = new LiveCatalog.Source();
                            src.name = "自定义直播源";
                            src.url = norm;
                            src.origin = norm;
                            src.headers.put("User-Agent", Cms.USER_AGENT);
                            LiveCatalog.Catalog cat = LiveCatalog.load(src);
                            count = cat.channelCount();
                            savedUrl = norm;
                            savedName = "自定义直播 (" + count + "台)";
                        } else {
                            LiveCatalog.Catalog cat = LiveCatalog.parse(text, "", null);
                            count = cat.channelCount();
                            java.io.File liveFile =
                                    new java.io.File(a.getFilesDir(), "custom_live.txt");
                            try (java.io.OutputStream out =
                                    new java.io.FileOutputStream(liveFile)) {
                                out.write(text.getBytes("UTF-8"));
                            }
                            savedUrl = Cms.fileUri(liveFile);
                            savedName = "本地直播 (" + count + "台)";
                        }
                        a.ui.post(
                                () -> {
                                    a.prefs
                                            .edit()
                                            .putString(Prefs.LIVE_URL, savedUrl)
                                            .putString(Prefs.LIVE_NAME, savedName)
                                            .apply();
                                    a.toast("直播源导入成功（共 " + count + " 个频道）");
                                    if (!a.isTvDevice()) MobileShell.show(a);
                                    else a.refresh();
                                });
                    } catch (Exception e) {
                        a.ui.post(() -> a.toast("直播源导入失败：" + a.message(e)));
                    }
                });
    }

    static void mergeOrSaveConfig(MainActivity a, Cms.Config incoming, String origin, boolean merge)
            throws Exception {
        if (!merge || a.config == null || a.config.sites.isEmpty()) {
            saveConfig(a, incoming, origin);
            return;
        }
        org.json.JSONObject baseJson;
        try {
            baseJson = new org.json.JSONObject(Cms.withoutComments(a.config.raw));
        } catch (Exception ignored) {
            saveConfig(a, incoming, origin);
            return;
        }
        org.json.JSONArray sitesArr = baseJson.optJSONArray("sites");
        if (sitesArr == null) {
            sitesArr = new org.json.JSONArray();
            baseJson.put("sites", sitesArr);
        }
        for (Cms.Site ns : incoming.sites) {
            for (int i = sitesArr.length() - 1; i >= 0; i--) {
                org.json.JSONObject existing = sitesArr.optJSONObject(i);
                if (existing != null
                        && (ns.key.equals(existing.optString("key"))
                                || ns.api.equals(existing.optString("api")))) {
                    sitesArr.remove(i);
                }
            }
            org.json.JSONObject sObj =
                    new org.json.JSONObject()
                            .put("key", ns.key)
                            .put("name", ns.name)
                            .put("type", ns.type)
                            .put("api", ns.api)
                            .put("searchable", ns.searchable ? 1 : 0);
            if (ns.jar != null && !ns.jar.isEmpty()) sObj.put("jar", ns.jar);
            if (ns.ext != null) sObj.put("ext", ns.ext);
            sitesArr.put(sObj);
        }
        String mergedRaw = baseJson.toString(2);
        Cms.Config merged = Cms.parseConfig(mergedRaw, a.configOrigin);
        if (!incoming.sites.isEmpty()) {
            a.prefs.edit().putString(Prefs.SELECTED_SITE, incoming.sites.get(0).key).apply();
        }
        saveConfig(a, merged, a.configOrigin);
    }

    static void importLocalUri(MainActivity a, Uri uri, boolean preferLive) {
        a.toast("正在读取本地文件…");
        a.worker.execute(
                () -> {
                    try {
                        String displayName = "imported";
                        try (android.database.Cursor cursor =
                                a.getContentResolver()
                                        .query(
                                                uri,
                                                new String[] {
                                                    android.provider.OpenableColumns.DISPLAY_NAME
                                                },
                                                null,
                                                null,
                                                null)) {
                            if (cursor != null && cursor.moveToFirst()) {
                                String n = cursor.getString(0);
                                if (n != null && !n.trim().isEmpty()) displayName = n.trim();
                            }
                        } catch (Exception ignored) {
                        }
                        byte[] rawBytes;
                        try (InputStream in = a.getContentResolver().openInputStream(uri);
                                java.io.ByteArrayOutputStream bos =
                                        new java.io.ByteArrayOutputStream()) {
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = in.read(buf)) != -1) {
                                if (bos.size() + n > 28 * 1024 * 1024)
                                    throw new IOException("本地文件超过 28 MB 大小限制");
                                bos.write(buf, 0, n);
                            }
                            rawBytes = bos.toByteArray();
                        }
                        String lowerName = displayName.toLowerCase(java.util.Locale.ROOT);
                        boolean isZip =
                                lowerName.endsWith(".zip")
                                        || (rawBytes.length >= 4
                                                && (rawBytes[0] & 0xff) == 0x50
                                                && (rawBytes[1] & 0xff) == 0x4b
                                                && (rawBytes[2] & 0xff) == 0x03
                                                && (rawBytes[3] & 0xff) == 0x04);
                        if (isZip && !lowerName.endsWith(".jar")) {
                            LocalSourcePackage pkg =
                                    LocalSourcePackage.open(a.getFilesDir(), rawBytes);
                            a.ui.post(
                                    () -> {
                                        try {
                                            applyLocalPackage(a, pkg);
                                        } catch (Exception ex) {
                                            a.toast("保存本地包配置失败：" + a.message(ex));
                                        }
                                    });
                            return;
                        }
                        String text = Cms.document(rawBytes);
                        if (preferLive
                                || lowerName.endsWith(".m3u")
                                || lowerName.endsWith(".m3u8")
                                || lowerName.endsWith(".txt")) {
                            try {
                                LiveCatalog.Catalog cat = LiveCatalog.parse(text, "", null);
                                java.io.File liveFile =
                                        new java.io.File(a.getFilesDir(), "custom_live.txt");
                                try (java.io.OutputStream out =
                                        new java.io.FileOutputStream(liveFile)) {
                                    out.write(text.getBytes("UTF-8"));
                                }
                                final String liveUrl = Cms.fileUri(liveFile);
                                final int count = cat.channelCount();
                                final String liveName = displayName + " (" + count + "台)";
                                a.ui.post(
                                        () -> {
                                            a.prefs
                                                    .edit()
                                                    .putString(Prefs.LIVE_URL, liveUrl)
                                                    .putString(Prefs.LIVE_NAME, liveName)
                                                    .apply();
                                            a.toast("直播源导入成功（共 " + count + " 个频道）");
                                            if (!a.isTvDevice()) MobileShell.show(a);
                                            else a.refresh();
                                        });
                                return;
                            } catch (Exception liveErr) {
                                if (preferLive) throw liveErr;
                            }
                        }
                        if (lowerName.endsWith(".js")
                                || lowerName.endsWith(".mjs")
                                || lowerName.endsWith(".py")
                                || Cms.looksLikeJsSpider(text, displayName)
                                || Cms.looksLikePySpider(text, displayName)) {
                            java.io.File spiderDir =
                                    new java.io.File(a.getFilesDir(), "local_spiders");
                            if (!spiderDir.exists()) spiderDir.mkdirs();
                            String safeName =
                                    displayName.replaceAll("[^A-Za-z0-9._\\-\\u4e00-\\u9fa5]", "_");
                            java.io.File savedFile = new java.io.File(spiderDir, safeName);
                            try (java.io.OutputStream out =
                                    new java.io.FileOutputStream(savedFile)) {
                                out.write(text.getBytes("UTF-8"));
                            }
                            String fileOrigin = Cms.fileUri(savedFile);
                            Cms.Config singleCfg = Cms.parseConfig(text, fileOrigin);
                            a.ui.post(
                                    () -> {
                                        try {
                                            mergeOrSaveConfig(a, singleCfg, "", true);
                                            a.toast("已导入扩展片源：" + singleCfg.sites.get(0).name);
                                        } catch (Exception ex) {
                                            a.toast("保存扩展片源失败：" + a.message(ex));
                                        }
                                    });
                            return;
                        }
                        try {
                            Cms.Config parsed = Cms.parseConfig(text, null);
                            a.ui.post(
                                    () -> {
                                        try {
                                            saveConfig(a, parsed, "");
                                        } catch (Exception ex) {
                                            a.toast("保存配置失败：" + a.message(ex));
                                        }
                                    });
                        } catch (Exception configErr) {
                            // Fallback: check if the JSON/text file is actually a live channel list
                            try {
                                LiveCatalog.Catalog cat = LiveCatalog.parse(text, "", null);
                                java.io.File liveFile =
                                        new java.io.File(a.getFilesDir(), "custom_live.txt");
                                try (java.io.OutputStream out =
                                        new java.io.FileOutputStream(liveFile)) {
                                    out.write(text.getBytes("UTF-8"));
                                }
                                final String liveUrl = Cms.fileUri(liveFile);
                                final int count = cat.channelCount();
                                a.ui.post(
                                        () -> {
                                            a.prefs
                                                    .edit()
                                                    .putString(Prefs.LIVE_URL, liveUrl)
                                                    .putString(
                                                            Prefs.LIVE_NAME,
                                                            "本地直播 (" + count + "台)")
                                                    .apply();
                                            a.toast("已识别并导入直播源（共 " + count + " 个频道）");
                                            if (!a.isTvDevice()) MobileShell.show(a);
                                            else a.refresh();
                                        });
                            } catch (Exception ignored) {
                                throw configErr;
                            }
                        }
                    } catch (Exception e) {
                        a.ui.post(() -> a.toast("文件读取失败：" + a.message(e)));
                    }
                });
    }

    static void applyLocalPackage(MainActivity a, LocalSourcePackage pkg) throws Exception {
        if (pkg.liveChannels > 0) {
            a.prefs
                    .edit()
                    .putString(Prefs.LIVE_URL, pkg.origin)
                    .putString(Prefs.LIVE_NAME, "本地包直播 (" + pkg.liveChannels + "台)")
                    .apply();
            a.toast("本地播放列表导入成功（共 " + pkg.liveChannels + " 个频道）");
            if (!a.isTvDevice()) MobileShell.show(a);
            else a.refresh();
        } else {
            saveConfig(a, pkg.config, pkg.origin);
            a.toast("本地包导入成功（共 " + pkg.config.sites.size() + " 个片源）");
        }
    }

    static void saveConfig(MainActivity a, Cms.Config c, String origin) throws Exception {
        if (!c.warehouse.isEmpty()) origin = c.warehouse;
        else if (!c.origin.isEmpty()) origin = c.origin;
        a.configStore.write(c.raw);
        SourcePicker.applyEdits(a, c, origin);
        a.closeEngines();
        a.config = c;
        a.configOrigin = origin;
        android.content.SharedPreferences.Editor editor =
                a.prefs.edit().putString(Prefs.ORIGIN, origin);
        String currentKey = a.prefs.getString(Prefs.SELECTED_SITE, "");
        boolean hasSelected = false;
        Cms.Site firstAvailable = null;
        for (Cms.Site s : c.sites) {
            if (available(a, s)) {
                if (firstAvailable == null) firstAvailable = s;
                if (s.key.equals(currentKey)) hasSelected = true;
            }
        }
        if (!hasSelected && firstAvailable != null) {
            editor.putString(Prefs.SELECTED_SITE, firstAvailable.key);
        }
        editor.apply();
        a.back.clear();
        a.current = new MainActivity.Screen(a::home);
        a.current.render.run();
        a.restore(a.current);
        a.toast("已导入 " + c.sites.size() + " 个来源");
    }
}
