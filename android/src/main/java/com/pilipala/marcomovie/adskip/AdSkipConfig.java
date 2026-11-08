package com.pilipala.marcomovie.adskip;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * 内嵌广告（音轨里烧录的口播广告）识别与跳过的配置。
 *
 * <p>移植自 TVBoxOSC，存储层由 Hawk 改为本应用惯用的 SharedPreferences。
 * 静态接口与原版保持一致；首次使用前须调用 {@link #init(Context)}
 * （由 {@link AdSkipController#init} 与设置界面负责，重复调用无副作用）。
 */
public class AdSkipConfig {

    public static final int DEFAULT_SKIP_SECONDS = 30;

    /**
     * 开箱即用的口播关键词。移植自 TVBoxOSC 设置里的示例词。
     *
     * <p>注意：原片刻版这里默认空串，而 {@link #isEnabled()} 要求「有关键词」才开启，
     * 于是全新安装后功能恒为关闭状态 —— 这正是「移植后检测没起作用」的原因之一。
     * 这里给默认值让功能开箱可用，用户可在设置里随时清空/覆盖。
     */
    public static final String DEFAULT_KEYWORDS = "澳门新葡京,新葡京";

    private static final String PREFS = "adskip";
    private static final String KEY_SKIP_SECONDS = "skip_seconds";
    private static final String KEY_KEYWORDS_RAW = "keywords_raw";

    private static volatile SharedPreferences prefs;

    /** 初始化存储。重复调用无副作用；未初始化时所有读写走内存默认值。 */
    public static void init(Context context) {
        if (prefs != null) return;
        synchronized (AdSkipConfig.class) {
            if (prefs == null && context != null) {
                prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
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

    private static void put(String key, String value) {
        SharedPreferences p = prefs;
        if (p != null) p.edit().putString(key, value).apply();
    }

    private static void put(String key, int value) {
        SharedPreferences p = prefs;
        if (p != null) p.edit().putInt(key, value).apply();
    }

    /** 识别到广告后向前跳过的秒数，0 表示关闭该功能 */
    public static int getSkipSeconds() {
        return Math.max(0, getInt(KEY_SKIP_SECONDS, DEFAULT_SKIP_SECONDS));
    }

    public static void setSkipSeconds(int seconds) {
        put(KEY_SKIP_SECONDS, Math.max(0, seconds));
    }

    /** 返回毫秒，便于直接 seekTo */
    public static long getSkipMillis() {
        return getSkipSeconds() * 1000L;
    }

    /** 原始关键词文本，用于回填输入框 */
    public static String getKeywordsRaw() {
        return getString(KEY_KEYWORDS_RAW, DEFAULT_KEYWORDS);
    }

    public static void setKeywordsRaw(String raw) {
        put(KEY_KEYWORDS_RAW, raw == null ? "" : raw.trim());
    }

    /**
     * 解析关键词列表。支持中文逗号、英文逗号、分号、换行分隔。
     */
    public static List<String> getKeywordList() {
        List<String> list = new ArrayList<>();
        String raw = getKeywordsRaw();
        if (raw.isEmpty()) return list;
        String[] parts = raw.split("[,，;；\\r?\\n]+");
        for (String part : parts) {
            String keyword = part.trim();
            if (!keyword.isEmpty() && !list.contains(keyword)) {
                list.add(keyword);
            }
        }
        return list;
    }

    /** 关键词数量，用于按钮上显示 */
    public static int getKeywordCount() {
        return getKeywordList().size();
    }

    /** 只有同时配置了跳过时长和关键词，功能才算开启 */
    public static boolean isEnabled() {
        return getSkipSeconds() > 0 && getKeywordCount() > 0;
    }

    /**
     * 判断识别出的文本是否命中广告关键词。
     * 供后续 ASR / KWS 模块调用。
     */
    public static String matchKeyword(String text) {
        if (text == null || text.isEmpty()) return null;
        String normalized = text.replace(" ", "");
        for (String keyword : getKeywordList()) {
            if (normalized.contains(keyword.replace(" ", ""))) {
                return keyword;
            }
        }
        return null;
    }
}
