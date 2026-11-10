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
}
