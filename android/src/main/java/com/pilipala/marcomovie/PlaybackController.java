package com.pilipala.marcomovie;

import android.graphics.Color;
import android.net.Uri;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Future;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Playback resolution (direct, plugin/JS player, web resolver, magnet stream), player page
 * lifecycle, mini-player transitions, remote seek gesture, control overlay auto-hide, and watch
 * history bookmark persistence extracted from {@link MainActivity}.
 */
final class PlaybackController {
    private PlaybackController() {}

    static String formatTime(int ms) {
        int sec = Math.max(0, ms / 1000);
        return sec >= 3600
                ? String.format(Locale.ROOT, "%d:%02d:%02d", sec / 3600, (sec / 60) % 60, sec % 60)
                : String.format(Locale.ROOT, "%02d:%02d", sec / 60, sec % 60);
    }

    static void stopMagnet(MainActivity a) {
        if (a.magnetStream != null) {
            a.magnetStream.close();
            a.magnetStream = null;
        }
    }

    static void stopWeb(MainActivity a) {
        if (a.webResolver != null) {
            a.webResolver.stop();
            a.webResolver = null;
        }
    }

    static void resolveWebPage(
            MainActivity a, Cms.Site s, Cms.Video v, Cms.Episode ep, String prefix) {
        String address = (prefix == null ? "" : prefix) + ep.url;
        if (!Cms.isHttp(address)) {
            a.changingMiniEpisode = false;
            clearPlaybackLoading(a);
            failInPlaceSwitch(a, "解析页面地址不受支持。");
            return;
        }
        final int ticket = a.generation;
        final int serial = a.playbackResolveSerial;
        try {
            a.webResolver =
                    new WebResolver(
                            a,
                            address,
                            ep.headers,
                            new WebResolver.Listener() {
                                public void media(String url, Map<String, String> headers) {
                                    if (ticket != a.generation || serial != a.playbackResolveSerial)
                                        return;
                                    a.webResolver = null;
                                    clearPlaybackLoading(a);
                                    Cms.Episode resolved = new Cms.Episode();
                                    resolved.name = ep.name;
                                    resolved.url = url;
                                    resolved.headers.putAll(headers);
                                    resolved.media = ep.media;
                                    openPlayer(a, s, v, resolved);
                                }

                                public void failed(String reason) {
                                    if (ticket == a.generation
                                            && serial == a.playbackResolveSerial) {
                                        a.changingMiniEpisode = false;
                                        clearPlaybackLoading(a);
                                        stopWeb(a);
                                        failInPlaceSwitch(a, reason);
                                    }
                                }
                            });
            a.playbackLoading.addView(a.webResolver.view(), new FrameLayout.LayoutParams(1, 1));
        } catch (Exception error) {
            AppLog.w("PlaybackController", "WebResolver init failed", error);
            a.changingMiniEpisode = false;
            clearPlaybackLoading(a);
            failInPlaceSwitch(a, "这台设备无法打开网页播放组件。");
        }
    }

    static void failInPlaceSwitch(MainActivity a, String message) {
        if (a.playerChrome != null) a.playerChrome.setLoading(false);
        if (a.current != null && a.current.player) {
            if (a.playStatus != null) a.playStatus.setText(message);
            showControls(a, true);
        }
        a.toast(message);
    }

    static WatchHistory.Entry lookupSavedBookmark(
            MainActivity a, Cms.Site s, Cms.Video v, Cms.Line line, Cms.Episode ep) {
        if (a == null
                || s == null
                || s.key == null
                || "live".equals(s.key)
                || v == null
                || v.id == null
                || ep == null) {
            return null;
        }
        int epIdx = line != null ? line.episodes.indexOf(ep) : -1;
        for (WatchHistory.Entry entry : a.watchHistory()) {
            if (s.key.equals(entry.site) && v.id.equals(entry.id)) {
                boolean sameName = ep.name != null && ep.name.equals(entry.episode);
                boolean sameIndex =
                        line != null
                                && line.name != null
                                && line.name.equals(entry.line)
                                && epIdx >= 0
                                && epIdx == entry.index;
                if (sameName || sameIndex) {
                    return entry;
                }
                break;
            }
        }
        return null;
    }

    private static Cms.Site findPushSite(MainActivity a) {
        if (a == null || a.config == null) return null;
        for (Cms.Site site : a.config.sites) {
            if (site.api != null
                    && site.api.toLowerCase(Locale.ROOT).contains("push")
                    && a.available(site)) {
                return site;
            }
        }
        return null;
    }

    private static boolean looksLikeNetdiskLine(Cms.Site s, Cms.Line line, String url) {
        String combined =
                ((s != null && s.name != null ? s.name : "")
                                + " "
                                + (line != null && line.name != null ? line.name : "")
                                + " "
                                + (url != null ? url : ""))
                        .toLowerCase(Locale.ROOT);
        return combined.contains("原画")
                || combined.contains("原畫")
                || combined.contains("网盘")
                || combined.contains("百度")
                || combined.contains("摆渡")
                || combined.contains("夸克")
                || combined.contains("夸父")
                || combined.contains("阿里")
                || combined.contains("天翼")
                || combined.contains("移动")
                || combined.contains("115")
                || combined.contains("123")
                || combined.contains("uc")
                || combined.contains("pan")
                || combined.contains("quark")
                || combined.contains("baidu");
    }

    private static Cms.Episode findMatchingEpisode(
            Cms.Line targetLine, Cms.Line fromLine, Cms.Episode ep) {
        if (targetLine == null || targetLine.episodes.isEmpty()) return null;
        if (ep != null && ep.name != null) {
            for (Cms.Episode candidate : targetLine.episodes) {
                if (ep.name.equalsIgnoreCase(candidate.name)) {
                    return candidate;
                }
            }
        }
        int idx = fromLine != null && ep != null ? fromLine.episodes.indexOf(ep) : 0;
        if (idx >= 0 && idx < targetLine.episodes.size()) {
            return targetLine.episodes.get(idx);
        }
        return targetLine.episodes.get(0);
    }

