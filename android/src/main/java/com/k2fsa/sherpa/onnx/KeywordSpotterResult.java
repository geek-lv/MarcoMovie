package com.k2fsa.sherpa.onnx;

/** 关键词识别结果，对应 kotlin-api 的 KeywordSpotterResult */
public class KeywordSpotterResult {

    /** 命中的关键词（@ 后的原始文本） */
    public final String keyword;
    /** 命中的音素序列 */
    public final String[] tokens;
    /** 各音素对应的时间点，单位秒 */
    public final float[] timestamps;

    /** 由 native 层通过反射构造，参数顺序不可调整 */
    public KeywordSpotterResult(String keyword, String[] tokens, float[] timestamps) {
        this.keyword = keyword;
        this.tokens = tokens;
        this.timestamps = timestamps;
    }

    @Override
    public String toString() {
        return "Keyword: " + keyword;
    }
}
