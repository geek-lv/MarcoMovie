package com.pilipala.marcomovie.safety;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 少儿不宜内容的本地判定规则。
 *
 * <p>纯 Java 逻辑，不依赖 Android，也不访问网络：输入只有客户端本来就会拿到的
 * 分类名、片名、备注、地区和简介文本，便于在 JVM 单测里跑正负样本。
 *
 * <p>判定分三个层面：
 *
 * <ul>
 *   <li><b>条目</b>：分类名强命中、片名/备注/地区里的厂牌词、番号样式、简介露骨词；</li>
 *   <li><b>来源</b>：来源名称命中厂牌词，或站点分类清单里成人分类占比过高；</li>
 *   <li><b>豁免</b>：少数正经片名会误命中关键词（例如讲成人产业的正剧），走白名单放过。</li>
 * </ul>
 *
 * <p>阈值取舍：宁可错杀少量边缘内容（写真、三级），也不放过混在正常聚合站里的成人条目，
 * 因为来源侧不做过滤时，这类条目通常就藏在普通分类里。
 */
public final class AdultRules {

    /** 分类强命中词：出现在条目分类名或站点分类清单里即判定成人 */
    /**
     * 成人**专有**分类词：正规影视站不会拿它当分类名。出现即强证据。
     */
    private static final String[] ADULT_ONLY_WORDS = {
        "福利视频", "福利片", "色情", "成人", "里番", "裏番", "无码", "無碼", "有码", "有碼", "自拍", "偷拍", "约啪", "約啪", "约炮",
        "約炮", "探花", "大尺度", "网黄", "番号"
    };

    /**
     * 歧义分类词：正规影视站也常设这类分类（伦理片、三级片、写真都是正规片种），
     * 单独出现不足以定性，要看它在整份分类清单里的占比。
     *
     * <p>这一层是实测出来的：「伦理」在 23 个采集站里出现了 18 个，
     * 如果当强词用会误杀大半正常站点。
     */
    private static final String[] AMBIGUOUS_TYPE_WORDS = {
        "伦理", "倫理", "福利", "情色", "三级", "三級", "写真", "寫真"
    };

    /** 条目级判定用的合并词表：条目分类叫「伦理片」时仍然值得拦 */
    private static final String[] TYPE_WORDS = merge(ADULT_ONLY_WORDS, AMBIGUOUS_TYPE_WORDS);

    /** 厂牌词：出现在片名、备注或来源名里即判定成人 */
    private static final String[] BRAND_WORDS = {
        "麻豆",
        "蜜桃传媒",
        "天美传媒",
        "果冻传媒",
        "乌鸦传媒",
        "精东",
        "swag",
        "91制片",
        "91porn",
        "含羞草",
        "杏吧",
        "一本道",
        "东京热",
        "javbus",
        "javlibrary",
        "missav",
        "madou",
        "fc2",
        "hentai",
        "国产自拍"
    };

    /** 站点分类软命中词：单独不足以定性，但占比高说明整站以成人内容为主 */
    private static final String[] SITE_SOFT_WORDS = {
        "激情", "主播", "网红", "人妻", "熟女", "调教", "私房", "情欲", "慾", "一夜情", "艳照", "裸", "淫"
    };

    /** 简介露骨词：只在详情页判定，且可由用户关闭「简介关键词」开关 */
    private static final String[] CONTENT_WORDS = {
        "做爱", "性爱", "性交", "口交", "肛交", "自慰", "呻吟", "淫荡", "淫叫", "强奸",
        "迷奸", "内射", "中出", "无套", "露点", "全裸", "性器官", "勃起", "射精", "淫秽"
    };

    /**
     * 白名单：讲成人产业但本身是正经发行的作品，避免关键词误伤。
     * 用「包含」匹配，命中后整条直接放行。
     */
    private static final String[] EXEMPT_TITLES = {
        "av帝王",
        "全裸导演",
        "性爱自修室",
        "下辈子我再好好过",
        // 最严档下只剩「极品」「美女」这类词的正经片，靠白名单兜住
        "极品家丁",
        "美女与野兽",
        "美女与野兽2017"
    };

    /**
     * 番号样式：两到五个大写字母 + 分隔符 + 数字，或 FC2-PPV 这类。
     * 前面不能紧跟字母数字（避免命中 "H264-10" 这种片段中间），后面同理。
     */
    private static final Pattern NUMBER_PATTERN =
            Pattern.compile(
                    "(?<![A-Z0-9])(?:FC2[-_ ]?PPV[-_ ]?\\d{3,8}|[A-Z]{2,5}[-_]\\d{2,5})(?![A-Z0-9])");

