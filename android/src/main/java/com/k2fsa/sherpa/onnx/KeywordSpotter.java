package com.k2fsa.sherpa.onnx;

import android.content.res.AssetManager;

/**
 * sherpa-onnx 关键词识别器，对应 kotlin-api 的 KeywordSpotter。
 *
 * <p>方法名与签名必须与 {@code libsherpa-onnx-jni.so} 中静态注册的
 * {@code Java_com_k2fsa_sherpa_onnx_KeywordSpotter_*} 一一对应（均为实例方法）。
 */
public class KeywordSpotter {

    static {
        // libsherpa-onnx-jni.so 动态依赖 libonnxruntime.so，需先加载
        System.loadLibrary("onnxruntime");
        System.loadLibrary("sherpa-onnx-jni");
    }

    private long ptr;

    /** 从文件系统路径加载模型 */
    public KeywordSpotter(KeywordSpotterConfig config) {
        ptr = newFromFile(config);
    }

    /** 从 APK assets 加载模型 */
    public KeywordSpotter(AssetManager assetManager, KeywordSpotterConfig config) {
        ptr = newFromAsset(assetManager, config);
    }

    public boolean isValid() {
        return ptr != 0;
    }

    /**
     * 创建一条识别流。
     *
     * @param keywords 关键词串，多个关键词之间用 "/" 分隔；
     *                 每个关键词格式为「空格分隔音素 + @ + 原始关键词」。
     *                 传空串则使用 keywordsFile 里的默认关键词。
     */
    public OnlineStream createStream(String keywords) {
        if (ptr == 0) return null;
        long streamPtr = createStream(ptr, keywords);
        if (streamPtr == 0) return null;
        return new OnlineStream(streamPtr);
    }

    public void decode(OnlineStream stream) {
        if (ptr != 0 && stream != null) decode(ptr, stream.ptr);
    }

    public void reset(OnlineStream stream) {
        if (ptr != 0 && stream != null) reset(ptr, stream.ptr);
    }

    public boolean isReady(OnlineStream stream) {
        return ptr != 0 && stream != null && isReady(ptr, stream.ptr);
    }

    public KeywordSpotterResult getResult(OnlineStream stream) {
        if (ptr == 0 || stream == null) return null;
        return getResult(ptr, stream.ptr);
    }

    public void release() {
        if (ptr != 0) {
            delete(ptr);
            ptr = 0;
        }
    }

    private native long newFromFile(KeywordSpotterConfig config);

    private native long newFromAsset(AssetManager assetManager, KeywordSpotterConfig config);

    private native void delete(long ptr);

    private native void decode(long ptr, long streamPtr);

    private native void reset(long ptr, long streamPtr);

    private native long createStream(long ptr, String keywords);

    private native boolean isReady(long ptr, long streamPtr);

    private native KeywordSpotterResult getResult(long ptr, long streamPtr);
}
