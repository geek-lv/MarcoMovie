package com.pilipala.marcomovie.safety;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 来源级判定的被动统计。
 *
 * <p>完全本地、零额外请求：数据全部来自客户端本来就要做的那一次搜索/分类列表响应——
 * 同一份 JSON 里的 {@code class} 分类数组和条目本身。没有任何探测、爬取或枚举行为。
 *
 * <p>判定方式：某个来源的一次响应里，成人条目占比偏高就丢弃整块结果（先判后出），
 * 并在本机累计；连续命中两次或单块硬证据足够多时，把它标记为成人源，
 * 之后正常搜索连请求都不发。标记结果写入 {@link ContentFilterConfig}，
 * 用户可以在设置页或来源菜单里手动取消误判。
 */
public final class AdultSourceStats {

    private static final String PREFS = "content_filter_stats";
    private static final String KEY_PREFIX = "src_";

    /** 判定为整站成人源所需的最少命中条目数（非严格模式：跨多次搜索累积） */
    private static final int MIN_HITS = 3;

    /** 严格模式：单个响应块内至少检查过这么多条才做比例判定 */
    private static final int BLOCK_MIN_SEEN = 3;

    /** 严格模式：强信号条数达到该值即整源隔离 */
    private static final int BLOCK_STRONG_HITS = 2;

    /** 严格模式：单块强证据达到该条数，不必等第二次就直接永久标记 */
    private static final int DECISIVE_STRONG_HITS = 3;

    /** 严格模式：连续命中多少次后永久标记（本块结果每次都先丢弃，不受影响） */
    private static final int MARK_STRIKES = 2;

    private static volatile SharedPreferences prefs;

    private AdultSourceStats() {}

    /**
     * 一个来源本次响应（含分页）的判定素材。
     *
     * <p>由调用方在产出条目的同时累加，最后一次性交给 {@link #judgeBlock} 下结论。
     * 之所以攒完再判，是因为结果一旦交给界面就收不回来了——判定必须在交付之前。
     */
    public static final class Block {
        public int seen;
        public int hits;
        public int strongHits;

        /** 响应里自带的分类清单给出的结论，null 表示分类清单没定性 */
        public String classReason;

        public void count(int hits, int strongHits) {
            this.hits += hits;
            this.strongHits += strongHits;
        }
    }

    public static void init(Context context) {
        if (prefs != null) return;
        synchronized (AdultSourceStats.class) {
            if (prefs == null && context != null) {
                prefs =
                        context.getApplicationContext()
                                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            }
        }
    }

    /**
     * 对"一个来源的一次完整响应"下结论。
     *
     * <p>这是"首次搜索不露头"的关键：调用方在把条目交付界面前调用本方法，
     * 返回 true 就直接丢弃整块结果，界面上一条都不会出现（而不是先显示再回收）。
     *
     * <p>严格模式（默认）用块内比例定性，判据见 {@link #strictVerdict}。
     * 判定成立时先只丢弃本块；同一来源连续命中两次、或单块强证据 ≥3 条，才永久标记。
     * 这样"偶尔搜了个成人片名"不会把综合站一刀切掉，而真正整站卖成人内容的来源
     * 从第一次搜索起就拿不到任何曝光。
     *
     * @return true 表示本块结果必须丢弃（该来源可能还没被永久标记）
     */
    public static synchronized boolean judgeBlock(
            String sourceKey, String sourceName, Block block) {
        if (sourceKey == null || sourceKey.isEmpty() || block == null) return false;
        // 用户已否决过的来源：不再反复标记，也不再因其内容丢结果
        if (ContentFilterConfig.isDismissed(sourceKey)) return false;
        if (ContentFilterConfig.isAdult(sourceKey)) return true;

        if (block.classReason != null) {
            ContentFilterConfig.markAdult(sourceKey, block.classReason, sourceName);
            return true;
        }

        if (block.seen <= 0) return false;

        int[] total = totals(sourceKey);
        int totalHits = total[0] + block.hits;
        int totalSeen = total[1] + block.seen;
        int strikes = total[2];

        if (!ContentFilterConfig.strictFirstSight()) {
            // 旧行为：跨多次搜索累积，够数才整源屏蔽
            save(sourceKey, totalHits, totalSeen, strikes);
            if (totalHits >= MIN_HITS && totalHits * 2 >= totalSeen) {
                ContentFilterConfig.markAdult(
                        sourceKey, "返回条目多为成人内容（" + totalHits + "/" + totalSeen + "）", sourceName);
                return true;
            }
            return false;
        }

        String reason = strictVerdict(block);
        if (reason == null) {
            save(sourceKey, totalHits, totalSeen, strikes);
            return false;
        }

        strikes++;
        save(sourceKey, totalHits, totalSeen, strikes);
        if (strikes >= MARK_STRIKES || block.strongHits >= DECISIVE_STRONG_HITS) {
            ContentFilterConfig.markAdult(sourceKey, reason, sourceName);
        }
        return true;
    }

