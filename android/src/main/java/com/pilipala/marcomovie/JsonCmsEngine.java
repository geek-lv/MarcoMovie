package com.pilipala.marcomovie;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Only shipped engine. An unsupported SourceEngine is never selected silently.
 *
 * <p>部分 CMS 源（如非凡资源）的「合集类目」（电影片 / 连续剧 / 综艺片 / 动漫片）在
 * {@code ac=detail&t=<父类目>} 下返回 0 条，内容只挂在子类目（国产剧 / 大陆综艺 …）上。
 * 这里在父类目返回空时按类目树合并子类结果，避免首页出现整片空白分类。
 */
public final class JsonCmsEngine implements SourceEngine {
    private final Cms.Site site;

    /** api 地址 → 类目树缓存：一次会话内避免每次点分类都回源拉 class */
    private static final java.util.Map<String, Tree> TREES = new java.util.HashMap<>();
    private static final long TREE_TTL_MS = 10 * 60 * 1000L;

    /** 父类目合并时单页最多取多少条，够一屏 3 列网格再翻页即可 */
    private static final int MERGE_PAGE_LIMIT = 40;

    private static final class Tree {
        long time;
        final java.util.Map<String, java.util.List<String>> children = new java.util.HashMap<>();
    }

    public JsonCmsEngine(Cms.Site site) {
        if (!site.supported()) throw new IllegalArgumentException("Unsupported source type");
        this.site = site;
    }

    private String detailAction() {
        return site.type == 0 ? "videolist" : "detail";
    }

    public String home() throws Exception {
        return Cms.fetch(Cms.query(site.api, "ac", "list"), site.headers);
    }

    public String category(String id, int page) throws Exception {
        return category(id, page, java.util.Collections.emptyMap());
    }

    public String category(String id, int page, java.util.Map<String, String> filters)
            throws Exception {
        String own = fetchCategory(id, page, filters);
        if (!isEmptyList(own)) return own;
        // 父类目无数据：回落到子类目合并（源站把内容都挂在子类下）
        java.util.List<String> kids = childrenOf(id);
        if (kids.isEmpty()) return own;
        String merged = mergeChildren(kids, page, filters);
        return merged == null ? own : merged;
    }

    private String fetchCategory(String id, int page, java.util.Map<String, String> filters)
            throws Exception {
        String url = Cms.query(site.api, "ac", detailAction(), "t", id, "pg", "" + page);
        for (java.util.Map.Entry<String, String> entry : filters.entrySet()) {
            // A remote filter schema cannot replace request routing/authentication.
            if (VideoFilters.allowedKey(entry.getKey()))
                url = Cms.query(url, entry.getKey(), entry.getValue());
        }
        return Cms.fetch(url, site.headers);
    }

    /** 返回结果里 list 为空（父类目在源站无数据）时返回 true；解析失败按「非空」处理，交给上层报错。 */
    private static boolean isEmptyList(String json) {
        if (json == null) return false;
        try {
            JSONArray list = new JSONObject(json).optJSONArray("list");
            return list != null && list.length() == 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 取某个类目的子类目 id 列表。类目树来自 {@code ac=list} 的 class 字段（type_pid），
     * 按 api 地址缓存 10 分钟；任何失败都退化为「没有子类」，让上层保留原有的空结果。
     */
    private java.util.List<String> childrenOf(String parentId) {
        if (parentId == null || parentId.isEmpty() || "0".equals(parentId)) {
            return java.util.Collections.emptyList();
        }
        Tree tree = tree();
        java.util.List<String> kids = tree == null ? null : tree.children.get(parentId);
        return kids == null ? java.util.Collections.<String>emptyList() : kids;
    }

    private Tree tree() {
        String key = site.api == null ? "" : site.api;
        synchronized (TREES) {
            Tree cached = TREES.get(key);
            if (cached != null && System.currentTimeMillis() - cached.time < TREE_TTL_MS)
                return cached;
        }
        try {
            String raw = Cms.fetch(Cms.query(site.api, "ac", "list"), site.headers);
            Tree fresh = new Tree();
            fresh.time = System.currentTimeMillis();
            for (Cms.Category c : Cms.categories(raw)) {
                if (c.parent == null || c.parent.isEmpty() || "0".equals(c.parent)) continue;
                java.util.List<String> bucket = fresh.children.get(c.parent);
                if (bucket == null) {
                    bucket = new java.util.ArrayList<>();
                    fresh.children.put(c.parent, bucket);
                }
                bucket.add(c.id);
            }
            synchronized (TREES) {
                TREES.put(key, fresh);
            }
            return fresh;
        } catch (Throwable e) {
            AppLog.w("JsonCmsEngine", "class tree unavailable: " + key, e);
            return null;
        }
    }

    /**
     * 依次拉各子类目同一页，按 vod_id 去重后拼成一页。返回 null 表示一条都没取到。
     */
    private String mergeChildren(
            java.util.List<String> kids, int page, java.util.Map<String, String> filters) {
        JSONObject merged = null;
        JSONArray out = new JSONArray();
        java.util.Set<String> seen = new java.util.HashSet<>();
        int pageCount = 1;
        int total = 0;
        for (String kid : kids) {
            if (out.length() >= MERGE_PAGE_LIMIT) break;
            try {
                JSONObject o = new JSONObject(fetchCategory(kid, page, filters));
                JSONArray list = o.optJSONArray("list");
                if (list != null) {
                    for (int i = 0; i < list.length(); i++) {
                        JSONObject v = list.optJSONObject(i);
                        if (v == null) continue;
                        String vid = v.optString("vod_id", "");
                        if (!vid.isEmpty() && !seen.add(vid)) continue;
                        out.put(v);
                    }
                }
                pageCount = Math.max(pageCount, Math.max(1, o.optInt("pagecount", 1)));
                total += Math.max(0, o.optInt("total", 0));
                if (merged == null) merged = o;
            } catch (Throwable e) {
                AppLog.w("JsonCmsEngine", "child category failed: " + kid, e);
            }
        }
        if (merged == null || out.length() == 0) return null;
        try {
            merged.put("list", out);
            merged.put("page", page);
            merged.put("pagecount", pageCount);
            merged.put("total", total);
            return merged.toString();
        } catch (Throwable e) {
            return null;
        }
    }

    public String detail(String id) throws Exception {
        return Cms.fetch(Cms.query(site.api, "ac", detailAction(), "ids", id), site.headers);
    }

    public String search(String keyword, int page) throws Exception {
        return Cms.fetch(
                Cms.query(site.api, "ac", detailAction(), "wd", keyword, "pg", "" + page),
                site.headers);
    }

    public boolean requiresExternalRuntime() {
        return false;
    }
}
