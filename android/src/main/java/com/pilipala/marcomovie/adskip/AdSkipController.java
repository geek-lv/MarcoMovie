package com.pilipala.marcomovie.adskip;

import android.content.Context;
import android.util.Log;

import com.pilipala.marcomovie.adskip.AdSkipConfig;
import com.k2fsa.sherpa.onnx.SherpaKwsEngine;

/**
 * 内嵌广告跳过的总协调器，进程内单例。
 *
 * <p>职责：
 * <ul>
 *   <li>持有识别引擎（模型只加载一次）与状态机（每集状态）；</li>
 *   <li>给播放器提供 PCM 探针；</li>
 *   <li>把"命中"翻译成一次 seek，并保证状态机同步感知这次跳转。</li>
 * </ul>
 */
public class AdSkipController {

    private static final String TAG = "AdSkip";

    public interface SeekHandler {
        /** 执行跳转 */
        void seekTo(long positionMs);

        /** 可选：给用户一个提示 */
        void onAdSkipped(long fromMs, long toMs, String keyword);
    }

    private static volatile AdSkipController instance;

    private final AdSkipStateMachine machine = new AdSkipStateMachine();
    private SherpaKwsEngine engine;
    private volatile SeekHandler seekHandler;
    private volatile boolean inited = false;
    /**
     * 引擎是后台异步加载的，而播放器往往几百毫秒就 prepared。若不记住这次起播，
     * {@code onPlaybackStart} 会因 {@code !inited} 被直接丢弃，状态机永远停在 IDLE，
     * 表现为「引擎就绪但一次都不检测」。init 完成后据此补一次 start。
     */
    private volatile boolean pendingStart = false;
    private volatile long pendingStartPositionMs = 0;
    private final java.util.concurrent.atomic.AtomicInteger tapCount =
            new java.util.concurrent.atomic.AtomicInteger();

    private AdSkipController() {
    }

    public static AdSkipController get() {
        if (instance == null) {
            synchronized (AdSkipController.class) {
                if (instance == null) {
                    instance = new AdSkipController();
                }
            }
        }
        return instance;
    }

    /** 首次使用时初始化，重复调用无副作用 */
    public void init(Context context) {
        if (inited) return;
        synchronized (this) {
            if (inited) return;
            try {
                // 必须先读配置再建流：预热调用（入口 Activity）可能早于任何界面读取 prefs，
                // 若不在此处初始化配置，会以空关键词建流，之后 init 又因 inited=true 被跳过，
                // 表现为"引擎就绪但永远不命中"
                AdSkipConfig.init(context);
                // 诊断 wav 落到应用私有缓存，普通应用对 /data/local/tmp 无写权限
                AdSkipStateMachine.setDiagnosticDir(
                        new java.io.File(context.getCacheDir(), "adskip"));
                Log.i(TAG, "init: 配置 enabled=" + AdSkipConfig.isEnabled()
                        + " 时长=" + AdSkipConfig.getSkipMillis() + "ms"
                        + " 关键词数=" + AdSkipConfig.getKeywordCount()
                        + " 关键词=" + AdSkipConfig.getKeywordList());
                engine = SherpaKwsEngine.get();
                engine.init(context);
                Log.i(TAG, "init: 引擎本体 ready=" + engine.isReadyRaw());
                engine.applyKeywords();
                Log.i(TAG, "init: 引擎含流 ready=" + engine.isReady());
                machine.attach(engine, new AdSkipStateMachine.Callback() {
                    @Override
                    public void onAdDetected(long fromMs, long toMs, String keyword) {
                        Log.i(TAG, ">>> 命中广告: 关键词=[" + keyword + "] 从 " + fromMs + "ms 跳到 " + toMs + "ms");
                        SeekHandler handler = seekHandler;
                        if (handler == null) {
                            Log.w(TAG, ">>> 命中但 SeekHandler 为空，无法跳转！");
                        } else {
                            handler.onAdSkipped(fromMs, toMs, keyword);
                            handler.seekTo(toMs);
                        }
                        // 以"程序化跳转"通知状态机：重置时钟但保留已跳区间，保证幂等
                        machine.onSeekTo(toMs, false);
                    }
                });
                inited = true;
                // 补做加载期间发生过的起播 / 换集，否则首集永远不会被识别。
                if (pendingStart) {
                    pendingStart = false;
                    machine.start(pendingStartPositionMs);
                    Log.i(TAG, "init: 补做延迟起播 @" + pendingStartPositionMs + "ms");
                }
            } catch (Throwable t) {
                Log.e(TAG, "init 异常", t);
                t.printStackTrace();
            }
        }
    }

    public boolean isEnabled() {
        return AdSkipConfig.isEnabled();
    }

