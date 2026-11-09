package com.pilipala.marcomovie.adblur;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 视频「牛皮癣」广告（二维码 / 贴片水印）模糊功能的配置。
 *
 * <p>存储层使用独立的 SharedPreferences {@code "adblur"}，风格与 {@code AdSkipConfig} 一致。
 * 静态接口；首次使用前须调用 {@link #init(Context)}（由 {@link AdBlurController#init} 与设置界面负责）。
 */
public class AdBlurConfig {

    private static final String PREFS = "adblur";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_BLUR_RADIUS = "blur_radius";        // 高斯模糊半径(px, 输出分辨率)
    private static final String KEY_DETECT_EVERY_N = "detect_every_n";  // 每 N 帧检测一次
    private static final String KEY_MATCH_THRESHOLD = "match_threshold"; // SAD 均值阈值[0,1]
    private static final String KEY_SCALE_MAX = "scale_max";            // 检测用降采样最大边长
    private static final String KEY_FEATHER = "feather";                // 边缘羽化(占短边比例)
    private static final String KEY_USE_ZXING = "use_zxing";            // 额外用 ZXing 定位二维码

    public static final int DEFAULT_BLUR_RADIUS = 18;
    public static final int DEFAULT_DETECT_EVERY_N = 6;
    public static final float DEFAULT_MATCH_THRESHOLD = 0.12f;
    public static final int DEFAULT_SCALE_MAX = 200;
    public static final float DEFAULT_FEATHER = 0.04f;
    public static final boolean DEFAULT_USE_ZXING = true;

    private static volatile SharedPreferences prefs;

    public static void init(Context context) {
        if (prefs != null) return;
        synchronized (AdBlurConfig.class) {
            if (prefs == null && context != null) {
                prefs = context.getApplicationContext()
                        .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            }
        }
    }

    private static String getString(String key, String def) {
        SharedPreferences p = prefs;
        return p == null ? def : p.getString(key, def);
    }

    private static int getInt(String key, int def) {
        SharedPreferences p = prefs;
        return p == null ? def : p.getInt(key, def);
    }

    private static float getFloat(String key, float def) {
        SharedPreferences p = prefs;
        return p == null ? def : p.getFloat(key, def);
    }

    private static boolean getBoolean(String key, boolean def) {
        SharedPreferences p = prefs;
        return p == null ? def : p.getBoolean(key, def);
    }

    private static void put(String key, String v) {
        SharedPreferences p = prefs;
        if (p != null) p.edit().putString(key, v).apply();
    }

    private static void put(String key, int v) {
        SharedPreferences p = prefs;
        if (p != null) p.edit().putInt(key, v).apply();
    }

    private static void put(String key, float v) {
        SharedPreferences p = prefs;
        if (p != null) p.edit().putFloat(key, v).apply();
    }

    private static void put(String key, boolean v) {
        SharedPreferences p = prefs;
        if (p != null) p.edit().putBoolean(key, v).apply();
    }

    private static int clampInt(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static float clampFloat(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** 是否开启牛皮癣模糊。关闭时完全不挂载特效（零开销）。 */
    public static boolean isEnabled() {
        return getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(boolean e) {
        put(KEY_ENABLED, e);
    }

    /** 高斯模糊半径（输出像素），越大越「毛玻璃」。 */
    public static int getBlurRadius() {
        return clampInt(getInt(KEY_BLUR_RADIUS, DEFAULT_BLUR_RADIUS), 2, 60);
    }

    public static void setBlurRadius(int r) {
        put(KEY_BLUR_RADIUS, clampInt(r, 2, 60));
    }

    /** 每 N 帧执行一次模板检测（其余帧复用上一帧矩形）。N 越大越省电，响应越迟钝。 */
    public static int getDetectEveryN() {
        return clampInt(getInt(KEY_DETECT_EVERY_N, DEFAULT_DETECT_EVERY_N), 1, 30);
    }

    public static void setDetectEveryN(int n) {
        put(KEY_DETECT_EVERY_N, clampInt(n, 1, 30));
    }

    /** SAD 平均差阈值[0,1]；低于此值判定为命中广告框。越小越严格、越易漏检。 */
    public static float getMatchThreshold() {
        return clampFloat(getFloat(KEY_MATCH_THRESHOLD, DEFAULT_MATCH_THRESHOLD), 0.02f, 0.5f);
    }

    public static void setMatchThreshold(float t) {
        put(KEY_MATCH_THRESHOLD, clampFloat(t, 0.02f, 0.5f));
    }

    /** 检测用降采样后的最大边长，越小越快、定位越粗。 */
    public static int getScaleMax() {
        return clampInt(getInt(KEY_SCALE_MAX, DEFAULT_SCALE_MAX), 64, 512);
    }

    public static void setScaleMax(int s) {
        put(KEY_SCALE_MAX, clampInt(s, 64, 512));
    }

    /** 矩形边缘羽化范围（占短边比例），让模糊与清晰之间平滑过渡。 */
    public static float getFeather() {
        return clampFloat(getFloat(KEY_FEATHER, DEFAULT_FEATHER), 0f, 0.3f);
    }

    public static void setFeather(float f) {
        put(KEY_FEATHER, clampFloat(f, 0f, 0.3f));
    }

    /** 是否额外用 ZXing 对画面中的二维码做定位（无需特征图即可模糊二维码类牛皮癣）。 */
    public static boolean isUseZxing() {
        return getBoolean(KEY_USE_ZXING, DEFAULT_USE_ZXING);
    }

    public static void setUseZxing(boolean b) {
        put(KEY_USE_ZXING, b);
    }
}