    static void resolvePlayer(
            MainActivity a, Cms.Site s, Cms.Video v, Cms.Line line, Cms.Episode ep) {
        if (a.miniMode) {
            a.changingMiniEpisode = true;
            showEpisodeDetails(a, s, v);
        }
        a.activeLine = line;
        a.activeEpisode = ep;
        if (a.resumeAt == 0) {
            WatchHistory.Entry saved = lookupSavedBookmark(a, s, v, line, ep);
            if (saved != null) {
                a.resumeAt = saved.resume();
            }
        }
        if (s.supported()) {
            String rewrittenEpUrl = LocalProxy.rewrite(ep.url);
            String cleanPath =
                    rewrittenEpUrl == null
                            ? ""
                            : rewrittenEpUrl.split("[?#]", 2)[0].toLowerCase(Locale.ROOT);
            boolean looksLikeWebPage =
                    cleanPath.endsWith(".html")
                            || cleanPath.endsWith(".htm")
                            || cleanPath.endsWith(".shtml")
                            || cleanPath.contains("/share/");
            if (Cms.isDirectStreamUrl(rewrittenEpUrl)
                    || (!s.requiresParser && Cms.isMedia(rewrittenEpUrl) && !looksLikeWebPage)) {
                openPlayer(a, s, v, ep);
                return;
            }
            if (v != null && v.lines != null) {
                for (Cms.Line sibling : v.lines) {
                    if (sibling == line) continue;
                    Cms.Episode match = findMatchingEpisode(sibling, line, ep);
                    if (match != null && Cms.isDirectStreamUrl(LocalProxy.rewrite(match.url))) {
                        a.activeLine = sibling;
                        a.activeEpisode = match;
                        rememberEpisodeOnBackStack(a, sibling, match);
                        openPlayer(a, s, v, match);
                        return;
                    }
                }
            }
            resolveCmsWithParser(a, s, v, ep, s.playUrl);
            return;
        }
        if (a.playbackLoading != null) return;
        showPlaybackLoading(a);
        final int serial = a.playbackResolveSerial, ticket = a.generation;
        Future<?> request =
                RequestScope.submit(
                        a.details,
                        () -> {
                            try {
                                Cms.Site targetSite = s;
                                Cms.Line chosenLine = line;
                                Cms.Episode chosenEp = ep;
                                String targetFlag = line != null ? line.name : "";
                                String targetId = ep.url != null ? ep.url : "";
                                if (targetId.regionMatches(true, 0, "push://", 0, 7)) {
                                    Cms.Site pushSite = findPushSite(a);
                                    if (pushSite != null) {
                                        targetSite = pushSite;
                                        String pushTarget = targetId.substring(7);
                                        Cms.Listing pushList =
                                                Cms.listing(a.engine(pushSite).detail(pushTarget));
                                        if (!pushList.videos.isEmpty()
                                                && !pushList.videos.get(0).lines.isEmpty()
                                                && !pushList.videos
                                                        .get(0)
                                                        .lines
                                                        .get(0)
                                                        .episodes
                                                        .isEmpty()) {
                                            Cms.Line pLine = pushList.videos.get(0).lines.get(0);
                                            targetFlag = pLine.name;
                                            targetId = pLine.episodes.get(0).url;
                                        }
                                    }
                                }
                                JSONObject result = null;
                                Cms.Episode resolvedCandidate = null;
                                Exception primaryError = null;
                                try {
                                    result =
                                            new JSONObject(
                                                    a.engine(targetSite)
                                                            .player(targetFlag, targetId));
                                    resolvedCandidate =
                                            MediaOptions.episode(targetSite, chosenEp, result);
                                    if (resolvedCandidate.url.regionMatches(
                                            true, 0, "push://", 0, 7)) {
                                        Cms.Site pushSite = findPushSite(a);
                                        if (pushSite != null) {
                                            String pushTarget = resolvedCandidate.url.substring(7);
                                            Cms.Listing pushList =
                                                    Cms.listing(
                                                            a.engine(pushSite).detail(pushTarget));
                                            if (!pushList.videos.isEmpty()
                                                    && !pushList.videos.get(0).lines.isEmpty()
                                                    && !pushList.videos
                                                            .get(0)
                                                            .lines
                                                            .get(0)
                                                            .episodes
                                                            .isEmpty()) {
                                                Cms.Line pLine =
                                                        pushList.videos.get(0).lines.get(0);
                                                JSONObject pushRes =
                                                        new JSONObject(
                                                                a.engine(pushSite)
                                                                        .player(
                                                                                pLine.name,
                                                                                pLine.episodes.get(
                                                                                                0)
                                                                                        .url));
                                                resolvedCandidate =
                                                        MediaOptions.episode(
                                                                pushSite, chosenEp, pushRes);
                                                result = pushRes;
                                            }
                                        }
                                    }
                                } catch (Exception e) {
                                    primaryError = e;
                                }

                                boolean primaryPlayable =
                                        resolvedCandidate != null
                                                && resolvedCandidate.url != null
                                                && !resolvedCandidate.url.trim().isEmpty()
                                                && (Cms.isMedia(resolvedCandidate.url.trim())
                                                        || resolvedCandidate
                                                                .url
                                                                .trim()
                                                                .startsWith("magnet:?")
                                                        || (result != null
                                                                && (result.optInt("parse", 0) != 0
                                                                        || result.optInt("jx", 0)
                                                                                != 0)));
                                if (!primaryPlayable
                                        && v != null
                                        && v.lines != null
                                        && v.lines.size() > 1
                                        && !looksLikeNetdiskLine(s, line, targetId)) {
                                    for (Cms.Line altLine : v.lines) {
                                        if (altLine == null
                                                || altLine == line
                                                || altLine.episodes.isEmpty()) {
                                            continue;
                                        }
                                        Cms.Episode altEp = findMatchingEpisode(altLine, line, ep);
                                        if (altEp == null || altEp.url == null) continue;
                                        if (looksLikeNetdiskLine(s, altLine, altEp.url)) continue;
                                        try {
                                            JSONObject altRes =
                                                    new JSONObject(
                                                            a.engine(s)
                                                                    .player(
                                                                            altLine.name,
                                                                            altEp.url));
                                            Cms.Episode altResolved =
                                                    MediaOptions.episode(s, altEp, altRes);
                                            String altUrl =
                                                    altResolved.url == null
                                                            ? ""
                                                            : altResolved.url.trim();
                                            if (!altUrl.isEmpty()
                                                    && (Cms.isMedia(altUrl)
                                                            || altUrl.startsWith("magnet:?")
                                                            || altRes.optInt("parse", 0) != 0
                                                            || altRes.optInt("jx", 0) != 0)) {
                                                result = altRes;
                                                resolvedCandidate = altResolved;
                                                chosenLine = altLine;
                                                chosenEp = altEp;
                                                primaryError = null;
                                                break;
                                            }
                                        } catch (Exception ignored) {
                                        }
                                    }
                                }
                                if (primaryError != null && resolvedCandidate == null) {
                                    throw primaryError;
                                }
                                final JSONObject finalResult =
                                        result != null ? result : new JSONObject();
                                final Cms.Episode resolved =
                                        resolvedCandidate != null
                                                ? resolvedCandidate
                                                : MediaOptions.episode(
                                                        targetSite, chosenEp, finalResult);
                                final Cms.Line finalLine = chosenLine;
                                final Cms.Episode finalEp = chosenEp;
                                final boolean switchedLine =
                                        chosenLine != line && chosenLine != null;
                                a.ui.post(
                                        () -> {
                                            if (serial != a.playbackResolveSerial
                                                    || ticket != a.generation
                                                    || a.isFinishing()) {
                                                JSONObject unused =
                                                        resolved.media.optJSONObject("embySession");
                                                if (unused != null
                                                        && s.api.startsWith(
                                                                com.pilipala.emby.EmbyLibrary
                                                                        .PREFIX))
                                                    try {
                                                        com.pilipala.emby.EmbyAccounts.open(
                                                                        a, s.api)
                                                                .track(unused)
                                                                .stop(0);
                                                    } catch (Exception ignored) {
                                                    }
                                                return;
                                            }
                                            try {
                                                if (switchedLine) {
                                                    a.activeLine = finalLine;
                                                    a.activeEpisode = finalEp;
                                                    a.toast("当前线路响应异常，已自动切换至 " + finalLine.name);
                                                }
                                                String url =
                                                        resolved.url == null
                                                                ? ""
                                                                : resolved.url.trim();
                                                if (url.isEmpty() && finalEp.url != null) {
                                                    String fallbackEpUrl =
                                                            LocalProxy.rewrite(finalEp.url.trim());
                                                    if (Cms.isMedia(fallbackEpUrl)
                                                            || fallbackEpUrl.startsWith(
                                                                    "magnet:?")) {
                                                        url = fallbackEpUrl;
                                                        resolved.url = fallbackEpUrl;
                                                    }
                                                }
                                                if (finalResult.optInt("parse", 0) != 0
                                                        || finalResult.optInt("jx", 0) != 0) {
                                                    String playPrefix =
                                                            finalResult.optString(
                                                                    "playUrl", s.playUrl);
                                                    if ((playPrefix == null || playPrefix.isEmpty())
                                                            && a.config != null
                                                            && !a.config.parses.isEmpty()) {
                                                        clearPlaybackLoading(a);
                                                        resolveCmsWithParser(a, s, v, resolved, "");
                                                        return;
                                                    }
                                                    resolveWebPage(a, s, v, resolved, playPrefix);
                                                    return;
                                                }
                                                clearPlaybackLoading(a);
                                                if (url.isEmpty()) {
                                                    a.changingMiniEpisode = false;
                                                    String spiderMsg =
                                                            finalResult
                                                                    .optString(
                                                                            "msg",
                                                                            finalResult.optString(
                                                                                    "message",
                                                                                    finalResult
                                                                                            .optString(
                                                                                                    "tip",
                                                                                                    "")))
                                                                    .trim();
                                                    if (!spiderMsg.isEmpty()) {
                                                        failInPlaceSwitch(a, spiderMsg);
                                                    } else if (looksLikeNetdiskLine(
                                                            s, finalLine, finalEp.url)) {
                                                        failInPlaceSwitch(
                                                                a,
                                                                "该网盘线路未返回播放地址，请先在「网盘与账号」扫码登录对应网盘，或点击右上角 ⇄ 切换其他线路");
                                                    } else {
                                                        failInPlaceSwitch(
                                                                a, "当前线路未返回有效播放地址，请点击右上角 ⇄ 切换其他线路");
                                                    }
                                                    return;
                                                }
                                                if (!Cms.isMedia(url)
                                                        && !url.startsWith("magnet:?")) {
                                                    a.changingMiniEpisode = false;
                                                    if (looksLikeNetdiskLine(s, finalLine, url)) {
                                                        failInPlaceSwitch(
                                                                a,
                                                                "该网盘线路需先在「网盘与账号」登录授权，或点击右上角 ⇄ 切换其他线路");
                                                    } else {
                                                        failInPlaceSwitch(
                                                                a, "来源返回的播放协议暂不支持，请切换其他线路");
                                                    }
                                                    return;
                                                }
                                                openPlayer(a, s, v, resolved);
                                            } catch (Exception error) {
                                                AppLog.w(
                                                        "PlaybackController",
                                                        "parse player response failed",
                                                        error);
                                                a.changingMiniEpisode = false;
                                                clearPlaybackLoading(a);
                                                failInPlaceSwitch(a, "播放信息无法读取，请重试");
                                            }
                                        });
                            } catch (Exception error) {
                                AppLog.w("PlaybackController", "engine.player failed", error);
                                a.ui.post(
                                        () -> {
                                            if (serial != a.playbackResolveSerial
                                                    || ticket != a.generation) return;
                                            a.changingMiniEpisode = false;
                                            clearPlaybackLoading(a);
                                            failInPlaceSwitch(a, a.message(error));
                                        });
                            }
                        });
        a.pageRequests.add(request);
    }

