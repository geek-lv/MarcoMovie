package com.pilipala.marcomovie.adskip;

import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType;
import net.sourceforge.pinyin4j.format.exception.BadHanyuPinyinOutputFormatCombination;

import java.util.List;

/**
 * 把用户输入的中文关键词转换成 sherpa-onnx 关键词唤醒引擎要求的音素格式。
 *
 * <p>引擎要求的每行格式为：
 * <pre>
 *   空格分隔的声母/韵母音素 + "@" + 原始关键词
 *   例：x iǎo ài t óng x ué @小爱同学
 * </pre>
 * 建模单元是「声母 + 带声调韵母」，零声母字则只有韵母（如「爱」→ {@code ài}）。
 *
 * <p>拆分规则来自模型自带的 tokens.txt：先按最长匹配取声母（zh/ch/sh 优先于 z/c/s），
 * 剩余部分即韵母。
 */
public final class KeywordPhonemeConverter {

    /** 声母表，顺序必须保证 zh/ch/sh 在 z/c/s 之前，以实现最长匹配 */
    private static final String[] INITIALS = {
        "zh", "ch", "sh", "b", "p", "m", "f", "d", "t", "n", "l", "g", "k", "h", "j", "q", "x", "r",
        "z", "c", "s", "y", "w"
    };

    /**
     * 带声调元音表，顺序对应 a o e i u ü，每个元音按 1/2/3/4 声排列。
     *
     * <p>这里必须用标准的 NFC 预组合字符（如第三声 a 是 U+01CE 的 ǎ），
     * 与模型 tokens.txt 完全一致。注意 pinyin4j 自带的 WITH_TONE_MARK
     * 输出的第三声是 breve（ă U+0103）而非 caron，与词表不符，因此这里自行转换。
     */
    private static final String[][] TONE_VOWELS = {
        {"ā", "á", "ǎ", "à"},
        {"ō", "ó", "ǒ", "ò"},
        {"ē", "é", "ě", "è"},
        {"ī", "í", "ǐ", "ì"},
        {"ū", "ú", "ǔ", "ù"},
        {"ǖ", "ǘ", "ǚ", "ǜ"},
    };

    private KeywordPhonemeConverter() {}