    /**
     * 给播放器创建一个 PCM 探针。
     *
     * <p>注意：这里<b>不检查引擎是否就绪</b>。因为换集时播放器实例会被复用，
     * 不会再走 buildAudioSink，若首集引擎尚未加载完就返回 null，后续就永远补不上探针了。
     * 引擎采取异步懒加载，加载完成后自动生效；未就绪时状态机会跳过识别，不影响播放。
     */
    public androidx.media3.common.audio.AudioProcessor createTapProcessor() {
        // 注意：这里不看 enabled。播放器实例在换集时会复用，buildAudioSink 不会再次执行，
        // 若此刻因"功能未开启"返回 null，探针将永久缺失，用户之后在设置里打开功能也无法生效。
        // 探针本身只是透传 + 旁路拷贝，开销可忽略；是否真正识别由状态机根据 enabled 决定。
        Log.i(TAG, "createTapProcessor: 挂载探针 enabled=" + AdSkipConfig.isEnabled()
                + " inited=" + inited
                + " engineRawReady=" + (engine != null && engine.isReadyRaw()));
        tapCount.incrementAndGet();
        return new AdSkipAudioProcessor(machine);
    }

    /** 设置页「引擎自检」用：把「为什么不检测」的整条链路一次性打出来。 */
    public String diagnostics() {
        SherpaKwsEngine e = engine;
        StringBuilder sb = new StringBuilder();
        sb.append("配置开启：").append(AdSkipConfig.isEnabled()).append('\n');
        sb.append("关键词：").append(AdSkipConfig.getKeywordList()).append('\n');
        sb.append("跳过时长：").append(AdSkipConfig.getSkipSeconds()).append(" 秒\n");
        sb.append("音素串：")
                .append(KeywordPhonemeConverter.toKeywordsParam(AdSkipConfig.getKeywordList()))
                .append('\n');
        sb.append("模型已加载：").append(e != null && e.isReadyRaw()).append('\n');
        sb.append("识别流就绪：").append(e != null && e.isReady()).append('\n');
        sb.append("状态机：").append(machine.getState()).append('\n');
        sb.append("音频探针挂载次数：").append(tapCount.get()).append('\n');
        sb.append("跳转回调：").append(seekHandler != null ? "已绑定" : "未绑定");
        return sb.toString();
    }

    public void setSeekHandler(SeekHandler handler) {
        Log.i(TAG, "setSeekHandler: " + (handler != null ? "已绑定" : "置空"));
        this.seekHandler = handler;
    }

    // ------------------------------------------------------------ 生命周期

    /** 开始一次播放（含换集后的重新起播） */
    public void onPlaybackStart(long positionMs) {
        pendingStartPositionMs = positionMs;
        pendingStart = true;
        if (!inited) return;
        pendingStart = false;
        machine.start(positionMs);
    }

    /** 换集 / 换源：清空每集状态，但保留引擎 */
    public void onEpisodeChange() {
        pendingStartPositionMs = 0;
        pendingStart = true;
        if (!inited) return;
        pendingStart = false;
        machine.resetForNewMedia();
    }

    /** 播放位置跳转 */
    public void onSeek(long positionMs, boolean userInitiated) {
        if (!inited) return;
        machine.onSeekTo(positionMs, userInitiated);
    }

    /** 播放位置更新，用于校正音频时钟漂移 */
    public void onPositionUpdate(long positionMs) {
        if (!inited) return;
        // 自愈：并非所有播放路径都会给出「起播」回调 —— Media3/ExoPlayer 直接 prepare
        // 完成时不会触发 setOnPreparedListener（那是系统播放器分支）。若只依赖该回调，
        // 状态机会永远停在 IDLE，表现为「探针已挂载、PCM 一直在流、但一次都不检测」。
        // PlaybackController.clock() 每秒上报真实位置，用它在 IDLE 时补一次 start，
        // 同时覆盖直播 / 手机端 / 续播 / 换源等所有路径，且 anchor 用的是真实播放位置。
        AdSkipStateMachine.State s = machine.getState();
        if (s == AdSkipStateMachine.State.IDLE && AdSkipConfig.isEnabled()) {
            Log.i(TAG, "onPositionUpdate: 检测态缺失(" + s + ")，自动补 start @" + positionMs + "ms");
            machine.start(positionMs);
        }
        machine.onPositionUpdate(positionMs);
    }

    public void onPaused() {
        if (!inited) return;
        machine.onPaused();
    }

    /** 用户修改了关键词或跳过时长 */
    public void onConfigChanged(Context context) {
        if (!inited) {
            init(context);
            return;
        }
        engine.applyKeywords();
        machine.onConfigChanged();
    }

    /** 退出播放页：销毁每集状态。引擎为进程级重量资源，保留以便下次秒进 */
    public void destroy() {
        pendingStart = false;
        machine.destroy();
        // 不再释放引擎：模型加载要数秒，且退出/进入播放页很频繁。
        // 只置 inited=false，下次进入播放页时重新绑定回调即可。
        inited = false;
    }
}
