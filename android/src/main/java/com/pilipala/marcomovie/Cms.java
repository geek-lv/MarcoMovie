package com.pilipala.marcomovie;

import org.json.*;
import com.pilipala.source.Warehouse;
import java.io.*;
import java.net.*;
import java.util.*;

/** A bounded, non-executing adapter for standard TVBox type-1 JSON CMS sites. */
public final class Cms {
    public static final int MAX_JSON = 4 * 1024 * 1024;
    public static final String USER_AGENT = "okhttp/3.15";

    public static final class Parse {
        public String name = "", url = "";
        public int type;
        public final Map<String, String> headers = new LinkedHashMap<>();
    }

    public static final class Config {
        public final List<Site> sites = new ArrayList<>();
        public final List<Parse> parses = new ArrayList<>();
        public String raw;
        public String spider;
        public String origin = "", warehouse = "";
        public JSONArray depots = new JSONArray();
    }

    public static final class Site {
        public String key, name, api, playUrl, jar = "";
        public int type;
        public final Map<String, String> headers = new LinkedHashMap<>();
        public boolean requiresParser;
        public boolean searchable = true;
        public Object ext;

        public boolean supported() {
            return (type == 0 || type == 1) && isHttp(api);
        }

        public String limitation() {
            if (type == 3 || api.startsWith("csp_") || api.endsWith(".js"))
                return "此来源需要 Spider / JS / JAR 运行环境，基础版尚未支持。";
            if (type != 0 && type != 1) return "暂不支持此类型来源，已保留在列表中。";
            return "此来源不是可直接请求的 HTTP(S) CMS。";
        }
    }

    public static final class Category {
        public String id, name;

        public Category() {}

