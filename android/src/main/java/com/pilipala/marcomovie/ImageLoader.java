package com.pilipala.marcomovie;

import android.content.ComponentCallbacks2;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.widget.ImageView;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Bounded asynchronous poster/artwork loader, memory cache trimming, and background atmosphere
 * rendering extracted from {@link MainActivity}.
 */
final class ImageLoader {
    private static final java.util.Map<String, java.util.List<ImageView>> IN_FLIGHT =
            new java.util.HashMap<>();

    private ImageLoader() {}

    static void cancel(MainActivity a) {
        MainActivity.flicker(
                "IMAGES_CANCEL generation="
                        + a.generation
                        + " requests="
                        + a.artworkRequests.size());
        synchronized (IN_FLIGHT) {
            IN_FLIGHT.clear();
        }
        for (Future<?> request : a.artworkRequests) request.cancel(true);
        a.artworkRequests.clear();
        ((ThreadPoolExecutor) a.artwork).purge();
    }

    static void trimMemory(MainActivity a, int level) {
        if (a.images == null) return;
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND || level >= 15) {
            a.images.evictAll();
        } else if (level >= 10) {
            UiCompat.trimCache(a.images, a.images.maxSize() / 2);
        }
    }

    static void poster(MainActivity a, String address, ImageView image, boolean detail) {
        MainActivity.flicker(
                "POSTER_BIND image="
                        + System.identityHashCode(image)
                        + " url="
                        + address.hashCode()
                        + " generation="
                        + a.generation
                        + " hasDrawable="
                        + (image.getDrawable() != null));
        image.setTag(address);
        if (!Cms.isHttp(address) && !address.startsWith("catpaw://")) {
            if (!address.isEmpty())
                android.util.Log.w("MM_POSTER", "skip non-http poster address=[" + address + "]");
            return;
        }
        final int ticket = a.generation;
        int initialW = ImageDecodePolicy.targetWidth(image.getWidth(), detail);
        int initialH = ImageDecodePolicy.targetHeight(image.getHeight(), detail);
        boolean initialCrop = image.getScaleType() == ImageView.ScaleType.CENTER_CROP;
        String initialKey =
                ImageDecodePolicy.cacheKey(address, initialW, initialH, initialCrop, detail);
        Bitmap immediate = a.images.get(initialKey);
        if (immediate != null && !immediate.isRecycled()) {
            MainActivity.flicker(
                    "POSTER_CACHE image="
                            + System.identityHashCode(image)
                            + " generation="
                            + ticket
                            + " kind=exact");
            image.setImageBitmap(immediate);
            com.pilipala.glass.GlassworkSurface.invalidateBackdrop(image);
            return;
        }
        Bitmap preview = a.images.get(address);
        if (preview != null && !preview.isRecycled()) {
            MainActivity.flicker(
                    "POSTER_CACHE image="
                            + System.identityHashCode(image)
                            + " generation="
                            + ticket
                            + " kind=preview");
            image.setImageBitmap(preview);
            com.pilipala.glass.GlassworkSurface.invalidateBackdrop(image);
            if (!detail || (preview.getWidth() >= initialW && preview.getHeight() >= initialH)) {
                return;
            }
        }
        startPosterLoad(a, address, image, detail, ticket);
    }

    private static void startPosterLoad(
            MainActivity a, String address, ImageView image, boolean detail, final int ticket) {
        if (ticket != a.generation || !address.equals(image.getTag())) return;
        MainActivity.flicker(
                "POSTER_LOAD image="
                        + System.identityHashCode(image)
                        + " url="
                        + address.hashCode()
                        + " generation="
                        + ticket);
        final int width = ImageDecodePolicy.targetWidth(image.getWidth(), detail);
        final int height = ImageDecodePolicy.targetHeight(image.getHeight(), detail);
        final boolean crop = image.getScaleType() == ImageView.ScaleType.CENTER_CROP;
        final String key = ImageDecodePolicy.cacheKey(address, width, height, crop, detail);
        Bitmap cached = a.images.get(key);
        if (cached != null && !cached.isRecycled()) {
            image.setImageBitmap(cached);
            com.pilipala.glass.GlassworkSurface.invalidateBackdrop(image);
            return;
        }
        final java.util.List<ImageView> owned = new java.util.ArrayList<>(2);
        synchronized (IN_FLIGHT) {
            java.util.List<ImageView> waiters = IN_FLIGHT.get(key);
            if (waiters != null) {
                if (!waiters.contains(image)) waiters.add(image);
                return;
            }
            owned.add(image);
            IN_FLIGHT.put(key, owned);
        }
        Future<?> request =
                RequestScope.submit(
                        a.artwork,
                        () -> {
                            if (ticket != a.generation) {
                                synchronized (IN_FLIGHT) {
                                    if (IN_FLIGHT.get(key) == owned) IN_FLIGHT.remove(key);
                                }
                                return;
                            }
                            HttpURLConnection connection = null;
                            try {
                                Cms.ImageRequest source = Cms.imageRequest(address);
                                String resolvedUrl = Cms.normalizeHttp(source.url);
                                if (resolvedUrl.startsWith("catpaw://")) {
                                    resolvedUrl =
                                            new com.pilipala.catpaw.CatPawHost(a)
                                                    .resolve(resolvedUrl);
                                }
                                String targetUrl = upgradePosterUrl(resolvedUrl, detail);
                                byte[] bytes =
                                        fetchPosterBytesWithFallback(
                                                a, source, resolvedUrl, targetUrl, detail);
                                if (bytes == null || bytes.length == 0) return;
                                BitmapFactory.Options options = new BitmapFactory.Options();
                                options.inJustDecodeBounds = true;
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                                if (options.outWidth <= 0 || options.outHeight <= 0) return;
                                options.inSampleSize =
                                        ImageDecodePolicy.sample(
                                                options.outWidth,
                                                options.outHeight,
                                                width,
                                                height,
                                                crop,
                                                detail);
                                options.inJustDecodeBounds = false;
                                RequestScope.current().check();
                                Bitmap bitmap =
                                        BitmapFactory.decodeByteArray(
                                                bytes, 0, bytes.length, options);
                                if (bitmap != null) {
                                    if (ticket != a.generation
                                            || Thread.currentThread().isInterrupted()) {
                                        bitmap.recycle();
                                        return;
                                    }
                                    a.images.put(key, bitmap);
                                    Bitmap existingPreview = a.images.get(address);
                                    if (existingPreview == null
                                            || existingPreview.isRecycled()
                                            || bitmap.getWidth() >= existingPreview.getWidth()) {
                                        a.images.put(address, bitmap);
                                    }
                                    final java.util.List<ImageView> targets;
                                    synchronized (IN_FLIGHT) {
                                        java.util.List<ImageView> removed =
                                                IN_FLIGHT.get(key) == owned
                                                        ? IN_FLIGHT.remove(key)
                                                        : null;
                                        targets =
                                                removed == null
                                                        ? java.util.Collections.singletonList(image)
                                                        : new java.util.ArrayList<>(removed);
                                    }
                                    a.ui.post(
                                            () -> {
                                                if (ticket != a.generation || a.isFinishing())
                                                    return;
                                                for (ImageView target : targets) {
                                                    if (target != null
                                                            && address.equals(target.getTag())) {
                                                        MainActivity.flicker(
                                                                "POSTER_APPLY image="
                                                                        + System.identityHashCode(
                                                                                target)
                                                                        + " generation="
                                                                        + ticket);
                                                        target.setImageBitmap(bitmap);
                                                        com.pilipala.glass.GlassworkSurface
                                                                .invalidateBackdrop(target);
                                                    }
                                                }
                                            });
                                }
                            } catch (Exception e) {
                                android.util.Log.e(
                                        "MM_POSTER", "poster load failed: " + address, e);
                            } finally {
                                synchronized (IN_FLIGHT) {
                                    if (IN_FLIGHT.get(key) == owned) IN_FLIGHT.remove(key);
                                }
                                if (connection != null) {
                                    RequestScope.detach(connection);
                                    connection.disconnect();
                                }
                            }
                        });
        for (java.util.Iterator<Future<?>> it = a.artworkRequests.iterator(); it.hasNext(); ) {
            if (it.next().isDone()) it.remove();
        }
        a.artworkRequests.add(request);
    }

    private static File diskCacheFile(MainActivity a, String url) {
        try {
            File dir = new File(a.getCacheDir(), "posters_v2");
            if (!dir.exists()) dir.mkdirs();
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(url.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(32);
            for (int i = 0; i < 16; i++) {
                sb.append(String.format(java.util.Locale.ROOT, "%02x", d[i] & 255));
            }
            return new File(dir, sb.toString() + ".img");
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] readDiskCachedPoster(MainActivity a, String url) {
        try {
            File f = diskCacheFile(a, url);
            if (f == null || !f.isFile()) return null;
            long len = f.length();
            if (len <= 128 || len > 8 * 1024 * 1024) return null;
            if (System.currentTimeMillis() - f.lastModified() > 7L * 24 * 3600 * 1000L) {
                return null;
            }
            byte[] buf = new byte[(int) len];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                int off = 0;
                while (off < buf.length) {
                    int r = in.read(buf, off, buf.length - off);
                    if (r < 0) break;
                    off += r;
                }
                if (off != buf.length) return null;
            }
            return buf;
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeDiskCachedPoster(MainActivity a, String url, byte[] bytes) {
        try {
            File f = diskCacheFile(a, url);
            if (f == null) return;
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
                out.write(bytes);
            }
        } catch (Exception ignored) {
        }
    }

    private static byte[] fetchPosterBytesWithFallback(
            MainActivity a,
            Cms.ImageRequest source,
            String resolvedUrl,
            String targetUrl,
            boolean detail)
            throws Exception {
        String[] candidates =
                detail && !targetUrl.equals(resolvedUrl)
                        ? new String[] {
                            targetUrl, upgradePosterUrl(resolvedUrl, false), resolvedUrl
                        }
                        : new String[] {targetUrl, resolvedUrl};
        Exception lastError = null;
        java.util.HashSet<String> tried = new java.util.HashSet<>();
        for (String candidate : candidates) {
            if (candidate == null || candidate.isEmpty() || !tried.add(candidate)) continue;
            byte[] cached = readDiskCachedPoster(a, candidate);
            if (cached != null) return cached;
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(candidate).openConnection();
                for (Map.Entry<String, String> header : source.headers.entrySet())
                    connection.setRequestProperty(header.getKey(), header.getValue());
                String fragment = new URL(candidate).getRef();
                if (fragment != null && fragment.startsWith("mm-emby=")) {
                    connection.setInstanceFollowRedirects(false);
                    com.pilipala.emby.EmbyLibrary library =
                            com.pilipala.emby.EmbyAccounts.open(
                                    a, "emby://account/" + fragment.substring(8));
                    for (Map.Entry<String, String> h : library.imageHeaders(candidate).entrySet())
                        connection.setRequestProperty(h.getKey(), h.getValue());
                }
                applyArtworkHeaders(connection, candidate, source.headers);
                connection.setConnectTimeout(4500);
                connection.setReadTimeout(5500);
                if (!source.headers.containsKey("User-Agent"))
                    connection.setRequestProperty("User-Agent", Cms.USER_AGENT);
                RequestScope.attach(connection);
                try (InputStream in = connection.getInputStream()) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        RequestScope.current().check();
                        if (out.size() + n > (detail ? 8 : 3) * 1024 * 1024)
                            throw new IOException("Poster too large");
                        out.write(buffer, 0, n);
                    }
                    byte[] bytes = out.toByteArray();
                    if (bytes.length > 128) {
                        writeDiskCachedPoster(a, candidate, bytes);
                        return bytes;
                    }
                }
            } catch (Exception e) {
                lastError = e;
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        if (lastError != null) throw lastError;
        return null;
    }

    static String upgradePosterUrl(String rawUrl, boolean detail) {
        if (rawUrl == null || rawUrl.isEmpty()) return "";
        String url = rawUrl;
        if (url.contains("doubanio.com")) {
            url =
                    url.replaceAll(
                            "https?://(?:img[4-9]|qnmob\\d*-sign)\\.doubanio\\.com/",
                            "https://img1.doubanio.com/");
            int qIdx = url.indexOf('?');
            if (qIdx > 0 && url.substring(0, qIdx).endsWith(".jpg")) {
                url = url.substring(0, qIdx);
            }
            if (detail) {
                url =
                        url.replace("/s_ratio_poster/", "/l_ratio_poster/")
                                .replace("/m_ratio_poster/", "/l_ratio_poster/")
                                .replace("/view/photo/spic/", "/view/photo/lpic/")
                                .replace("/view/photo/mpic/", "/view/photo/lpic/")
                                .replace("/view/photo/s/", "/view/photo/l/")
                                .replace("/view/photo/m/", "/view/photo/l/")
                                .replace("/view/photo/large/", "/view/photo/l/");
            } else {
                url =
                        url.replace("/s_ratio_poster/", "/m_ratio_poster/")
                                .replace("/view/photo/spic/", "/view/photo/mpic/");
            }
        }
        if (url.contains("image.tmdb.org/t/p/")) {
            if (detail) {
                url =
                        url.replace("/t/p/w185/", "/t/p/w780/")
                                .replace("/t/p/w300/", "/t/p/w780/")
                                .replace("/t/p/w342/", "/t/p/w780/")
                                .replace("/t/p/w500/", "/t/p/w780/");
            } else {
                url =
                        url.replace("/t/p/w185/", "/t/p/w500/")
                                .replace("/t/p/w300/", "/t/p/w500/")
                                .replace("/t/p/w342/", "/t/p/w500/");
            }
        }
        if (detail) {
            if (url.contains("lain.bgm.tv") || url.contains("bgm.tv/pic/cover/")) {
                url =
                        url.replace("/pic/cover/c/", "/pic/cover/l/")
                                .replace("/pic/cover/m/", "/pic/cover/l/")
                                .replace("/pic/cover/s/", "/pic/cover/l/")
                                .replace("/r/400/", "/r/800/");
            }
            if (url.contains("hdslb.com") || url.contains("biliimg.com")) {
                int at = url.indexOf('@');
                if (at > 0) url = url.substring(0, at);
            }
            if (url.contains("bcebos.com")) {
                int q = url.indexOf('?');
                if (q > 0) url = url.substring(0, q);
            }
        }
        return url;
    }

    static void applyArtworkHeaders(
            HttpURLConnection connection, String url, Map<String, String> existing) {
        if (existing != null && existing.containsKey("Referer")) return;
        String lower = url.toLowerCase();
        if (lower.contains("doubanio.com") || lower.contains("douban.com")) {
            connection.setRequestProperty("Referer", "https://movie.douban.com/");
        } else if (lower.contains("hdslb.com")
                || lower.contains("biliimg.com")
                || lower.contains("bilibili.com")) {
            connection.setRequestProperty("Referer", "https://www.bilibili.com/");
        } else if (lower.contains("bcebos.com") || lower.contains("baidu.com")) {
            connection.setRequestProperty("Referer", "https://baike.baidu.com/");
        } else if (lower.contains("bgm.tv") || lower.contains("bangumi.tv")) {
            connection.setRequestProperty("Referer", "https://bgm.tv/");
        } else if (lower.contains("bing.net") || lower.contains("bing.com")) {
            connection.setRequestProperty("Referer", "https://cn.bing.com/");
        } else if (lower.contains("qpic.cn") || lower.contains("gtimg.cn")) {
            connection.setRequestProperty("Referer", "https://v.qq.com/");
        } else if (lower.contains("sinaimg.cn")) {
            connection.setRequestProperty("Referer", "https://weibo.com/");
        }
    }

    /** 单次下载（含来源请求头、emby 头、超时与体积上限），连接在方法内必定关闭。 */
    private static byte[] readBytes(
            MainActivity a, String url, Cms.ImageRequest source, boolean detail) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            for (Map.Entry<String, String> header : source.headers.entrySet())
                connection.setRequestProperty(header.getKey(), header.getValue());
            String fragment = new URL(url).getRef();
            if (fragment != null && fragment.startsWith("mm-emby=")) {
                connection.setInstanceFollowRedirects(false);
                com.pilipala.emby.EmbyLibrary library =
                        com.pilipala.emby.EmbyAccounts.open(
                                a, "emby://account/" + fragment.substring(8));
                for (Map.Entry<String, String> h : library.imageHeaders(url).entrySet())
                    connection.setRequestProperty(h.getKey(), h.getValue());
            }
            connection.setConnectTimeout(7000);
            connection.setReadTimeout(8000);
            if (!source.headers.containsKey("User-Agent"))
                connection.setRequestProperty("User-Agent", Cms.USER_AGENT);
            RequestScope.attach(connection);
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    RequestScope.current().check();
                    if (out.size() + n > (detail ? 8 : 3) * 1024 * 1024)
                        throw new IOException("Poster too large");
                    out.write(buffer, 0, n);
                }
                return out.toByteArray();
            }
        } finally {
            RequestScope.detach(connection);
            connection.disconnect();
        }
    }

    static View createAtmosphere(MainActivity a) {
        return new Atmosphere(a);
    }

    static final class Atmosphere extends View {
        final MainActivity a;
        final Paint paint = new Paint(3);
        Bitmap wallpaper;

        Atmosphere(Context c) {
            super(c);
            a = (MainActivity) c;
            if (a.prefs.getBoolean(Prefs.WALLPAPER_ENABLED, false))
                wallpaper =
                        BitmapFactory.decodeFile(
                                new File(a.getFilesDir(), "wallpaper.jpg").getAbsolutePath());
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawColor(MainActivity.DARK);
            if (wallpaper != null) {
                float scale =
                        Math.max(
                                (float) getWidth() / wallpaper.getWidth(),
                                (float) getHeight() / wallpaper.getHeight());
                float w = wallpaper.getWidth() * scale, h = wallpaper.getHeight() * scale;
                canvas.drawBitmap(
                        wallpaper,
                        null,
                        new RectF(
                                (getWidth() - w) / 2,
                                (getHeight() - h) / 2,
                                (getWidth() + w) / 2,
                                (getHeight() + h) / 2),
                        paint);
                int dim = Prefs.clampWallpaperDim(a.prefs.getInt(Prefs.WALLPAPER_DIM, 60));
                canvas.drawColor(Color.argb(Math.round(255 * dim / 100f), 0, 0, 0));
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (wallpaper != null) {
                wallpaper.recycle();
                wallpaper = null;
            }
        }
    }
}