    /** 番号正则的误伤拦截：编码/格式类前缀不是番号 */
    private static final String[] NUMBER_BLOCKLIST = {
        "HD", "FHD", "UHD", "SD", "BD", "DVD", "WEB", "DL", "HDR", "AVC", "HEVC",
        "MKV", "MP4", "MV", "OP", "ED", "SP", "PV", "CM", "CD", "TS", "TC"
    };

    private AdultRules() {}

    /** 条目判定（搜索结果、收藏、历史都可用）：返回命中原因，null 表示放行 */
    /**
     * 判断某条命中理由是否属于"强信号"。
     *
     * <p>分类命中、厂牌词、番号样本都是硬证据；简介里的露骨词只是软证据（可能只是剧情描述）。
     * 整块判定时两者权重不同：强信号用更少的条数就能定性整个来源。
     */
    public static boolean isStrongReason(String why) {
        if (why == null) return false;
        return why.startsWith("分类含") || why.startsWith("片名像番号") || why.startsWith("片名或标签含");
    }

    /**
     * 直播频道判定：频道名与分组名都参与。
     *
     * <p>成人直播源通常整组命名（"福利""激情""网红"），所以分组允许用软词定性；
     * 单个频道名则只认强词，避免把正常的秀场频道一刀切。
     *
     * @return 命中原因，null 表示放行
     */
    public static String matchChannel(String name, String group) {
        return matchChannel(name, group, false);
    }

    /**
     * @param strict 最严模式：频道名本身也参与低俗词与软词判定
     */
    public static String matchChannel(String name, String group, boolean strict) {
        String channel = clean(name);
        String category = clean(group);
        String reason = firstHit(TYPE_WORDS, channel + "|" + category, null);
        if (reason != null) return "频道分类含「" + reason + "」";
        reason = firstHit(BRAND_WORDS, channel + "|" + category, null);
        if (reason != null) return "频道名含「" + reason + "」";
        reason = firstHit(SITE_SOFT_WORDS, category, null);
        if (reason != null) return "频道分组含「" + reason + "」";
        if (strict) {
            reason = firstHit(SITE_SOFT_WORDS, channel, null);
            if (reason != null) return "频道名含「" + reason + "」";
            List<String> suggest = suggestiveHits(channel + "|" + category);
            if (!suggest.isEmpty()) return "频道名含低俗词「" + String.join("、", suggest) + "」";
        }
        return null;
    }

    public static String matchItem(
            String name,
            String type,
            String remarks,
            String area,
            String description,
            boolean numberRegex,
            List<String> extra) {
        // 判定强度固定为最严档：低俗词单个即拦
        return matchItem(name, type, remarks, area, description, numberRegex, extra, true);
    }

    /**
     * @param strictSuggestive 严格模式：低俗倾向词命中 1 个就拦（默认要 2 个不同词）
     */
    public static String matchItem(
            String name,
            String type,
            String remarks,
            String area,
            String description,
            boolean numberRegex,
            List<String> extra,
            boolean strictSuggestive) {
        String title = clean(name);
        if (isExempt(title)) return null;
        String typeText = clean(type);
        // 非严格模式：国内剧集分类里的「家庭伦理」「都市伦理」是正经题材，先把这几个词组摘掉再判。
        // 最严模式不摘，「伦理」一律拦（用户明确要求宁可错杀）。
        String typeProbe =
                strictSuggestive
                        ? typeText
                        : typeText.replace("家庭伦理", "").replace("都市伦理", "").replace("伦理剧", "");
        String reason = firstHit(TYPE_WORDS, typeProbe, extra);
        if (reason != null) return "分类含「" + reason + "」";
        String text = title + "|" + typeText + "|" + clean(remarks) + "|" + clean(area);
        reason = firstHit(BRAND_WORDS, text, extra);
        if (reason != null) return "片名或标签含「" + reason + "」";
        if (numberRegex && !title.isEmpty()) {
            String token = matchNumber(name);
            if (token != null) return "片名像番号「" + token + "」";
        }
        // 低俗倾向词：数不同词的数量，单词命中不足以定性
        List<String> suggest = suggestiveHits(title + "|" + clean(remarks));
        if (!suggest.isEmpty()) {
            if (strictSuggestive || suggest.size() >= 2) {
                return "标题含低俗词「" + String.join("、", suggest) + "」";
            }
        }
        if (description != null && !description.isEmpty()) {
            // 简介是长文本，「美女」「极品」这类词在正经剧里也会出现，
            // 所以最严档也只要求 2 个不同倾向词（标题是 1 个）
            List<String> inDesc = suggestiveHits(description);
            if (inDesc.size() >= 2) {
                return "简介含低俗词「" + String.join("、", inDesc) + "」";
            }
            reason = firstHit(CONTENT_WORDS, clean(description), extra);
            if (reason != null) return "简介含「" + reason + "」";
        }
        return null;
    }