        public Category(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    public static final class Video {
        public String id,
                title,
                poster,
                remarks,
                year,
                area,
                genre,
                actor,
                director,
                description,
                action,
                tag;
        public final List<Line> lines = new ArrayList<>();
    }

    public static final class Line {
        public String name;
        public final List<Episode> episodes = new ArrayList<>();
    }

    public static final class Episode {
        public String name, url;
        public final Map<String, String> headers = new LinkedHashMap<>();
        public JSONObject media = new JSONObject();
    }

    public static final class Listing {
        public int page = 1, pageCount = 1;
        public final List<Video> videos = new ArrayList<>();
    }

    public static final class ImageRequest {
        public String url;
        public final Map<String, String> headers = new LinkedHashMap<>();
    }

    public static ImageRequest imageRequest(String value) {
        ImageRequest out = new ImageRequest();
        out.url = value;
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("@(?i:Referer|User-Agent)=").matcher(value);
        int first = -1;
        String key = null;
        int start = 0;
        while (m.find()) {
            if (first < 0) first = m.start();
            if (key != null) out.headers.put(key, value.substring(start, m.start()));
            key = value.substring(m.start() + 1, m.end() - 1);
            start = m.end();
        }
        if (key != null) {
            out.headers.put(key, value.substring(start));
            out.url = value.substring(0, first);
        }
        for (Iterator<Map.Entry<String, String>> it = out.headers.entrySet().iterator();
                it.hasNext(); ) {
            String val = it.next().getValue();
            if (val.contains("\r") || val.contains("\n")) it.remove();
        }
        return out;
    }

    public static boolean isMedia(String value) {
        if (isHttp(value)) return true;
        if (value == null) return false;
        String lower = value.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("rtsp://")
                || lower.startsWith("rtsps://")
                || lower.startsWith("rtmp://")
                || lower.startsWith("rtmps://")
                || lower.startsWith("rtmpt://")
                || lower.startsWith("rtmpe://")
                || lower.startsWith("rtmfp://")
                || lower.startsWith("mms://")
                || lower.startsWith("mmsh://")
                || lower.startsWith("mmst://")
                || lower.startsWith("udp://")
                || lower.startsWith("rtp://")
                || lower.startsWith("srt://")
                || lower.startsWith("rist://")
                || lower.startsWith("webrtc://")
                || lower.startsWith("proxy://")
                || lower.startsWith("file://")
                || lower.startsWith("content://");
    }

    public static boolean isDirectStreamUrl(String value) {
        if (value == null || value.isEmpty()) return false;
        String lower = value.trim().toLowerCase(Locale.ROOT);
        if (lower.startsWith("rtsp://")
                || lower.startsWith("rtsps://")
                || lower.startsWith("rtmp://")
                || lower.startsWith("rtmps://")
                || lower.startsWith("rtmpt://")
                || lower.startsWith("rtmpe://")
                || lower.startsWith("rtmfp://")
                || lower.startsWith("udp://")
                || lower.startsWith("rtp://")
                || lower.startsWith("mms://")
                || lower.startsWith("mmsh://")
                || lower.startsWith("mmst://")
                || lower.startsWith("srt://")
                || lower.startsWith("rist://")
                || lower.startsWith("webrtc://")
                || lower.startsWith("proxy://")
                || lower.startsWith("file://")
                || lower.startsWith("content://")
                || lower.startsWith("magnet:?")) return true;
        if (!isHttp(value)) return false;
        String path = value.split("[?#]", 2)[0].toLowerCase(Locale.ROOT);
        return path.endsWith(".m3u8")
                || path.endsWith(".mp4")
                || path.endsWith(".flv")
                || path.endsWith(".mkv")
                || path.endsWith(".ts")
                || path.endsWith(".webm")
                || path.endsWith(".mpd")
                || path.endsWith(".mov")
                || path.endsWith(".avi")
                || lower.contains(".m3u8?")
                || lower.contains(".mp4?")
                || lower.contains(".flv?")
                || lower.contains("/proxy?");
    }

    public static boolean isHttp(String value) {
        try {
            URL u = new URL(value);
            return (u.getProtocol().equals("http") || u.getProtocol().equals("https"))
                    && !u.getHost().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    static boolean sameOrigin(String first, String second) {
        if (!isHttp(first) || !isHttp(second)) return false;
        try {
            URL a = new URL(first), b = new URL(second);
            int ap = a.getPort() < 0 ? a.getDefaultPort() : a.getPort();
            int bp = b.getPort() < 0 ? b.getDefaultPort() : b.getPort();
            return a.getProtocol().equalsIgnoreCase(b.getProtocol())
                    && a.getHost().equalsIgnoreCase(b.getHost())
                    && ap == bp;
        } catch (MalformedURLException invalid) {
            return false;
        }
    }

    /** Source credentials belong to that origin; a play result may explicitly supply its own. */
    static Map<String, String> inheritedHeaders(
            String origin, String target, Map<String, String> input) {
        Map<String, String> out = new LinkedHashMap<>();
        boolean same = sameOrigin(origin, target);
        for (Map.Entry<String, String> entry : input.entrySet()) {
            String key = entry.getKey();
            if (same
                    || !(key.equalsIgnoreCase("Authorization")
                            || key.equalsIgnoreCase("Proxy-Authorization")
                            || key.equalsIgnoreCase("Cookie")
                            || key.equalsIgnoreCase("Cookie2"))) out.put(key, entry.getValue());
        }
        return out;
    }

    static void mergeHeaders(Map<String, String> target, Map<String, String> overrides) {
        for (Map.Entry<String, String> entry : overrides.entrySet()) {
            for (Iterator<String> keys = target.keySet().iterator(); keys.hasNext(); )
                if (keys.next().equalsIgnoreCase(entry.getKey())) keys.remove();
            target.put(entry.getKey(), entry.getValue());
        }
    }

    public static String normalizeHttp(String value) throws Exception {
        URL u = new URL(value);
        String host = u.getHost();
        if (!host.startsWith("[")) host = IDN.toASCII(host);
        return u.getProtocol()
                + "://"
                + (u.getUserInfo() != null ? u.getUserInfo() + "@" : "")
                + host
                + (u.getPort() >= 0 ? ":" + u.getPort() : "")
                + u.getFile()
                + (u.getRef() != null ? "#" + u.getRef() : "");
    }

    static String fileUri(java.io.File file) {
        return "file://" + file.toURI().getRawPath();
    }

    static String resolve(String origin, String target) {
        if (target == null) return "";
        if (isHttp(target)
                || target.isEmpty()
                || target.startsWith("file://")
                || target.startsWith("csp_")
                || origin == null
                || (!isHttp(origin) && !origin.startsWith("file://"))) return target;
        try {
            URL resolved = new URL(new URL(origin), target);
            return "file".equals(resolved.getProtocol()) && origin.startsWith("file://")
                    ? fileUri(new java.io.File(resolved.toURI()))
                    : resolved.toString();
        } catch (Exception e) {
            return target;
        }
    }

    public static boolean looksLikeJsSpider(String text, String origin) {
        String content = text == null ? "" : text.trim().replaceFirst("^\\uFEFF", "");
        if (content.startsWith("{") || content.startsWith("[") || content.startsWith("<"))
            return false;
        String cleanOrigin =
                origin == null ? "" : origin.split("[?#]", 2)[0].toLowerCase(Locale.ROOT);
        if (cleanOrigin.endsWith(".js") || cleanOrigin.endsWith(".mjs")) return true;
        if (text == null) return false;
        String t = text.trim();
        return t.contains("__jsEvalReturn")
                || t.contains("export default")
                || java.util.regex.Pattern.compile(
                                "\\bmodule\\s*\\.\\s*exports\\b|\\bexports\\s*(?:\\.|\\[)")
                        .matcher(t)
                        .find()
                || java.util.regex.Pattern.compile(
                                "\\bexport\\s+(?:(?:async\\s+)?function|const|let|var)\\s+(?:init|home|homeVod|category|search|detail|play)\\b")
                        .matcher(t)
                        .find()
                || java.util.regex.Pattern.compile("\\bexport\\s*(?:\\{|\\*)").matcher(t).find()
                || looksLikeDrpyRule(t);
    }

    static boolean looksLikeDrpyRule(String text) {
        if (text == null) return false;
        String content = scriptCode(text);
        if (java.util.regex.Pattern.compile("\\b__jsEvalReturn\\b").matcher(content).find()
                || java.util.regex.Pattern.compile(
                                "\\b(?:module\\s*(?:\\.\\s*exports\\b|\\[)|exports\\s*(?:\\.|\\[))|(?:^|[;}\\n])\\s*export\\b")
                        .matcher(content)
                        .find()) return false;
        return java.util.regex.Pattern.compile(
                        "(?:^|[;\\n])\\s*(?:var|let|const)\\s+rule\\s*=",
                        java.util.regex.Pattern.MULTILINE)
                .matcher(content)
                .find();
    }

    /** Classification only: omit comments and literals, never evaluate downloaded code. */
    private static String scriptCode(String source) {
        StringBuilder code = new StringBuilder(source.length());
        boolean regexAllowed = true;
        for (int i = 0; i < source.length(); ) {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : 0;
            if (c == '/' && (next == '/' || next == '*')) {
                boolean line = next == '/';
                i += 2;
                code.append(' ');
                while (i < source.length()) {
                    char current = source.charAt(i++);
                    if (current == '\n') {
                        code.append('\n');
                        if (line) break;
                    }
                    if (!line && current == '*' && i < source.length() && source.charAt(i) == '/') {
                        i++;
                        break;
                    }
                }
                continue;
            }
            if (c == '\'' || c == '"' || c == '`' || (c == '/' && regexAllowed)) {
                char quote = c;
                boolean escaped = false, characterClass = false;
                code.append(' ');
                i++;
                while (i < source.length()) {
                    char current = source.charAt(i++);
                    if (escaped) {
                        escaped = false;
                        continue;
                    }
                    if (current == '\\') {
                        escaped = true;
                        continue;
                    }
                    if (quote == '/') {
                        if (current == '[') characterClass = true;
                        if (current == ']') characterClass = false;
                    }
                    if (current == quote && !characterClass) break;
                }
                regexAllowed = false;
                continue;
            }
            if (Character.isJavaIdentifierStart(c)) {
                int start = i++;
                while (i < source.length() && Character.isJavaIdentifierPart(source.charAt(i))) i++;
                String token = source.substring(start, i);
                code.append(token);
                regexAllowed =
                        Arrays.asList(
                                        "return", "throw", "case", "delete", "typeof", "void",
                                        "new", "in", "of", "yield", "await")
                                .contains(token);
                continue;
            }
            code.append(c);
            i++;
            if (!Character.isWhitespace(c)) regexAllowed = "=(:,[!&|?;{}+*-~%<>".indexOf(c) >= 0;
        }
        return code.toString();
    }

    public static boolean looksLikePySpider(String text, String origin) {
        String content = text == null ? "" : text.trim().replaceFirst("^\\uFEFF", "");
        if (content.startsWith("{") || content.startsWith("[") || content.startsWith("<"))
            return false;
        String cleanOrigin =
                origin == null ? "" : origin.split("[?#]", 2)[0].toLowerCase(Locale.ROOT);
        if (cleanOrigin.endsWith(".py")) return true;
        if (text == null) return false;
        String t = text.trim();
        return (t.contains("class Spider") && t.contains("def "))
                || (t.contains("def homeContent") && t.contains("def playerContent"));
    }

    private static String extractScriptTitle(String text, String origin, String prefix) {
        if (text != null) {
            java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile(
                                    "(?:title|name)\\s*[:=]\\s*['\"]([^'\"\\r\\n]{1,32})['\"]")
                            .matcher(text);
            if (m.find()) return prefix + " · " + m.group(1).trim();
        }
        if (origin != null && !origin.isEmpty()) {
            String path = origin.split("[?#]", 2)[0];
            int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
            String file = slash >= 0 ? path.substring(slash + 1) : path;
            int dot = file.lastIndexOf('.');
            if (dot > 0) file = file.substring(0, dot);
            if (!file.isEmpty()) return prefix + " · " + file;
        }
        return prefix + " 扩展片源";
    }

    private static void copySafeHeaders(Map<String, String> target, Object headerObj) {
        if (headerObj instanceof String) {
            String str = ((String) headerObj).trim();
            if (str.startsWith("{")) {
                try {
                    headerObj = new JSONObject(str);
                } catch (Exception ignored) {
                    headerObj = null;
                }
            }
        }
        if (headerObj instanceof JSONObject) {
            JSONObject o = (JSONObject) headerObj;
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                String v = o.optString(k, "");
                if (k == null || v == null || v.isEmpty() || v.contains("\r") || v.contains("\n"))
                    continue;
                for (String allowed :
                        new String[] {
                            "User-Agent", "Referer", "Cookie", "Origin", "Authorization"
                        }) {
                    if (k.equalsIgnoreCase(allowed)) {
                        target.put(allowed, v);
                        break;
                    }
                }
            }
        }
    }

    public static Config parseConfig(String text, String origin) throws Exception {
        if (text == null || text.length() > MAX_JSON) throw new IOException("配置超过 4 MB 限制");
        String trimmed = text.trim().replaceFirst("^\\uFEFF", "");
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            if (origin != null
                    && isHttp(origin)
                    && !trimmed.startsWith("/")
                    && (trimmed.startsWith("#EXTM3U")
                            || trimmed.contains("#EXTINF:")
                            || trimmed.contains(",#genre#"))) {
                JSONObject synthetic = new JSONObject();
                JSONArray lives = new JSONArray();
                lives.put(new JSONObject().put("name", "电视直播").put("url", origin));
                synthetic.put("lives", lives);
                JSONArray sites = new JSONArray();
                sites.put(
                        new JSONObject()
                                .put("key", "live-only-placeholder")
                                .put("name", "仅直播源 (请在直播页观看)")
                                .put("type", 3)
                                .put("api", "csp_LivePlaceholder"));
                synthetic.put("sites", sites);
                trimmed = synthetic.toString();
                text = trimmed;
            } else if (looksLikeDrpyRule(trimmed) && (origin == null || origin.isEmpty())) {
                JSONObject rule =
                        new JSONObject()
                                .put("key", "drpy_" + Math.abs(trimmed.hashCode()))
                                .put("name", extractScriptTitle(trimmed, "", "DRPY"))
                                .put("type", 3)
                                .put("api", "csp_LocalRule.js")
                                .put("ext", new JSONObject().put("_mm_entrySource", trimmed));
                return parseConfig(
                        new JSONObject().put("sites", new JSONArray().put(rule)).toString(),
                        origin);
            } else if (looksLikeJsSpider(trimmed, origin)
                    && origin != null
                    && (isHttp(origin) || origin.startsWith("file://"))) {
                String title = extractScriptTitle(trimmed, origin, "JS");
                JSONObject siteObj =
                        new JSONObject()
                                .put("key", "js_" + Math.abs(origin.hashCode()))
                                .put("name", title)
                                .put("type", 3)
                                .put("api", origin)
                                .put("ext", new JSONObject().put("_mm_entrySource", trimmed))
                                .put("searchable", 1);
                JSONObject synth = new JSONObject().put("sites", new JSONArray().put(siteObj));
                return parseConfig(synth.toString(), origin);
            } else if (looksLikePySpider(trimmed, origin)) {
                String title = extractScriptTitle(trimmed, origin, "PY");
                JSONObject siteObj = new JSONObject();
                siteObj.put(
                        "key",
                        "py_"
                                + Math.abs(
                                        (origin == null || origin.isEmpty() ? trimmed : origin)
                                                .hashCode()));
                siteObj.put("name", title);
                siteObj.put("type", 3);
                if (origin != null
                        && origin.split("[?#]", 2)[0].toLowerCase(Locale.ROOT).endsWith(".py")) {
                    siteObj.put("api", origin);
                } else {
                    siteObj.put("api", "csp_LocalPython");
                    siteObj.put("ext", trimmed);
                }
                siteObj.put("searchable", 1);
                JSONObject synth = new JSONObject().put("sites", new JSONArray().put(siteObj));
                return parseConfig(synth.toString(), origin);
            }
        }
        String json = withoutComments(trimmed);
        JSONObject root;
        if (json.trim().startsWith("[")) {
            JSONArray array = new JSONArray(json);
            if (!isSiteArray(array)) throw new IOException("配置数组需要包含带 api 的来源对象");
            root = new JSONObject().put("sites", array);
        } else {
            root = new JSONObject(json);
        }
        JSONArray sites = root.optJSONArray("sites");
        if ((sites == null || sites.length() == 0)
                && root.has("api")
                && !root.optString("api").isEmpty()) {
            sites = new JSONArray().put(root);
        }
        if (Warehouse.isCatalog(root)) {
            String resolved =
                    Warehouse.resolve(
                            text,
                            origin,
                            url -> fetch(url, null),
                            raw -> new JSONObject(withoutComments(raw)),
                            (raw, base) -> parseConfig(raw, base));
            return parseConfig(resolved, origin);
        }
        if ((sites == null || sites.length() == 0)
                && root.optJSONArray("lives") != null
                && root.optJSONArray("lives").length() > 0) {
            sites = new JSONArray();
            sites.put(
                    new JSONObject()
                            .put("key", "live-only-placeholder")
                            .put("name", "仅直播配置 (请前往直播页)")
                            .put("type", 3)
                            .put("api", "csp_LivePlaceholder"));
        }
        origin = root.optString(Warehouse.ORIGIN, origin);
        if (sites == null || sites.length() == 0) throw new IOException("配置中没有 sites 来源列表");
        if (sites.length() > 500) throw new IOException("来源超过 500 个限制");
        Config out = new Config();
        out.raw = text;
        out.origin = origin == null ? "" : origin;
        out.warehouse = root.optString(Warehouse.ROOT, "");
        JSONArray directory = root.optJSONArray(Warehouse.DEPOTS);
        if (directory != null) out.depots = directory;
        String rawSpider = root.optString("spider", "");
        if (rawSpider.isEmpty()) rawSpider = root.optString("jar", "");
        if (!rawSpider.isEmpty()) {
            String[] spParts = rawSpider.split(";md5;", 2);
            out.spider =
                    resolve(origin, spParts[0]) + (spParts.length == 2 ? ";md5;" + spParts[1] : "");
        } else {
            out.spider = "";
        }
        JSONArray parses = root.optJSONArray("parses");
        if (parses != null) {
            for (int i = 0; i < Math.min(100, parses.length()); i++) {
                JSONObject pj = parses.optJSONObject(i);
                if (pj == null) continue;
                String pUrl = pj.optString("url", "").trim();
                if (!isHttp(pUrl)) continue;
                Parse p = new Parse();
                p.name = pj.optString("name", "解析 " + (i + 1));
                p.url = pUrl;
                p.type = pj.optInt("type", 0);
                p.headers.put("User-Agent", USER_AGENT);
                copySafeHeaders(p.headers, pj.opt("header"));
                copySafeHeaders(p.headers, pj.opt("headers"));
                JSONObject pExt = pj.optJSONObject("ext");
                if (pExt != null) {
                    copySafeHeaders(p.headers, pExt.opt("header"));
                    copySafeHeaders(p.headers, pExt.opt("headers"));
                }
                out.parses.add(p);
            }
        }
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < sites.length(); i++) {
            JSONObject j = sites.optJSONObject(i);
            if (j == null) continue;
            Site s = new Site();
            s.key = j.optString("key", "site-" + i);
            s.name = j.optString("name", s.key);
            if (!keys.add(s.key)) s.key = s.key + "-" + i;
            s.type = j.optInt("type", -1);
            s.api = resolve(origin, j.optString("api", ""));
            String rawJar = j.optString("jar", "");
            if (!rawJar.isEmpty()) {
                String[] jarParts = rawJar.split(";md5;", 2);
                s.jar =
                        resolve(origin, jarParts[0])
                                + (jarParts.length == 2 ? ";md5;" + jarParts[1] : "");
            }
            s.playUrl = j.optString("playUrl", "");
            s.searchable = j.optInt("searchable", 1) != 0;
            s.ext = j.opt("ext");
            if (origin != null && origin.startsWith("file://") && s.ext instanceof String) {
                String ext = (String) s.ext;
                if (ext.startsWith("./") || ext.startsWith("../")) s.ext = resolve(origin, ext);
            }
            s.requiresParser =
                    !s.playUrl.isEmpty() || j.optInt("parse", 0) == 1 || j.optInt("jx", 0) == 1;
            s.headers.put("User-Agent", USER_AGENT);
            Object h = j.opt("headers");
            if (h == null) h = j.opt("header");
            copySafeHeaders(s.headers, j.opt("header"));
            copySafeHeaders(s.headers, j.opt("headers"));
            String ua = j.optString("ua", "");
            if (!ua.isEmpty() && !ua.contains("\r") && !ua.contains("\n")) {
                s.headers.put("User-Agent", ua);
            }
            if (h instanceof JSONObject) {
                JSONObject o = (JSONObject) h;
                Iterator<String> it = o.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    if (k.equalsIgnoreCase("User-Agent")
                            || k.equalsIgnoreCase("Referer")
                            || (s.type == 4
                                    && k.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
                                    && !o.optString(k).contains("\r")
                                    && !o.optString(k).contains("\n")
                                    && !Arrays.asList(
                                                    "content-length",
                                                    "transfer-encoding",
                                                    "connection")
                                            .contains(k.toLowerCase(Locale.ROOT))))
                        s.headers.put(k, o.optString(k));
                }
            }
            if (s.ext instanceof JSONObject) {
                JSONObject extObj = (JSONObject) s.ext;
                copySafeHeaders(s.headers, extObj.opt("header"));
                copySafeHeaders(s.headers, extObj.opt("headers"));
                String extUa = extObj.optString("ua", "");
                if (!extUa.isEmpty() && !extUa.contains("\r") && !extUa.contains("\n")) {
                    s.headers.put("User-Agent", extUa);
                }
            }
            out.sites.add(s);
        }
        if (out.sites.isEmpty()) throw new IOException("配置没有可识别的站点");
        return out;
    }

