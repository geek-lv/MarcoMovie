package com.pilipala.marcomovie.adskip;

/**
 * 关键词识别引擎抽象。
 *
 * <p>实现需保证：
 * <ul>
 *   <li>{@link #accept(float[])} 送入的必须是 16kHz 单声道 float PCM（-1.0 ~ 1.0）；</li>
 *   <li>{@link #accept(float[])} 可以阻塞（在工作线程调用），但不能在播放线程调用；</li>
 *   <li>{@link #resetStream()} 必须丢弃累积的音频上下文，用于 seek / 换集 / 重复命中后清零。</li>
 * </ul>
 */
public interface KwsEngine {

    /** 引擎是否已就绪（模型已加载且关键词已设置） */
    boolean isReady();

    /**
     * 送入一块 16kHz 单声道音频。
     *
     * @param pcm16k 归一化到 -1.0 ~ 1.0 的 float PCM
     * @return 命中的关键词（原始中文），未命中返回 null
     */
    String accept(float[] pcm16k);

    /** 清空累积的音频上下文，重新开始一次新的匹配 */
    void resetStream();

    /** 释放引擎持有的资源 */
    void release();
}
