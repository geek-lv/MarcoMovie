package com.pilipala.marcomovie;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Small Media3 adapter for the app's existing TV controls. All player access is
 * marshalled to the main looper, including reads made by device instrumentation. */
public class MoviePlayer {
    public interface OnPreparedListener {
        void onPrepared(MoviePlayer player);
    }

    public interface OnCompletionListener {
        void onCompletion(MoviePlayer player);
    }

    public interface OnVideoSizeChangedListener {
        void onVideoSizeChanged(MoviePlayer player, int width, int height);
    }

    public interface OnErrorListener {
        boolean onError(MoviePlayer player, int what, int extra);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Context context;
    private ExoPlayer exo;
    private Uri uri;
    private Map<String, String> headers = Collections.emptyMap();
    private OnPreparedListener preparedListener;
    private OnCompletionListener completionListener;
    private OnVideoSizeChangedListener sizeListener;
    private OnErrorListener errorListener;
    private boolean readySent, unsupportedVideo;
    private volatile boolean released;
    private int preparation;
    private volatile RequestScope probeScope;
    private long prepareStarted, firstFrameMs = -1;
    private String lastError = "";
    private String videoDecoder = "", audioDecoder = "";
    private int lastHttpStatus, lastErrorTrack;
    private org.json.JSONObject options = new org.json.JSONObject();
    private DefaultMediaSourceFactory mediaSourceFactory;
    private String mediaMime;
    private boolean hlsFallbackAttempted;
    private final boolean ijkMode;

    public interface SubtitleListener {
        void text(CharSequence text);
    }

    private SubtitleListener subtitleListener;

    public interface CueListener {
        void cues(androidx.media3.common.text.CueGroup group);
    }

    private CueListener cueListener;

    public static MoviePlayer createIjk(Context context) {
        return new MoviePlayer(context, true, -1, true);
    }

    public MoviePlayer(Context context) {
        this(context, true, -1, false);
    }

    public MoviePlayer(Context context, int decoderMode) {
        this(context, true, decoderMode, false);
    }

    public MoviePlayer(Context context, int decoderMode, boolean forceIjk) {
        this(context, true, decoderMode, forceIjk);
    }

    protected MoviePlayer(Context context, boolean initializeExo) {
        this(context, initializeExo, -1, false);
    }

