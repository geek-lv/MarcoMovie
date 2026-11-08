package com.k2fsa.sherpa.onnx;

/**
 * sherpa-onnx 的配置类容器。
 *
 * <p>这些类的<b>包名、类名、字段名与类型必须与 Kotlin API 完全一致</b>，
 * 因为 {@code libsherpa-onnx-jni.so} 是通过 JNI 反射
 * （{@code GetFieldID}）按签名读取字段的，任何一处不一致都会导致 native 层取不到配置。
 *
 * <p>字段一律声明为 public，便于 JNI 反射；嵌套对象类型也必须位于本包下。
 */
public class SherpaOnnxConfigs {
    private SherpaOnnxConfigs() {
    }
}

/** 特征提取配置，对应 kotlin-api 的 FeatureConfig */
class FeatureConfig {
    public int sampleRate = 16000;
    public int featureDim = 80;
    public float dither = 0.0f;
}

/** QNN 配置（本项目用 CPU，保持默认空值即可） */
class QnnConfig {
    public String backendLib = "";
    public String contextBinary = "";
    public String systemLib = "";
}

/** Transducer 模型配置 */
class OnlineTransducerModelConfig {
    public String encoder = "";
    public String decoder = "";
    public String joiner = "";
    public QnnConfig qnnConfig = new QnnConfig();
}

/** Paraformer 模型配置 */
class OnlineParaformerModelConfig {
    public String encoder = "";
    public String decoder = "";
}

/** Zipformer2 CTC 模型配置 */
class OnlineZipformer2CtcModelConfig {
    public String model = "";
}

/** NeMo CTC 模型配置 */
class OnlineNeMoCtcModelConfig {
    public String model = "";
}

/** Tone CTC 模型配置 */
class OnlineToneCtcModelConfig {
    public String model = "";
}

/** 在线模型总配置 */
class OnlineModelConfig {
    public OnlineTransducerModelConfig transducer = new OnlineTransducerModelConfig();
    public OnlineParaformerModelConfig paraformer = new OnlineParaformerModelConfig();
    public OnlineZipformer2CtcModelConfig zipformer2Ctc = new OnlineZipformer2CtcModelConfig();
    public OnlineNeMoCtcModelConfig neMoCtc = new OnlineNeMoCtcModelConfig();
    public OnlineToneCtcModelConfig toneCtc = new OnlineToneCtcModelConfig();
    public String tokens = "";
    public int numThreads = 1;
    public boolean debug = false;
    public String provider = "cpu";
    public String modelType = "";
    public String modelingUnit = "";
    public String bpeVocab = "";
}

/** 关键词识别配置，对应 kotlin-api 的 KeywordSpotterConfig */
class KeywordSpotterConfig {
    public FeatureConfig featConfig = new FeatureConfig();
    public OnlineModelConfig modelConfig = new OnlineModelConfig();
    public int maxActivePaths = 4;
    public String keywordsFile = "keywords.txt";
    public float keywordsScore = 1.5f;
    public float keywordsThreshold = 0.25f;
    public int numTrailingBlanks = 2;
}
