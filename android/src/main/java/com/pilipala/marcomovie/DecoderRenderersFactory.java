package com.pilipala.marcomovie;

import android.content.Context;
import android.media.MediaCrypto;
import android.os.Handler;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.FormatHolder;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer;
import androidx.media3.exoplayer.video.VideoRendererEventListener;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Keep audio extensions available even when hardware-only video is requested. */
final class DecoderRenderersFactory extends DefaultRenderersFactory {
    private final int mode;

    DecoderRenderersFactory(Context context, int decoderMode) {
        super(context);
        DecoderSupport.configure();
        mode = decoderMode;
        setEnableDecoderFallback(true);
        setExtensionRendererMode(
                (mode == 2
                                || mode == 3
                                || context.getSharedPreferences(Prefs.FILE, 0)
                                        .getBoolean(Prefs.SOFTWARE_AUDIO, false))
                        ? EXTENSION_RENDERER_MODE_PREFER
                        : EXTENSION_RENDERER_MODE_ON);
    }

    static boolean hasNativeDolbyVisionDecoder() {
        try {
            for (MediaCodecInfo info :
                    MediaCodecUtil.getDecoderInfos(MimeTypes.VIDEO_DOLBY_VISION, false, false)) {
                if (info.hardwareAccelerated) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    static Format toBaseLayerFormat(Format format) {
        if (format == null || !MimeTypes.VIDEO_DOLBY_VISION.equals(format.sampleMimeType)) {
            return format;
        }
        String altMime =
                format.codecs != null
                                && (format.codecs.startsWith("dvav")
                                        || format.codecs.startsWith("dva1")
                                        || format.codecs.startsWith("avc"))
                        ? MimeTypes.VIDEO_H264
                        : MimeTypes.VIDEO_H265;
        List<byte[]> initData = format.initializationData;
        if (initData != null && initData.size() > 1 && MimeTypes.VIDEO_H265.equals(altMime)) {
            initData = Collections.singletonList(initData.get(0));
        }
        return format.buildUpon()
                .setSampleMimeType(altMime)
                .setCodecs(null)
                .setInitializationData(initData != null ? initData : Collections.emptyList())
                .build();
    }

    static Format fallbackVideoFormat(Format format) {
        if (format == null || !MimeTypes.VIDEO_DOLBY_VISION.equals(format.sampleMimeType)) {
            return format;
        }
        if (hasNativeDolbyVisionDecoder()) {
            return format;
        }
        return toBaseLayerFormat(format);
    }

    /**
     * 在音频输出链上挂一个旁路 PCM 探针（移植自片刻/TVBoxOSC 的口播识别挂载方式）。
     *
     * <p>探针只做旁路拷贝（降混单声道 + 16kHz 重采样），不改变任何音频输出；
     * 引擎未初始化或功能未开启时状态机自行空转，音频链路不受影响。
     * 挂载失败时回退默认音频链路，绝不影响正常播放。
     */
    @Override
    protected androidx.media3.exoplayer.audio.AudioSink buildAudioSink(
            Context context, boolean enableFloatOutput, boolean enableAudioTrackPlaybackParams) {
        try {
            androidx.media3.common.audio.AudioProcessor tap =
                    com.pilipala.marcomovie.adskip.AdSkipController.get().createTapProcessor();
            if (tap != null) {
                return new androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                        .setEnableFloatOutput(enableFloatOutput)
                        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                        .setAudioProcessorChain(
                                new androidx.media3.exoplayer.audio.DefaultAudioSink
                                        .DefaultAudioProcessorChain(tap))
                        .build();
            }
        } catch (Throwable t) {
            android.util.Log.w("AdSkip", "buildAudioSink: 探针挂载失败，回退默认音频链路", t);
        }
        return super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams);
    }

    @Override
    protected void buildVideoRenderers(
            Context context,
            int extensionMode,
            MediaCodecSelector selector,
            boolean fallback,
            Handler handler,
            VideoRendererEventListener listener,
            long joiningMs,
            ArrayList<Renderer> out) {
        int effectiveMode =
                mode == 1
                        ? EXTENSION_RENDERER_MODE_OFF
                        : mode == 2 ? EXTENSION_RENDERER_MODE_PREFER : EXTENSION_RENDERER_MODE_ON;
        super.buildVideoRenderers(
                context, effectiveMode, selector, fallback, handler, listener, joiningMs, out);
        MediaCodecVideoRenderer doviFallbackRenderer =
                new MediaCodecVideoRenderer(
                        context,
                        getCodecAdapterFactory(),
                        selector,
                        joiningMs,
                        fallback,
                        handler,
                        listener,
                        MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY) {
                    @Override
                    protected int supportsFormat(
                            MediaCodecSelector mediaCodecSelector, Format format)
                            throws MediaCodecUtil.DecoderQueryException {
                        if (format != null
                                && MimeTypes.VIDEO_DOLBY_VISION.equals(format.sampleMimeType)) {
                            if (mode == 2 || !hasNativeDolbyVisionDecoder()) {
                                return super.supportsFormat(
                                        mediaCodecSelector, toBaseLayerFormat(format));
                            }
                            int dvSupport = super.supportsFormat(mediaCodecSelector, format);
                            if ((dvSupport & 7) >= C.FORMAT_HANDLED) {
                                return dvSupport;
                            }
                            int baseSupport =
                                    super.supportsFormat(
                                            mediaCodecSelector, toBaseLayerFormat(format));
                            return (baseSupport & 7) > (dvSupport & 7) ? baseSupport : dvSupport;
                        }
                        return super.supportsFormat(mediaCodecSelector, format);
                    }

                    @Override
                    protected List<MediaCodecInfo> getDecoderInfos(
                            MediaCodecSelector mediaCodecSelector,
                            Format format,
                            boolean requiresSecureDecoder)
                            throws MediaCodecUtil.DecoderQueryException {
                        if (format != null
                                && MimeTypes.VIDEO_DOLBY_VISION.equals(format.sampleMimeType)) {
                            List<MediaCodecInfo> combined = new ArrayList<>();
                            if (mode != 2 && hasNativeDolbyVisionDecoder()) {
                                try {
                                    combined.addAll(
                                            super.getDecoderInfos(
                                                    mediaCodecSelector,
                                                    format,
                                                    requiresSecureDecoder));
                                } catch (Throwable ignored) {
                                }
                            }
                            try {
                                List<MediaCodecInfo> baseDecoders =
                                        super.getDecoderInfos(
                                                mediaCodecSelector,
                                                toBaseLayerFormat(format),
                                                requiresSecureDecoder);
                                for (MediaCodecInfo info : baseDecoders) {
                                    boolean exists = false;
                                    for (MediaCodecInfo c : combined) {
                                        if (c.name.equals(info.name)) {
                                            exists = true;
                                            break;
                                        }
                                    }
                                    if (!exists) combined.add(info);
                                }
                            } catch (Throwable ignored) {
                            }
                            if (!combined.isEmpty()) return combined;
                        }
                        return super.getDecoderInfos(
                                mediaCodecSelector,
                                fallbackVideoFormat(format),
                                requiresSecureDecoder);
                    }

                    @Override
                    protected MediaCodecAdapter.Configuration getMediaCodecConfiguration(
                            MediaCodecInfo codecInfo,
                            Format format,
                            MediaCrypto crypto,
                            float codecOperatingRate) {
                        Format resolved =
                                codecInfo != null
                                                && !MimeTypes.VIDEO_DOLBY_VISION.equals(
                                                        codecInfo.mimeType)
                                        ? toBaseLayerFormat(format)
                                        : format;
                        return super.getMediaCodecConfiguration(
                                codecInfo, resolved, crypto, codecOperatingRate);
                    }

                    @Override
                    protected DecoderReuseEvaluation onInputFormatChanged(FormatHolder formatHolder)
                            throws ExoPlaybackException {
                        if (formatHolder != null
                                && formatHolder.format != null
                                && (mode == 2 || !hasNativeDolbyVisionDecoder())) {
                            formatHolder.format = toBaseLayerFormat(formatHolder.format);
                        }
                        return super.onInputFormatChanged(formatHolder);
                    }
                };
        for (int i = 0; i < out.size(); i++) {
            if (out.get(i) instanceof MediaCodecVideoRenderer) {
                out.set(i, doviFallbackRenderer);
                break;
            }
        }
    }
}
