package com.pilipala.marcomovie;

import com.pilipala.marcomovie.safety.AdultRules;
import com.pilipala.marcomovie.safety.AdultSourceStats;
import com.pilipala.marcomovie.safety.ContentFilterConfig;
import com.pilipala.marcomovie.safety.ContentGate;
import java.util.Iterator;
import java.util.List;

/**
 * 内容安全（成人内容过滤）在 MarcoMovie 数据流中的统一接入点。
 *
 * <p>移植自片刻 APP 的 ContentGate / AdultRules / AdultSourceStats 体系，
 * 适配 MarcoMovie 的 {@link Cms.Video} / {@link LiveCatalog.Channel} 数据结构。
 * 全部本地判定，不产生任何网络请求。
 *
 * <p>挂载拓扑：
 * <ul>
 *   <li>{@code Cms.listing}：所有列表 / 搜索 / 详情解析的公共出口，一处兜底条目过滤；</li>
 *   <li>带 site 的重载：额外做「整块判定 + 成人来源累计标记」，可疑来源整块丢弃；</li>
 *   <li>{@code MainActivity.openListing}：外部跳转 / 历史入口再拦一次；</li>
 *   <li>{@code Favorites} / {@code WatchHistory}：收藏与历史；</li>
 *   <li>{@code LiveCatalog.parse}：直播频道名 + 分组。</li>
 * </ul>
 */
public final class Safety {

    private Safety() {}

    /** 条目是否应隐藏（详情入口兜底用；不产生统计副作用） */
    public static boolean hideItem(Cms.Video v) {
        return v != null
                && ContentGate.blockedItem(v.title, v.genre, v.remarks, v.area, v.description);
    }

    /**
     * 无来源上下文时的兜底过滤。{@code Cms.listing(String)} 直接调用它，
     * 因此所有列表、搜索、详情、手机端路径都会被覆盖，不依赖调用方逐个挂点。
     */
    public static void filterItems(List<Cms.Video> videos) {
        if (videos == null || videos.isEmpty() || !ContentGate.locked()) return;
        Iterator<Cms.Video> it = videos.iterator();
        while (it.hasNext()) {
            Cms.Video v = it.next();
            if (v == null || hideItem(v)) it.remove();
        }
    }

    /**
     * 一个来源的一块列表结果：条目过滤 + 整块判定，就地修改。
     *
     * <p>整块判定（来源条目成人占比过高）成立时清空整块并累计标记，
     * 达到阈值后来源被永久标记，之后连请求结果都会直接丢弃。
     */
    public static void applyBlock(String siteKey, String siteName, List<Cms.Video> videos) {
        if (videos == null || videos.isEmpty() || !ContentGate.locked()) return;
        if (siteKey != null && ContentFilterConfig.isAdult(siteKey)) {
            videos.clear();
            return;
        }
        AdultSourceStats.Block block = new AdultSourceStats.Block();
        Iterator<Cms.Video> iterator = videos.iterator();
        while (iterator.hasNext()) {
            Cms.Video v = iterator.next();
            if (v == null) {
                iterator.remove();
                continue;
            }
            block.seen++;
            String why = ContentGate.reasonItem(v.title, v.genre, v.remarks, v.area, v.description);
            if (why != null) {
                block.count(1, AdultRules.isStrongReason(why) ? 1 : 0);
                iterator.remove();
            }
        }
        if (siteKey != null && AdultSourceStats.judgeBlock(siteKey, siteName, block)) {
            videos.clear();
        }
    }

    /** 直播频道：频道名 + 所在分组 */
    public static boolean hideChannel(String name, String group) {
        return ContentGate.blockedChannel(name, group == null ? "" : group);
    }

    /** 收藏 / 历史记录（只有标题可用） */
    public static boolean hideRecord(String title) {
        return ContentGate.blockedItem(title, "", "", "", "");
    }

    /** 记录数组过滤，供直接使用 JSONArray 的调用方 */
    public static org.json.JSONArray filterRecords(org.json.JSONArray entries) {
        return ContentGate.filterRecords(entries);
    }

    /** 来源是否已被永久标记为成人源（用于跳过请求 / 隐藏来源） */
    public static boolean siteHidden(Cms.Site site) {
        return site != null && ContentGate.locked() && ContentFilterConfig.isAdult(site.key);
    }
}
