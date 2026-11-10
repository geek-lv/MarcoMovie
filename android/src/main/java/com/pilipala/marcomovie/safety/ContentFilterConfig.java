package com.pilipala.marcomovie.safety;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 内容过滤的配置与状态存储。
 *
 * <p>全部数据留在本机 SharedPreferences，不上传、不请求任何外部接口：
 *
 * <ul>
 *   <li>开关：总开关、简介关键词、番号正则；</li>
 *   <li>儿童锁：PIN 只存加盐 SHA-256，临时解锁窗口只活在内存里（进程结束自动重新锁定）；</li>
 *   <li>来源标记：被判定为成人源的 {@code key} 与判定原因、时间戳。</li>
 * </ul>
 *
 * <p>接口与 {@code AdSkipConfig} 保持同一套静态风格，重复 {@link #init(Context)} 无副作用。
 */
public final class ContentFilterConfig {

    /** 一次临时解锁的有效时长 */
    public static final long UNLOCK_MILLIS = 30 * 60 * 1000L;

    private static final String PREFS = "content_filter";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_PIN_SALT = "pin_salt";
    private static final String KEY_PIN_HASH = "pin_hash";
    private static final String KEY_EXTRA = "extra_keywords";
    private static final String KEY_MARKS = "source_marks";

    private static volatile SharedPreferences prefs;

    /** 标记表的只读缓存（原文 + 解析结果），写入时同步失效 */
    private static volatile String marksRaw;
    private static volatile JSONObject marksParsed;

    /** 临时解锁截止时间；只存内存，应用重启即失效 */
    private static volatile long unlockUntil;

    private ContentFilterConfig() {
    }

    public static void init(Context context) {
        if (prefs != null) return;
        synchronized (ContentFilterConfig.class) {
            if (prefs == null && context != null) {
                prefs = context.getApplicationContext()
                        .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            }
        }
    }

    // ---------------------------------------------------------------- 开关

    /** 总开关，默认开启：这类内容默认不该出现在家庭设备上 */
    public static boolean enabled() {
        SharedPreferences p = prefs;
        return p == null || p.getBoolean(KEY_ENABLED, true);
    }

    public static void setEnabled(boolean enabled) {
        SharedPreferences p = prefs;
        if (p == null) return;
        SharedPreferences.Editor editor = p.edit().putBoolean(KEY_ENABLED, enabled);
        if (enabled) {
            // 旧版本的细分开关已废弃，重新开启时顺手清掉残留
            editor.remove("scan_description").remove("number_regex")
                    .remove("strict_first_sight").remove("strict_suggestive");
        }
        editor.apply();
    }

    // 判定强度固定为最高档，不再提供细分开关。
    // 下面几个方法保留成常量，是为了让调用点读起来仍有语义，而不是散落的 true。

    /** 始终扫描详情页简介 */
    public static boolean scanDescription() {
        return true;
    }

    /** 始终启用番号样式判定 */
    public static boolean numberRegex() {
        return true;
    }

    /** 始终「先判定再显示」：可疑来源的整块结果在交付界面前就丢弃 */
    public static boolean strictFirstSight() {
        return true;
    }

    // ---------------------------------------------------------------- 儿童锁

    public static boolean hasPin() {
        SharedPreferences p = prefs;
        return p != null && p.getString(KEY_PIN_HASH, "").length() > 0;
    }

    /** 设置或修改 PIN；只接受 4–6 位数字。成功后立即重新锁定。 */
    public static synchronized boolean setPin(String pin) {
        SharedPreferences p = prefs;
        if (p == null || pin == null || !pin.matches("\\d{4,6}")) return false;
        String salt = hex(randomBytes(8));
        p.edit()
                .putString(KEY_PIN_SALT, salt)
                .putString(KEY_PIN_HASH, sha256(salt + pin))
                .apply();
        unlockUntil = 0;
        return true;
    }

    public static synchronized void clearPin() {
        SharedPreferences p = prefs;
        if (p == null) return;
        p.edit().remove(KEY_PIN_SALT).remove(KEY_PIN_HASH).apply();
        unlockUntil = 0;
    }

    public static boolean verifyPin(String pin) {
        SharedPreferences p = prefs;
        if (p == null || pin == null || !pin.matches("\\d{4,6}")) return false;
        String salt = p.getString(KEY_PIN_SALT, "");
        String hash = p.getString(KEY_PIN_HASH, "");
        if (salt.isEmpty() || hash.isEmpty()) return false;
        return MessageDigest.isEqual(
                hash.getBytes(StandardCharsets.UTF_8),
                sha256(salt + pin).getBytes(StandardCharsets.UTF_8));
    }

    /** 当前是否处于临时解锁状态。没有 PIN 时永远为 false：默认就是锁住的。 */
    public static boolean unlocked() {
        long until = unlockUntil;
        return until > 0 && System.currentTimeMillis() < until;
    }

    public static void unlock() {
        unlockUntil = System.currentTimeMillis() + UNLOCK_MILLIS;
    }

    public static void lock() {
        unlockUntil = 0;
    }

    /** 解锁剩余分钟数，用于设置页展示 */
    public static long unlockMinutesLeft() {
        long left = unlockUntil - System.currentTimeMillis();
        return left <= 0 ? 0 : (left + 59999) / 60000;
    }

    // ---------------------------------------------------------------- 关键词

    /** 用户追加的关键词原文，用于回填输入框 */
    public static String extraKeywordsRaw() {
        SharedPreferences p = prefs;
        return p == null ? "" : p.getString(KEY_EXTRA, "");
    }

    /** 与 {@link #extraKeywordsRaw()} 同义，供配置同步序列化调用（保持命名一致） */
    public static String getExtraKeywordsRaw() {
        return extraKeywordsRaw();
    }

    public static void setExtraKeywordsRaw(String raw) {
        SharedPreferences p = prefs;
        if (p != null) p.edit().putString(KEY_EXTRA, raw == null ? "" : raw.trim()).apply();
    }

    /** 解析用户追加关键词，支持中英文逗号、分号与换行分隔 */
    public static List<String> extraKeywords() {
        List<String> list = new ArrayList<>();
        String raw = extraKeywordsRaw();
        if (raw.isEmpty()) return list;
        for (String part : raw.split("[,，;；\\r\\n]+")) {
            String keyword = part.trim();
            if (!keyword.isEmpty() && !list.contains(keyword)) list.add(keyword);
        }
        return list;
    }

    public static int extraKeywordCount() {
        return extraKeywords().size();
    }

    // ---------------------------------------------------------------- 来源标记

    /**
     * 只读访问。带缓存：搜索结果里每一条都要问一次"这个来源是不是成人源"，
     * 每次都重新解析 JSON 会拖慢结果渲染。
     */
    private static JSONObject marks() {
        SharedPreferences p = prefs;
        if (p == null) return new JSONObject();
        String raw = p.getString(KEY_MARKS, "{}");
        JSONObject parsed = marksParsed;
        if (parsed != null && raw.equals(marksRaw)) return parsed;
        try {
            parsed = new JSONObject(raw);
        } catch (Exception ignored) {
            parsed = new JSONObject();
        }
        marksParsed = parsed;
        marksRaw = raw;
        return parsed;
    }

    /** 写入路径：另取一份可改副本，避免污染只读缓存 */
    private static JSONObject marksForWrite() {
        SharedPreferences p = prefs;
        if (p == null) return new JSONObject();
        try {
            return new JSONObject(p.getString(KEY_MARKS, "{}"));
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private static void writeMarks(JSONObject marks) {
        SharedPreferences p = prefs;
        if (p == null) return;
        String raw = marks.toString();
        // 用 commit：标记结果要立即对正在跑的搜索可见，apply 的异步写入会让缓存读到旧值
        p.edit().putString(KEY_MARKS, raw).commit();
        marksParsed = marks;
        marksRaw = raw;
    }

    /** 标记为成人源；reason 会展示在设置页，便于回看判定依据 */
    public static synchronized void markAdult(String sourceKey, String reason, String sourceName) {
        if (sourceKey == null || sourceKey.isEmpty()) return;
        try {
            JSONObject marks = marksForWrite();
            JSONObject entry = new JSONObject();
            entry.put("adult", true);
            entry.put("why", reason == null ? "" : reason);
            entry.put("name", sourceName == null ? "" : sourceName);
            entry.put("ts", System.currentTimeMillis());
            marks.put(sourceKey, entry);
            writeMarks(marks);
        } catch (Exception ignored) {
        }
    }

    /** 手动取消标记（用户认为误判） */
    public static synchronized void unmark(String sourceKey) {
        try {
            JSONObject marks = marksForWrite();
            marks.remove(sourceKey);
            writeMarks(marks);
        } catch (Exception ignored) {
        }
    }

    /**
     * 用户明确否掉这条判定。与 {@link #unmark} 不同，会留下「已否决」记录，
     * 后续的名称初判与被动统计都不会再把它标回成人源。
     */
    public static synchronized void dismiss(String sourceKey, String sourceName) {
        if (sourceKey == null || sourceKey.isEmpty()) return;
        try {
            JSONObject marks = marksForWrite();
            JSONObject entry = new JSONObject();
            entry.put("adult", false);
            entry.put("dismissed", true);
            entry.put("name", sourceName == null ? "" : sourceName);
            entry.put("ts", System.currentTimeMillis());
            marks.put(sourceKey, entry);
            writeMarks(marks);
        } catch (Exception ignored) {
        }
    }

    /** 该来源是否已有判定结论（成人与「已否决」都算，避免反复自动标记） */
    public static boolean hasMark(String sourceKey) {
        if (sourceKey == null || sourceKey.isEmpty()) return false;
        return marks().has(sourceKey);
    }

    public static boolean isDismissed(String sourceKey) {
        JSONObject entry = marks().optJSONObject(sourceKey);
        return entry != null && entry.optBoolean("dismissed", false);
    }

    public static boolean isAdult(String sourceKey) {
        if (sourceKey == null || sourceKey.isEmpty()) return false;
        JSONObject entry = marks().optJSONObject(sourceKey);
        return entry != null && entry.optBoolean("adult", false);
    }

    public static String markReason(String sourceKey) {
        JSONObject entry = marks().optJSONObject(sourceKey);
        return entry == null ? "" : entry.optString("why", "");
    }

    public static String markName(String sourceKey) {
        JSONObject entry = marks().optJSONObject(sourceKey);
        return entry == null ? "" : entry.optString("name", "");
    }

    public static int adultCount() {
        JSONObject marks = marks();
        int count = 0;
        java.util.Iterator<String> keys = marks.keys();
        while (keys.hasNext()) {
            JSONObject entry = marks.optJSONObject(keys.next());
            if (entry != null && entry.optBoolean("adult", false)) count++;
        }
        return count;
    }

    /** 已标记的 key 集合，供来源列表过滤使用 */
    public static Set<String> adultKeys() {
        Set<String> keys = new HashSet<>();
        JSONObject marks = marks();
        java.util.Iterator<String> iterator = marks.keys();
        while (iterator.hasNext()) {
            String key = iterator.next();
            JSONObject entry = marks.optJSONObject(key);
            if (entry != null && entry.optBoolean("adult", false)) keys.add(key);
        }
        return keys;
    }

    public static synchronized void clearMarks() {
        writeMarks(new JSONObject());
    }

    /**
     * 低俗倾向词的判定强度：固定最严档，命中 1 个就拦。
     *
     * <p>不给开关是刻意的——这一层的目标是家庭设备上的"不出现"，宁可误杀。
     * 关掉（改为按 2 个不同倾向词累计）会放过《极品家丁》《美女与野兽》这类只碰一个词的
     * 正经片名，换来的是同样的漏网风险，取舍上不划算。误杀可以在来源列表里逐个手动取消标记。
     */
    public static boolean strictSuggestive() {
        return true;
    }

    // ---------------------------------------------------------------- 工具

    private static byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    private static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) builder.append(String.format("%02x", b));
        return builder.toString();
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return hex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