    /**
     * 把单个中文关键词转成引擎可接受的音素行。
     *
     * @param keyword 原始中文关键词，如「澳门新葡京」
     * @return 形如 {@code ào m én x īn p ú j īng @澳门新葡京} 的字符串；
     *         若关键词为空或无法转换出任何音素，返回 null
     */
    public static String toPhonemeLine(String keyword) {
        if (keyword == null) return null;
        String trimmed = keyword.trim();
        if (trimmed.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        int phonemeCount = 0;
        for (char c : trimmed.toCharArray()) {
            String pinyin = toPinyinWithTone(c);
            if (pinyin == null || pinyin.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            phonemeCount += appendPhonemes(sb, pinyin);
        }
        if (phonemeCount == 0) return null;

        sb.append(" @").append(trimmed);
        return sb.toString();
    }

    /**
     * 把多个关键词转成可直接传给 createStream 的参数。
     * 引擎用 "/" 作为关键词之间的分隔符（C++ 层会先把 "/" 还原成换行再逐行解析）。
     */
    public static String toKeywordsParam(List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String keyword : keywords) {
            String line = null;
            try {
                line = toPhonemeLine(keyword);
            } catch (Throwable t) {
                // pinyin4j 的 pinyindb 资源在部分设备 / ClassLoader 上可能读不到，
                // 此时它会在静态初始化里抛 NPE。不能让一个词毁掉整条识别流。
                android.util.Log.w("AdSkip", "音素转换异常: " + keyword, t);
            }
            if (line == null) line = builtinLine(keyword);
            if (line == null) continue;
            if (!first) {
                sb.append('/');
            }
            sb.append(line);
            first = false;
        }
        return sb.toString();
    }

    /**
     * 内置默认关键词的音素行。用于拼音资源完全不可用时兜底 —— 保证开箱即用的
     * 「澳门新葡京 / 新葡京」仍能建流，避免出现「模型已就绪但一次都不检测」。
     * 自定义关键词仍依赖 pinyin4j 资源（构建脚本已把 pinyindb/ 打进 APK）。
     */
    private static String builtinLine(String keyword) {
        if ("澳门新葡京".equals(keyword)) return "ào m én x īn p ú j īng @澳门新葡京";
        if ("新葡京".equals(keyword)) return "x īn p ú j īng @新葡京";
        return null;
    }

    /** 取单个汉字的带声调拼音，非汉字返回 null；多音字取第一个读音 */
    private static String toPinyinWithTone(char c) {
        try {
            HanyuPinyinOutputFormat format = new HanyuPinyinOutputFormat();
            // 先取数字声调，再自行转成标准声调符号，避免 pinyin4j 第三声符号与词表不符
            format.setToneType(HanyuPinyinToneType.WITH_TONE_NUMBER);
            format.setVCharType(HanyuPinyinVCharType.WITH_U_UNICODE);
            format.setCaseType(HanyuPinyinCaseType.LOWERCASE);
            String[] array = PinyinHelper.toHanyuPinyinStringArray(c, format);
            if (array == null || array.length == 0) return null;
            return applyToneMark(array[0]);
        } catch (BadHanyuPinyinOutputFormatCombination e) {
            return null;
        }
    }

    /** 把数字声调拼音（如 ao3 / men2）转成带标准声调符号的形式（ǎo / mén） */
    private static String applyToneMark(String pinyin) {
        if (pinyin == null || pinyin.isEmpty()) return pinyin;
        int len = pinyin.length();
        char last = pinyin.charAt(len - 1);
        if (last < '1' || last > '5') return pinyin;
        String base = pinyin.substring(0, len - 1);
        if (last == '5') return base; // 轻声不标调

        int tone = last - '1';
        int index = findToneVowelIndex(base);
        if (index < 0) return base;

        int row = vowelRow(base.charAt(index));
        if (row < 0) return base;
        return base.substring(0, index) + TONE_VOWELS[row][tone] + base.substring(index + 1);
    }

    /** 按汉语拼音标调规则找出该标声调的元音位置：a > o/e > iu 标 u、ui 标 i > 末位元音 */
    private static int findToneVowelIndex(String base) {
        int a = base.indexOf('a');
        if (a >= 0) return a;
        int o = base.indexOf('o');
        if (o >= 0) return o;
        int e = base.indexOf('e');
        if (e >= 0) return e;
        int iu = base.indexOf("iu");
        if (iu >= 0) return iu + 1;
        int ui = base.indexOf("ui");
        if (ui >= 0) return ui + 1;
        for (int i = base.length() - 1; i >= 0; i--) {
            if (vowelRow(base.charAt(i)) >= 0) return i;
        }
        return -1;
    }

    /** 元音对应的行号：a=0 o=1 e=2 i=3 u=4 ü=5，非元音返回 -1 */
    private static int vowelRow(char c) {
        switch (c) {
            case 'a':
                return 0;
            case 'o':
                return 1;
            case 'e':
                return 2;
            case 'i':
                return 3;
            case 'u':
                return 4;
            case 'ü':
            case 'v':
                return 5;
            default:
                return -1;
        }
    }

    /** 把带声调拼音拆成「声母 + 韵母」写入 sb，返回写入的音素个数 */
    private static int appendPhonemes(StringBuilder sb, String pinyin) {
        String initial = "";
        for (String candidate : INITIALS) {
            if (pinyin.startsWith(candidate)) {
                initial = candidate;
                break;
            }
        }
        String rhyme = pinyin.substring(initial.length());
        int count = 0;
        if (!initial.isEmpty()) {
            sb.append(initial);
            count++;
        }
        if (!rhyme.isEmpty()) {
            if (count > 0) sb.append(' ');
            sb.append(rhyme);
            count++;
        }
        return count;
    }
}
