package com.k2fsa.sherpa.onnx;

import android.content.Context;

import com.pilipala.marcomovie.adskip.KeywordPhonemeConverter;
import com.pilipala.marcomovie.adskip.KwsEngine;
import com.pilipala.marcomovie.adskip.AdSkipConfig;

import java.util.List;

/**
 * 基于 sherpa-onnx 的关键词识别引擎实现。
 *
 * <p><b>进程内单例</b>：模型加载较重（INT8 约 4.8MB），换集 / 换源时不应重建。
 * 用户修改关键词只需重建识别流（{@code createStream}），成本极低。
 */
public class SherpaKwsEngine implements KwsEngine {

    private static final String ASSET_DIR = "sherpa-onnx-kws";
    private static final int SAMPLE_RATE = 16000;

    private static volatile SherpaKwsEngine instance;

    private KeywordSpotter spotter;
    private OnlineStream stream;
    private volatile boolean ready = false;

    private SherpaKwsEngine() {
    }

    public static SherpaKwsEngine get() {
        if (instance == null) {
            synchronized (SherpaKwsEngine.class) {
                if (instance == null) {
                    instance = new SherpaKwsEngine();
                }
            }
        }
        return instance;
    }

    /** 懒加载模型，重复调用无副作用 */
    public synchronized void init(Context context) {
        if (spotter != null && ready) return;
        try {
            FeatureConfig featConfig = new FeatureConfig();
            featConfig.sampleRate = SAMPLE_RATE;
            featConfig.featureDim = 80;
            featConfig.dither = 0.0f;

            OnlineModelConfig modelConfig = new OnlineModelConfig();
            modelConfig.transducer.encoder = ASSET_DIR + "/encoder.int8.onnx";
            modelConfig.transducer.decoder = ASSET_DIR + "/decoder.int8.onnx";
            modelConfig.transducer.joiner = ASSET_DIR + "/joiner.int8.onnx";
            modelConfig.tokens = ASSET_DIR + "/tokens.txt";
            modelConfig.modelType = "zipformer2";
            modelConfig.numThreads = 1;
            modelConfig.provider = "cpu";
            modelConfig.debug = false;

            KeywordSpotterConfig config = new KeywordSpotterConfig();
            config.featConfig = featConfig;
            config.modelConfig = modelConfig;
            // 指向一个空的关键词文件：native 层在构造时若打不开该文件会直接 exit，
            // 留空可避免默认关键词与用户关键词被合并而误命中。
            config.keywordsFile = ASSET_DIR + "/keywords.txt";
            // 灵敏度参数：阈值越低越容易召回（也可能误报），boost 越大关键词路径得分越高。
            // 2026-11-02 离线网格扫描（官方模型 + TTS 口播混入真实剧集，0.5s 块逐块喂）：
            // - 旧参数 score=2.0 在剧集背景对白下几乎 0 命中（阈值不是主因，boost 强度才是）
            // - 稳定平台：score 3.0~5.0 + th 0.05~0.1 + blanks 1~2 → 召回最好且干净音频 0 误报
            // - score=6.0 起行为失衡（连纯口播都漏），score=8.0 出现无中生有的误报
            // - blanks=5 全面变差，2 最佳
            // 该 3.3M 小模型在口播叠人声场景召回上限约 60%，真实口播（音量占优）会好于合成最坏情况
            config.keywordsScore = 4.0f;
            config.keywordsThreshold = 0.10f;
            config.numTrailingBlanks = 2;
            config.maxActivePaths = 8;

            spotter = new KeywordSpotter(context.getApplicationContext().getAssets(), config);
            ready = spotter.isValid();
        } catch (Throwable t) {
            t.printStackTrace();
            ready = false;
            spotter = null;
        }
    }

    /**
     * 用当前配置里的关键词重建识别流。引擎本体不动，因此成本很低。
     * 关键词为空或转换失败时流会被置空，{@link #isReady()} 返回 false。
     */
    public synchronized void applyKeywords() {
        applyKeywordsParam(KeywordPhonemeConverter.toKeywordsParam(AdSkipConfig.getKeywordList()));
    }

    /**
     * 用给定的音素串重建识别流，成功返回 true。
     * 与 {@link #applyKeywords()} 的区别只是关键词来源，便于自检与外部调用。
     */
    public synchronized boolean applyKeywordsParam(String param) {
        if (spotter == null || !ready) return false;
        if (stream != null) {
            stream.release();
            stream = null;
        }
        if (param == null || param.isEmpty()) return false;
        android.util.Log.i("AdSkip", "applyKeywordsParam: " + param);
        try {
            stream = spotter.createStream(param);
        } catch (Throwable t) {
            t.printStackTrace();
            stream = null;
        }
        return stream != null;
    }

    @Override
    public boolean isReady() {
        return ready && spotter != null && stream != null;
    }

    /** 只判断引擎本体（模型）是否就绪，不要求已建流。自检使用。 */
    public boolean isReadyRaw() {
        return ready && spotter != null;
    }

    @Override
    public synchronized String accept(float[] pcm16k) {
        if (!isReady()) return null;
        try {
            stream.acceptWaveform(pcm16k, SAMPLE_RATE);
            while (spotter.isReady(stream)) {
                spotter.decode(stream);
            }
            KeywordSpotterResult result = spotter.getResult(stream);
            if (result == null || result.keyword == null || result.keyword.isEmpty()) {
                return null;
            }
            // 命中后必须立刻重置，否则同一个词会持续不断地被返回
            dumpHit(result);
            spotter.reset(stream);
            return result.keyword;
        } catch (Throwable t) {
            t.printStackTrace();
            return null;
        }
    }

    /** 打印命中的音素与各自时间点，用于核对关键词在音频里的确切位置 */
    private static void dumpHit(KeywordSpotterResult r) {
        StringBuilder sb = new StringBuilder();
        if (r.tokens != null) {
            for (int i = 0; i < r.tokens.length; i++) {
                sb.append(r.tokens[i]);
                if (r.timestamps != null && i < r.timestamps.length) {
                    sb.append('@').append(r.timestamps[i]).append('s');
                }
                sb.append(' ');
            }
        }
        android.util.Log.i("AdSkip", "命中明细: 关键词=[" + r.keyword + "] 音素时间=" + sb);
    }

    @Override
    public synchronized void resetStream() {
        if (spotter != null && stream != null) {
            try {
                spotter.reset(stream);
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
    }

    @Override
    public synchronized void release() {
        if (stream != null) {
            stream.release();
            stream = null;
        }
        if (spotter != null) {
            spotter.release();
            spotter = null;
        }
        ready = false;
    }
}
