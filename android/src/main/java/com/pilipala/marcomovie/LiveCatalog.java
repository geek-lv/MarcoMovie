package com.pilipala.marcomovie;

import java.io.*;
import java.net.*;
import java.nio.charset.Charset;
import java.util.*;
import java.util.regex.*;
import java.util.zip.GZIPInputStream;
import org.json.*;

/** Parses operator-supplied live lists. A parsed stream is not a playback success. */
public final class LiveCatalog {
    private LiveCatalog() {}

    public static final int MAX_BYTES = 8 * 1024 * 1024, MAX_CHANNELS = 12000, MAX_LINES = 60000;

    public static final class Source {
        public String name = "直播", url = "", origin = "";
        public int timeoutMs = 15000;
        public Object inline;
        public Cms.Site plugin;
        public final Map<String, String> headers = new LinkedHashMap<>(),
                options = new LinkedHashMap<>();

        public boolean isLocalProxy() {
            if (liveDirectoryQuery(url) != null) return false;
            return ProxyAddress.isSourceProxy(url);
        }
    }

    public interface PluginLoader {
        String load(Cms.Site site, String url) throws Exception;
    }

    public static final class Catalog {
        public String origin = "";
        public final List<Group> groups = new ArrayList<>();
        public final Map<String, String> options = new LinkedHashMap<>();
        public final List<String> warnings = new ArrayList<>();

        public int channelCount() {
            int n = 0;
            for (Group g : groups) n += g.channels.size();
            return n;
        }

        public int streamCount() {
            int n = 0;
            for (Group g : groups) for (Channel c : g.channels) n += c.streams.size();
            return n;
        }
    }

    public static final class Group {
        public String name;
        public final List<Channel> channels = new ArrayList<>();
    }

    public static final class Channel {
        public String name = "", id = "", logo = "";
        public final List<Stream> streams = new ArrayList<>();
        public final Map<String, String> options = new LinkedHashMap<>();
    }

    public static final class Stream {
        public String url = "", label = "";
        public final Map<String, String> headers = new LinkedHashMap<>(),
                options = new LinkedHashMap<>();

        public boolean isHttp() {
            return Cms.isHttp(url);
        }
    }