    private MoviePlayer(
            Context context, boolean initializeExo, int requestedDecoder, boolean forceIjk) {
        this.context = context.getApplicationContext();
        this.ijkMode = forceIjk;
        if (!initializeExo) return;
        onMain(
                () -> {
                    int userDecoder =
                            this.context
                                    .getSharedPreferences(Prefs.FILE, 0)
                                    .getInt(Prefs.DECODER_MODE, 0);
                    int decoderMode =
                            requestedDecoder >= 0
                                    ? requestedDecoder
                                    : (this.ijkMode && userDecoder == 0 ? 3 : userDecoder);
                    DefaultRenderersFactory renderers =
                            new DecoderRenderersFactory(this.context, decoderMode);
                    renderers.setMediaCodecSelector(
                            (mime, secure, tunneling) -> {
                                java.util.List<androidx.media3.exoplayer.mediacodec.MediaCodecInfo>
                                        raw =
                                                new java.util.ArrayList<>(
                                                        androidx.media3.exoplayer.mediacodec
                                                                .MediaCodecUtil.getDecoderInfos(
                                                                mime, secure, tunneling));
                                if (MimeTypes.VIDEO_DOLBY_VISION.equals(mime)) {
                                    for (androidx.media3.exoplayer.mediacodec.MediaCodecInfo h265 :
                                            androidx.media3.exoplayer.mediacodec.MediaCodecUtil
                                                    .getDecoderInfos(
                                                            MimeTypes.VIDEO_H265,
                                                            secure,
                                                            tunneling)) {
                                        boolean exists = false;
                                        for (androidx.media3.exoplayer.mediacodec.MediaCodecInfo c :
                                                raw) {
                                            if (c.name.equals(h265.name)) {
                                                exists = true;
                                                break;
                                            }
                                        }
                                        if (!exists) raw.add(h265);
                                    }
                                } else if (MimeTypes.AUDIO_E_AC3_JOC.equals(mime)) {
                                    for (androidx.media3.exoplayer.mediacodec.MediaCodecInfo eac3 :
                                            androidx.media3.exoplayer.mediacodec.MediaCodecUtil
                                                    .getDecoderInfos(
                                                            MimeTypes.AUDIO_E_AC3,
                                                            secure,
                                                            tunneling)) {
                                        boolean exists = false;
                                        for (androidx.media3.exoplayer.mediacodec.MediaCodecInfo c :
                                                raw) {
                                            if (c.name.equals(eac3.name)) {
                                                exists = true;
                                                break;
                                            }
                                        }
                                        if (!exists) raw.add(eac3);
                                    }
                                }
                                if (decoderMode == 0 || decoderMode == 3) return raw;
                                java.util.List<androidx.media3.exoplayer.mediacodec.MediaCodecInfo>
                                        selected = new java.util.ArrayList<>();
                                for (androidx.media3.exoplayer.mediacodec.MediaCodecInfo codec :
                                        raw)
                                    if (!mime.startsWith("video/")
                                            || (decoderMode == 2
                                                    ? codec.softwareOnly
                                                    : codec.hardwareAccelerated))
                                        selected.add(codec);
                                return selected.isEmpty() ? raw : selected;
                            });
                    int buffer =
                            Math.max(
                                            15,
                                            Math.min(
                                                    50,
                                                    this.context
                                                            .getSharedPreferences(Prefs.FILE, 0)
                                                            .getInt(Prefs.BUFFER_SECONDS, 30)))
                                    * 1000;
                    int minBuffer = Math.min(12000, buffer);
                    int maxBuffer = Math.max(20000, buffer);
                    int startBufferMs = this.ijkMode ? 500 : 800;
                    int rebufferMs = this.ijkMode ? 1200 : 1600;
                    exo =
                            new ExoPlayer.Builder(this.context, renderers)
                                    .setLoadControl(
                                            new androidx.media3.exoplayer.DefaultLoadControl
                                                            .Builder()
                                                    .setBufferDurationsMs(
                                                            minBuffer,
                                                            maxBuffer,
                                                            startBufferMs,
                                                            rebufferMs)
                                                    .setPrioritizeTimeOverSizeThresholds(true)
                                                    .setTargetBufferBytes(64 * 1024 * 1024)
                                                    .build())
                                    .setLooper(Looper.getMainLooper())
                                    .build();
                    androidx.media3.exoplayer.trackselection.DefaultTrackSelector.Parameters.Builder
                            trackParams =
                                    new androidx.media3.exoplayer.trackselection
                                                    .DefaultTrackSelector.Parameters.Builder(
                                                    this.context)
                                            .setExceedRendererCapabilitiesIfNecessary(true)
                                            .setExceedVideoConstraintsIfNecessary(true)
                                            .setExceedAudioConstraintsIfNecessary(true);
                    if (this.context
                            .getSharedPreferences(Prefs.FILE, 0)
                            .getBoolean(Prefs.PREFER_AAC, false))
                        trackParams.setPreferredAudioMimeTypes(MimeTypes.AUDIO_AAC);
                    exo.setTrackSelectionParameters(trackParams.build());
                    exo.addAnalyticsListener(
                            new androidx.media3.exoplayer.analytics.AnalyticsListener() {
                                @Override
                                public void onVideoDecoderInitialized(
                                        EventTime time,
                                        String name,
                                        long completed,
                                        long duration) {
                                    videoDecoder = name;
                                }

                                @Override
                                public void onAudioDecoderInitialized(
                                        EventTime time,
                                        String name,
                                        long completed,
                                        long duration) {
                                    audioDecoder = name;
                                }

                                @Override
                                public void onRenderedFirstFrame(
                                        EventTime time, Object output, long renderTimeMs) {
                                    firstFrameMs =
                                            android.os.SystemClock.elapsedRealtime()
                                                    - prepareStarted;
                                }
                            });
                    exo.addListener(
                            new Player.Listener() {
                                @Override
                                public void onPlaybackStateChanged(int state) {
                                    if (released) return;
                                    if (state == Player.STATE_READY
                                            && !readySent
                                            && !unsupportedVideo) {
                                        readySent = true;
                                        if (preparedListener != null)
                                            preparedListener.onPrepared(MoviePlayer.this);
                                    } else if (state == Player.STATE_ENDED
                                            && completionListener != null)
                                        completionListener.onCompletion(MoviePlayer.this);
                                }

                                @Override
                                public void onTracksChanged(androidx.media3.common.Tracks tracks) {
                                    boolean video = false, supported = false;
                                    for (androidx.media3.common.Tracks.Group group :
                                            tracks.getGroups())
                                        if (group.getType() == C.TRACK_TYPE_VIDEO) {
                                            video = true;
                                            supported |=
                                                    group.isSupported(true) || group.isSelected();
                                        }
                                    if (video && !supported && !unsupportedVideo) {
                                        unsupportedVideo = true;
                                        lastError = "ERROR_CODE_DECODING_FORMAT_UNSUPPORTED";
                                        lastErrorTrack = C.TRACK_TYPE_VIDEO;
                                        exo.pause();
                                        if (errorListener != null)
                                            errorListener.onError(
                                                    MoviePlayer.this,
                                                    PlaybackException
                                                            .ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                                                    0);
                                    }
                                }

                                @Override
                                public void onVideoSizeChanged(VideoSize size) {
                                    if (!released && sizeListener != null)
                                        sizeListener.onVideoSizeChanged(
                                                MoviePlayer.this,
                                                Math.round(size.width * size.pixelWidthHeightRatio),
                                                size.height);
                                }

                                @Override
                                public void onPlayerError(PlaybackException error) {
                                    if (released) return;
                                    if (!hlsFallbackAttempted
                                            && uri != null
                                            && mediaSourceFactory != null) {
                                        String scheme = uri.getScheme();
                                        if ("http".equalsIgnoreCase(scheme)
                                                || "https".equalsIgnoreCase(scheme)) {
                                            if ((error.errorCode
                                                                    == PlaybackException
                                                                            .ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
                                                            || error.errorCode
                                                                    == PlaybackException
                                                                            .ERROR_CODE_PARSING_CONTAINER_MALFORMED)
                                                    && mediaMime == null) {
                                                hlsFallbackAttempted = true;
                                                mediaMime = MimeTypes.APPLICATION_M3U8;
                                                try {
                                                    exo.setMediaSource(playbackSource(uri));
                                                    exo.prepare();
                                                    return;
                                                } catch (Exception ignored) {
                                                }
                                            } else if (ijkMode
                                                    && MimeTypes.APPLICATION_M3U8.equalsIgnoreCase(
                                                            mediaMime)
                                                    && (error.errorCode
                                                                    == PlaybackException
                                                                            .ERROR_CODE_PARSING_MANIFEST_MALFORMED
                                                            || error.errorCode
                                                                    == PlaybackException
                                                                            .ERROR_CODE_PARSING_CONTAINER_MALFORMED)) {
                                                hlsFallbackAttempted = true;
                                                mediaMime = null;
                                                try {
                                                    exo.setMediaSource(playbackSource(uri));
                                                    exo.prepare();
                                                    return;
                                                } catch (Exception ignored) {
                                                }
                                            }
                                        }
                                    }
                                    lastError = error.getErrorCodeName();
                                    lastErrorTrack =
                                            error
                                                            instanceof
                                                            androidx.media3.exoplayer
                                                                    .ExoPlaybackException
                                                    ? (((androidx.media3.exoplayer
                                                                                            .ExoPlaybackException)
                                                                                    error)
                                                                            .rendererFormat
                                                                    == null
                                                            ? C.TRACK_TYPE_UNKNOWN
                                                            : MimeTypes.getTrackType(
                                                                    ((androidx.media3.exoplayer
                                                                                            .ExoPlaybackException)
                                                                                    error)
                                                                            .rendererFormat
                                                                            .sampleMimeType))
                                                    : C.TRACK_TYPE_UNKNOWN;
                                    lastHttpStatus = 0;
                                    for (Throwable cause = error;
                                            cause != null;
                                            cause = cause.getCause())
                                        if (cause
                                                instanceof
                                                androidx.media3.datasource.HttpDataSource
                                                        .InvalidResponseCodeException) {
                                            lastHttpStatus =
                                                    ((androidx.media3.datasource.HttpDataSource
                                                                            .InvalidResponseCodeException)
                                                                    cause)
                                                            .responseCode;
                                            break;
                                        }
                                    if (errorListener != null)
                                        errorListener.onError(MoviePlayer.this, error.errorCode, 0);
                                }

                                @Override
                                public void onCues(androidx.media3.common.text.CueGroup group) {
                                    if (cueListener != null) cueListener.cues(group);
                                    if (subtitleListener != null) {
                                        StringBuilder text = new StringBuilder();
                                        for (androidx.media3.common.text.Cue cue : group.cues)
                                            if (cue.text != null) {
                                                if (text.length() > 0) text.append('\n');
                                                text.append(cue.text);
                                            }
                                        subtitleListener.text(text);
                                    }
                                }
                            });
                    return null;
                });
    }