    /** 严格模式的块内判据，返回定性理由；干净返回 null。纯函数，便于单测。 */
    public static String strictVerdict(Block block) {
        if (block == null) return null;
        if (block.seen < BLOCK_MIN_SEEN) {
            // 结果太少时只看硬证据堆叠：两条以上强信号也不可能是巧合
            if (block.strongHits >= BLOCK_STRONG_HITS) {
                return "返回条目多为成人内容（" + block.hits + "/" + block.seen + "）";
            }
            return null;
        }
        if (block.strongHits >= BLOCK_STRONG_HITS) {
            return "返回条目多为成人内容（" + block.hits + "/" + block.seen + "）";
        }
        if (block.hits >= MIN_HITS && block.hits * 2 >= block.seen) {
            return "返回条目多为成人内容（" + block.hits + "/" + block.seen + "）";
        }
        // 强信号已经很硬，占比只需 1/3：整站成人但分类名伪装成普通分类的站点主要靠这条兜住
        if (block.strongHits >= 1 && block.hits * 3 >= block.seen) {
            return "返回条目含成人内容（" + block.hits + "/" + block.seen + "）";
        }
        return null;
    }

    /**
     * 用响应里自带的 class 数组做站点级判定；数组不存在时返回 null。
     * 这是同一次请求的附带数据，不产生额外流量。
     *
     * @return 定性理由；分类清单看起来干净则返回 null
     */
    public static String observeClasses(String sourceKey, String sourceName, JSONArray classes) {
        if (sourceKey == null || sourceKey.isEmpty() || classes == null || classes.length() == 0)
            return null;
        if (ContentFilterConfig.isDismissed(sourceKey)) return null;
        List<String> names = new ArrayList<>();
        for (int i = 0; i < classes.length(); i++) {
            Object item = classes.opt(i);
            if (item instanceof JSONObject) names.add(((JSONObject) item).optString("type_name"));
            else if (item != null) names.add(String.valueOf(item));
        }
        String reason = AdultRules.matchClassesStrict(names);
        if (reason != null && !ContentFilterConfig.hasMark(sourceKey)) {
            ContentFilterConfig.markAdult(sourceKey, reason, sourceName);
        }
        return reason;
    }

    /** 清空某个来源的累计计数（用户在设置页取消标记时调用） */
    public static synchronized void reset(String sourceKey) {
        SharedPreferences p = prefs;
        if (p == null || sourceKey == null) return;
        p.edit().remove(KEY_PREFIX + sourceKey).apply();
    }

    public static synchronized void clearAll() {
        SharedPreferences p = prefs;
        if (p != null) p.edit().clear().apply();
    }

    private static int[] totals(String sourceKey) {
        SharedPreferences p = prefs;
        if (p == null) return new int[] {0, 0, 0};
        String raw = p.getString(KEY_PREFIX + sourceKey, "");
        if (raw.isEmpty()) return new int[] {0, 0, 0};
        try {
            JSONObject json = new JSONObject(raw);
            return new int[] {
                json.optInt("hits", 0), json.optInt("seen", 0), json.optInt("strikes", 0)
            };
        } catch (Exception ignored) {
            return new int[] {0, 0, 0};
        }
    }

    private static void save(String sourceKey, int hits, int seen, int strikes) {
        SharedPreferences p = prefs;
        if (p == null) return;
        try {
            JSONObject json =
                    new JSONObject().put("hits", hits).put("seen", seen).put("strikes", strikes);
            p.edit().putString(KEY_PREFIX + sourceKey, json.toString()).apply();
        } catch (Exception ignored) {
        }
    }
}
