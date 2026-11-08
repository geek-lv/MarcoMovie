package com.k2fsa.sherpa.onnx;

/** 一条在线识别流，对应 kotlin-api 的 OnlineStream */
public class OnlineStream {

    /** native 指针，同包内的 KeywordSpotter 直接读取 */
    public long ptr;

    public OnlineStream(long ptr) {
        this.ptr = ptr;
    }

    /**
     * 送入一段音频。
     *
     * @param samples    归一化到 -1.0 ~ 1.0 的 float PCM
     * @param sampleRate 采样率，模型要求 16000
     */
    public void acceptWaveform(float[] samples, int sampleRate) {
        if (ptr != 0) acceptWaveform(ptr, samples, sampleRate);
    }

    public void inputFinished() {
        if (ptr != 0) inputFinished(ptr);
    }

    public void release() {
        if (ptr != 0) {
            delete(ptr);
            ptr = 0;
        }
    }

    private native void acceptWaveform(long ptr, float[] samples, int sampleRate);

    private native void inputFinished(long ptr);

    private native void delete(long ptr);
}