    public void configureDefaultAudio() {
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            setAudioAttributes(
                    new android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build());
        }
    }

    void bindSubtitles(final SubtitleOverlayView view, final SubtitleListener textCallback) {
        setCuesListener(
                group -> {
                    if (view != null) {
                        view.setCues(group.cues);
                    }
                    if (textCallback != null) {
                        textCallback.text(
                                group.cues.isEmpty() ? "" : String.valueOf(group.cues.get(0).text));
                    }
                });
    }

    public void setAudioAttributes(android.media.AudioAttributes attributes) {
        onMain(
                () -> {
                    check();
                    exo.setAudioAttributes(
                            new androidx.media3.common.AudioAttributes.Builder()
                                    .setUsage(attributes.getUsage())
                                    .setContentType(attributes.getContentType())
                                    .build(),
                            true);
                    return null;
                });
    }

    public void setDataSource(Context unused, Uri uri, Map<String, String> headers) {
        onMain(
                () -> {
                    check();
                    this.uri = uri;
                    this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
                    return null;
                });
    }

    public void setSurface(Surface surface) {
        onMain(
                () -> {
                    check();
                    exo.setVideoSurface(surface);
                    return null;
                });
    }

    public void setOnPreparedListener(OnPreparedListener listener) {
        onMain(
                () -> {
                    preparedListener = listener;
                    return null;
                });
    }

    public void setOnCompletionListener(OnCompletionListener listener) {
        onMain(
                () -> {
                    completionListener = listener;
                    return null;
                });
    }

    public void setOnVideoSizeChangedListener(OnVideoSizeChangedListener listener) {
        onMain(
                () -> {
                    sizeListener = listener;
                    return null;
                });
    }

    public void setOnErrorListener(OnErrorListener listener) {
        onMain(
                () -> {
                    errorListener = listener;
                    return null;
                });
    }

    public void setOptions(org.json.JSONObject metadata) {
        onMain(
                () -> {
                    options = new org.json.JSONObject(metadata.toString());
                    return null;
                });
    }

    static final int PROBE_BUDGET_MS = 3000;
    static final int PROBE_MAX_BYTES = 512;

    private static Map<String, String> sanitizeHeadersForAddress(
            Uri address, Map<String, String> sourceHeaders) {
        Map<String, String> out = new LinkedHashMap<>(sourceHeaders);
        if (address != null && ProxyAddress.isLoopbackHost(address.getHost())) {
            for (java.util.Iterator<Map.Entry<String, String>> it = out.entrySet().iterator();
                    it.hasNext(); ) {
                Map.Entry<String, String> e = it.next();
                String k = e.getKey();
                if ("Origin".equalsIgnoreCase(k)) {
                    String v = e.getValue();
                    it.remove();
                    if (v != null && !v.isEmpty()) out.put("X-Movie-Origin", v);
                    break;
                }
            }
            for (java.util.Iterator<Map.Entry<String, String>> it = out.entrySet().iterator();
                    it.hasNext(); ) {
                Map.Entry<String, String> e = it.next();
                String k = e.getKey();
                if ("Host".equalsIgnoreCase(k)) {
                    String v = e.getValue();
                    it.remove();
                    if (v != null && !v.isEmpty()) out.put("X-Movie-Host", v);
                    break;
                }
            }
        }
        return out;
    }