    public static List<Source> sourcesFor(
            String configRaw, String configOrigin, String customUrl, String customName) {
        List<Source> out = new ArrayList<>();
        String trimmedCustom = customUrl == null ? "" : customUrl.trim();
        if (!trimmedCustom.isEmpty()) {
            Source custom = new Source();
            custom.name = customName == null ? "" : customName.trim();
            if (custom.name.isEmpty()) custom.name = "自定义直播源";
            custom.url = trimmedCustom;
            custom.origin = trimmedCustom;
            custom.headers.put("User-Agent", Cms.USER_AGENT);
            out.add(custom);
        }
        if (configRaw != null && !configRaw.isEmpty()) {
            try {
                out.addAll(sources(configRaw, configOrigin));
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    public static List<Source> sources(String configJson, String origin) throws Exception {
        if (configJson == null || configJson.length() > MAX_BYTES)
            throw new IOException("直播配置为空或超过大小限制");
        Object root =
                new JSONTokener(Cms.withoutComments(configJson.replaceFirst("^\\uFEFF", "")))
                        .nextValue();
        Object value = root instanceof JSONObject ? ((JSONObject) root).opt("lives") : root;
        if (value == null || value == JSONObject.NULL) return Collections.emptyList();
        JSONArray list =
                value instanceof JSONArray ? (JSONArray) value : new JSONArray().put(value);
        List<Source> out = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            Object item = list.opt(i);
            Source s = new Source();
            s.origin = origin == null ? "" : origin;
            if (item instanceof String) {
                s.name = "直播 " + (i + 1);
                s.url = resolve(origin, (String) item);
            } else if (item instanceof JSONObject) {
                JSONObject j = (JSONObject) item;
                s.name = j.optString("name", "直播 " + (i + 1));
                s.options.putAll(attributes(j));
                copyHeaders(s.headers, j.opt("headers"));
                copyHeaders(s.headers, j.opt("header"));
                header(s.headers, "User-Agent", j.optString("ua", ""));
                header(s.headers, "Referer", j.optString("referer", ""));
                header(s.headers, "Origin", j.optString("origin", ""));
                int seconds = j.optInt("timeout", 15);
                s.timeoutMs = Math.max(1000, Math.min(30000, seconds * 1000));
                s.url = resolve(origin, j.optString("url", ""));
                if (!j.optString("api", "").trim().isEmpty()) {
                    // liveContent owns this argument; it may be an opaque ID, not a URL.
                    s.url = j.optString("url", "");
                    JSONObject definition = new JSONObject(j.toString());
                    String identity = s.origin + "\n" + i + "\n" + j.toString();
                    definition.put(
                            "key",
                            "live-plugin:" + UUID.nameUUIDFromBytes(identity.getBytes("UTF-8")));
                    definition.put("type", 3);
                    definition.put("header", new JSONObject(s.headers));
                    if (!definition.has("jar") || definition.optString("jar").isEmpty())
                        definition.put(
                                "jar",
                                root instanceof JSONObject
                                        ? ((JSONObject) root).optString("spider", "")
                                        : "");
                    s.plugin =
                            Cms.parseConfig(
                                            new JSONObject()
                                                    .put("sites", new JSONArray().put(definition))
                                                    .toString(),
                                            s.origin)
                                    .sites
                                    .get(0);
                    if (s.plugin.ext instanceof String) {
                        String ext = (String) s.plugin.ext;
                        if (ext.startsWith("./") || ext.startsWith("../"))
                            s.plugin.ext = Cms.resolve(s.origin, ext);
                    }
                }
                if (j.has("groups") || j.has("channels")) {
                    JSONArray groups = j.optJSONArray("groups"),
                            channels = j.optJSONArray("channels");
                    if (s.plugin == null
                            || (groups != null && groups.length() > 0)
                            || (channels != null && channels.length() > 0)) s.inline = j;
                }
            } else continue;
            if (!s.headers.containsKey("User-Agent")) s.headers.put("User-Agent", Cms.USER_AGENT);
            if (s.inline != null && s.plugin == null)
                s.inline = separateDirectories(s.inline, s, out, 0);
            if (s.inline != null || !s.url.isEmpty() || s.plugin != null) out.add(s);
            if (out.size() > MAX_CHANNELS) throw new IOException("直播目录数量过多");
        }
        return out;
    }

    public static String unwrapProxyExtUrl(String url) {
        try {
            String dirUrl = liveDirectoryUrl(url, "");
            if (dirUrl != null && !dirUrl.equals(url) && Cms.isHttp(dirUrl)) return dirUrl;
        } catch (Exception ignored) {
        }
        return "";
    }

    public static Catalog load(Source source) throws Exception {
        return load(source, null);
    }

    public static Catalog load(Source source, PluginLoader loader) throws Exception {
        if (source.inline != null)
            return parse(source.inline.toString(), source.origin, source.headers);
        if (source.plugin != null) {
            if (loader == null) throw new IOException("此直播目录需要来源扩展引擎");
            return parse(loader.load(source.plugin, source.url), source.origin, source.headers);
        }
        String sourceUrl = liveDirectoryUrl(source.url, source.origin);
        if (sourceUrl != null && sourceUrl.startsWith("file://")) {
            File localFile = new File(URI.create(sourceUrl));
            try (InputStream in = new FileInputStream(localFile)) {
                return parse(Cms.read(in, MAX_BYTES), sourceUrl, source.headers);
            }
        }
        if (!Cms.isHttp(sourceUrl)) throw new IOException("直播列表地址不是 HTTP(S)");
        URL next = new URL(Cms.normalizeHttp(sourceUrl));
        long deadline = System.nanoTime() + source.timeoutMs * 1000000L;
        Map<String, String> requestHeaders = new LinkedHashMap<>(source.headers);
        for (int hop = 0; hop < 6; hop++) {
            long remaining = (deadline - System.nanoTime()) / 1000000L;
            if (remaining <= 0) throw new SocketTimeoutException("直播列表请求超时");
            HttpURLConnection connection = (HttpURLConnection) next.openConnection();
            connection.setConnectTimeout((int) Math.min(remaining, 12000));
            connection.setReadTimeout((int) Math.min(remaining, 12000));
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept-Encoding", "gzip");
            for (Map.Entry<String, String> h :
                    ProxyAddress.requestHeaders(next.toString(), requestHeaders).entrySet())
                connection.setRequestProperty(h.getKey(), h.getValue());
            try {
                RequestScope.attach(connection);
                int status = connection.getResponseCode();
                if (status == 301
                        || status == 302
                        || status == 303
                        || status == 307
                        || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("直播列表重定向没有地址");
                    URL target = new URL(next, location);
                    if (!Cms.isHttp(target.toString())) throw new IOException("直播列表重定向协议不受支持");
                    if (!sameOrigin(next, target)) {
                        Iterator<String> keys = requestHeaders.keySet().iterator();
                        while (keys.hasNext()) {
                            String key = keys.next();
                            if (key.equalsIgnoreCase("Cookie")
                                    || key.equalsIgnoreCase("Authorization")) keys.remove();
                        }
                    }
                    next = target;
                    continue;
                }
                if (status < 200 || status >= 300) throw new IOException("直播列表返回 HTTP " + status);
                String mime = connection.getContentType();
                InputStream input = connection.getInputStream();
                if ("gzip".equalsIgnoreCase(connection.getContentEncoding()))
                    input = new GZIPInputStream(input);
                byte[] bytes;
                try (InputStream in = input;
                        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        if (System.nanoTime() > deadline)
                            throw new SocketTimeoutException("直播列表请求超时");
                        if (out.size() + n > MAX_BYTES) throw new IOException("直播列表超过 8 MB");
                        out.write(buffer, 0, n);
                    }
                    bytes = out.toByteArray();
                }
                String charset = "UTF-8";
                if (mime != null) {
                    Matcher m =
                            Pattern.compile(
                                            "charset\\s*=\\s*[\"']?([A-Za-z0-9_-]+)",
                                            Pattern.CASE_INSENSITIVE)
                                    .matcher(mime);
                    if (m.find()) charset = m.group(1);
                }
                try {
                    return parse(
                            bytes.length >= 2
                                            && ((bytes[0] & 255) == 255 && (bytes[1] & 255) == 254
                                                    || (bytes[0] & 255) == 254
                                                            && (bytes[1] & 255) == 255)
                                    ? Cms.document(bytes)
                                    : new String(bytes, Charset.forName(charset)),
                            next.toString(),
                            requestHeaders);
                } catch (java.nio.charset.UnsupportedCharsetException e) {
                    throw new IOException("直播列表字符编码不受支持", e);
                }
            } finally {
                RequestScope.detach(connection);
                connection.disconnect();
            }
        }
        throw new IOException("直播列表重定向次数过多");
    }

    public static Catalog parse(String text, String origin, Map<String, String> inheritedHeaders)
            throws Exception {
        if (text == null || text.length() > MAX_BYTES) throw new IOException("直播列表为空或超过大小限制");
        text = text.replaceFirst("^\\uFEFF", "").trim();
        if (!text.startsWith("{")
                && !text.startsWith("[")
                && !text.startsWith("#EXTM3U")
                && !text.startsWith("<")
                && !text.contains("#EXTINF:")
                && !text.contains(",#genre#")
                && !text.contains("://")) {
            try {
                String doc =
                        Cms.document(text.getBytes("UTF-8")).replaceFirst("^\\uFEFF", "").trim();
                if (!doc.equals(text)
                        && (doc.startsWith("{")
                                || doc.startsWith("[")
                                || doc.startsWith("#EXTM3U")
                                || doc.contains("#EXTINF:")
                                || doc.contains("://"))) {
                    text = doc;
                } else {
                    String candidate = text;
                    int star = candidate.lastIndexOf("**");
                    if (star >= 0 && star + 2 < candidate.length()) {
                        candidate = candidate.substring(star + 2).trim();
                    }
                    String encoded =
                            candidate.replaceAll("\\s+", "").replace('-', '+').replace('_', '/');
                    String alphabet =
                            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
                    ByteArrayOutputStream decoded = new ByteArrayOutputStream();
                    int bits = 0, count = 0;
                    for (int i = 0; i < encoded.length() && encoded.charAt(i) != '='; i++) {
                        int v = alphabet.indexOf(encoded.charAt(i));
                        if (v < 0) break;
                        bits = (bits << 6) | v;
                        count += 6;
                        if (count >= 8) {
                            count -= 8;
                            decoded.write((bits >> count) & 255);
                        }
                    }
                    String str =
                            new String(decoded.toByteArray(), "UTF-8")
                                    .replaceFirst("^\\uFEFF", "")
                                    .trim();
                    if (str.startsWith("#EXTM3U")
                            || str.contains("#EXTINF:")
                            || str.contains(",#genre#")
                            || str.contains("://")
                            || str.startsWith("{")
                            || str.startsWith("[")) {
                        text = str;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        Catalog out = new Catalog();
        out.origin = origin == null ? "" : origin;
        String lower = text.substring(0, Math.min(100, text.length())).toLowerCase(Locale.ROOT);
        if (lower.startsWith("<!doctype html") || lower.startsWith("<html"))
            throw new IOException("直播列表返回网页，未读取到频道");
        if (text.startsWith("{") || text.startsWith("[")) {
            Object root = new JSONTokener(Cms.withoutComments(text)).nextValue();
            parseJson(root, "默认", out, inheritedHeaders, 0);
        } else {
            if (text.contains("#EXT-X-TARGETDURATION:")
                    || text.contains("#EXT-X-STREAM-INF:")
                    || text.contains("#EXT-X-MEDIA-SEQUENCE:"))
                throw new IOException("此地址返回 HLS 播放清单，不是直播频道目录");
            parseText(text, out, inheritedHeaders);
        }
        // 内容安全：按频道名 + 分组隐藏成人频道，解析阶段一次过滤，所有直播入口共用
        for (Group g : out.groups) {
            java.util.Iterator<Channel> it = g.channels.iterator();
            while (it.hasNext()) {
                Channel c = it.next();
                if (Safety.hideChannel(c.name, g.name)) it.remove();
            }
        }
        if (out.channelCount() == 0) throw new IOException("直播列表没有可识别的频道");
        return out;
    }

    private static void parseJson(
            Object item, String group, Catalog out, Map<String, String> inherited, int depth)
            throws Exception {
        if (depth > 8) throw new IOException("直播分组层级过深");
        if (item instanceof JSONArray) {
            JSONArray a = (JSONArray) item;
            for (int i = 0; i < a.length(); i++)
                parseJson(a.opt(i), group, out, inherited, depth + 1);
            return;
        }
        if (!(item instanceof JSONObject)) return;
        JSONObject j = (JSONObject) item;
        Map<String, String> headers = new LinkedHashMap<>();
        if (inherited != null) headers.putAll(inherited);
        copyHeaders(headers, j.opt("headers"));
        copyHeaders(headers, j.opt("header"));
        header(headers, "User-Agent", j.optString("ua", ""));
        header(headers, "Referer", j.optString("referer", ""));
        header(headers, "Origin", j.optString("origin", ""));
        if (j.has("lives")) {
            parseJson(j.opt("lives"), group, out, headers, depth + 1);
            return;
        }
        if (j.has("groups")) {
            parseJson(j.opt("groups"), group, out, headers, depth + 1);
            return;
        }
        if (j.has("channels")) {
            parseJson(
                    j.opt("channels"),
                    j.optString("name", j.optString("group", group)),
                    out,
                    headers,
                    depth + 1);
            return;
        }
        Channel channel = new Channel();
        channel.name = j.optString("name", j.optString("title", "未命名频道"));
        channel.logo = resolve(out.origin, j.optString("logo", j.optString("tvg-logo", "")));
        channel.id = j.optString("id", j.optString("tvg-id", ""));
        channel.options.putAll(attributes(j));
        Object urls = j.has("urls") ? j.opt("urls") : j.opt("url");
        JSONArray a = urls instanceof JSONArray ? (JSONArray) urls : new JSONArray().put(urls);
        for (int i = 0; i < a.length(); i++) {
            Object u = a.opt(i);
            if (u == null || u == JSONObject.NULL) continue;
            if (u instanceof JSONObject) {
                JSONObject value = (JSONObject) u;
                Map<String, String> hs = new LinkedHashMap<>(headers);
                copyHeaders(hs, value.opt("headers"));
                copyHeaders(hs, value.opt("header"));
                List<Stream> streams = streams(value.optString("url"), out.origin, hs);
                for (Stream stream : streams) {
                    stream.label = value.optString("name", stream.label);
                    stream.options.putAll(attributes(value));
                }
                channel.streams.addAll(streams);
            } else channel.streams.addAll(streams(u.toString(), out.origin, headers));
        }
        add(out, j.optString("group", j.optString("group-title", group)), channel);
    }

    private static void parseText(String text, Catalog out, Map<String, String> inherited)
            throws Exception {
        String[] lines = text.split("\\r?\\n");
        if (lines.length > MAX_LINES) throw new IOException("直播列表行数过多");
        boolean m3u = text.startsWith("#EXTM3U") || text.contains("#EXTINF:");
        String group = "默认";
        Channel pending = null;
        Map<String, String> local = new LinkedHashMap<>();
        Map<String, String> opts = new LinkedHashMap<>();
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXTM3U")) {
                out.options.putAll(attrs(line));
                continue;
            }
            if (line.startsWith("#EXTINF:")) {
                int comma = unquotedComma(line);
                String metadata = comma < 0 ? line : line.substring(0, comma);
                Map<String, String> attrs = attrs(metadata);
                pending = new Channel();
                pending.options.putAll(attrs);
                String tvgName = attrs.get("tvg-name");
                pending.name =
                        comma < 0
                                ? (tvgName != null ? tvgName : "未命名频道")
                                : line.substring(comma + 1).trim();
                String tvgLogo = attrs.get("tvg-logo");
                pending.logo = resolve(out.origin, tvgLogo != null ? tvgLogo : "");
                String tvgId = attrs.get("tvg-id");
                pending.id = tvgId != null ? tvgId : "";
                String grp = attrs.get("group-title");
                group = grp != null ? grp : "默认";
                local.clear();
                opts.clear();
                header(local, "User-Agent", attrs.get("http-user-agent"));
                header(local, "Referer", attrs.get("http-referrer"));
                continue;
            }
            if (line.startsWith("#EXTGRP:")) {
                group = line.substring(8).trim();
                continue;
            }
            if (line.startsWith("#EXTVLCOPT:")) {
                String option = line.substring(11);
                int eq = option.indexOf('=');
                if (eq > 0) {
                    String key = option.substring(0, eq).trim(),
                            value = option.substring(eq + 1).trim();
                    opts.put(key, value);
                    if (key.equalsIgnoreCase("http-user-agent")) header(local, "User-Agent", value);
                    if (key.equalsIgnoreCase("http-referrer")
                            || key.equalsIgnoreCase("http-referer"))
                        header(local, "Referer", value);
                    if (key.equalsIgnoreCase("http-cookie")) header(local, "Cookie", value);
                }
                continue;
            }
            if (line.startsWith("#KODIPROP:")) {
                String option = line.substring(10);
                int eq = option.indexOf('=');
                if (eq > 0) opts.put(option.substring(0, eq), option.substring(eq + 1));
                continue;
            }
            if (line.startsWith("#")) continue;
            Map<String, String> hs = new LinkedHashMap<>();
            if (inherited != null) hs.putAll(inherited);
            hs.putAll(local);
            if (m3u) {
                if (pending == null) {
                    pending = new Channel();
                    pending.name = "频道 " + (out.channelCount() + 1);
                }
                pending.streams.addAll(streams(line, out.origin, hs));
                for (Stream s : pending.streams) s.options.putAll(opts);
                add(out, group, pending);
                pending = null;
                local.clear();
                opts.clear();
            } else {
                int comma = line.indexOf(',');
                if (comma <= 0) continue;
                String name = line.substring(0, comma).trim(),
                        address = line.substring(comma + 1).trim();
                if (address.equalsIgnoreCase("#genre#")) {
                    group = name;
                    continue;
                }
                Channel channel = new Channel();
                channel.name = name;
                channel.streams.addAll(streams(address, out.origin, hs));
                add(out, group, channel);
            }
        }
        if (pending != null) out.warnings.add("末尾频道缺少播放地址");
    }

    private static List<Stream> streams(
            String value, String origin, Map<String, String> inherited) {
        List<Stream> out = new ArrayList<>();
        if (value == null) return out;
        // A fragment is not automatically an alternative stream: split only before a scheme.
        for (String part : value.trim().split("#(?=[A-Za-z][A-Za-z0-9+.-]*://)")) {
            if (part.isEmpty()) continue;
            Stream s = new Stream();
            if (inherited != null) s.headers.putAll(inherited);
            int pipe = part.indexOf('|');
            if (pipe >= 0) {
                String query = part.substring(pipe + 1);
                part = part.substring(0, pipe);
                for (String pair : query.split("&")) {
                    int eq = pair.indexOf('=');
                    if (eq > 0) {
                        String k = decode(pair.substring(0, eq)),
                                v = decode(pair.substring(eq + 1));
                        s.options.put(k, v);
                        header(s.headers, k, v);
                    }
                }
            }
            Matcher headers =
                    Pattern.compile("@(?i:User-Agent|Referer|Cookie|Origin)=").matcher(part);
            int first = -1, start = 0;
            String key = null;
            while (headers.find()) {
                if (first < 0) first = headers.start();
                if (key != null) header(s.headers, key, part.substring(start, headers.start()));
                key = part.substring(headers.start() + 1, headers.end() - 1);
                start = headers.end();
            }
            if (key != null) {
                header(s.headers, key, part.substring(start));
                part = part.substring(0, first);
            }
            int dollar = part.lastIndexOf('$');
            int schemeSep = part.indexOf("://");
            if (dollar > (schemeSep >= 0 ? schemeSep + 3 : 0)
                    && dollar + 1 < part.length()
                    && part.charAt(dollar + 1) != '{') {
                String tail = part.substring(dollar + 1).trim();
                boolean hasQuery = part.indexOf('?') >= 0 && dollar > part.indexOf('?');
                boolean looksLikeLabel =
                        !hasQuery
                                ? tail.matches("[^/?=&$]{1,40}")
                                : (tail.matches("[^/?=&$#]{1,32}")
                                        && (!tail.matches("[\\x00-\\x7F]+")
                                                || tail.matches(
                                                        "(?i).*(?:hd|sd|fhd|uhd|4k|8k|ipv[46]|line|bak|flv|hls|m3u8|\\d+p).*")));
                if (looksLikeLabel) {
                    s.label = tail;
                    part = part.substring(0, dollar);
                }
            }
            s.url = resolve(origin, part.trim());
            if (!s.url.isEmpty()) out.add(s);
        }
        return out;
    }

    private static void add(Catalog out, String group, Channel channel) throws IOException {
        if (channel.streams.isEmpty()) return;
        if (out.channelCount() >= MAX_CHANNELS) throw new IOException("直播频道超过 12000 个");
        Group target = null;
        for (Group g : out.groups)
            if (g.name.equals(group)) {
                target = g;
                break;
            }
        if (target == null) {
            target = new Group();
            target.name = group == null || group.isEmpty() ? "默认" : group;
            out.groups.add(target);
        }
        target.channels.add(channel);
    }

    private static String resolve(String origin, String value) {
        if (value == null || value.trim().isEmpty()) return "";
        value = value.trim();
        if (value.startsWith("//")) {
            String scheme =
                    origin != null && origin.regionMatches(true, 0, "https:", 0, 6)
                            ? "https:"
                            : "http:";
            return scheme + value;
        }
        if (value.matches("^[A-Za-z0-9.-]+:\\d+/.*")) {
            return "http://" + value;
        }
        if (value.matches("^[A-Za-z][A-Za-z0-9+.-]*://.*")
                || value.matches("^(?i:magnet|content|file|javascript):.*")) {
            return value;
        }
        try {
            return Cms.resolve(origin, value);
        } catch (Exception ignored) {
            return value;
        }
    }

    /** TVBox's do=live&type=txt wrapper points at a catalogue, not a video or JAR. */
    private static Map<String, String> liveDirectoryQuery(String value) {
        if (value == null || value.length() > 16384) return null;
        String query;
        if (value.startsWith("proxy://")) {
            query = value.substring(8);
            if (query.startsWith("?")) query = query.substring(1);
        } else {
            try {
                URL url = new URL(value);
                if (!(url.getProtocol().equals("http") || url.getProtocol().equals("https"))
                        || !(url.getHost().equals("127.0.0.1")
                                || url.getHost().equals("localhost")
                                || url.getHost().equals("[::1]"))
                        || !url.getPath().equals("/proxy")) return null;
                query = url.getQuery();
            } catch (Exception e) {
                return null;
            }
        }
        if (query == null) return null;
        Map<String, String> values = new LinkedHashMap<>();
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            String key = decode(pair.substring(0, eq));
            // URI query '+' is literal: standard Base64 can contain it.
            String decoded = decode(pair.substring(eq + 1).replace("+", "%2B"));
            if (values.containsKey(key)) values.put("duplicate", "true");
            values.put(key, decoded);
        }
        return "live".equals(values.get("do")) && "txt".equals(values.get("type")) ? values : null;
    }

    private static String liveDirectoryUrl(String value, String origin) throws IOException {
        Map<String, String> query = liveDirectoryQuery(value);
        if (query == null) return value;
        String ext = query.get("ext");
        if (ext == null || ext.trim().isEmpty() || query.containsKey("duplicate"))
            throw new IOException("直播目录包装地址缺少有效 ext 参数");
        ext = ext.trim();
        if (!(Cms.isHttp(ext)
                || ext.startsWith("file://")
                || ext.startsWith("./")
                || ext.startsWith("../"))) {
            // Keep API 23 compatibility: java.util.Base64 requires Android API 26.
            String encoded = ext.replaceAll("\\s", "").replace('-', '+').replace('_', '/');
            if (!encoded.matches("[A-Za-z0-9+/]+={0,2}")
                    || encoded.length() % 4 == 1
                    || (encoded.contains("=") && encoded.length() % 4 != 0))
                throw new IOException("直播目录 ext 不是有效的地址或 Base64");
            ByteArrayOutputStream decoded = new ByteArrayOutputStream();
            String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
            int bits = 0, count = 0;
            for (int i = 0; i < encoded.length() && encoded.charAt(i) != '='; i++) {
                bits = (bits << 6) | alphabet.indexOf(encoded.charAt(i));
                count += 6;
                if (count >= 8) {
                    count -= 8;
                    decoded.write((bits >> count) & 255);
                }
            }
            ext = new String(decoded.toByteArray(), "UTF-8").trim();
        }
        String target = resolve(origin, ext);
        if (Cms.isHttp(target)) return target;
        if (target.startsWith("file://") && origin != null && origin.startsWith("file://"))
            return target;
        throw new IOException("直播目录 ext 必须指向 HTTP(S) 或本地包内的列表");
    }

    /** Split directory placeholders from ordinary inline channels without discarding either. */
    private static Object separateDirectories(
            Object item, Source parent, List<Source> directories, int depth) throws Exception {
        if (depth > 8) throw new IOException("直播分组层级过深");
        if (item instanceof JSONArray) {
            JSONArray remaining = new JSONArray();
            JSONArray items = (JSONArray) item;
            for (int i = 0; i < items.length(); i++) {
                Object next = separateDirectories(items.opt(i), parent, directories, depth + 1);
                if (next != null) remaining.put(next);
            }
            return remaining.length() == 0 ? null : remaining;
        }
        if (!(item instanceof JSONObject)) return item;
        JSONObject object = new JSONObject(item.toString());
        Source scoped = new Source();
        scoped.name = object.optString("name", object.optString("group", parent.name));
        scoped.origin = parent.origin;
        scoped.timeoutMs =
                object.has("timeout")
                        ? Math.max(1000, Math.min(30000, object.optInt("timeout", 15) * 1000))
                        : parent.timeoutMs;
        scoped.headers.putAll(parent.headers);
        scoped.options.putAll(parent.options);
        scoped.options.putAll(attributes(object));
        copyHeaders(scoped.headers, object.opt("headers"));
        copyHeaders(scoped.headers, object.opt("header"));
        header(scoped.headers, "User-Agent", object.optString("ua", ""));
        header(scoped.headers, "Referer", object.optString("referer", ""));
        header(scoped.headers, "Origin", object.optString("origin", ""));
        String children =
                object.has("groups") ? "groups" : object.has("channels") ? "channels" : "";
        if (!children.isEmpty()) {
            Object remaining =
                    separateDirectories(object.opt(children), scoped, directories, depth + 1);
            if (remaining == null) return null;
            object.put(children, remaining);
            return object;
        }
        Object urls = object.has("urls") ? object.opt("urls") : object.opt("url");
        JSONArray entries =
                urls instanceof JSONArray ? (JSONArray) urls : new JSONArray().put(urls);
        JSONArray remaining = new JSONArray();
        boolean extracted = false;
        for (int i = 0; i < entries.length(); i++) {
            Object entry = entries.opt(i);
            String url =
                    entry instanceof JSONObject
                            ? ((JSONObject) entry).optString("url", "")
                            : entry instanceof String ? (String) entry : "";
            if (liveDirectoryQuery(url) == null) {
                remaining.put(entry);
                continue;
            }
            Source directory = new Source();
            directory.name = scoped.name;
            directory.url = url;
            directory.origin = scoped.origin;
            directory.timeoutMs = scoped.timeoutMs;
            directory.headers.putAll(scoped.headers);
            directory.options.putAll(scoped.options);
            if (entry instanceof JSONObject) {
                JSONObject definition = (JSONObject) entry;
                directory.name = definition.optString("name", directory.name);
                copyHeaders(directory.headers, definition.opt("headers"));
                copyHeaders(directory.headers, definition.opt("header"));
            }
            directories.add(directory);
            if (directories.size() > MAX_CHANNELS) throw new IOException("直播目录数量过多");
            extracted = true;
        }
        if (!extracted) return object;
        if (remaining.length() == 0) return null;
        object.remove("url");
        object.put("urls", remaining);
        return object;
    }

    private static Map<String, String> attrs(String value) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m =
                Pattern.compile("([A-Za-z0-9_-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s]+))")
                        .matcher(value);
        while (m.find())
            out.put(
                    m.group(1).toLowerCase(Locale.ROOT),
                    m.group(2) != null ? m.group(2) : m.group(3) != null ? m.group(3) : m.group(4));
        return out;
    }

    private static int unquotedComma(String value) {
        char quote = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
            } else if (c == '\"' || c == '\'') quote = c;
            else if (c == ',') return i;
        }
        return -1;
    }

    private static Map<String, String> attributes(JSONObject object) {
        Map<String, String> out = new LinkedHashMap<>();
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = object.opt(key);
            if (value != null
                    && value != JSONObject.NULL
                    && !(value instanceof JSONObject)
                    && !(value instanceof JSONArray)) out.put(key, String.valueOf(value));
        }
        return out;
    }

    private static void copyHeaders(Map<String, String> target, Object value) {
        if (value instanceof String)
            try {
                value = new JSONObject((String) value);
            } catch (Exception ignored) {
                return;
            }
        if (value instanceof JSONObject) {
            JSONObject j = (JSONObject) value;
            Iterator<String> keys = j.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                header(target, key, j.optString(key));
            }
        }
    }

    private static void header(Map<String, String> target, String key, String value) {
        if (key == null
                || value == null
                || value.isEmpty()
                || !key.matches("[A-Za-z0-9_-]+")
                || value.contains("\r")
                || value.contains("\n")) return;
        String canonical = key;
        for (String known :
                new String[] {"User-Agent", "Referer", "Cookie", "Origin", "Authorization"})
            if (key.equalsIgnoreCase(known)) canonical = known;
        target.put(canonical, value);
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (Exception ignored) {
            return value;
        }
    }

    private static boolean sameOrigin(URL a, URL b) {
        return a.getProtocol().equalsIgnoreCase(b.getProtocol())
                && a.getHost().equalsIgnoreCase(b.getHost())
                && a.getPort() == b.getPort();
    }
}
