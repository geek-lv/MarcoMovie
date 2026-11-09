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
        image.setTag(address);
        if (!Cms.isHttp(address) && !address.startsWith("catpaw://")) return;
        final int ticket = a.generation;
        image.post(
                () -> {
                    if (ticket != a.generation || !address.equals(image.getTag())) return;
                    int width = ImageDecodePolicy.targetWidth(image.getWidth(), detail);
                    int height = ImageDecodePolicy.targetHeight(image.getHeight(), detail);
                    boolean crop = image.getScaleType() == ImageView.ScaleType.CENTER_CROP;
                    final String key =
                            ImageDecodePolicy.cacheKey(address, width, height, crop, detail);
                    Bitmap cached = a.images.get(key);
                    if (cached != null) {
                        image.setImageBitmap(cached);
                        com.pilipala.glass.GlassworkSurface.invalidateBackdrop(image);
                        return;
                    }
                    Bitmap preview = a.images.get(address);
                    if (preview != null) {
                        image.setImageBitmap(preview);
                        com.pilipala.glass.GlassworkSurface.invalidateBackdrop(image);
                        if (!detail
                                || (preview.getWidth() >= width && preview.getHeight() >= height))
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
                                                if (IN_FLIGHT.get(key) == owned)
                                                    IN_FLIGHT.remove(key);
                                            }
                                            return;
                                        }
                                        try {
                                            Cms.ImageRequest source = Cms.imageRequest(address);
                                            String originalUrl = Cms.normalizeHttp(source.url);
                                            if (originalUrl.startsWith("catpaw://"))
                                                originalUrl =
                                                        new com.pilipala.catpaw.CatPawHost(a)
                                                                .resolve(originalUrl);
                                            String targetUrl = originalUrl;
                                            if (targetUrl.contains("doubanio.com")) {
                                                targetUrl =
                                                        targetUrl
                                                                .replace(
                                                                        "img9.doubanio.com",
                                                                        "img3.doubanio.com")
                                                                .replace(
                                                                        "/s_ratio_poster/",
                                                                        "/l_ratio_poster/")
                                                                .replace(
                                                                        "/m_ratio_poster/",
                                                                        "/l_ratio_poster/")
                                                                .replace(
                                                                        "/view/photo/spic/",
                                                                        "/view/photo/lpic/")
                                                                .replace(
                                                                        "/view/photo/mpic/",
                                                                        "/view/photo/lpic/");
                                            }
                                            byte[] bytes;
                                            try {
                                                bytes = readBytes(a, targetUrl, source, detail);
                                            } catch (IOException upgradeFailed) {
                                                // 豆瓣 l_ratio_poster（大图）对新上线条目常常不存在，
                                                // 升级地址 404 时退回原始小图，否则封面会一直空着
                                                if (targetUrl.equals(originalUrl))
                                                    throw upgradeFailed;
                                                bytes = readBytes(a, originalUrl, source, detail);
                                            }
                                            BitmapFactory.Options options =
                                                    new BitmapFactory.Options();
                                            options.inJustDecodeBounds = true;
                                            BitmapFactory.decodeByteArray(
                                                    bytes, 0, bytes.length, options);
                                            if (options.outWidth <= 0 || options.outHeight <= 0)
                                                return;
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
                                                final java.util.List<ImageView> targets;
                                                synchronized (IN_FLIGHT) {
                                                    java.util.List<ImageView> removed =
                                                            IN_FLIGHT.get(key) == owned
                                                                    ? IN_FLIGHT.remove(key)
                                                                    : null;
                                                    targets =
                                                            removed == null
                                                                    ? java.util.Collections
                                                                            .singletonList(image)
                                                                    : new java.util.ArrayList<>(
                                                                            removed);
                                                }
                                                a.ui.post(
                                                        () -> {
                                                            if (ticket != a.generation
                                                                    || a.isFinishing()) return;
                                                            for (ImageView target : targets) {
                                                                if (target != null
                                                                        && address.equals(
                                                                                target.getTag())) {
                                                                    target.setImageBitmap(bitmap);
                                                                    com.pilipala.glass
                                                                            .GlassworkSurface
                                                                            .invalidateBackdrop(
                                                                                    target);
                                                                }
                                                            }
                                                        });
                                            }
                                        } catch (Exception e) {
                                            // 静默失败会让「封面空白」无从排查，这里留一条可查的痕迹
                                            AppLog.w(
                                                    "ImageLoader",
                                                    "poster failed: " + address,
                                                    e);
                                        } finally {
                                            synchronized (IN_FLIGHT) {
                                                if (IN_FLIGHT.get(key) == owned)
                                                    IN_FLIGHT.remove(key);
                                            }
                                        }
                                    });
                    for (java.util.Iterator<Future<?>> it = a.artworkRequests.iterator();
                            it.hasNext(); ) {
                        if (it.next().isDone()) it.remove();
                    }
                    a.artworkRequests.add(request);
                });
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