    static boolean isSiteArray(JSONArray entries) {
        if (entries.length() == 0) return false;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject item = entries.optJSONObject(i);
            Object api = item == null ? null : item.opt("api");
            if (!(api instanceof String) || ((String) api).trim().isEmpty()) return false;
        }
        return true;
    }

    public static String withoutComments(String source) throws IOException {
        StringBuilder clean = new StringBuilder();
        boolean quoted = false, escaped = false;
        for (int i = 0; i < source.length(); i++) {
            char ch = source.charAt(i);
            if (quoted) {
                clean.append(ch);
                if (escaped) escaped = false;
                else if (ch == '\\') escaped = true;
                else if (ch == '"') quoted = false;
                continue;
            }
            if (ch == '"') {
                quoted = true;
                clean.append(ch);
                continue;
            }
            if (ch == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                while (i < source.length() && source.charAt(i) != '\n') i++;
                clean.append('\n');
                continue;
            }
            if (ch == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < source.length()
                        && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) i++;
                if (i + 1 >= source.length()) throw new IOException("配置注释没有结束");
                i++;
                clean.append(' ');
                continue;
            }
            clean.append(ch);
        }
        // Strip trailing commas before '}' or ']' outside string literals
        String noComments = clean.toString();
        StringBuilder out = new StringBuilder(noComments.length());
        quoted = false;
        escaped = false;
        for (int i = 0; i < noComments.length(); i++) {
            char ch = noComments.charAt(i);
            if (quoted) {
                out.append(ch);
                if (escaped) escaped = false;
                else if (ch == '\\') escaped = true;
                else if (ch == '"') quoted = false;
                continue;
            }
            if (ch == '"') {
                quoted = true;
                out.append(ch);
                continue;
            }
            if (ch == ',') {
                int j = i + 1;
                while (j < noComments.length() && Character.isWhitespace(noComments.charAt(j))) j++;
                if (j < noComments.length()
                        && (noComments.charAt(j) == '}' || noComments.charAt(j) == ']')) {
                    continue;
                }
            }
            out.append(ch);
        }
        return out.toString();
    }

    public static String read(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
            if (out.size() + n > limit) throw new IOException("响应超过大小限制");
            out.write(buf, 0, n);
        }
        return document(out.toByteArray());
    }

    private static byte[] gunzipIfNeeded(byte[] bytes) {
        if (bytes != null
                && bytes.length >= 2
                && (bytes[0] & 0xff) == 0x1f
                && (bytes[1] & 0xff) == 0x8b) {
            try (java.util.zip.GZIPInputStream gis =
                            new java.util.zip.GZIPInputStream(new ByteArrayInputStream(bytes));
                    ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = gis.read(buf)) != -1) {
                    if (bos.size() + n > MAX_JSON) break;
                    bos.write(buf, 0, n);
                }
                return bos.toByteArray();
            } catch (Exception ignored) {
            }
        }
        return bytes;
    }

    private static boolean looksLikeConfigPayload(String s) {
        if (s == null) return false;
        String t = s.trim().replaceFirst("^\\uFEFF", "");
        return t.startsWith("{")
                || t.startsWith("[")
                || t.startsWith("//")
                || t.startsWith("/*")
                || t.startsWith("#EXTM3U")
                || t.contains(",#genre#");
    }

    /** Some operator-provided TVBox configs are JPEG/PNG/GIF + '**' + Base64(JSON) or AES hex. */
    public static String document(byte[] bytes) throws IOException {
        bytes = gunzipIfNeeded(bytes);
        if (bytes.length >= 2 && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 216) {
            int end = -1;
            for (int i = 2; i < bytes.length - 1; i++)
                if ((bytes[i] & 255) == 255 && (bytes[i + 1] & 255) == 217) {
                    end = i + 2;
                    break;
                }
            int marker = -1;
            if (end >= 0)
                for (int i = end; i < Math.min(bytes.length - 1, end + 128); i++)
                    if (bytes[i] == '*' && bytes[i + 1] == '*') {
                        marker = i + 2;
                        break;
                    }
            if (marker < 0) {
                for (int i = bytes.length - 2; i >= 2; i--) {
                    if (bytes[i] == '*' && bytes[i + 1] == '*') {
                        marker = i + 2;
                        break;
                    }
                }
            }
            if (marker < 0) throw new IOException("配置图片中没有受支持的 JSON 载荷");
            return decodeBase64Range(bytes, marker, bytes.length);
        }
        // Check binary PNG / GIF / arbitrary carrier with '**' + Base64
        boolean isPngOrGif =
                (bytes.length >= 4
                                && (bytes[0] & 0xff) == 0x89
                                && bytes[1] == 'P'
                                && bytes[2] == 'N'
                                && bytes[3] == 'G')
                        || (bytes.length >= 4
                                && bytes[0] == 'G'
                                && bytes[1] == 'I'
                                && bytes[2] == 'F'
                                && bytes[3] == '8');
        if (isPngOrGif) {
            for (int i = bytes.length - 2; i >= 4; i--) {
                if (bytes[i] == '*' && bytes[i + 1] == '*') {
                    String decoded = decodeBase64Range(bytes, i + 2, bytes.length);
                    if (looksLikeConfigPayload(decoded)) return decoded;
                    break;
                }
            }
        }
        String text;
        if (bytes.length >= 2 && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 254)
            text = new String(bytes, 2, bytes.length - 2, "UTF-16LE");
        else if (bytes.length >= 2 && (bytes[0] & 255) == 254 && (bytes[1] & 255) == 255)
            text = new String(bytes, 2, bytes.length - 2, "UTF-16BE");
        else text = new String(bytes, "UTF-8");
        String trimmed = text.trim().replaceFirst("^\\uFEFF", "");
        if (looksLikeJsSpider(trimmed, "") || looksLikePySpider(trimmed, "")) return text;
        if (!trimmed.startsWith("{")
                && !trimmed.startsWith("[")
                && !trimmed.startsWith("/")
                && !trimmed.startsWith("<")) {
            int star = trimmed.lastIndexOf("**");
            if (star >= 0 && star + 2 < trimmed.length()) {
                try {
                    byte[] sub = trimmed.substring(star + 2).getBytes("US-ASCII");
                    String decoded = decodeBase64Range(sub, 0, sub.length);
                    if (looksLikeConfigPayload(decoded)) return decoded;
                } catch (Exception ignored) {
                }
            }
            String compactHex = trimmed.replaceAll("\\s+", "");
            if (compactHex.startsWith("2423")
                    && compactHex.length() > 36
                    && compactHex.length() % 2 == 0) {
                try {
                    String decrypted = decryptHexAes(compactHex);
                    if (looksLikeConfigPayload(decrypted)) return decrypted;
                } catch (Exception ignored) {
                }
            }
            if (trimmed.startsWith("eyJ") || (trimmed.length() >= 32 && !trimmed.contains(" "))) {
                try {
                    byte[] sub = trimmed.getBytes("US-ASCII");
                    String decoded = decodeBase64Range(sub, 0, sub.length);
                    if (looksLikeConfigPayload(decoded)) return decoded;
                } catch (Exception ignored) {
                }
            }
        }
        return text;
    }

    private static String decodeBase64Range(byte[] bytes, int start, int end) throws IOException {
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        int bits = 0, count = 0;
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        for (int i = start; i < end; i++) {
            char ch = (char) (bytes[i] & 255);
            if (ch == '=') break;
            if (Character.isWhitespace(ch)) continue;
            if (ch == '-') ch = '+';
            else if (ch == '_') ch = '/';
            int v = alphabet.indexOf(ch);
            if (v < 0) throw new IOException("配置图片中的 Base64 格式不受支持");
            bits = (bits << 6) | v;
            count += 6;
            if (count >= 8) {
                count -= 8;
                decoded.write((bits >> count) & 255);
            }
        }
        return new String(decoded.toByteArray(), "UTF-8");
    }

    private static String decryptHexAes(String hex) throws Exception {
        byte[] raw = new byte[hex.length() / 2];
        for (int i = 0; i < raw.length; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) return null;
            raw[i] = (byte) ((hi << 4) | lo);
        }
        String ascii = new String(raw, "ISO-8859-1");
        int endKey = ascii.indexOf("#$");
        if (!ascii.startsWith("$#") || endKey < 2 || ascii.length() < endKey + 2 + 16) return null;
        String keyPart = ascii.substring(2, endKey);
        String ivPart = ascii.substring(ascii.length() - 13);
        byte[] keyBytes = pad16(keyPart);
        byte[] ivBytes = pad16(ivPart);
        int cipherLen = raw.length - (endKey + 2) - 13;
        if (cipherLen <= 0 || cipherLen % 16 != 0) return null;
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(
                javax.crypto.Cipher.DECRYPT_MODE,
                new javax.crypto.spec.SecretKeySpec(keyBytes, "AES"),
                new javax.crypto.spec.IvParameterSpec(ivBytes));
        byte[] out = cipher.doFinal(raw, endKey + 2, cipherLen);
        return new String(out, "UTF-8");
    }

    private static byte[] pad16(String s) throws Exception {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < 16) sb.append('0');
        return sb.substring(0, 16).getBytes("UTF-8");
    }

    public static String fetch(String address, Map<String, String> headers) throws Exception {
        return fetch(address, headers, null);
    }

    /** A non-null form selects POST; redirects preserve the existing credential boundary. */
    public static String fetch(
            String address, Map<String, String> headers, Map<String, String> form)
            throws Exception {
        if (!isHttp(address)) throw new IOException("只支持 HTTP(S) 地址");
        URL next = new URL(normalizeHttp(address));
        Map<String, String> forwarded = new LinkedHashMap<>();
        byte[] body = null;
        if (form != null) {
            StringBuilder encoded = new StringBuilder();
            for (Map.Entry<String, String> item : form.entrySet()) {
                if (encoded.length() > 0) encoded.append('&');
                encoded.append(URLEncoder.encode(item.getKey(), "UTF-8"))
                        .append('=')
                        .append(URLEncoder.encode(item.getValue(), "UTF-8"));
            }
            body = encoded.toString().getBytes("UTF-8");
        }
        if (headers != null) mergeHeaders(forwarded, headers);
        if (next.getUserInfo() != null) {
            boolean explicit = false;
            for (String key : forwarded.keySet()) explicit |= key.equalsIgnoreCase("Authorization");
            if (!explicit) {
                String credentials =
                        URLDecoder.decode(next.getUserInfo().replace("+", "%2B"), "UTF-8");
                forwarded.put("Authorization", basicAuthorization(credentials));
            }
            next = new URL(next.getProtocol(), next.getHost(), next.getPort(), next.getFile());
        }
        for (int hop = 0; hop < 5; hop++) {
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
            HttpURLConnection c = (HttpURLConnection) next.openConnection();
            c.setConnectTimeout(12000);
            c.setReadTimeout(18000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", USER_AGENT);
            for (Map.Entry<String, String> e : forwarded.entrySet())
                c.setRequestProperty(e.getKey(), e.getValue());
            try {
                RequestScope.attach(c);
                if (body != null) {
                    c.setRequestMethod("POST");
                    c.setDoOutput(true);
                    c.setRequestProperty(
                            "Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                    c.setFixedLengthStreamingMode(body.length);
                    try (OutputStream output = c.getOutputStream()) {
                        output.write(body);
                    }
                }
                int status = c.getResponseCode();
                if (status == 301
                        || status == 302
                        || status == 303
                        || status == 307
                        || status == 308) {
                    String loc = c.getHeaderField("Location");
                    if (loc == null) throw new IOException("重定向缺少地址");
                    URL target = new URL(next, loc);
                    if (!isHttp(target.toString())) throw new IOException("不支持此重定向协议");
                    if (target.getUserInfo() != null) throw new IOException("来源重定向不能注入登录凭据");
                    forwarded = inheritedHeaders(next.toString(), target.toString(), forwarded);
                    if (status == 301 || status == 302 || status == 303) body = null;
                    next = target;
                    continue;
                }
                if (status < 200 || status >= 300) throw new IOException("来源返回 HTTP " + status);
                InputStream rawIn = c.getInputStream();
                if ("gzip".equalsIgnoreCase(c.getContentEncoding())) {
                    rawIn = new java.util.zip.GZIPInputStream(rawIn);
                }
                try (InputStream input = rawIn) {
                    return read(input, MAX_JSON);
                }
            } finally {
                RequestScope.detach(c);
                c.disconnect();
            }
        }
        throw new IOException("来源重定向次数过多");
    }

    // Keep this parser usable in the JVM checks and on Android 23 (java.util.Base64 is API 26).
    static String basicAuthorization(String credentials) throws IOException {
        byte[] input = credentials.getBytes("UTF-8");
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        StringBuilder result = new StringBuilder("Basic ");
        for (int i = 0; i < input.length; i += 3) {
            int a = input[i] & 255,
                    b = i + 1 < input.length ? input[i + 1] & 255 : 0,
                    c = i + 2 < input.length ? input[i + 2] & 255 : 0;
            result.append(alphabet.charAt(a >> 2))
                    .append(alphabet.charAt(((a & 3) << 4) | (b >> 4)))
                    .append(
                            i + 1 < input.length
                                    ? alphabet.charAt(((b & 15) << 2) | (c >> 6))
                                    : '=')
                    .append(i + 2 < input.length ? alphabet.charAt(c & 63) : '=');
        }
        return result.toString();
    }

    public static String query(String address, String... pairs) throws Exception {
        URI u = new URI(address);
        String existing = u.getRawQuery();
        List<String> p = new ArrayList<>();
        Set<String> replaced = new HashSet<>();
        for (int i = 0; i < pairs.length; i += 2) replaced.add(pairs[i]);
        if (existing != null)
            for (String part : existing.split("&")) {
                String key = URLDecoder.decode(part.split("=", 2)[0], "UTF-8");
                if (!replaced.contains(key)) p.add(part);
            }
        for (int i = 0; i < pairs.length; i += 2)
            p.add(
                    URLEncoder.encode(pairs[i], "UTF-8")
                            + "="
                            + URLEncoder.encode(pairs[i + 1], "UTF-8"));
        String base = address.split("[?#]", 2)[0];
        StringBuilder joined = new StringBuilder();
        for (String part : p) {
            if (joined.length() > 0) joined.append('&');
            joined.append(part);
        }
        return base + "?" + joined;
    }

    private static String unescapeXml(String text) {
        if (text == null || text.isEmpty()) return "";
        String s = text.trim();
        if (s.startsWith("<![CDATA[") && s.endsWith("]]>")) {
            s = s.substring(9, s.length() - 3).trim();
        } else {
            s = s.replaceAll("<!\\[CDATA\\[([\\s\\S]*?)\\]\\]>", "$1");
        }
        return s.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&#39;", "'")
                .trim();
    }

    private static String xmlTagValue(String xmlBlock, String tag) {
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile(
                                "<" + tag + "\\b[^>]*>([\\s\\S]*?)</" + tag + ">",
                                java.util.regex.Pattern.CASE_INSENSITIVE)
                        .matcher(xmlBlock);
        return m.find() ? unescapeXml(m.group(1)) : "";
    }

    public static List<Category> categories(String json) throws Exception {
        if (json != null && json.trim().startsWith("<")) {
            List<Category> result = new ArrayList<>();
            java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile(
                                    "<ty\\b[^>]*\\bid\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>([\\s\\S]*?)</ty>",
                                    java.util.regex.Pattern.CASE_INSENSITIVE)
                            .matcher(json);
            while (m.find()) {
                Category c = new Category();
                c.id = m.group(1).trim();
                c.name = unescapeXml(m.group(2));
                if (c.name.isEmpty()) c.name = c.id;
                if (!c.id.isEmpty()) result.add(c);
            }
            return result;
        }
        JSONObject o = new JSONObject(json);
        JSONArray a = o.optJSONArray("class");
        List<Category> result = new ArrayList<>();
        if (a != null)
            for (int i = 0; i < a.length(); i++) {
                JSONObject j = a.optJSONObject(i);
                if (j == null) continue;
                Category c = new Category();
                c.id = j.optString("type_id");
                c.name = j.optString("type_name", c.id);
                if (!c.id.isEmpty()) result.add(c);
            }
        return result;
    }

    public static String searchTitle(Video v) {
        if (v.id != null && v.id.startsWith("msearch:")) {
            String[] parts = v.id.substring(8).split("###", -1);
            for (String part : parts) {
                String text = part.trim();
                // Some catalogue spiders put a Douban/TMDB id before the title.
                // That id is metadata, not a title to prefill into global search.
                if (!text.isEmpty() && !isHttp(text) && !text.matches("[0-9]+")) return text;
            }
        }
        return v.title == null ? "" : v.title.trim();
    }

    private static void parsePlayLines(Video v, String[] from, String[] urls) {
        for (int i = 0; i < urls.length; i++) {
            if (urls[i] == null || urls[i].trim().isEmpty()) continue;
            Line l = new Line();
            l.name =
                    i < from.length && from[i] != null && !from[i].trim().isEmpty()
                            ? from[i].trim()
                            : "线路 " + (i + 1);
            for (String part : urls[i].split("#")) {
                if (part.trim().isEmpty()) continue;
                int at = part.indexOf('$');
                Episode e = new Episode();
                e.name = at >= 0 ? part.substring(0, at) : "播放";
                e.url = at >= 0 ? part.substring(at + 1) : part;
                l.episodes.add(e);
            }
            if (!l.episodes.isEmpty()) v.lines.add(l);
        }
    }

    private static void prioritizeDirectStreamLines(Video v) {
        if (v == null || v.lines.size() <= 1) return;
        List<Line> direct = new ArrayList<>();
        List<Line> indirect = new ArrayList<>();
        for (Line l : v.lines) {
            boolean hasDirect =
                    !l.episodes.isEmpty()
                            && (isDirectStreamUrl(l.episodes.get(0).url)
                                    || (l.name != null
                                            && l.name.toLowerCase(Locale.ROOT).contains("m3u8")));
            if (hasDirect) direct.add(l);
            else indirect.add(l);
        }
        if (!direct.isEmpty() && !indirect.isEmpty()) {
            v.lines.clear();
            v.lines.addAll(direct);
            v.lines.addAll(indirect);
        }
    }

    private static Video video(JSONObject j) {
        Video v = new Video();
        v.id = j.optString("vod_id");
        v.title = j.optString("vod_name", "未命名影片");
        v.poster = j.optString("vod_pic");
        v.remarks = j.optString("vod_remarks");
        Object action = j.opt("action");
        v.action = action == null || action == JSONObject.NULL ? "" : action.toString();
        v.tag = j.optString("vod_tag");
        if (v.action.isEmpty()
                && "action".equalsIgnoreCase(v.tag)
                && v.id != null
                && !v.id.isEmpty()) {
            v.action = v.id;
        }
        v.year = j.optString("vod_year");
        v.area = j.optString("vod_area");
        v.genre = j.optString("vod_class", j.optString("type_name"));
        v.actor = j.optString("vod_actor");
        v.director = j.optString("vod_director");
        v.description =
                j.optString("vod_content").replaceAll("<[^>]*>", " ").replace("&nbsp;", " ").trim();
        String[] from = j.optString("vod_play_from").split("\\$\\$\\$", -1),
                urls = j.optString("vod_play_url").split("\\$\\$\\$", -1);
        parsePlayLines(v, from, urls);
        prioritizeDirectStreamLines(v);
        return v;
    }

    private static Video videoXml(String block) {
        Video v = new Video();
        v.id = xmlTagValue(block, "id");
        if (v.id.isEmpty()) v.id = xmlTagValue(block, "vod_id");
        v.title = xmlTagValue(block, "name");
        if (v.title.isEmpty()) v.title = xmlTagValue(block, "vod_name");
        if (v.title.isEmpty()) v.title = "未命名影片";
        v.poster = xmlTagValue(block, "pic");
        if (v.poster.isEmpty()) v.poster = xmlTagValue(block, "vod_pic");
        v.remarks = xmlTagValue(block, "note");
        if (v.remarks.isEmpty()) v.remarks = xmlTagValue(block, "vod_remarks");
        v.action = "";
        v.tag = xmlTagValue(block, "type");
        v.year = xmlTagValue(block, "year");
        v.area = xmlTagValue(block, "area");
        v.actor = xmlTagValue(block, "actor");
        v.director = xmlTagValue(block, "director");
        String des = xmlTagValue(block, "des");
        if (des.isEmpty()) des = xmlTagValue(block, "vod_content");
        v.description = des.replaceAll("<[^>]*>", " ").replace("&nbsp;", " ").trim();

        java.util.regex.Matcher ddMatcher =
                java.util.regex.Pattern.compile(
                                "<dd\\b([^>]*)>([\\s\\S]*?)</dd>",
                                java.util.regex.Pattern.CASE_INSENSITIVE)
                        .matcher(block);
        int lineIdx = 0;
        while (ddMatcher.find()) {
            String attrs = ddMatcher.group(1);
            String body = unescapeXml(ddMatcher.group(2));
            if (body.isEmpty()) continue;
            String flag = "";
            java.util.regex.Matcher flagM =
                    java.util.regex.Pattern.compile(
                                    "flag\\s*=\\s*[\"']([^\"']+)[\"']",
                                    java.util.regex.Pattern.CASE_INSENSITIVE)
                            .matcher(attrs);
            if (flagM.find()) flag = flagM.group(1).trim();
            String[] subUrls = body.split("\\$\\$\\$", -1);
            String[] subFrom = new String[subUrls.length];
            for (int i = 0; i < subUrls.length; i++) {
                lineIdx++;
                subFrom[i] =
                        !flag.isEmpty()
                                ? (subUrls.length > 1 ? flag + " " + (i + 1) : flag)
                                : ("线路 " + lineIdx);
            }
            parsePlayLines(v, subFrom, subUrls);
        }
        prioritizeDirectStreamLines(v);
        return v;
    }

    /** 无来源上下文：解析后做兜底条目过滤（覆盖所有列表 / 搜索 / 详情 / 手机端路径）。 */
    public static Listing listing(String json) throws Exception {
        return parseListing(json, null);
    }

    /**
     * 带来源上下文：解析后做「整块判定 + 成人来源累计标记」。
     * 可疑来源在交付界面之前整块丢弃，实现「首次搜索就不露头」。
     */
    public static Listing listing(String json, Site site) throws Exception {
        return parseListing(json, site);
    }

    private static Listing parseListing(String json, Site site) throws Exception {
        Listing l = new Listing();
        if (json != null && json.trim().startsWith("<")) {
            java.util.regex.Matcher listM =
                    java.util.regex.Pattern.compile(
                                    "<list\\b([^>]*)>", java.util.regex.Pattern.CASE_INSENSITIVE)
                            .matcher(json);
            if (listM.find()) {
                String attrs = listM.group(1);
                java.util.regex.Matcher pgM =
                        java.util.regex.Pattern.compile(
                                        "\\bpage\\s*=\\s*[\"']?(\\d+)",
                                        java.util.regex.Pattern.CASE_INSENSITIVE)
                                .matcher(attrs);
                if (pgM.find()) {
                    try {
                        l.page = Math.max(1, Integer.parseInt(pgM.group(1)));
                    } catch (Exception ignored) {
                    }
                }
                java.util.regex.Matcher pcM =
                        java.util.regex.Pattern.compile(
                                        "\\bpagecount\\s*=\\s*[\"']?(\\d+)",
                                        java.util.regex.Pattern.CASE_INSENSITIVE)
                                .matcher(attrs);
                if (pcM.find()) {
                    try {
                        l.pageCount = Math.max(l.page, Integer.parseInt(pcM.group(1)));
                    } catch (Exception ignored) {
                    }
                }
            }
            java.util.regex.Matcher vidM =
                    java.util.regex.Pattern.compile(
                                    "<video\\b[^>]*>([\\s\\S]*?)</video>",
                                    java.util.regex.Pattern.CASE_INSENSITIVE)
                            .matcher(json);
            while (vidM.find()) {
                if (l.videos.size() >= 500) throw new IOException("单页超过 500 项限制");
                Video v = videoXml(vidM.group(1));
                if (v.id != null && !v.id.isEmpty()) l.videos.add(v);
            }
        } else {
            JSONObject o = new JSONObject(json);
            l.page = Math.max(1, o.optInt("page", 1));
            l.pageCount = Math.max(l.page, o.optInt("pagecount", l.page));
            JSONArray a = o.optJSONArray("list");
            if (a == null) throw new IOException("来源没有返回 JSON list");
            if (a.length() > 500) throw new IOException("单页超过 500 项限制");
            for (int i = 0; i < a.length(); i++) {
                JSONObject j = a.optJSONObject(i);
                if (j != null) l.videos.add(video(j));
            }
        }
        if (site != null) Safety.applyBlock(site.key, site.name, l.videos);
        else Safety.filterItems(l.videos);
        return l;
    }

    public static void assertMedia(String address, Map<String, String> headers) throws Exception {
        if (!isHttp(address)) throw new IOException("此剧集不是 HTTP(S) 直连媒体，基础版不能播放");
        HttpURLConnection c = (HttpURLConnection) new URL(normalizeHttp(address)).openConnection();
        c.setRequestMethod("HEAD");
        c.setConnectTimeout(12000);
        c.setReadTimeout(12000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", USER_AGENT);
        for (Map.Entry<String, String> e : headers.entrySet())
            c.setRequestProperty(e.getKey(), e.getValue());
        try {
            int status = c.getResponseCode();
            String type = c.getContentType();
            if (type != null
                    && (type.toLowerCase(Locale.ROOT).contains("text/html")
                            || type.toLowerCase(Locale.ROOT).contains("application/json")))
                throw new IOException("剧集返回网页或 JSON，需要额外解析，基础版尚未支持");
            if (status >= 400 && status != 405) throw new IOException("媒体地址返回 HTTP " + status);
        } finally {
            c.disconnect();
        }
    }

    private Cms() {}
}