    private static String[] merge(String[] first, String[] second) {
        String[] out = new String[first.length + second.length];
        System.arraycopy(first, 0, out, 0, first.length);
        System.arraycopy(second, 0, out, first.length, second.length);
        return out;
    }

    /**
     * 单个分类名是否命中软词（激情/主播/人妻这类，单独出现不足以定性，
     * 但整份分类清单里占比高就说明站点性质）。
     */
    public static boolean hitsSoftType(String type) {
        return firstHit(SITE_SOFT_WORDS, clean(type), null) != null;
    }

    /**
     * 来源名称判定：只看名称里的厂牌词和强分类词。
     */
    public static String matchSourceName(String name, List<String> extra) {
        String text = clean(name);
        if (text.isEmpty()) return null;
        String reason = firstHit(BRAND_WORDS, text, extra);
        if (reason != null) return "来源名称含「" + reason + "」";
        reason = firstHit(TYPE_WORDS, text, extra);
        if (reason != null) return "来源名称含「" + reason + "」";
        return null;
    }

    /**
     * 站点分类清单判定。输入是接口响应里本来就带的 class 名称，不额外请求。
     *
     * <p>规则：任一分类强命中即定性；否则软命中至少 2 个且占比不低于四分之一时定性。
     */
    public static String matchClasses(List<String> classNames) {
        List<String> detail = new ArrayList<>();
        int risk = classRisk(classNames, detail, false);
        return risk >= CLASS_SUSPICIOUS && !detail.isEmpty() ? detail.get(0) : null;
    }

    /** 严格版：歧义分类与软词也定性（返回定性理由，干净返回 null） */
    public static String matchClassesStrict(List<String> classNames) {
        List<String> detail = new ArrayList<>();
        int risk = classRisk(classNames, detail, true);
        return risk == CLASS_ADULT && !detail.isEmpty() ? detail.get(0) : null;
    }

    /**
     * 低俗倾向词：低俗站标题里的高频词，但正经片名里也会出现
     * （《极品家丁》《美女与野兽》《性感护士》都有）。
     *
     * <p>所以这一层<b>不做单词定性</b>，只数「命中了几个不同的词」：
     * 低俗标题是堆词堆出来的（「极品美女勾引」一次命中 3 个），正经片名通常只碰 1 个。
     * 严格模式下阈值降到 1，由用户自己选。
     */
    private static final String[] SUGGESTIVE_WORDS = {
        "勾引", "誘惑", "诱惑", "偷情", "偷歡", "出轨", "出軌", "情欲", "情慾", "欲火", "湿身", "濕身", "春宵", "禁恋", "禁戀",
        "不伦", "不倫", "艳遇", "艷遇", "魅惑", "嫂子", "小姨子", "继母", "繼母", "空姐", "女上司", "老板娘", "老闆娘", "少妇",
        "少婦", "人妻", "熟女", "尤物", "风骚", "風騷", "浪荡", "浪蕩", "呻吟", "娇喘", "嬌喘", "床戏", "床戲", "私密", "私房",
        "走光", "艳照", "艷照", "销魂", "銷魂", "极品", "極品", "美女", "辣妹", "嫩模", "性感", "内衣", "內衣", "丝袜", "絲襪",
        "制服", "调教", "調教", "驯服", "馴服", "裸聊", "裸舞", "艳舞", "艷舞", "艳星", "艷星", "福利姬", "房事", "情事", "偷腥",
        "春药", "春藥", "调情", "調情", "床伴", "炮友", "援交", "包养", "包養"
    };

    /**
     * 统计文本命中的<b>不同</b>倾向词。
     *
     * @return 命中的词列表；调用方按数量判定，不要按单个词判定
     */
    public static List<String> suggestiveHits(String text) {
        List<String> hits = new ArrayList<>();
        String normalized = clean(text);
        if (normalized.isEmpty()) return hits;
        for (String word : SUGGESTIVE_WORDS) {
            if (normalized.contains(word)) hits.add(word);
        }
        return hits;
    }

    /**
     * 分类清单的洁净度分级
     */
    public static final int CLASS_CLEAN = 0;

    public static final int CLASS_SUSPICIOUS = 1;
    public static final int CLASS_ADULT = 2;

    /**
     * 只看分类清单判断站点性质（不发请求、不读条目内容）。
     *
     * <p>判据分层：专有分类 ≥2 个直接定性；专有 1 个且边缘分类也不少也定性；
     * 只有专有 1 个判可疑；没有任何专有词但边缘分类占比高判可疑。
     *
     * @param detail 非空时写入一句人话依据（只含命中词与计数，不含站点内容）
     */
    public static int classRisk(List<String> classNames, List<String> detail) {
        return classRisk(classNames, detail, false);
    }

