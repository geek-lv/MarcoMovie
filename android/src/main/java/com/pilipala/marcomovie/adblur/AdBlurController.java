package com.pilipala.marcomovie.adblur;

import android.content.Context;

import androidx.annotation.Nullable;

import com.pilipala.marcomovie.adblur.QrAdBlurEffect.CaptureListener;

/**
 * 牛皮癣模糊功能的协调器（单例）。
 *
 * <p>负责：初始化配置、持有当前播放实例挂载的 {@link QrAdBlurEffect}、转发截图捕获请求、
 * 以及在模板目录变化（用户新增/删除特征图）时通知播放器重建特效。
 */
public class AdBlurController {

    private static volatile AdBlurController instance;

    private Context appContext;
    @Nullable private QrAdBlurEffect currentEffect;
    @Nullable private CaptureListener captureListener;
    @Nullable private Runnable onReload;

    public static AdBlurController get(Context context) {
        if (instance == null) {
            synchronized (AdBlurController.class) {
                if (instance == null) instance = new AdBlurController(context);
            }
        }
        return instance;
    }

    private AdBlurController(Context context) {
        this.appContext = context.getApplicationContext();
        AdBlurConfig.init(this.appContext);
        // 旧版 64px 无 .meta 模板会全帧乱匹配，装新版后自动清理一次。
        AdBlurTemplates.purgeLegacy(this.appContext);
    }

    /** 构建（或重建）特效实例供播放器挂载。 */
    public QrAdBlurEffect buildEffect() {
        QrAdBlurEffect e = new QrAdBlurEffect(appContext);
        if (captureListener != null) e.setCaptureListener(captureListener);
        currentEffect = e;
        return e;
    }

    @Nullable
    public QrAdBlurEffect getCurrentEffect() {
        return currentEffect;
    }

    public void setCaptureListener(@Nullable CaptureListener l) {
        this.captureListener = l;
        if (currentEffect != null) currentEffect.setCaptureListener(l);
    }

    /** 请求捕获当前帧（结果经 CaptureListener 回传，在主线程）。 */
    public void requestCapture() {
        if (currentEffect != null) currentEffect.requestCapture();
    }

    /** 播放器注册重建回调：模板目录变化后由控制器触发。 */
    public void setOnReload(@Nullable Runnable r) {
        this.onReload = r;
    }

    /** 用户新增/删除特征图后调用，触发播放器重建特效以热重载。 */
    public void notifyTemplatesChanged() {
        if (onReload != null) onReload.run();
    }

    // —— 会话级特效停用（HDR 片源 / 特效管线不兼容时的优雅降级）——

    private volatile boolean suspended;

    /**
     * 本次播放会话内临时停用特效：HDR 片源（特效仅支持 SDR）或特效管线导致播放失败时调用，
     * 之后 {@code applyAdBlurEffect} 不再挂载，播放走普通 Surface 输出路径。
     */
    public void suspendEffects() {
        suspended = true;
    }

    public boolean effectsSuspended() {
        return suspended;
    }

    /** 新的媒体项开始（setDataSource）时重置，让下一条片源重新尝试启用特效。 */
    public void resumeEffects() {
        suspended = false;
    }
}
