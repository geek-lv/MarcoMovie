package com.pilipala.marcomovie.adskip;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;

import java.nio.ByteBuffer;

/**
 * 挂在 ExoPlayer 音频处理链上的 PCM 探针。
 *
 * <p>位置在解码之后、AudioTrack 之前，拿到的是解码后的 PCM。
 * 它<b>原样透传</b>音频（不改变任何输出内容），只额外做一次旁路拷贝：
 * 声道降混成单声道、重采样到 16kHz，然后交给 {@link AdSkipStateMachine}。
 *
 * <p>所有处理都在播放线程同步执行，因此只做极轻量的算术，识别动作由状态机丢到工作线程。
 */
public final class AdSkipAudioProcessor extends BaseAudioProcessor {

    private static final int TARGET_SAMPLE_RATE = AdSkipStateMachine.KWS_SAMPLE_RATE;
    private static final int CHUNK = 512;

    private final AdSkipStateMachine machine;

    private int sampleRate = TARGET_SAMPLE_RATE;
    private int channelCount = 2;
    private int encoding = C.ENCODING_PCM_16BIT;

    private double ratio = 1.0;
    private double acc = 0.0;
    private float prev = 0.0f;

    private final float[] chunkBuffer = new float[CHUNK];
    private int chunkLen = 0;

    public AdSkipAudioProcessor(AdSkipStateMachine machine) {
        this.machine = machine;
    }

    @Override
    protected AudioProcessor.AudioFormat onConfigure(AudioProcessor.AudioFormat inputAudioFormat)
            throws AudioProcessor.UnhandledAudioFormatException {
        sampleRate = inputAudioFormat.sampleRate;
        channelCount = inputAudioFormat.channelCount;
        encoding = inputAudioFormat.encoding;
        ratio = sampleRate > 0 ? (double) sampleRate / TARGET_SAMPLE_RATE : 1.0;
        if (ratio <= 0) ratio = 1.0;
        acc = 0.0;
        prev = 0.0f;
        android.util.Log.i("AdSkip", "onConfigure: in=" + sampleRate + "Hz x" + channelCount
                + " enc=" + encoding + " -> ratio=" + ratio);
        // 保持原格式透传，不做任何音频改动
        return inputAudioFormat;
    }

    @Override
    public boolean isActive() {
        return true;
    }

    @Override
    public void queueInput(ByteBuffer inputBuffer) {
        if (machine == null) {
            return;
        }
        ByteBuffer output = replaceOutputBuffer(inputBuffer.remaining());

        if (encoding == C.ENCODING_PCM_16BIT) {
            while (inputBuffer.hasRemaining()) {
                float mono = 0.0f;
                for (int c = 0; c < channelCount && inputBuffer.hasRemaining(); c++) {
                    short s = inputBuffer.getShort();
                    output.putShort(s);
                    mono += s;
                }
                if (channelCount > 0) {
                    pushSample(mono / channelCount / 32768.0f);
                }
            }
        } else if (encoding == C.ENCODING_PCM_FLOAT) {
            while (inputBuffer.hasRemaining()) {
                float mono = 0.0f;
                for (int c = 0; c < channelCount && inputBuffer.hasRemaining(); c++) {
                    float f = inputBuffer.getFloat();
                    output.putFloat(f);
                    mono += f;
                }
                if (channelCount > 0) {
                    pushSample(mono / channelCount);
                }
            }
        } else {
            // 其余编码不认识，原样透传，不参与识别
            while (inputBuffer.hasRemaining()) {
                output.put(inputBuffer.get());
            }
        }

        output.flip();
    }

    /** 重采样到 16kHz 并攒成定长块后交给状态机 */
    private void pushSample(float current) {
        // 线性插值重采样：ratio = inRate/targetRate。
        // ratio>1（如 48k→16k）为抽取，ratio<1（如 8k→16k）为上采样，ratio≈1 为透传。
        // 旧实现对 ratio<=1 直接透传，低采样率源（8k/11k/12k）会使音频时钟半速漂移，
        // 触发"位置校正"每几秒空转重锚，且命中广告时算错跳转位置。
        acc += 1.0 / ratio;                 // 每输入 1 样本应产出 1/ratio 个输出样本
        int emit = (int) acc;
        if (emit > 0) {
            acc -= emit;
            for (int k = 1; k <= emit; k++) {
                float t = (float) k / emit;
                write(prev + (current - prev) * t);
            }
        }
        prev = current;
    }

    private void write(float value) {
        chunkBuffer[chunkLen++] = value;
        if (chunkLen >= CHUNK) {
            machine.onPcm(chunkBuffer, chunkLen);
            chunkLen = 0;
        }
    }

    @Override
    protected void onFlush() {
        resetBuffer();
    }

    @Override
    protected void onReset() {
        resetBuffer();
    }

    private void resetBuffer() {
        chunkLen = 0;
        acc = 0.0;
        prev = 0.0f;
    }
}
