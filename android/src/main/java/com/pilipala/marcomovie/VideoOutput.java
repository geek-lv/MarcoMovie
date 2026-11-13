package com.pilipala.marcomovie;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.view.*;
import android.widget.FrameLayout;

/** SurfaceView preserves the decoder's HDR output; TextureView remains an explicit fallback. */
final class VideoOutput extends FrameLayout
        implements com.pilipala.glass.GlassworkSurface.FrameSource {
    interface Listener {
        void available(Surface surface);

        void destroyed();
    }

    private Listener listener;
    private Surface current;
    private final TextureView texture;
    private SurfaceView surfaceView;
    private Bitmap glassFrame;
    private boolean framePending, advancing;
    private long frameAt;
    private int frameGeneration;
    private final java.util.ArrayList<Runnable> frameWaiters = new java.util.ArrayList<>();
    private final Paint glassPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    VideoOutput(Context context, boolean compatible) {
        super(context);
        setTag("video-output");
        setFocusable(false);
        if (compatible || Build.VERSION.SDK_INT < 24) {
            texture = new TextureView(context);
            addView(texture, new LayoutParams(-1, -1));
            texture.setSurfaceTextureListener(
                    new TextureView.SurfaceTextureListener() {
                        public void onSurfaceTextureAvailable(SurfaceTexture value, int w, int h) {
                            ready(new Surface(value));
                        }

                        public void onSurfaceTextureSizeChanged(
                                SurfaceTexture value, int w, int h) {}

                        public boolean onSurfaceTextureDestroyed(SurfaceTexture value) {
                            gone();
                            return true;
                        }

                        public void onSurfaceTextureUpdated(SurfaceTexture value) {}
                    });
        } else {
            texture = null;
            SurfaceView surface = new SurfaceView(context);
            surfaceView = surface;
            addView(surface, new LayoutParams(-1, -1));
            surface.getHolder()
                    .addCallback(
                            new SurfaceHolder.Callback() {
                                public void surfaceCreated(SurfaceHolder holder) {
                                    ready(holder.getSurface());
                                }

                                public void surfaceChanged(
                                        SurfaceHolder holder, int format, int w, int h) {}

                                public void surfaceDestroyed(SurfaceHolder holder) {
                                    gone();
                                }
                            });
        }
    }

    void listen(Listener value) {
        listener = value;
        if (current != null && current.isValid()) listener.available(current);
    }

    private void ready(Surface value) {
        current = value;
        if (listener != null) listener.available(value);
    }

    private void gone() {
        frameGeneration++;
        glassFrame = null;
        framePending = false;
        finishGlassFrame();
        Surface old = current;
        current = null;
        if (listener != null) listener.destroyed();
        if (texture != null && old != null) old.release();
    }

    boolean supportsPicture() {
        return texture != null;
    }

    String outputName() {
        return texture == null ? "SurfaceView · 保真输出" : "TextureView · 兼容输出";
    }

    void picture(int preset) {
        if (texture != null) PlaybackEffects.picture(texture, preset);
    }

    void setFrameAdvancing(boolean value) {
        if (advancing == value) return;
        advancing = value;
        com.pilipala.glass.GlassworkSurface.invalidateBackdrop(this);
    }

    public boolean glassFrameAdvancing() {
        return advancing && current != null;
    }

    /** 内层 SurfaceView（保真输出模式）；兼容输出模式下为 null。 */
    public SurfaceView surfaceView() {
        return surfaceView;
    }

    /** 内层 TextureView（兼容输出模式）；保真输出模式下为 null。 */
    public TextureView textureView() {
        return texture;
    }

    /** 全分辨率抓帧回调（主线程）。bitmap 为显示朝向，可能为 null（抓取失败），调用方负责回收。 */
    interface FullFrameCallback {
        void onFrame(Bitmap bitmap);
    }

    /**
     * 抓取当前显示帧（全分辨率）。应在暂停后调用：TextureView 直取合成位图，
     * SurfaceView 走 PixelCopy 抓取当前屏幕内容——均不经过 GL 特效管线，
     * 不受数据空间 / HDR 与管线初始化问题影响。主线程回调。
     */
    public void snapshotFullFrame(FullFrameCallback callback) {
        if (current == null || !current.isValid() || getWidth() <= 0 || getHeight() <= 0) {
            callback.onFrame(null);
            return;
        }
        if (texture != null) {
            Bitmap frame = null;
            try {
                frame = texture.getBitmap();
            } catch (RuntimeException unavailable) {
                frame = null;
            }
            callback.onFrame(frame);
        } else if (Build.VERSION.SDK_INT >= 24 && surfaceView != null) {
            final Bitmap frame =
                    Bitmap.createBitmap(getWidth(), getHeight(), Bitmap.Config.ARGB_8888);
            try {
                PixelCopy.request(
                        surfaceView,
                        frame,
                        result -> {
                            if (result == PixelCopy.SUCCESS) {
                                callback.onFrame(frame);
                            } else {
                                frame.recycle();
                                callback.onFrame(null);
                            }
                        },
                        new Handler(Looper.getMainLooper()));
            } catch (RuntimeException unavailable) {
                frame.recycle();
                callback.onFrame(null);
            }
        } else {
            callback.onFrame(null);
        }
    }

    /** All visible controls share one bounded readback per 100 ms. */
    public void prepareGlassFrame(Runnable ready) {
        long now = android.os.SystemClock.uptimeMillis();
        if (current == null
                || !current.isValid()
                || getWidth() <= 0
                || getHeight() <= 0
                || (frameAt > 0 && now - frameAt < 100)) {
            ready.run();
            return;
        }
        frameWaiters.add(ready);
        if (framePending) return;
        framePending = true;
        final int token = frameGeneration;
        double scale =
                Math.min(
                        1,
                        Math.min(
                                320.0 / Math.max(getWidth(), getHeight()),
                                Math.sqrt(57600.0 / ((double) getWidth() * getHeight()))));
        final Bitmap frame =
                Bitmap.createBitmap(
                        Math.max(1, (int) (getWidth() * scale)),
                        Math.max(1, (int) (getHeight() * scale)),
                        Bitmap.Config.ARGB_8888);
        if (texture != null) {
            try {
                glassFrame = texture.getBitmap(frame);
                frameAt = now;
            } catch (RuntimeException unavailable) {
                frame.recycle();
                glassFrame = null;
            }
            frameAt = now;
            framePending = false;
            finishGlassFrame();
        } else if (Build.VERSION.SDK_INT >= 24 && surfaceView != null) {
            try {
                PixelCopy.request(
                        surfaceView,
                        frame,
                        result -> {
                            if (token != frameGeneration) {
                                frame.recycle();
                                return;
                            }
                            glassFrame = result == PixelCopy.SUCCESS ? frame : null;
                            if (glassFrame == null) frame.recycle();
                            frameAt = android.os.SystemClock.uptimeMillis();
                            framePending = false;
                            finishGlassFrame();
                        },
                        new Handler(Looper.getMainLooper()));
            } catch (RuntimeException unavailable) {
                frame.recycle();
                glassFrame = null;
                frameAt = now;
                framePending = false;
                finishGlassFrame();
            }
        } else {
            frame.recycle();
            framePending = false;
            finishGlassFrame();
        }
    }

    private void finishGlassFrame() {
        java.util.ArrayList<Runnable> callbacks = new java.util.ArrayList<>(frameWaiters);
        frameWaiters.clear();
        for (Runnable callback : callbacks) callback.run();
    }

    @Override
    public void draw(Canvas canvas) {
        if (com.pilipala.glass.GlassworkSurface.isCapturingBackdrop()) {
            if (glassFrame != null && !glassFrame.isRecycled())
                canvas.drawBitmap(
                        glassFrame, null, new Rect(0, 0, getWidth(), getHeight()), glassPaint);
            else canvas.drawColor(com.pilipala.glass.GlassworkMaterial.DARK_TINT);
            return;
        }
        super.draw(canvas);
    }
}