    public void prepareAsync() {
        onMain(
                () -> {
                    check();
                    if (uri == null) throw new IllegalStateException("No media item");
                    final int ticket = ++preparation;
                    RequestScope previousScope = probeScope;
                    if (previousScope != null) previousScope.cancel();
                    final RequestScope scope = new RequestScope();
                    probeScope = scope;
                    final Uri address = uri;
                    final Map<String, String> requestHeaders =
                            sanitizeHeadersForAddress(address, headers);
                    final String epName =
                            options.optString("_episodeName", "").toLowerCase(Locale.ROOT);
                    final boolean isLive = options.optBoolean("live", false);
                    readySent = false;
                    hlsFallbackAttempted = false;
                    maxKnownDurationMs = 0;
                    prepareStarted = android.os.SystemClock.elapsedRealtime();
                    firstFrameMs = -1;
                    // Local proxy and signed URLs often have no extension. Inspect a bounded
                    // response prefix off the UI thread so HLS/DASH uses the correct parser.
                    Thread sniff =
                            new Thread(
                                    () -> {
                                        String mime = null;
                                        try {
                                            String declared = MediaOptions.mime(options);
                                            String fullLower =
                                                    address.toString().toLowerCase(Locale.ROOT);
                                            boolean knownContainer =
                                                    !fullLower.contains(".m3u8")
                                                            && !fullLower.contains(".mpd")
                                                            && (epName.contains(".mkv")
                                                                    || epName.contains(".mp4")
                                                                    || epName.contains(".webm")
                                                                    || epName.contains(".avi")
                                                                    || epName.contains(".mov")
                                                                    || epName.contains(".flv")
                                                                    || fullLower.contains(".mkv")
                                                                    || fullLower.contains(".mp4")
                                                                    || fullLower.contains(".flv")
                                                                    || fullLower.contains(".ts"));
                                            if (knownContainer) {
                                                mime = null;
                                            } else if (declared != null) {
                                                mime = declared;
                                            } else if (isLive && fullLower.contains(".m3u8")) {
                                                mime = MimeTypes.APPLICATION_M3U8;
                                            } else if (isLive && fullLower.contains(".mpd")) {
                                                mime = MimeTypes.APPLICATION_MPD;
                                            } else {
                                                mime =
                                                        scope.call(
                                                                () ->
                                                                        sniffMime(
                                                                                address,
                                                                                requestHeaders,
                                                                                scope));
                                            }
                                        } catch (Exception ignored) {
                                        }
                                        if (scope.cancelled) return;
                                        final String resolvedMime = mime;
                                        main.post(
                                                () -> {
                                                    if (released
                                                            || scope.cancelled
                                                            || ticket != preparation) return;
                                                    try {
                                                        String userAgent = "MarcoMovie/0.2.0";
                                                        for (Map.Entry<String, String> header :
                                                                requestHeaders.entrySet())
                                                            if (header.getKey()
                                                                    .equalsIgnoreCase("User-Agent"))
                                                                userAgent = header.getValue();
                                                        DefaultHttpDataSource.Factory http =
                                                                new DefaultHttpDataSource.Factory()
                                                                        .setUserAgent(userAgent)
                                                                        .setDefaultRequestProperties(
                                                                                requestHeaders)
                                                                        .setAllowCrossProtocolRedirects(
                                                                                true)
                                                                        .setConnectTimeoutMs(
                                                                                ijkMode
                                                                                        ? 20000
                                                                                        : 15000)
                                                                        .setReadTimeoutMs(
                                                                                ijkMode
                                                                                        ? 45000
                                                                                        : 30000);
                                                        DefaultDataSource.Factory data =
                                                                new DefaultDataSource.Factory(
                                                                        context, http);
                                                        mediaMime = resolvedMime;
                                                        androidx.media3.extractor
                                                                        .DefaultExtractorsFactory
                                                                extractors =
                                                                        new androidx.media3
                                                                                        .extractor
                                                                                        .DefaultExtractorsFactory()
                                                                                .setConstantBitrateSeekingEnabled(
                                                                                        true)
                                                                                .setTsExtractorTimestampSearchBytes(
                                                                                        (ijkMode
                                                                                                        ? 3600
                                                                                                        : 1500)
                                                                                                * 188);
                                                        if (ijkMode) {
                                                            extractors.setTsExtractorFlags(
                                                                    androidx.media3.extractor.ts
                                                                                    .DefaultTsPayloadReaderFactory
                                                                                    .FLAG_ALLOW_NON_IDR_KEYFRAMES
                                                                            | androidx.media3
                                                                                    .extractor.ts
                                                                                    .DefaultTsPayloadReaderFactory
                                                                                    .FLAG_DETECT_ACCESS_UNITS
                                                                            | androidx.media3
                                                                                    .extractor.ts
                                                                                    .DefaultTsPayloadReaderFactory
                                                                                    .FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS
                                                                            | androidx.media3
                                                                                    .extractor.ts
                                                                                    .DefaultTsPayloadReaderFactory
                                                                                    .FLAG_IGNORE_SPLICE_INFO_STREAM);
                                                        }
                                                        mediaSourceFactory =
                                                                new DefaultMediaSourceFactory(
                                                                        data, extractors);
                                                        exo.setMediaSource(playbackSource(address));
                                                        exo.prepare();
                                                    } catch (Exception error) {
                                                        lastError =
                                                                error.getClass().getSimpleName();
                                                        if (errorListener != null)
                                                            errorListener.onError(
                                                                    MoviePlayer.this,
                                                                    PlaybackException
                                                                            .ERROR_CODE_UNSPECIFIED,
                                                                    0);
                                                    }
                                                });
                                    },
                                    "media-type-probe");
                    sniff.setDaemon(true);
                    sniff.start();
                    return null;
                });
    }