    private static String extractEmbeddedStreamUrl(String baseUrl, String body) {
        if (body == null || body.isEmpty()) return "";
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile(
                                "(?:const|var|let)?\\s*(?:url|main|playurl|src|vid)\\s*[:=]\\s*[\"']([^\"']+\\.(?:m3u8|mp4)(?:\\?[^\"']*)?)[\"']",
                                java.util.regex.Pattern.CASE_INSENSITIVE)
                        .matcher(body);
        if (!m.find()) {
            m =
                    java.util.regex.Pattern.compile(
                                    "[\"']((?:https?:)?//[^\"']+\\.(?:m3u8|mp4)(?:\\?[^\"']*)?|/[^\"'\\s<>]+\\.(?:m3u8|mp4)(?:\\?[^\"'\\s<>]*)?)[\"']",
                                    java.util.regex.Pattern.CASE_INSENSITIVE)
                            .matcher(body);
            if (!m.find()) return "";
        }
        String found = m.group(1).trim().replace("\\/", "/");
        if (found.isEmpty()) return "";
        try {
            if (found.startsWith("//")) return "https:" + found;
            if (Cms.isHttp(found)) return found;
            if (baseUrl != null && Cms.isHttp(baseUrl)) {
                return new java.net.URI(baseUrl).resolve(found).toString();
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    static void resolveCmsWithParser(
            MainActivity a, Cms.Site s, Cms.Video v, Cms.Episode ep, String preferredPrefix) {
        if (a.playbackLoading != null) return;
        showPlaybackLoading(a);
        final int serial = a.playbackResolveSerial, ticket = a.generation;
        Future<?> request =
                RequestScope.submit(
                        a.details,
                        () -> {
                            // 0. If ep.url is an HTML share wrapper with an embedded .m3u8/.mp4,
                            // extract it directly before falling back to external parsers.
                            if (ep.url != null && Cms.isHttp(ep.url)) {
                                try {
                                    String html = Cms.fetch(ep.url, s.headers);
                                    String embedded = extractEmbeddedStreamUrl(ep.url, html);
                                    if (Cms.isDirectStreamUrl(embedded)) {
                                        final Cms.Episode directEp = new Cms.Episode();
                                        directEp.name = ep.name;
                                        directEp.url = embedded;
                                        directEp.headers.putAll(
                                                Cms.inheritedHeaders(s.api, embedded, s.headers));
                                        a.ui.post(
                                                () -> {
                                                    if (serial != a.playbackResolveSerial
                                                            || ticket != a.generation
                                                            || a.isFinishing()) return;
                                                    clearPlaybackLoading(a);
                                                    openPlayer(a, s, v, directEp);
                                                });
                                        return;
                                    }
                                } catch (Exception ignored) {
                                }
                            }
                            // 1. Try preferredPrefix or JSON parsers in config.parses
                            List<Cms.Parse> candidates = new java.util.ArrayList<>();
                            if (preferredPrefix != null && Cms.isHttp(preferredPrefix)) {
                                Cms.Parse direct = new Cms.Parse();
                                direct.name = s.name;
                                direct.url = preferredPrefix;
                                direct.type = 1;
                                direct.headers.putAll(s.headers);
                                candidates.add(direct);
                            }
                            if (a.config != null && a.config.parses != null) {
                                for (Cms.Parse p : a.config.parses) {
                                    if (p != null && Cms.isHttp(p.url)) candidates.add(p);
                                }
                            }
                            String webFallbackPrefix =
                                    preferredPrefix != null ? preferredPrefix : "";
                            for (int i = 0; i < Math.min(4, candidates.size()); i++) {
                                if (Thread.currentThread().isInterrupted()) return;
                                Cms.Parse p = candidates.get(i);
                                if (p.type == 0 && webFallbackPrefix.isEmpty()) {
                                    webFallbackPrefix = p.url;
                                }
                                if (p.type == 1 || p.type == 2 || p.type == 3 || i == 0) {
                                    try {
                                        String targetUrl =
                                                p.url + java.net.URLEncoder.encode(ep.url, "UTF-8");
                                        String resp = Cms.fetch(targetUrl, p.headers);
                                        if (resp != null && resp.trim().startsWith("{")) {
                                            JSONObject jo = new JSONObject(resp.trim());
                                            String mediaUrl = jo.optString("url", "").trim();
                                            if (Cms.isMedia(mediaUrl)) {
                                                final Cms.Episode resolved =
                                                        MediaOptions.episode(s, ep, jo);
                                                a.ui.post(
                                                        () -> {
                                                            if (serial != a.playbackResolveSerial
                                                                    || ticket != a.generation
                                                                    || a.isFinishing()) return;
                                                            clearPlaybackLoading(a);
                                                            openPlayer(a, s, v, resolved);
                                                        });
                                                return;
                                            }
                                        }
                                    } catch (Exception ignored) {
                                    }
                                }
                            }
                            final String finalPrefix = webFallbackPrefix;
                            a.ui.post(
                                    () -> {
                                        if (serial != a.playbackResolveSerial
                                                || ticket != a.generation
                                                || a.isFinishing()) return;
                                        if (Cms.isHttp(finalPrefix + ep.url)) {
                                            resolveWebPage(a, s, v, ep, finalPrefix);
                                        } else if (Cms.isMedia(ep.url)) {
                                            clearPlaybackLoading(a);
                                            openPlayer(a, s, v, ep);
                                        } else {
                                            a.changingMiniEpisode = false;
                                            clearPlaybackLoading(a);
                                            failInPlaceSwitch(a, "此剧集需要外部解析，未找到可用解析接口");
                                        }
                                    });
                        });
        a.pageRequests.add(request);
    }

    static void showPlaybackLoading(MainActivity a) {
        a.playbackLoadingFocus = a.getCurrentFocus();
        a.playbackLoading = new PlaybackLoading(a);
        a.root.addView(a.playbackLoading, new FrameLayout.LayoutParams(-1, -1));
        if (a.playerChrome != null) a.playerChrome.update();
        a.playbackLoading.requestFocus();
    }

    static void clearPlaybackLoading(MainActivity a) {
        a.playbackResolveSerial++;
        if (a.playbackLoading != null) {
            if (a.playbackLoading.getParent() instanceof ViewGroup)
                ((ViewGroup) a.playbackLoading.getParent()).removeView(a.playbackLoading);
            a.playbackLoading = null;
            if (a.playerChrome != null) a.playerChrome.update();
            if (a.playbackLoadingFocus != null && UiCompat.isAttached(a.playbackLoadingFocus))
                a.playbackLoadingFocus.requestFocus();
            a.playbackLoadingFocus = null;
        }
    }

    static void openPlayer(MainActivity a, Cms.Site s, Cms.Video v, Cms.Episode e) {
        if (e.url != null) {
            e.url = e.url.trim();
            if (e.url.startsWith("//")) {
                e.url = "https:" + e.url;
            }
            if (e.url.regionMatches(true, 0, "webview://", 0, 10)) {
                e.url = e.url.substring(10).trim();
                resolveWebPage(a, s, v, e, "");
                return;
            }
            if (a.config != null
                    && (e.url.regionMatches(true, 0, "proxy://", 0, 8)
                            || e.url.contains("/proxy"))) {
                try {
                    a.ensureProxy(a.config);
                } catch (Exception ignored) {
                }
            }
            e.url = LocalProxy.rewrite(e.url);
        }
        if (e.url != null && e.url.startsWith("magnet:?")) {
            magnetPlayer(a, s, v, e);
            return;
        }
        if (!Cms.isMedia(e.url)) {
            if (a.playerChrome != null) a.playerChrome.setLoading(false);
            a.notice("这条剧集需要解析或使用了特殊协议，基础版尚未支持。");
            return;
        }
        MainActivity.Screen screen = new MainActivity.Screen(() -> playerPage(a, s, v, e));
        screen.player = true;
        a.open(screen, a.current == null || !a.current.player);
    }

    static void magnetPlayer(MainActivity a, Cms.Site site, Cms.Video video, Cms.Episode episode) {
        a.open(
                new MainActivity.Screen(
                        () -> {
                            a.renderPage(video.title, "MAGNET PLAYBACK");
                            TextView state = a.text("正在获取磁力文件列表…", 17, MainActivity.MUTED);
                            a.body.addView(state);
                            final int ticket = a.generation;
                            try {
                                a.magnetStream =
                                        MagnetStream.start(
                                                a,
                                                episode.url,
                                                new MagnetStream.Listener() {
                                                    public void progress(String message) {
                                                        if (ticket == a.generation)
                                                            state.setText(message);
                                                    }

                                                    public void files(
                                                            List<MagnetStream.MediaFile> files,
                                                            int suggested,
                                                            MagnetStream.Selection selection) {
                                                        if (ticket != a.generation) return;
                                                        String[] names = new String[files.size()];
                                                        for (int i = 0; i < names.length; i++)
                                                            names[i] = files.get(i).name;
                                                        new AppDialog.Builder(a)
                                                                .setTitle("选择要播放的视频")
                                                                .setSingleChoiceItems(
                                                                        names,
                                                                        suggested,
                                                                        (dialog, index) -> {
                                                                            dialog.dismiss();
                                                                            selection.select(index);
                                                                        })
                                                                .setNegativeButton(
                                                                        "取消",
                                                                        (dialog, index) ->
                                                                                a.onBackPressed())
                                                                .setOnCancelListener(
                                                                        dialog -> a.onBackPressed())
                                                                .show();
                                                    }

                                                    public void ready(
                                                            String url, String mime, String name) {
                                                        if (ticket != a.generation) return;
                                                        Cms.Episode resolved = new Cms.Episode();
                                                        resolved.name = name;
                                                        resolved.url = url;
                                                        resolved.headers.putAll(episode.headers);
                                                        try {
                                                            resolved.media =
                                                                    new JSONObject(
                                                                                    episode.media
                                                                                            .toString())
                                                                            .put("mimeType", mime);
                                                        } catch (Exception ignored) {
                                                        }
                                                        if (!a.back.isEmpty())
                                                            a.current = a.back.pop();
                                                        openPlayer(a, site, video, resolved);
                                                    }

                                                    public void error(String message) {
                                                        if (ticket == a.generation) {
                                                            state.setText(message);
                                                            a.addButton(
                                                                    a.body,
                                                                    a.button(
                                                                            "重试",
                                                                            "primary",
                                                                            a::refresh));
                                                        }
                                                    }
                                                });
                            } catch (Exception error) {
                                AppLog.w("PlaybackController", "MagnetStream start failed", error);
                                state.setText("磁力播放初始化失败：" + error.getClass().getSimpleName());
                            }
                        }),
                true);
    }

    static void changeEpisode(
            MainActivity a,
            Cms.Site site,
            Cms.Video video,
            Cms.Line line,
            Cms.Episode episode,
            int position) {
        a.changingMiniEpisode = a.miniMode;
        a.resumeAt = position;
        if (a.miniMode) {
            showEpisodeDetails(a, site, video);
        } else {
            rememberEpisodeOnBackStack(a, line, episode);
            if (a.current != null && a.current.player) {
                prepareInPlaceSwitch(a, line, episode);
            }
        }
        resolvePlayer(a, site, video, line, episode);
    }

    static void rememberEpisodeOnBackStack(MainActivity a, Cms.Line line, Cms.Episode episode) {
        if (a.back.isEmpty() || line == null || episode == null) return;
        MainActivity.Screen prev = a.back.peek();
        if (prev == null) return;
        int epIdx = line.episodes.indexOf(episode);
        if (epIdx >= 0) {
            boolean desc = a.prefs.getBoolean(Prefs.EPISODES_DESCENDING, false);
            int page = EpisodeSelection.pageFor(epIdx, line.episodes.size(), desc);
            prev.selection.select(line.name, page, line.episodes.size());
            prev.focus = "episode:" + line.name + ":" + epIdx;
            prev.episodeFocus = epIdx;
            prev.episodeFocusLine = line.name;
        }
    }

    static void prepareInPlaceSwitch(MainActivity a, Cms.Line line, Cms.Episode episode) {
        finishSeek(a, false);
        savePlaybackHistory(a, true);
        stopWeb(a);
        stopMagnet(a);
        if (a.player != null) {
            try {
                a.player.release();
            } catch (Exception error) {
                AppLog.w("PlaybackController", "player.release failed", error);
            }
            a.player = null;
        }
        a.prepared = false;
        a.playing = false;
        // 换集 / 换源：清空每集识别状态，模型保持加载
        com.pilipala.marcomovie.adskip.AdSkipController.get().onEpisodeChange();
        if (a.playerChrome != null) {
            a.playerChrome.switchEpisode(episode, line);
        }
    }

    static void showEpisodeDetails(MainActivity a, Cms.Site site, Cms.Video video) {
        releasePlayer(a);
        if (!a.isTvDevice()) {
            a.applyMobileSystemBars(false);
            a.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER);
            if (a.usableWidth > a.usableHeight && a.usableHeight > 0) {
                int tmp = a.usableWidth;
                a.usableWidth = a.usableHeight;
                a.usableHeight = tmp;
            }
        }
        if (a.current != null && a.current.player)
            a.current =
                    a.back.isEmpty()
                            ? new MainActivity.Screen(() -> a.detail(site, video))
                            : a.back.pop();
        if (a.current != null) {
            a.detail(site, video, video);
            a.restore(a.current);
        }
    }

    static void leavePlayback(MainActivity a) {
        if (!a.isTvDevice()) {
            a.applyMobileSystemBars(false);
            a.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER);
            if (a.usableWidth > a.usableHeight && a.usableHeight > 0) {
                int tmp = a.usableWidth;
                a.usableWidth = a.usableHeight;
                a.usableHeight = tmp;
            }
        }
        if (!a.back.isEmpty()) {
            releasePlayer(a);
            a.current = a.back.pop();
            a.renderReturn(a.current);
            a.restore(a.current);
        } else a.onBackPressed();
    }

    static void minimizePlayback(MainActivity a) {
        if (a.playbackControls == null || a.miniMode) return;
        a.miniMode = true;
        showControls(a, false);
        a.playbackControls.subtitle.setVisibility(View.GONE);
        a.playbackControls.danmaku.setVisibility(View.GONE);
        a.miniDetails = new MiniDetails(a);
    }

    static void expandPlayback(MainActivity a) {
        if (!a.miniMode) return;
        a.miniMode = false;
        if (a.miniDetails != null) {
            a.miniDetails.remove();
            a.miniDetails = null;
        }
        UiCompat.setClipToOutline(a.playbackLayer, false);
        a.playbackLayer.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        a.playbackControls.subtitle.setVisibility(View.VISIBLE);
        a.playbackControls.danmaku.setVisibility(View.VISIBLE);
        a.playbackLayer.post(
                () -> {
                    if (a.playbackControls != null) {
                        a.playbackControls.resize(
                                a.playbackControls.videoWidth, a.playbackControls.videoHeight);
                        showControls(a, true);
                        a.playbackControls.timeline.requestFocus();
                    }
                });
    }

    static void playerPage(MainActivity a, Cms.Site s, Cms.Video v, Cms.Episode ep) {
        ep.url = LocalProxy.rewrite(ep.url);
        try {
            String dm = ep.media.optString("danmaku");
            if (dm != null && !dm.isEmpty()) {
                ep.media.put("danmaku", LocalProxy.rewrite(dm));
            } else {
                String recentDm = CspEngine.consumeRecentPluginDanmaku(25000L);
                if (!recentDm.isEmpty()) {
                    ep.media.put("danmaku", LocalProxy.rewrite(recentDm));
                }
            }
            JSONArray subtitles = ep.media.optJSONArray("subtitles");
            if (subtitles != null)
                for (int i = 0; i < subtitles.length(); i++) {
                    JSONObject sub = subtitles.optJSONObject(i);
                    if (sub != null) sub.put("url", LocalProxy.rewrite(sub.optString("url")));
                }
        } catch (Exception ignored) {
        }
        JSONObject embySession = ep.media.optJSONObject("embySession");
        com.pilipala.emby.EmbyLibrary.Reporter retained = a.embyReporter;
        if (retained != null && retained.matches(embySession)) a.embyReporter = null;
        else retained = null;
        releasePlayer(a);
        a.embyReporter = retained;
        a.playingBookmark = null;
        if (s.api.startsWith(com.pilipala.emby.EmbyLibrary.PREFIX) && embySession != null) {
            try {
                if (a.embyReporter == null)
                    a.embyReporter =
                            com.pilipala.emby.EmbyAccounts.open(a, s.api).track(embySession);
            } catch (Exception ignored) {
                // Check-in setup must not prevent an already resolved video from playing.
            }
        }
        String bookmarkId =
                v != null && v.id != null && !v.id.trim().isEmpty()
                        ? v.id.trim()
                        : (a.activeEpisode != null
                                        && a.activeEpisode.url != null
                                        && !a.activeEpisode.url.trim().isEmpty()
                                ? a.activeEpisode.url.trim()
                                : (v != null && v.title != null ? v.title.trim() : ""));
        if (v != null && !bookmarkId.isEmpty() && (v.id == null || v.id.trim().isEmpty())) {
            v.id = bookmarkId;
        }
        if (s.key != null
                && !s.key.equals("live")
                && !bookmarkId.isEmpty()
                && a.activeLine != null
                && a.activeEpisode != null) {
            WatchHistory.Entry saved = lookupSavedBookmark(a, s, v, a.activeLine, a.activeEpisode);
            int initialResume =
                    a.resumeAt > 0
                            ? a.resumeAt
                            : (a.resumeAt < 0 ? 0 : (saved != null ? saved.resume() : 0));
            a.playingBookmark = new WatchHistory.Entry();
            a.playingBookmark.site = s.key;
            a.playingBookmark.id = bookmarkId;
            a.playingBookmark.title = v.title;
            a.playingBookmark.poster = v.poster;
            a.playingBookmark.line = a.activeLine.name;
            a.playingBookmark.episode = a.activeEpisode.name;
            a.playingBookmark.index = a.activeLine.episodes.indexOf(a.activeEpisode);
            a.playingBookmark.position = Math.max(0, initialResume);
            a.playingBookmark.duration = saved != null ? Math.max(0, saved.duration) : 0;
            a.playingBookmark.updated = System.currentTimeMillis();
            try {
                a.prefs
                        .edit()
                        .putString(
                                Prefs.WATCH_HISTORY,
                                WatchHistory.upsert(
                                        a.prefs.getString(Prefs.WATCH_HISTORY, "[]"),
                                        a.playingBookmark))
                        .commit();
                a.lastBookmarkSave = a.playingBookmark.updated;
            } catch (Exception ignored) {
            }
        }
        a.generation++;
        final int ticket = a.generation;
        a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (!a.isTvDevice()) {
            a.applyMobileSystemBars(true);
            a.setRequestedOrientation(
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        }
        a.root = new FrameLayout(a);
        a.root.setBackgroundColor(Color.BLACK);
        a.root.setFocusable(true);
        a.root.setFocusableInTouchMode(true);
        a.root.setTag("player-surface");
        a.scroll = null;
        VideoOutput surface = new VideoOutput(a, a.prefs.getInt(Prefs.VIDEO_OUTPUT, 0) == 1);
        a.playbackLayer = new FrameLayout(a);
        a.playbackLayer.setTag("playback-layer");
        a.root.addView(a.playbackLayer, new FrameLayout.LayoutParams(-1, -1));
        a.playbackLayer.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        a.playerChrome = new PlayerChrome(a, v, ep, "live".equals(s.key));
        if (!a.isTvDevice()) {
            final PlayerChrome chrome = a.playerChrome;
            a.root.setOnApplyWindowInsetsListener(
                    (view, insets) -> {
                        int left = insets.getSystemWindowInsetLeft();
                        int top = insets.getSystemWindowInsetTop();
                        int right = insets.getSystemWindowInsetRight();
                        int bottom = insets.getSystemWindowInsetBottom();
                        if (android.os.Build.VERSION.SDK_INT >= 30) {
                            android.graphics.Insets safe =
                                    insets.getInsets(
                                            android.view.WindowInsets.Type.systemBars()
                                                    | android.view.WindowInsets.Type.displayCutout()
                                                    | android.view.WindowInsets.Type
                                                            .systemGestures());
                            left = safe.left;
                            top = safe.top;
                            right = safe.right;
                            android.graphics.Insets navigation =
                                    insets.getInsetsIgnoringVisibility(
                                            android.view.WindowInsets.Type.navigationBars());
                            left = Math.max(left, navigation.left);
                            right = Math.max(right, navigation.right);
                            bottom = Math.max(safe.bottom, navigation.bottom);
                        } else if (android.os.Build.VERSION.SDK_INT >= 28
                                && insets.getDisplayCutout() != null) {
                            android.view.DisplayCutout cutout = insets.getDisplayCutout();
                            left = Math.max(left, cutout.getSafeInsetLeft());
                            top = Math.max(top, cutout.getSafeInsetTop());
                            right = Math.max(right, cutout.getSafeInsetRight());
                            bottom = Math.max(bottom, cutout.getSafeInsetBottom());
                        }
                        chrome.safeInsets(left, top, right, bottom);
                        return insets;
                    });
            a.root.requestApplyInsets();
        }
        a.playbackControls =
                new PlaybackControls(
                        a, a.playerChrome.dock, a.playerChrome.progress, surface, s, v, ep);
        TouchPlayerGesture.attach(a, surface);
        final PlaybackControls controls = a.playbackControls;
        a.root.addOnLayoutChangeListener(
                (view, l, t, r, b, ol, ot, or, ob) -> {
                    int w = r - l, h = b - t;
                    if (w > 0 && h > 0) {
                        if (w != a.usableWidth || h != a.usableHeight) {
                            a.usableWidth = w;
                            a.usableHeight = h;
                            view.requestApplyInsets();
                            view.post(
                                    () -> {
                                        if (ticket != a.generation || a.isFinishing()) return;
                                        if (a.playerChrome != null) a.playerChrome.relayout();
                                        if (a.playbackControls != null) {
                                            a.playbackControls.resize(
                                                    a.playbackControls.videoWidth,
                                                    a.playbackControls.videoHeight);
                                        }
                                    });
                        }
                    }
                });
        a.setContentView(a.root);
        if (!a.isTvDevice()) a.root.requestApplyInsets();
        surface.listen(
                new VideoOutput.Listener() {
                    Surface renderSurface;
                    int surfaceRevision;

                    public void available(Surface value) {
                        renderSurface = value;
                        final int revision = ++surfaceRevision;
                        if (ticket != a.generation) return;
                        if (a.player != null) {
                            a.player.setSurface(value);
                            return;
                        }
                        a.worker.execute(
                                () -> {
                                    MoviePlayer candidate = null;
                                    try {
                                        Map<String, String> mediaHeaders =
                                                Cms.inheritedHeaders(s.api, ep.url, s.headers);
                                        Cms.mergeHeaders(mediaHeaders, ep.headers);
                                        int engPref = a.prefs.getInt(Prefs.PLAYER_ENGINE, 0);
                                        candidate =
                                                ep.media.optBoolean("_systemFallback")
                                                                || engPref == 1
                                                        ? new SystemPlayer(a)
                                                        : ep.media.optBoolean("_softwareFallback")
                                                                ? new MoviePlayer(
                                                                        a,
                                                                        ep.media.optInt(
                                                                                "_fallbackMode", 2),
                                                                        engPref == 2)
                                                                : engPref == 2
                                                                        ? MoviePlayer.createIjk(a)
                                                                        : new MoviePlayer(a);
                                        candidate.configureDefaultAudio();
                                        candidate.setDataSource(
                                                a,
                                                Uri.parse(
                                                        Cms.isHttp(ep.url)
                                                                ? Cms.normalizeHttp(ep.url)
                                                                : ep.url),
                                                mediaHeaders);
                                        candidate.setOptions(ep.media);
                                        final MoviePlayer ready = candidate;
                                        a.ui.post(
                                                () -> {
                                                    if (ticket != a.generation
                                                            || revision != surfaceRevision
                                                            || a.isFinishing()
                                                            || renderSurface == null
                                                            || !renderSurface.isValid()) {
                                                        ready.release();
                                                        return;
                                                    }
                                                    a.player = ready;
                                                    ready.setSurface(renderSurface);
                                                    ready.bindSubtitles(
                                                            controls.subtitle,
                                                            text -> {
                                                                if (ticket == a.generation) {
                                                                    controls.lastSubtitle =
                                                                            String.valueOf(text);
                                                                }
                                                            });
                                                    ready.setOnPreparedListener(
                                                            mp -> {
                                                                if (ticket != a.generation) return;
                                                                a.prepared = true;
                                                                // 起播：锚定音频时钟。引擎若仍在后台加载，控制器会记住并在就绪后补做
                                                                com.pilipala.marcomovie.adskip.AdSkipController.get()
                                                                        .onPlaybackStart(mp.getCurrentPosition());
                                                                if (a.playerChrome != null)
                                                                    a.playerChrome.setLoading(
                                                                            false);
                                                                a.playing = a.foreground;
                                                                if (a.foreground) mp.start();
                                                                else mp.pause();
                                                                controls.prepared();
                                                                savePlaybackHistoryOnPrepared(a);
                                                                a.playStatus.setText(
                                                                        a.foreground
                                                                                ? ("live"
                                                                                                .equals(
                                                                                                        s.key)
                                                                                        ? (a
                                                                                                        .isTvDevice()
                                                                                                ? "正在直播 · 上下换台 · 菜单键工具条"
                                                                                                : "正在直播 · 单击显示选台工具栏")
                                                                                        : (a
                                                                                                        .isTvDevice()
                                                                                                ? "正在播放 · 确认暂停 · 上下键显示工具条"
                                                                                                : "正在播放 · 双击暂停 · 左右滑快进 · 上下滑调亮度音量"))
                                                                                : (a.isTvDevice()
                                                                                        ? "已暂停 · 按确认继续"
                                                                                        : "已暂停 · 双击继续"));
                                                                clock(a, ticket);
                                                                if (a.changingMiniEpisode) {
                                                                    a.changingMiniEpisode = false;
                                                                    minimizePlayback(a);
                                                                } else if (a.foreground) {
                                                                    if ("live".equals(s.key)) {
                                                                        showControls(a, false);
                                                                        a.toast(
                                                                                "频道 "
                                                                                        + (a.liveSession
                                                                                                        == null
                                                                                                ? ""
                                                                                                : (a.liveSession
                                                                                                                        .index
                                                                                                                + 1)
                                                                                                        + " · ")
                                                                                        + v.title);
                                                                    } else scheduleHide(a);
                                                                }
                                                            });
                                                    ready.setOnVideoSizeChangedListener(
                                                            (mp, w, h) -> {
                                                                if (ticket == a.generation)
                                                                    controls.resize(w, h);
                                                            });
                                                    ready.setOnCompletionListener(
                                                            mp -> {
                                                                if (ticket != a.generation) return;
                                                                if (a.playingBookmark != null) {
                                                                    int dur = ready.getDuration();
                                                                    if (dur > 0) {
                                                                        a.playingBookmark.duration =
                                                                                dur;
                                                                        a.playingBookmark.position =
                                                                                Math.max(
                                                                                        a.playingBookmark
                                                                                                .position,
                                                                                        dur);
                                                                    }
                                                                }
                                                                savePlaybackHistory(a, true);
                                                                a.playing = false;
                                                                stopEmbySession(a);
                                                                a.playStatus.setText("播放结束");
                                                                controls.completed();
                                                            });
                                                    ready.setOnErrorListener(
                                                            (mp, what, extra) -> {
                                                                if (ticket != a.generation)
                                                                    return true;
                                                                if (PlaybackFailure.decoderFallback(
                                                                                what)
                                                                        && a.prefs.getInt(
                                                                                        Prefs
                                                                                                .DECODER_MODE,
                                                                                        0)
                                                                                == 0) {
                                                                    if (!ep.media.optBoolean(
                                                                            "_softwareFallback")) {
                                                                        try {
                                                                            ep.media.put(
                                                                                    "_softwareFallback",
                                                                                    true);
                                                                            ep.media.put(
                                                                                    "_fallbackMode",
                                                                                    mp
                                                                                            .softFallbackMode());
                                                                        } catch (
                                                                                Exception ignored) {
                                                                        }
                                                                        a.resumeAt =
                                                                                mp
                                                                                        .getCurrentPosition();
                                                                        a.playStatus.setText(
                                                                                "正在尝试内置软解码…");
                                                                        a.ui.post(
                                                                                () -> {
                                                                                    if (ticket
                                                                                            == a.generation)
                                                                                        playerPage(
                                                                                                a,
                                                                                                s,
                                                                                                v,
                                                                                                ep);
                                                                                });
                                                                        return true;
                                                                    }
                                                                    if (!ep.media.optBoolean(
                                                                                    "_systemFallback")
                                                                            && a.prefs.getInt(
                                                                                            Prefs
                                                                                                    .PLAYER_ENGINE,
                                                                                            0)
                                                                                    != 1) {
                                                                        try {
                                                                            ep.media.put(
                                                                                    "_systemFallback",
                                                                                    true);
                                                                        } catch (
                                                                                Exception ignored) {
                                                                        }
                                                                        a.resumeAt =
                                                                                mp
                                                                                        .getCurrentPosition();
                                                                        a.playStatus.setText(
                                                                                "正在切换至系统硬件播放器…");
                                                                        a.ui.post(
                                                                                () -> {
                                                                                    if (ticket
                                                                                            == a.generation)
                                                                                        playerPage(
                                                                                                a,
                                                                                                s,
                                                                                                v,
                                                                                                ep);
                                                                                });
                                                                        return true;
                                                                    }
                                                                }
                                                                JSONArray qualities =
                                                                        ep.media.optJSONArray(
                                                                                "qualities");
                                                                int qIdx =
                                                                        ep.media.optInt(
                                                                                "_qualityIdx", 0);
                                                                if (qualities != null
                                                                        && qIdx + 1
                                                                                < qualities
                                                                                        .length()) {
                                                                    JSONObject nextQ =
                                                                            qualities.optJSONObject(
                                                                                    qIdx + 1);
                                                                    String nextUrl =
                                                                            nextQ != null
                                                                                    ? nextQ
                                                                                            .optString(
                                                                                                    "url",
                                                                                                    "")
                                                                                    : "";
                                                                    if (!nextUrl.isEmpty()) {
                                                                        try {
                                                                            ep.media.put(
                                                                                    "_qualityIdx",
                                                                                    qIdx + 1);
                                                                            ep.media.remove(
                                                                                    "_softwareFallback");
                                                                            ep.media.remove(
                                                                                    "_systemFallback");
                                                                        } catch (
                                                                                Exception ignored) {
                                                                        }
                                                                        ep.url =
                                                                                LocalProxy.rewrite(
                                                                                        nextUrl);
                                                                        JSONObject nextHeaders =
                                                                                nextQ.optJSONObject(
                                                                                        "headers");
                                                                        if (nextHeaders != null) {
                                                                            ep.headers.clear();
                                                                            java.util.Iterator<
                                                                                            String>
                                                                                    hk =
                                                                                            nextHeaders
                                                                                                    .keys();
                                                                            while (hk.hasNext()) {
                                                                                String k =
                                                                                        hk.next();
                                                                                String val =
                                                                                        nextHeaders
                                                                                                .optString(
                                                                                                        k,
                                                                                                        "");
                                                                                if (!val.isEmpty())
                                                                                    ep.headers.put(
                                                                                            k, val);
                                                                            }
                                                                        }
                                                                        String qName =
                                                                                nextQ.optString(
                                                                                        "name",
                                                                                        "备用线路");
                                                                        a.playStatus.setText(
                                                                                "正在切换至 " + qName
                                                                                        + "…");
                                                                        a.ui.post(
                                                                                () -> {
                                                                                    if (ticket
                                                                                            == a.generation)
                                                                                        playerPage(
                                                                                                a,
                                                                                                s,
                                                                                                v,
                                                                                                ep);
                                                                                });
                                                                        return true;
                                                                    }
                                                                }
                                                                if (v != null
                                                                        && v.lines != null
                                                                        && v.lines.size() > 1
                                                                        && a.activeLine != null) {
                                                                    int curLineIdx =
                                                                            v.lines.indexOf(
                                                                                    a.activeLine);
                                                                    int hopCount =
                                                                            ep.media.optInt(
                                                                                    "_lineHopCount",
                                                                                    0);
                                                                    if (curLineIdx >= 0
                                                                            && hopCount
                                                                                    < Math.min(
                                                                                            3,
                                                                                            v.lines
                                                                                                            .size()
                                                                                                    - 1)) {
                                                                        Cms.Line nextLine =
                                                                                v.lines.get(
                                                                                        (curLineIdx
                                                                                                        + 1)
                                                                                                % v
                                                                                                        .lines
                                                                                                        .size());
                                                                        Cms.Episode nextEp =
                                                                                findMatchingEpisode(
                                                                                        nextLine,
                                                                                        a.activeLine,
                                                                                        a.activeEpisode
                                                                                                        != null
                                                                                                ? a.activeEpisode
                                                                                                : ep);
                                                                        if (nextEp != null
                                                                                && nextLine
                                                                                        != a.activeLine) {
                                                                            try {
                                                                                nextEp.media.put(
                                                                                        "_lineHopCount",
                                                                                        hopCount
                                                                                                + 1);
                                                                            } catch (
                                                                                    Exception
                                                                                            ignored) {
                                                                            }
                                                                            a.resumeAt =
                                                                                    mp
                                                                                            .getCurrentPosition();
                                                                            a.playStatus.setText(
                                                                                    "当前线路异常，正在切换至 "
                                                                                            + nextLine.name
                                                                                            + "…");
                                                                            a.ui.post(
                                                                                    () -> {
                                                                                        if (ticket
                                                                                                == a.generation) {
                                                                                            rememberEpisodeOnBackStack(
                                                                                                    a,
                                                                                                    nextLine,
                                                                                                    nextEp);
                                                                                            resolvePlayer(
                                                                                                    a,
                                                                                                    s,
                                                                                                    v,
                                                                                                    nextLine,
                                                                                                    nextEp);
                                                                                        }
                                                                                    });
                                                                            return true;
                                                                        }
                                                                    }
                                                                }
                                                                stopEmbySession(a);
                                                                a.prepared = false;
                                                                a.playing = false;
                                                                if (a.playerChrome != null)
                                                                    a.playerChrome.setLoading(
                                                                            false);
                                                                a.playStatus.setText(
                                                                        mp.getFailureMessage(what)
                                                                                + "（"
                                                                                + what
                                                                                + "）");
                                                                showControls(a, true);
                                                                return true;
                                                            });
                                                    a.playStatus.setText("正在加载播放…");
                                                    ready.prepareAsync();
                                                });
                                    } catch (Exception ex) {
                                        AppLog.w("PlaybackController", "player init failed", ex);
                                        if (candidate != null) candidate.release();
                                        a.ui.post(
                                                () -> {
                                                    if (ticket == a.generation) {
                                                        if (a.playerChrome != null)
                                                            a.playerChrome.setLoading(false);
                                                        a.playStatus.setText(a.message(ex));
                                                        showControls(a, true);
                                                    }
                                                });
                                    }
                                });
                    }

                    public void destroyed() {
                        surfaceRevision++;
                        if (ticket == a.generation && a.player != null) a.player.setSurface(null);
                        renderSurface = null;
                    }
                });
        a.playbackControls.timeline.requestFocus();
        if ("live".equals(s.key)) showControls(a, false);
    }

    static void showControls(MainActivity a, boolean visible) {
        showControls(a, visible, false);
    }

    static void showControls(MainActivity a, boolean visible, boolean progressOnly) {
        if (!visible) finishSeek(a, false);
        if (a.miniMode && visible) return;
        boolean changed = a.controlsVisible != visible;
        a.controlsVisible = visible;
        if (a.playerChrome != null) {
            a.playerChrome.setProgressOnlyMode(visible && progressOnly && a.isTvDevice());
        }
        if (a.playbackControls != null) a.playbackControls.visible(visible);
        if (a.playerChrome != null) a.playerChrome.update();
        if (a.playerOverlay != null) {
            a.playerOverlay.animate().cancel();
            if (visible) {
                a.playerOverlay.setAlpha(1f);
                a.playerOverlay.setTranslationY(0f);
                a.playerOverlay.setVisibility(View.VISIBLE);
                if (changed && !a.reduced && !a.isTvDevice()) {
                    a.playerOverlay.setAlpha(0f);
                    a.playerOverlay.animate().alpha(1f).setDuration(150).start();
                }
            } else {
                if (a.reduced || a.isTvDevice()) {
                    a.playerOverlay.setAlpha(0f);
                    a.playerOverlay.setVisibility(View.GONE);
                } else {
                    long hideDur = 140;
                    UiCompat.startWithEndAction(
                            a.playerOverlay.animate().alpha(0f).setDuration(hideDur),
                            a.playerOverlay,
                            hideDur,
                            () -> {
                                if (!a.controlsVisible && a.playerOverlay != null)
                                    a.playerOverlay.setVisibility(View.GONE);
                            });
                }
                a.root.requestFocus();
            }
        }
        if (visible) {
            if (changed && a.playbackControls != null && a.isTvDevice()) {
                a.playbackControls.timeline.requestFocus();
            }
            scheduleHide(a);
        } else if (a.hideControls != null) a.ui.removeCallbacks(a.hideControls);
    }

    static void scheduleHide(MainActivity a) {
        if (a.hideControls != null) a.ui.removeCallbacks(a.hideControls);
        a.hideControls =
                () -> {
                    if (a.prepared
                            && a.playing
                            && (a.playbackControls == null || !a.playbackControls.dialogOpen))
                        showControls(a, false);
                };
        a.ui.postDelayed(a.hideControls, 5000);
    }

    static void clock(MainActivity a, int ticket) {
        if (ticket != a.generation || a.player == null || !a.prepared) return;
        savePlaybackHistory(a, false);
        // 每秒校正一次音频时钟：既能解除冷却，也能自动兜底所有未显式通知的 seek
        com.pilipala.marcomovie.adskip.AdSkipController.get()
                .onPositionUpdate(a.player.getCurrentPosition());
        try {
            if (a.embyReporter != null)
                a.embyReporter.update(a.player.getCurrentPosition(), !a.player.isPlaying());
            if (a.seekGesture.active) previewSeek(a);
            else if (a.playbackControls == null || !a.playbackControls.isUserSeeking()) {
                int rawPos = a.player.getCurrentPosition();
                int displayPos =
                        a.playbackControls != null
                                ? a.playbackControls.displayPosition(rawPos)
                                : rawPos;
                int dur = a.player.getDuration();
                if (dur <= 0 && a.playingBookmark != null && a.playingBookmark.duration > 0) {
                    dur = a.playingBookmark.duration;
                }
                a.playClock.setText(
                        formatTime(displayPos)
                                + " / "
                                + (dur > 0 ? formatTime(dur) : (a.isLive() ? "直播" : "--:--")));
                if (a.playbackControls != null) a.playbackControls.tick();
            }
            if (a.playerChrome != null) a.playerChrome.update();
        } catch (Exception ignored) {
        }
        a.ui.postDelayed(() -> clock(a, ticket), 1000);
    }

    static void togglePlayback(MainActivity a) {
        if (a.player == null || !a.prepared) return;
        if (a.embyReporter == null
                && a.playbackControls != null
                && a.playbackControls.site.api.startsWith(com.pilipala.emby.EmbyLibrary.PREFIX)) {
            a.resumeAt = -1;
            resolvePlayer(
                    a,
                    a.playbackControls.site,
                    a.playbackControls.video,
                    a.activeLine,
                    a.activeEpisode);
            return;
        }
        try {
            if (a.player.isPlaying()) {
                a.player.pause();
                a.playing = false;
                savePlaybackHistory(a, true);
                a.playStatus.setText(a.isTvDevice() ? "已暂停 · 按确认继续" : "已暂停 · 双击继续");
            } else {
                if (a.player.getDuration() > 0
                        && a.player.getCurrentPosition() >= a.player.getDuration() - 300)
                    a.player.seekTo(0);
                a.player.start();
                a.playing = true;
                a.playStatus.setText(
                        a.isTvDevice()
                                ? "正在播放 · 确认暂停 · 上下键显示工具条"
                                : "正在播放 · 双击暂停 · 左右滑快进 · 上下滑调亮度音量");
            }
            if (a.playerChrome != null) a.playerChrome.update();
            if (a.embyReporter != null)
                a.embyReporter.update(a.player.getCurrentPosition(), !a.playing);
        } catch (Exception error) {
            AppLog.w("PlaybackController", "togglePlayback failed", error);
        }
    }

    static void beginSeek(MainActivity a, int direction) {
        if (a.player == null || !a.prepared || a.player.getDuration() <= 0) return;
        int position = a.seekGesture.active ? a.seekGesture.target : a.player.getCurrentPosition();
        if (!a.seekGesture.active) a.seekStatus = a.playStatus.getText().toString();
        a.seekGesture.begin(
                direction, position, a.player.getDuration(), SystemClock.uptimeMillis());
        showControls(a, true, a.isTvDevice());
        if (a.playbackControls != null && a.isTvDevice()) {
            a.playbackControls.timeline.requestFocus();
        }
        previewSeek(a);
        a.ui.removeCallbacks(a.seekPulse);
        a.ui.postDelayed(a.seekPulse, 100);
    }

    static void previewSeek(MainActivity a) {
        if (!a.seekGesture.active || a.playbackControls == null) return;
        a.playbackControls.timeline.setProgress(a.seekGesture.target);
        a.playClock.setText(
                formatTime(a.seekGesture.target) + " / " + formatTime(a.seekGesture.duration));
        long now = SystemClock.uptimeMillis();
        int seekSpeed = a.seekGesture.speed(now);
        boolean accel = a.seekGesture.accelerated(now);
        String msg =
                (a.seekGesture.direction > 0 ? "快进" : "快退")
                        + (seekSpeed > 0
                                ? (accel ? " · 加速 " : " · ") + seekSpeed + " 秒/秒"
                                : " · 15 秒")
                        + " · 松开跳转";
        if (a.playStatus != null) a.playStatus.setText(msg);
        if (a.playerChrome != null && a.playerChrome.seekHint != null) {
            a.playerChrome.seekHint.setText(msg);
        }
    }

    static void finishSeek(MainActivity a, boolean commit) {
        if (!a.seekGesture.active) return;
        a.seekGesture.advance(SystemClock.uptimeMillis());
        int target = a.seekGesture.target;
        a.seekGesture.cancel();
        a.ui.removeCallbacks(a.seekPulse);
        if (commit && a.player != null && a.prepared) {
            a.player.seekTo(target);
            if (a.playingBookmark != null) {
                a.playingBookmark.position = Math.max(0, target);
            }
            savePlaybackHistory(a, true);
        }
        if (a.playStatus != null) a.playStatus.setText(a.seekStatus);
        if (a.playerChrome != null && a.playerChrome.seekHint != null) {
            a.playerChrome.seekHint.setText("");
        }
        if (a.player != null && a.prepared && a.playbackControls != null) {
            int position = commit ? target : a.player.getCurrentPosition();
            a.playbackControls.timeline.setProgress(position);
            a.playClock.setText(formatTime(position) + " / " + formatTime(a.player.getDuration()));
        }
        if (commit) scheduleHide(a);
    }

    static void savePlaybackHistoryOnPrepared(MainActivity a) {
        if (a.privateMode) return;
        if (a.player == null || !a.prepared || a.playingBookmark == null) return;
        try {
            int rawPos = a.player.getCurrentPosition();
            int displayPos =
                    a.playbackControls != null
                            ? a.playbackControls.displayPosition(rawPos)
                            : rawPos;
            if (a.playbackControls != null
                    && a.playbackControls.pendingResumeMs > 0
                    && displayPos < 2000) {
                displayPos = a.playbackControls.pendingResumeMs;
            }
            if (displayPos >= 1000 || a.playingBookmark.position <= 0) {
                a.playingBookmark.position = Math.max(0, displayPos);
            }
            int dur = a.player.getDuration();
            if (dur > 0) {
                a.playingBookmark.duration = dur;
            }
            a.playingBookmark.updated = System.currentTimeMillis();
            a.prefs
                    .edit()
                    .putString(
                            Prefs.WATCH_HISTORY,
                            WatchHistory.upsert(
                                    a.prefs.getString(Prefs.WATCH_HISTORY, "[]"),
                                    a.playingBookmark))
                    .commit();
            a.lastBookmarkSave = a.playingBookmark.updated;
        } catch (Exception error) {
            AppLog.w("PlaybackController", "savePlaybackHistoryOnPrepared failed", error);
        }
    }

    static void savePlaybackHistory(MainActivity a, boolean force) {
        if (a.privateMode) return;
        if (a.player == null || !a.prepared || a.playingBookmark == null) return;
        long now = System.currentTimeMillis();
        if (!force && now - a.lastBookmarkSave < 2000) return;
        try {
            int rawPos = a.player.getCurrentPosition();
            int position =
                    a.playbackControls != null
                            ? a.playbackControls.displayPosition(rawPos)
                            : rawPos;
            if (a.playbackControls != null
                    && a.playbackControls.pendingResumeMs > 0
                    && position < 2000) {
                position = a.playbackControls.pendingResumeMs;
            }
            if (position < 1000 && a.playingBookmark.position == 0) return;
            if (position < 1000 && a.playingBookmark.position >= 1000) {
                position = a.playingBookmark.position;
            }
            a.playingBookmark.position = Math.max(0, position);
            int dur = a.player.getDuration();
            if (dur > 0) {
                a.playingBookmark.duration = dur;
            }
            a.playingBookmark.updated = now;
            a.prefs
                    .edit()
                    .putString(
                            Prefs.WATCH_HISTORY,
                            WatchHistory.upsert(
                                    a.prefs.getString(Prefs.WATCH_HISTORY, "[]"),
                                    a.playingBookmark))
                    .commit();
            a.lastBookmarkSave = now;
        } catch (Exception error) {
            AppLog.w("PlaybackController", "savePlaybackHistory failed", error);
        }
    }

    static void stopEmbySession(MainActivity a) {
        if (a.embyReporter == null) return;
        int position = 0;
        try {
            if (a.player != null && a.prepared) position = a.player.getCurrentPosition();
        } catch (Exception ignored) {
        }
        a.embyReporter.stop(position);
        a.embyReporter = null;
    }

    static void releasePlayer(MainActivity a) {
        finishSeek(a, false);
        a.clearLiveNumber();
        savePlaybackHistory(a, true);
        stopEmbySession(a);
        a.miniMode = false;
        a.miniDetails = null;
        if (a.playbackControls != null) a.playbackControls.close();
        a.playbackControls = null;
        a.prepared = false;
        a.playing = false;
        if (a.player != null) {
            try {
                a.player.release();
            } catch (Exception error) {
                AppLog.w("PlaybackController", "player.release failed", error);
            }
            a.player = null;
        }
        a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    static boolean dispatchPlaybackKey(MainActivity a, KeyEvent event) {
        if (a.current == null
                || !a.current.player
                || a.miniMode
                || (a.playbackControls != null && a.playbackControls.dialogOpen)) return false;
        int key = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        View focus = a.getCurrentFocus();
        boolean horizontal =
                key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT;
        if (a.seekGesture.active && !horizontal && down)
            finishSeek(a, key != KeyEvent.KEYCODE_BACK);
        boolean confirm = key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER;
        if (confirm
                && (!a.controlsVisible
                        || focus == a.root
                        || a.playbackControls != null && focus == a.playbackControls.timeline)) {
            if (down && event.getRepeatCount() == 0) {
                togglePlayback(a);
                showControls(a, true);
            }
            return true;
        }
        if ((!a.controlsVisible || (a.playerChrome != null && a.playerChrome.progressOnlyMode))
                && (key == KeyEvent.KEYCODE_DPAD_UP
                        || key == KeyEvent.KEYCODE_DPAD_DOWN
                        || key == KeyEvent.KEYCODE_MENU)) {
            if (down) {
                showControls(a, true, false);
                if (a.playbackControls != null)
                    a.playbackControls.buttons.get("next-episode").requestFocus();
            }
            return true;
        }
        if (a.controlsVisible && key == KeyEvent.KEYCODE_MENU) {
            if (down && event.getRepeatCount() == 0) {
                if (a.playbackControls != null
                        && focus == a.playbackControls.timeline
                        && a.playbackControls.buttons.get("next-episode") != null) {
                    a.playbackControls.buttons.get("next-episode").requestFocus();
                } else {
                    showControls(a, false);
                }
            }
            return true;
        }
        if (horizontal
                && !a.isLive()
                && (a.seekGesture.active
                        || !a.controlsVisible
                        || focus == a.root
                        || a.playbackControls != null && focus == a.playbackControls.timeline)) {
            int direction = key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1;
            if (down) {
                if (!a.seekGesture.active && event.getRepeatCount() == 0
                        || a.seekGesture.active
                                && a.seekGesture.direction != direction
                                && event.getRepeatCount() == 0) beginSeek(a, direction);
            } else if (event.getAction() == KeyEvent.ACTION_UP
                    && a.seekGesture.active
                    && a.seekGesture.direction == direction) finishSeek(a, !event.isCanceled());
            return true;
        }
        if (key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                || key == KeyEvent.KEYCODE_MEDIA_PLAY
                || key == KeyEvent.KEYCODE_MEDIA_PAUSE) {
            if (down && event.getRepeatCount() == 0) {
                if (key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                        || (key == KeyEvent.KEYCODE_MEDIA_PLAY && !a.playing)
                        || (key == KeyEvent.KEYCODE_MEDIA_PAUSE && a.playing)) togglePlayback(a);
                showControls(a, true);
            }
            return true;
        }
        if (a.controlsVisible && down) scheduleHide(a);
        return false;
    }
}
