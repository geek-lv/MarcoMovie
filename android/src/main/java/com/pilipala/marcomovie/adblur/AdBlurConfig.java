package com.pilipala.marcomovie.adblur;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 视频「牛皮癣」广告（贴片水印）模糊功能的配置。
 *
 * <p>存储层使用独立的 SharedPreferences {@code "adblur"}，风格与 {@code AdSkipConfig} 一致。
 * 静态接口；首次使用前须调用 {@link #init(Context)}（由 {@link AdBlurController#init} 与设置界面负责）。
 */
public class AdBlurConfig {

    private static final String PREFS = "adblur";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_BLUR_RADIUS = "blur_radius"; // 高斯模糊半径(px, 输出分辨率)
    private static final String KEY_BLUR_STYLE = "blur_style"; // 0=柔和高斯 1=马赛克
    private static final String KEY_MIN_CONFIDENCE = "min_confidence"; // 识别置信度门槛(0~100)
    private static final String KEY_DETECT_EVERY_N = "detect_every_n"; // 旧版：每 N 帧检测一次
    private static final String KEY_DETECT_INTERVAL_MS = "detect_interval_ms"; // 检测周期(ms)
    // v2：旧 key 可能被配置同步写入过 0.22，换 key 确保新默认阈值真正生效
    private static final String KEY_MATCH_THRESHOLD = "match_threshold_v2"; // ZNCC 失配度[0,1]，越低越像
    private static final String KEY_SCALE_MAX = "scale_max"; // 检测用降采样最大边长
    private static final String KEY_FEATHER = "feather"; // 边缘羽化(占短边比例)

    public static final int DEFAULT_BLUR_RADIUS = 18;
    public static final int STYLE_GAUSSIAN = 0;
    public static final int STYLE_MOSAIC = 1;
    /** 默认柔和高斯：小步长多轮迭代，无条纹网格感。 */
    public static final int DEFAULT_BLUR_STYLE = STYLE_GAUSSIAN;
    /** 置信度档位：高了宁可不糊也不糊错，低了更灵敏但会误报。 */
    public static final int CONF_STRICT = 72;
    public static final int CONF_BALANCED = 58;
    public static final int CONF_LOOSE = 42;
    public static final int DEFAULT_MIN_CONFIDENCE = CONF_BALANCED;
    public static final int DEFAULT_DETECT_EVERY_N = 6;
    /** 抽帧检测周期：500ms = 2Hz。按真实时间节流，不受片源帧率影响。 */
    public static final int DEFAULT_DETECT_INTERVAL_MS = 500;
    /** (1 - ZNCC) / 2，0=完全一致、0.5=无关；0.30 对应 ncc > 0.40。 */
    public static final float DEFAULT_MATCH_THRESHOLD = 0.30f;
    public static final int DEFAULT_SCALE_MAX = 200;
    public static final float DEFAULT_FEATHER = 0.04f;

    private static volatile SharedPreferences prefs;

    public static void init(Context context) {
        if (prefs != null) return;
        synchronized (AdBlurConfig.class) {
            if (prefs == null && context != null) {
                prefs =
                        context.getApplicationContext()
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

    /** 模糊样式：{@link #STYLE_GAUSSIAN} 平滑高斯 / {@link #STYLE_MOSAIC} 方块马赛克。 */
    public static int getBlurStyle() {
        return getInt(KEY_BLUR_STYLE, DEFAULT_BLUR_STYLE) == STYLE_MOSAIC
                ? STYLE_MOSAIC
                : STYLE_GAUSSIAN;
    }

    public static void setBlurStyle(int s) {
        put(KEY_BLUR_STYLE, s == STYLE_MOSAIC ? STYLE_MOSAIC : STYLE_GAUSSIAN);
    }

    /** 识别置信度门槛(0~100)：低于此值直接关闭模糊，只有高置信度才打码。 */
    public static int getMinConfidence() {
        return clampInt(getInt(KEY_MIN_CONFIDENCE, DEFAULT_MIN_CONFIDENCE), 20, 95);
    }

    public static void setMinConfidence(int v) {
        put(KEY_MIN_CONFIDENCE, clampInt(v, 20, 95));
    }

    /** 检测周期(ms)，默认 500ms（2Hz）。检测只更新矩形，模糊仍逐帧绘制。 */
    public static int getDetectIntervalMs() {
        return clampInt(getInt(KEY_DETECT_INTERVAL_MS, DEFAULT_DETECT_INTERVAL_MS), 100, 5000);
    }

    public static void setDetectIntervalMs(int ms) {
        put(KEY_DETECT_INTERVAL_MS, clampInt(ms, 100, 5000));
    }

    /** @deprecated 旧版按帧数节流，帧率变化时周期不稳；仅为兼容旧配置快照保留。 */
    @Deprecated
    public static int getDetectEveryN() {
        return clampInt(getInt(KEY_DETECT_EVERY_N, DEFAULT_DETECT_EVERY_N), 1, 30);
    }

    /** @deprecated 见 {@link #getDetectEveryN()}。 */
    @Deprecated
    public static void setDetectEveryN(int n) {
        put(KEY_DETECT_EVERY_N, clampInt(n, 1, 30));
    }

    /** 匹配失配度阈值[0,1]；低于此值判定为命中广告框。越小越严格、越易漏检。 */
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

    /** 矩形外侧羽化范围（占短边比例）：矩形内全模糊，向外渐变到清晰，不吃掉四边。 */
    public static float getFeather() {
        return clampFloat(getFloat(KEY_FEATHER, DEFAULT_FEATHER), 0f, 0.3f);
    }

    public static void setFeather(float f) {
        put(KEY_FEATHER, clampFloat(f, 0f, 0.3f));
    }

}