    private static String sniffMime(
            Uri address, Map<String, String> requestHeaders, RequestScope scope) {
        if (scope != null && scope.cancelled) return null;
        int type = Util.inferContentType(address);
        if (type == C.CONTENT_TYPE_HLS) return MimeTypes.APPLICATION_M3U8;
        if (type == C.CONTENT_TYPE_DASH) return MimeTypes.APPLICATION_MPD;
        String path = address.getPath();
        if (path != null
                && path.toLowerCase(Locale.ROOT)
                        .matches(".*\\.(mp4|mkv|webm|mp3|aac|flac|ogg|ts|flv|mov|avi)$"))
            return null;
        String fullUri = address.toString().toLowerCase(Locale.ROOT);
        if (fullUri.contains(".m3u8")) return MimeTypes.APPLICATION_M3U8;
        if (fullUri.contains(".mpd")) return MimeTypes.APPLICATION_MPD;
        if (fullUri.contains(".mkv")
                || fullUri.contains(".mp4")
                || fullUri.contains(".flv")
                || fullUri.contains(".ts")) return null;
        String scheme = address.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return null;
        long deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PROBE_BUDGET_MS);
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(address.toString()).openConnection();
            RequestScope.attach(connection);
            connection.setConnectTimeout(2500);
            connection.setReadTimeout(2500);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "MarcoMovie/0.2.0");
            connection.setRequestProperty("Range", "bytes=0-" + (PROBE_MAX_BYTES - 1));
            for (Map.Entry<String, String> header : requestHeaders.entrySet())
                connection.setRequestProperty(header.getKey(), header.getValue());
            if (scope != null && scope.cancelled) return null;
            String contentType = connection.getContentType();
            if (contentType != null) {
                String lower = contentType.toLowerCase(Locale.ROOT);
                if (lower.contains("mpegurl")) return MimeTypes.APPLICATION_M3U8;
                if (lower.contains("dash+xml")) return MimeTypes.APPLICATION_MPD;
                if (lower.startsWith("video/")
                        || lower.startsWith("audio/")
                        || lower.contains("matroska")
                        || lower.contains("octet-stream")) return null;
            }
            byte[] prefix = new byte[PROBE_MAX_BYTES];
            int count = 0;
            try (InputStream stream = connection.getInputStream()) {
                while (count < prefix.length && System.nanoTime() < deadlineNs) {
                    if (scope != null && scope.cancelled) return null;
                    int remainingMs =
                            (int)
                                    Math.max(
                                            250L,
                                            TimeUnit.NANOSECONDS.toMillis(
                                                    deadlineNs - System.nanoTime()));
                    connection.setReadTimeout(Math.min(2500, remainingMs));
                    int n = stream.read(prefix, count, prefix.length - count);
                    if (n < 0) break;
                    count += n;
                    if (count >= 4
                            && (prefix[0] & 0xFF) == 0x1A
                            && (prefix[1] & 0xFF) == 0x45
                            && (prefix[2] & 0xFF) == 0xDF
                            && (prefix[3] & 0xFF) == 0xA3) {
                        // EBML / Matroska (.mkv / .webm) magic bytes
                        return null;
                    }
                    String partial = new String(prefix, 0, count, "UTF-8").trim();
                    if (partial.startsWith("\ufeff")) partial = partial.substring(1).trim();
                    if (partial.startsWith("#EXTM3U")) return MimeTypes.APPLICATION_M3U8;
                    if (partial.contains("<MPD")) return MimeTypes.APPLICATION_MPD;
                    if (count >= 16 && !partial.startsWith("<") && !partial.startsWith("#"))
                        return null;
                }
            }
            String start = new String(prefix, 0, count, "UTF-8").trim();
            if (start.startsWith("\ufeff")) start = start.substring(1).trim();
            if (start.startsWith("#EXTM3U")) return MimeTypes.APPLICATION_M3U8;
            if (start.contains("<MPD")) return MimeTypes.APPLICATION_MPD;
        } catch (Exception ignored) {
            /* The real loader reports transport failures. */
        } finally {
            if (connection != null) RequestScope.detach(connection);
            if (connection != null) connection.disconnect();
        }
        return null;
    }

    public void start() {
        onMain(
                () -> {
                    check();
                    exo.play();
                    return null;
                });
    }

    public void pause() {
        onMain(
                () -> {
                    if (!released) exo.pause();
                    return null;
                });
    }

    public void seekTo(int ms) {
        final int target = Math.max(0, ms);
        // 所有用户发起的 seek（遥控器、进度条、手势、跳过片头）统一在此通知状态机重锚。
        // 放在 MoviePlayer 而不是各调用点，一处覆盖，避免漏挂。
        com.pilipala.marcomovie.adskip.AdSkipController.get().onSeek(target, true);
        onMain(
                () -> {
                    check();
                    exo.seekTo(target);
                    return null;
                });
    }

    /**
     * 口播跳过的程序化跳转专用入口：不通知状态机。
     * 若走 {@link #seekTo(int)} 会被当成用户拖动而清掉「已跳区间」，破坏幂等。
     */
    public void seekToFromAdSkip(int ms) {
        final int target = Math.max(0, ms);
        onMain(
                () -> {
                    check();
                    exo.seekTo(target);
                    return null;
                });
    }

    public boolean isPlaying() {
        return onMain(() -> !released && exo.isPlaying());
    }

    public int getCurrentPosition() {
        return onMain(() -> released ? 0 : milliseconds(exo.getCurrentPosition()));
    }

    private int maxKnownDurationMs = 0;

    public int getDuration() {
        return onMain(
                () -> {
                    if (released) return 0;
                    long raw = exo.getDuration();
                    if (raw == C.TIME_UNSET || raw <= 0) {
                        raw = exo.getContentDuration();
                    }
                    int ms = milliseconds(raw);
                    if (ms > maxKnownDurationMs) {
                        maxKnownDurationMs = ms;
                    }
                    return ms > 0 ? ms : maxKnownDurationMs;
                });
    }

    public int getVideoWidth() {
        return onMain(
                () ->
                        released
                                ? 0
                                : Math.round(
                                        exo.getVideoSize().width
                                                * exo.getVideoSize().pixelWidthHeightRatio));
    }

    private static String shortVideoCodec(String mime) {
        if (mime == null) return "";
        String m = mime.toLowerCase(Locale.ROOT);
        if (m.contains("dolby-vision") || m.contains("dvhe") || m.contains("dvh1")) return "杜比视界";
        if (m.contains("hevc") || m.contains("h265")) return "HEVC";
        if (m.contains("avc") || m.contains("h264")) return "H.264";
        if (m.contains("av01") || m.contains("av1")) return "AV1";
        if (m.contains("vp9")) return "VP9";
        if (m.contains("mp4v")) return "MPEG4";
        return "";
    }

    private static String shortAudioCodec(String mime) {
        if (mime == null) return "";
        String m = mime.toLowerCase(Locale.ROOT);
        if (m.contains("eac3")) return "EAC3";
        if (m.contains("ac3")) return "AC3";
        if (m.contains("truehd")) return "TrueHD";
        if (m.contains("dts")) return "DTS";
        if (m.contains("flac")) return "FLAC";
        if (m.contains("opus")) return "Opus";
        if (m.contains("mp4a") || m.contains("aac")) return "AAC";
        if (m.contains("mpeg") || m.contains("mp3")) return "MP3";
        return "";
    }

    private String shortContainerFormat() {
        String epName =
                options != null
                        ? options.optString("_episodeName", "").toLowerCase(Locale.ROOT)
                        : "";
        String u = uri != null ? uri.toString().toLowerCase(Locale.ROOT) : "";
        String m = mediaMime != null ? mediaMime.toLowerCase(Locale.ROOT) : "";
        if (epName.contains(".mkv") || u.contains(".mkv") || m.contains("matroska")) return "MKV";
        if (m.contains("mpegurl") || u.contains(".m3u8")) return "HLS";
        if (m.contains("dash") || u.contains(".mpd")) return "DASH";
        if (epName.contains(".mp4") || u.contains(".mp4")) return "MP4";
        if (epName.contains(".flv") || u.contains(".flv")) return "FLV";
        if (epName.contains(".ts") || u.contains(".ts")) return "TS";
        if (epName.contains(".webm") || u.contains(".webm")) return "WebM";
        return "";
    }

    public String videoResolution() {
        return onMain(
                () -> {
                    if (released) return "分辨率未知";
                    androidx.media3.common.Format format = exo.getVideoFormat();
                    androidx.media3.common.Format audioFormat = exo.getAudioFormat();
                    int w =
                            format != null && format.width > 0
                                    ? format.width
                                    : exo.getVideoSize().width;
                    int h =
                            format != null && format.height > 0
                                    ? format.height
                                    : exo.getVideoSize().height;
                    if (w <= 0 || h <= 0) return "分辨率未知";
                    int longEdge = Math.max(w, h);
                    String tier =
                            longEdge >= 3800
                                    ? "4K "
                                    : longEdge >= 2500
                                            ? "2K "
                                            : longEdge >= 1900
                                                    ? "1080P "
                                                    : longEdge >= 1200 ? "720P " : "";
                    StringBuilder sb = new StringBuilder();
                    sb.append(tier).append(w).append(" × ").append(h);
                    String vCodec = format != null ? shortVideoCodec(format.sampleMimeType) : "";
                    if (!vCodec.isEmpty()) sb.append(" · ").append(vCodec);
                    if (format != null
                            && format.colorInfo != null
                            && androidx.media3.common.ColorInfo.isTransferHdr(format.colorInfo)
                            && !"杜比视界".equals(vCodec)) {
                        sb.append(" HDR");
                    }
                    String aCodec =
                            audioFormat != null ? shortAudioCodec(audioFormat.sampleMimeType) : "";
                    if (!aCodec.isEmpty()) sb.append(" · ").append(aCodec);
                    String container = shortContainerFormat();
                    if (!container.isEmpty()) sb.append(" · ").append(container);
                    return sb.toString();
                });
    }

    public int getVideoHeight() {
        return onMain(() -> released ? 0 : exo.getVideoSize().height);
    }

    public String getLastError() {
        return onMain(() -> lastError);
    }

    public int softFallbackMode() {
        return onMain(() -> lastErrorTrack == C.TRACK_TYPE_AUDIO ? 3 : 2);
    }

    public String getFailureMessage(int code) {
        return onMain(() -> PlaybackFailure.message(code, lastHttpStatus));
    }

    public org.json.JSONObject decoderDiagnostics() {
        return onMain(
                () -> {
                    org.json.JSONObject info =
                            new org.json.JSONObject()
                                    .put("video_decoder", videoDecoder)
                                    .put("audio_decoder", audioDecoder);
                    if (released) return info;
                    androidx.media3.common.Format vf = exo.getVideoFormat(),
                            af = exo.getAudioFormat();
                    if (vf != null) {
                        info.put("video_mime", vf.sampleMimeType)
                                .put("width", vf.width)
                                .put("height", vf.height)
                                .put("frame_rate", vf.frameRate);
                        if (vf.colorInfo != null)
                            info.put("color_space", vf.colorInfo.colorSpace)
                                    .put("color_transfer", vf.colorInfo.colorTransfer)
                                    .put("color_range", vf.colorInfo.colorRange)
                                    .put("luma_bitdepth", vf.colorInfo.lumaBitdepth)
                                    .put(
                                            "hdr",
                                            androidx.media3.common.ColorInfo.isTransferHdr(
                                                    vf.colorInfo));
                    }
                    info.put("first_frame_ms", firstFrameMs);
                    if (af != null)
                        info.put("audio_mime", af.sampleMimeType).put("channels", af.channelCount);
                    androidx.media3.exoplayer.DecoderCounters vc = exo.getVideoDecoderCounters(),
                            ac = exo.getAudioDecoderCounters();
                    if (vc != null) {
                        vc.ensureUpdated();
                        info.put("video_rendered", vc.renderedOutputBufferCount)
                                .put("video_dropped", vc.droppedBufferCount);
                    }
                    if (ac != null) {
                        ac.ensureUpdated();
                        info.put("audio_rendered", ac.renderedOutputBufferCount);
                    }
                    return info;
                });
    }

    public String playbackInfo() {
        org.json.JSONObject d = decoderDiagnostics();
        return (ijkMode ? "IJK (FFmpeg)\n视频：" : "Exo (Media3)\n视频：")
                + d.optString("video_mime", "尚未识别")
                + " · "
                + d.optString("video_decoder", "尚未启动")
                + "\n音频："
                + d.optString("audio_mime", "尚未识别")
                + " · "
                + d.optString("audio_decoder", "尚未启动")
                + "\n"
                + d.optInt("width")
                + " × "
                + d.optInt("height")
                + (getLastError().isEmpty() ? "" : "\n" + getLastError());
    }

    public int getAudioSessionId() {
        return onMain(() -> released ? 0 : exo.getAudioSessionId());
    }

    public void setCuesListener(CueListener listener) {
        onMain(
                () -> {
                    cueListener = listener;
                    return null;
                });
    }

    private androidx.media3.exoplayer.source.MediaSource playbackSource(Uri address)
            throws Exception {
        androidx.media3.exoplayer.source.MediaSource video =
                mediaSourceFactory.createMediaSource(mediaItem(address));
        org.json.JSONObject extra = options.optJSONObject("extra");
        String audio = extra == null ? "" : extra.optString("audio");
        if (!Cms.isHttp(audio)) return video;
        return new androidx.media3.exoplayer.source.MergingMediaSource(
                true, video, mediaSourceFactory.createMediaSource(MediaItem.fromUri(audio)));
    }

    private MediaItem mediaItem(Uri address) throws Exception {
        MediaItem.Builder item = new MediaItem.Builder().setUri(address);
        if (mediaMime != null
                && (MimeTypes.APPLICATION_M3U8.equalsIgnoreCase(mediaMime)
                        || MimeTypes.APPLICATION_MPD.equalsIgnoreCase(mediaMime)
                        || MimeTypes.APPLICATION_SS.equalsIgnoreCase(mediaMime))) {
            item.setMimeType(mediaMime);
        }
        if (options.optBoolean("live", false)) {
            item.setLiveConfiguration(
                    new MediaItem.LiveConfiguration.Builder()
                            .setTargetOffsetMs(3500)
                            .setMinPlaybackSpeed(0.97f)
                            .setMaxPlaybackSpeed(1.04f)
                            .build());
        }
        java.util.List<MediaItem.SubtitleConfiguration> tracks = new java.util.ArrayList<>();
        org.json.JSONArray subtitles = options.optJSONArray("subtitles");
        if (subtitles != null)
            for (int i = 0; i < subtitles.length(); i++) {
                org.json.JSONObject sub = subtitles.optJSONObject(i);
                if (sub == null || sub.optString("url").isEmpty()) continue;
                tracks.add(
                        new MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub.optString("url")))
                                .setMimeType(
                                        MediaOptions.subtitleMime(
                                                sub.optString("url"), sub.optString("mimeType")))
                                .setLabel(sub.optString("name", "外挂字幕"))
                                .setLanguage(sub.optString("language", "zh"))
                                .setSelectionFlags(i == 0 ? C.SELECTION_FLAG_DEFAULT : 0)
                                .build());
            }
        item.setSubtitleConfigurations(tracks);
        org.json.JSONObject drm = options.optJSONObject("drm");
        String scheme =
                drm == null
                        ? options.optString("drmScheme")
                        : drm.optString("scheme", drm.optString("type"));
        String license =
                drm == null
                        ? options.optString("drmLicenseUrl")
                        : drm.optString("licenseUrl", drm.optString("licenseUri"));
        if (!scheme.isEmpty()) {
            java.util.UUID uuid = Util.getDrmUuid(scheme);
            if (uuid == null || !Cms.isHttp(license))
                throw new IllegalArgumentException("来源缺少有效DRM许可配置");
            java.util.Map<String, String> licenseHeaders = new LinkedHashMap<>();
            if (drm != null && drm.optJSONObject("headers") != null)
                MediaOptions.headers(licenseHeaders, drm.optJSONObject("headers"));
            item.setDrmConfiguration(
                    new MediaItem.DrmConfiguration.Builder(uuid)
                            .setLicenseUri(license)
                            .setLicenseRequestHeaders(licenseHeaders)
                            .build());
        }
        return item.build();
    }

    public void addExternalSubtitle(Uri subtitle, String name, String mime) {
        onMain(
                () -> {
                    check();
                    if (mediaSourceFactory == null) throw new IllegalStateException("请等待视频开始播放");
                    org.json.JSONArray tracks = options.optJSONArray("subtitles");
                    if (tracks == null) {
                        tracks = new org.json.JSONArray();
                        options.put("subtitles", tracks);
                    }
                    tracks.put(
                            new org.json.JSONObject()
                                    .put("url", subtitle.toString())
                                    .put("name", name)
                                    .put("mimeType", mime));
                    long position = exo.getCurrentPosition();
                    boolean play = exo.getPlayWhenReady();
                    exo.setMediaSource(playbackSource(uri), position);
                    exo.prepare();
                    exo.setPlayWhenReady(play);
                    return null;
                });
    }

    public void setSubtitleListener(SubtitleListener listener) {
        onMain(
                () -> {
                    subtitleListener = listener;
                    return null;
                });
    }

    public void setSpeed(float speed) {
        onMain(
                () -> {
                    check();
                    exo.setPlaybackSpeed(speed);
                    return null;
                });
    }

    public float getSpeed() {
        return onMain(() -> exo.getPlaybackParameters().speed);
    }

    public void setRepeatOne(boolean repeat) {
        onMain(
                () -> {
                    check();
                    exo.setRepeatMode(repeat ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
                    return null;
                });
    }

    public java.util.List<String> trackLabels(int type) {
        return onMain(
                () -> {
                    java.util.List<String> out = new java.util.ArrayList<>();
                    for (androidx.media3.common.Tracks.Group group :
                            exo.getCurrentTracks().getGroups())
                        if (group.getType() == type)
                            for (int i = 0; i < group.length; i++)
                                if (group.isTrackSupported(i)) {
                                    androidx.media3.common.Format f = group.getTrackFormat(i);
                                    String label =
                                            f.label != null
                                                    ? f.label
                                                    : f.language != null
                                                            ? f.language
                                                            : "轨道 " + (out.size() + 1);
                                    if (f.channelCount > 0) label += " · " + f.channelCount + " 声道";
                                    out.add((group.isTrackSelected(i) ? "✓ " : "") + label);
                                }
                    return out;
                });
    }

    public void selectTrack(int type, int index) {
        onMain(
                () -> {
                    check();
                    androidx.media3.common.TrackSelectionParameters.Builder p =
                            exo.getTrackSelectionParameters()
                                    .buildUpon()
                                    .clearOverridesOfType(type)
                                    .setTrackTypeDisabled(type, index == -2);
                    if (index >= 0) {
                        int count = 0;
                        for (androidx.media3.common.Tracks.Group group :
                                exo.getCurrentTracks().getGroups())
                            if (group.getType() == type)
                                for (int i = 0; i < group.length; i++)
                                    if (group.isTrackSupported(i) && count++ == index)
                                        p.addOverride(
                                                new androidx.media3.common.TrackSelectionOverride(
                                                        group.getMediaTrackGroup(), i));
                    }
                    exo.setTrackSelectionParameters(p.build());
                    return null;
                });
    }

    public void release() {
        RequestScope activeScope = probeScope;
        if (activeScope != null) activeScope.cancel();
        onMain(
                () -> {
                    if (!released) {
                        released = true;
                        preparation++;
                        RequestScope current = probeScope;
                        if (current != null) current.cancel();
                        exo.release();
                    }
                    return null;
                });
    }

    private static int milliseconds(long value) {
        return value == C.TIME_UNSET || value < 0 ? 0 : (int) Math.min(Integer.MAX_VALUE, value);
    }

    private void check() {
        if (released) throw new IllegalStateException("Player released");
    }

    private <T> T onMain(Callable<T> work) {
        if (Looper.myLooper() == Looper.getMainLooper())
            try {
                return work.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        FutureTask<T> task = new FutureTask<>(work);
        if (!main.post(task)) throw new IllegalStateException("Main looper unavailable");
        try {
            return task.get(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Player call interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new IllegalStateException(cause);
        } catch (java.util.concurrent.TimeoutException e) {
            task.cancel(false);
            throw new IllegalStateException("Player UI thread timed out", e);
        }
    }
}