    /**
     * @param strict 最严模式：歧义分类（伦理/福利/情色/三级/写真）按专有分类计，
     *               软词（激情/主播/人妻/熟女…）出现也算定性证据
     */
    public static int classRisk(List<String> classNames, List<String> detail, boolean strict) {
        if (classNames == null || classNames.isEmpty()) return CLASS_CLEAN;
        int size = classNames.size();
        int adultOnly = 0, ambiguous = 0, soft = 0;
        java.util.LinkedHashSet<String> adultWords = new java.util.LinkedHashSet<>();

        for (String className : classNames) {
            String text = clean(className);
            if (text.isEmpty()) continue;
            String hit = firstHit(ADULT_ONLY_WORDS, text, null);
            if (hit != null) {
                adultOnly++;
                adultWords.add(hit);
                continue;
            }
            String ambiguousHit = firstHit(AMBIGUOUS_TYPE_WORDS, text, null);
            if (ambiguousHit != null) {
                if (strict) {
                    adultOnly++;
                    adultWords.add(ambiguousHit);
                } else {
                    ambiguous++;
                }
                continue;
            }
            String softHit = firstHit(SITE_SOFT_WORDS, text, null);
            if (softHit != null) {
                soft++;
                if (strict) {
                    adultWords.add(softHit);
                }
            }
        }

        if (strict) {
            if (adultOnly >= 1) {
                if (detail != null)
                    detail.add(
                            "分类含成人/边缘分类「" + adultWords.iterator().next() + "」（共 " + size + " 个分类）");
                return CLASS_ADULT;
            }
            if (soft >= 1) {
                if (detail != null)
                    detail.add("分类含低俗分类「" + adultWords.iterator().next() + "」（共 " + size + " 个分类）");
                return CLASS_ADULT;
            }
            if (detail != null) detail.add("分类清单未见成人分类（共 " + size + " 个分类）");
            return CLASS_CLEAN;
        }

        if (adultOnly >= 2) {
            if (detail != null) detail.add("分类含 " + adultOnly + " 个成人专有分类（共 " + size + "）");
            return CLASS_ADULT;
        }
        if (adultOnly == 1 && adultOnly + ambiguous >= 2) {
            if (detail != null)
                detail.add("成人专有分类与边缘分类并存（" + (adultOnly + ambiguous) + "/" + size + "）");
            return CLASS_ADULT;
        }
        if (adultOnly == 1) {
            if (detail != null) detail.add("分类含成人专有分类「" + adultWords.iterator().next() + "」");
            return CLASS_SUSPICIOUS;
        }
        if (ambiguous + soft >= 2 && (ambiguous + soft) * 4 >= size) {
            if (detail != null) detail.add("边缘分类占比偏高（" + (ambiguous + soft) + "/" + size + "）");
            return CLASS_SUSPICIOUS;
        }
        if (detail != null) detail.add("分类清单未见成人分类（共 " + size + " 个分类）");
        return CLASS_CLEAN;
    }

    /** 片名里的番号样式；命中则返回该片段，便于在提示里展示 */
    public static String matchNumber(String name) {
        if (name == null || name.isEmpty()) return null;
        String upper = name.toUpperCase(Locale.ROOT);
        Matcher matcher = NUMBER_PATTERN.matcher(upper);
        while (matcher.find()) {
            String token = matcher.group();
            String head = token.split("[-_ ]", 2)[0];
            boolean blocked = false;
            for (String prefix : NUMBER_BLOCKLIST) {
                if (head.equals(prefix)) {
                    blocked = true;
                    break;
                }
            }
            if (!blocked) return token;
        }
        return null;
    }

    /** 规范化：小写、去空白。中文关键词用包含匹配即可，无需分词 */
    static String clean(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT).replaceAll("[\\s\\u00a0\\u3000]+", "");
    }

    private static String firstHit(String[] words, String text, List<String> extra) {
        if (text != null && !text.isEmpty()) {
            for (String word : words) {
                if (!word.isEmpty() && text.contains(word)) return word;
            }
        }
        if (extra != null && text != null) {
            for (String word : extra) {
                String candidate = clean(word);
                if (!candidate.isEmpty() && text.contains(candidate)) return word.trim();
            }
        }
        return null;
    }

    private static boolean isExempt(String cleanTitle) {
        for (String title : EXEMPT_TITLES) {
            if (cleanTitle.contains(title)) return true;
        }
        return false;
    }
}
