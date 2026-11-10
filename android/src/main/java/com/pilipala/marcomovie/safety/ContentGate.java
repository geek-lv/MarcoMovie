package com.pilipala.marcomovie.safety;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 内容过滤的统一入口。
 *
 * <p>把"要不要拦"这件事收敛到一个地方，避免每条内容通路各写一套判定。
 * 覆盖范围：搜索结果、详情、收藏、历史、播放列表、直播频道、直连播放器。
 *
 * <p>全部本地判定，不产生任何网络请求。只有 {@link #locked()} 为真时才拦
 * （总开关打开且未临时解锁）。
 */
public final class ContentGate {

    private ContentGate() {}

    /** 当前是否处于"应当拦截"的状态：总开关打开且未临时解锁 */
    public static boolean locked() {
        return ContentFilterConfig.enabled() && !ContentFilterConfig.unlocked();
    }

    /**
     * 条目级判定。
     *
     * @return 命中原因，null 表示放行
     */
    public static String reasonItem(
            String name, String type, String remarks, String area, String desc) {
        if (!locked()) return null;
        return AdultRules.matchItem(
                name,
                type,
                remarks,
                area,
                ContentFilterConfig.scanDescription() ? desc : null,
                ContentFilterConfig.numberRegex(),
                ContentFilterConfig.extraKeywords(),
                ContentFilterConfig.strictSuggestive());
    }

    /** 是否应隐藏该条目 */
    public static boolean blockedItem(
            String name, String type, String remarks, String area, String desc) {
        return reasonItem(name, type, remarks, area, desc) != null;
    }

    /** 直播频道：频道名 + 分组 */
    public static boolean blockedChannel(String name, String group) {
        if (!locked()) return false;
        return AdultRules.matchChannel(name, group, true) != null;
    }

    /**
     * 收藏 / 历史 / 播放列表里的记录。兼容两种结构：
     * 带 {@code item} 子对象的（收藏、历史），以及扁平的（片单）。
     */
    public static boolean blockedRecord(JSONObject entry) {
        if (!locked() || entry == null) return false;
        JSONObject item = entry.optJSONObject("item");
        if (item != null) {
            return blockedItem(
                    firstOf(item, "name", "vod_name", "title"),
                    item.optString("typeName", item.optString("type_name")),
                    item.optString("remarks"),
                    item.optString("area"),
                    item.optString("content", item.optString("vod_content")));
        }
        return blockedItem(
                firstOf(entry, "title", "name", "vod_name"),
                entry.optString("typeName", entry.optString("type_name")),
                entry.optString("remarks"),
                entry.optString("area"),
                entry.optString("content", entry.optString("vod_content")));
    }

    /** 过滤记录数组；未锁定时原样返回 */
    public static JSONArray filterRecords(JSONArray entries) {
        if (!locked() || entries == null) return entries;
        JSONArray visible = new JSONArray();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null) continue;
            if (!blockedRecord(entry)) visible.put(entry);
        }
        return visible;
    }

    /** 过滤直播频道数组；未锁定时原样返回 */
    public static JSONArray filterChannels(JSONArray channels) {
        if (!locked() || channels == null) return channels;
        JSONArray visible = new JSONArray();
        for (int i = 0; i < channels.length(); i++) {
            JSONObject channel = channels.optJSONObject(i);
            if (channel == null) continue;
            if (!blockedChannel(channel.optString("name"), channel.optString("group")))
                visible.put(channel);
        }
        return visible;
    }

    private static String firstOf(JSONObject object, String... keys) {
        for (String key : keys) {
            String value = object.optString(key, "");
            if (!value.isEmpty()) return value;
        }
        return "";
    }
}
