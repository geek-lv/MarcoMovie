package com.pilipala.marcomovie.adskip;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.pilipala.marcomovie.adskip.AdSkipConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内嵌广告（音轨里烧录的口播广告）识别与跳过的状态机。
 *
 * <h3>设计要点</h3>
 * <ol>
 *   <li><b>幂等</b>：命中后把 [命中点, 命中点+跳过时长] 记入已跳区间；
 *       再命中时若落点已被覆盖则忽略，保证同一段广告只跳一次。</li>
 *   <li><b>每次命中每次跳过</b>：跳过结束后回到 {@link State#ARMED}，
 *       若落点仍在广告里会再次命中并再次跳固定时长，形成链式跳跃。</li>
 *   <li><b>音频时钟</b>：用已 tap 的样本数换算位置，而不是 getCurrentPosition()，
 *       以抵消识别窗口带来的延迟；seek / 换集时重置锚点。</li>
 *   <li><b>分层生命周期</b>：引擎（模型）由外部持有、进程内复用；
 *       本状态机只管"每集"的状态，换集调 {@link #resetForNewMedia()}，退出调 {@link #destroy()}。</li>
 * </ol>
 */
public class AdSkipStateMachine {

    private static final String TAG = "AdSkip";

    /**
     * 诊断音频输出目录。普通应用对 /data/local/tmp 没有写权限，
     * 因此由 AdSkipController.init() 指向应用私有缓存目录；未设置时静默跳过导出。
     */
    private static volatile File diagDir;

    /** 设置诊断 wav 的落盘目录（应用私有目录），重复调用无副作用。 */
    public static void setDiagnosticDir(File dir) {
        diagDir = dir;
        if (dir != null) {
            try {
                dir.mkdirs();
            } catch (Throwable ignored) {
            }
        }
    }

    private static File diagFile(String name) {
        File d = diagDir;
        return d == null ? null : new File(d, name);
    }

    public enum State {
        /** 功能未开启，或尚未开始播放 */
        IDLE,
        /** 已就绪，等待音频输入 */
        ARMED,
        /** 正在逐窗识别 */
        DETECTING,
        /** 刚跳过，抑制重复触发，直到播放位置越过跳过的终点 */
        COOLDOWN,
        /** 已销毁，不再接受任何输入 */
        DISPOSED
    }

    public interface Callback {
        /**
         * 请求跳转到指定位置。已切到主线程调用。
         *
         * @param fromMs 判定为广告起点的位置
         * @param toMs   建议跳转到的位置
         * @param keyword 命中的关键词
         */
        void onAdDetected(long fromMs, long toMs, String keyword);
    }

    /** 识别引擎要求的采样率 */
    public static final int KWS_SAMPLE_RATE = 16000;

    /** 每次送给引擎的音频块长度（0.5 秒） */
    private static final int CHUNK_SAMPLES = KWS_SAMPLE_RATE / 2;

    /** 命中点相对音频时钟的回退补偿，用于抵消识别窗口延迟，避免跳过正片 */
    private static final long HIT_BACKOFF_MS = 1000;

    /** 判定"落在已跳区间内"时允许的误差，吸收 seek 落点抖动 */
    private static final long COVER_TOLERANCE_MS = 1500;

    /** 音频时钟与实际播放位置偏差超过该值就重新锚定 */
    private static final long CLOCK_DRIFT_MS = 2500;

    /** 已跳区间最多保留的条数，避免长时间播放无限增长 */
    private static final int MAX_SKIP_RANGES = 32;

    private final Object lock = new Object();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /**
     * PCM 队列容量。它直接决定识别的最大滞后 = 容量 × CHUNK_SAMPLES / 采样率。
     * 32 块 × 0.5s = 16s，表现为"播报完了很久才跳"甚至整块被挤掉丢检，故收紧到 8（4s）。
     */
    private static final int PCM_QUEUE_CAPACITY = 8;

    private final ArrayBlockingQueue<float[]> pcmQueue = new ArrayBlockingQueue<float[]>(PCM_QUEUE_CAPACITY);

    /**
     * 与 pcmQueue 一一对应：每块 PCM 末尾对应的音频时钟(ms)。
     * 识别是异步的，命中时"当前时钟"已经跑到播报之后很久，
     * 只有记住块自己的时刻，才能把跳过起点定位到真实播报位置。
     */
    private final ArrayBlockingQueue<Long> pcmPosQueue = new ArrayBlockingQueue<Long>(PCM_QUEUE_CAPACITY);

    /** 打点用：丢块数、识别耗时统计 */
    private volatile long droppedChunks = 0;

    // ------------------------------------------------------- 诊断：音频取证

    /**
     * 保留最近 N 秒的 16kHz 单声道 PCM。命中时导出成 wav，
     * 由于两段播报间隔通常只有十几秒，导出内容必然覆盖"上一次漏检"的那次播报，
     * 便于离线用同一模型对比两次播报的得分差异。
     */
    private static final int RING_SECONDS = 120;
    private static final int RING_CAP = KWS_SAMPLE_RATE * RING_SECONDS;
    private static final Object ringLock = new Object();
    private short[] ringBuf = null;
    private int ringPos = 0;
    private long ringTotal = 0;

    private void recordRing(float[] pcm, int length) {
        short[] probeSnapshot = null;
        synchronized (ringLock) {
            if (ringBuf == null) {
                ringBuf = new short[RING_CAP];
            }
            for (int i = 0; i < length; i++) {
                float v = pcm[i];
                if (v > 1f) v = 1f;
                else if (v < -1f) v = -1f;
                ringBuf[ringPos] = (short) (v * 32767f);
                ringPos++;
                if (ringPos >= RING_CAP) ringPos = 0;
            }
            ringTotal += length;
            // 诊断探针：每 60s 快照最近 10s 音频；写盘必须放到 ringLock 之外
            if (ringTotal - lastProbeTotal >= KWS_SAMPLE_RATE * 60L) {
                lastProbeTotal = ringTotal;
                if (diagDir != null)
                    probeSnapshot = snapshotRing((int) Math.min(ringTotal, KWS_SAMPLE_RATE * 10L));
            }
        }
        if (probeSnapshot != null) {
            File out = diagFile("adskip_probe.wav");
            if (out != null) writeDiagnosticWav(out, probeSnapshot, "[探针] ");
        }
    }

    private long lastProbeTotal = 0;

    /** 命中时导出最近音频。锁内只做内存快照，磁盘 I/O 一律在锁外，避免阻塞音频采集。 */
    private void dumpRingPcm(long clockMs) {
        short[] snapshot;
        synchronized (ringLock) {
            if (ringBuf == null || ringTotal <= 0 || diagDir == null) return;
            snapshot = snapshotRing((int) Math.min(ringTotal, RING_CAP));
        }
        File out = diagFile("adskip_hit_" + clockMs + ".wav");
        if (out != null) writeDiagnosticWav(out, snapshot, "");
    }

    /** 必须在 ringLock 内调用：按时间顺序拷贝最近 n 个样本。 */
    private short[] snapshotRing(int n) {
        short[] out = new short[n];
        int start = (int) ((ringTotal - n) % RING_CAP);
        if (start < 0) start += RING_CAP;
        for (int i = 0; i < n; i++) out[i] = ringBuf[(start + i) % RING_CAP];
        return out;
    }

    /** 锁外写盘；诊断用途，失败只记日志，绝不影响识别与跳转。 */
    private static void writeDiagnosticWav(File out, short[] pcm, String prefix) {
        try (FileOutputStream fos = new FileOutputStream(out)) {
            writeWavHeader(fos, pcm.length, KWS_SAMPLE_RATE);
            byte[] b = new byte[2];
            for (short s : pcm) {
                b[0] = (byte) (s & 0xff);
                b[1] = (byte) ((s >> 8) & 0xff);
                fos.write(b);
            }
            Log.i(TAG, prefix + "已导出 " + (pcm.length / KWS_SAMPLE_RATE) + "s 音频 -> "
                    + out.getAbsolutePath() + " (" + (pcm.length * 2 / 1024) + "KB)");
        } catch (Throwable t) {
            Log.e(TAG, prefix + "导出音频失败: " + t);
        }
    }

    private static void writeWavHeader(FileOutputStream fos, int numSamples, int sampleRate)
            throws java.io.IOException {
        int dataLen = numSamples * 2;
        byte[] h = new byte[44];
        h[0] = 'R'; h[1] = 'I'; h[2] = 'F'; h[3] = 'F';
        putLe32(h, 4, 36 + dataLen);
        h[8] = 'W'; h[9] = 'A'; h[10] = 'V'; h[11] = 'E';
        h[12] = 'f'; h[13] = 'm'; h[14] = 't'; h[15] = ' ';
        putLe32(h, 16, 16);
        putLe16(h, 20, 1);          // PCM
        putLe16(h, 22, 1);          // 单声道
        putLe32(h, 24, sampleRate);
        putLe32(h, 28, sampleRate * 2);
        putLe16(h, 32, 2);          // block align
        putLe16(h, 34, 16);         // 位深
        h[36] = 'd'; h[37] = 'a'; h[38] = 't'; h[39] = 'a';
        putLe32(h, 40, dataLen);
        fos.write(h);
    }

    private static void putLe32(byte[] a, int off, int v) {
        a[off] = (byte) (v & 0xff);
        a[off + 1] = (byte) ((v >> 8) & 0xff);
        a[off + 2] = (byte) ((v >> 16) & 0xff);
        a[off + 3] = (byte) ((v >> 24) & 0xff);
    }

    private static void putLe16(byte[] a, int off, int v) {
        a[off] = (byte) (v & 0xff);
        a[off + 1] = (byte) ((v >> 8) & 0xff);
    }
    private long statChunks = 0;
    private long statCostMs = 0;

    /** 从锚点起累计送出的样本数（16kHz 单声道等效），播放线程写、工作线程读 */
    private final AtomicLong samplesTapped = new AtomicLong(0);

    /** 冷却的墙钟兜底上限：无论如何不允许无限期冷却 */
    private static final long COOLDOWN_MAX_WALL_MS = 60_000;

    private volatile State state = State.IDLE;
    private volatile long anchorPositionMs = 0;
    private volatile long cooldownEndMs = 0;
    private volatile long cooldownStartWallMs = 0;
    private volatile boolean running = false;

    private KwsEngine engine;
    private Callback callback;
    private Thread workerThread;

    /** 已跳过的区间 {startMs, endMs}，保证幂等 */
    private final List<long[]> skipRanges = new ArrayList<>();

    /** 累积器：把播放线程的小块 PCM 攒成定长块再入队 */
    private float[] pendingChunk = new float[CHUNK_SAMPLES];
    private int pendingLen = 0;

    /** 仅用于打点，避免每条 PCM 都刷日志 */
    private volatile int pcmLogCount = 0;
    private volatile int workerLogCount = 0;
    private volatile long lastDriftLogMs = 0;

    /** 周期性状态快照：每 5 秒音频打一次，便于事后定位"某时刻为什么不检测" */
    private static final long SNAPSHOT_INTERVAL_SAMPLES = KWS_SAMPLE_RATE * 5L;
    private volatile long lastSnapshotSamples = 0;

    // ---------------------------------------------------------------- 生命周期

    /**
     * 绑定引擎与回调。不改变状态，需再调用 {@link #start(long)} 才会开始工作。
     */
    public void attach(KwsEngine engine, Callback callback) {
        synchronized (lock) {
            this.engine = engine;
            this.callback = callback;
        }
    }

    /**
     * 开始（或重新锚定）一次播放。换集、切源、重新起播都应调用。
     *
     * @param positionMs 当前播放位置，作为音频时钟的锚点
     */
    public void start(long positionMs) {
        synchronized (lock) {
            // 允许从 DISPOSED 复活：退出播放页会 destroy，再次进入播放页必须能重新开始，
            // 否则表现为"退出一次后再也不跳过"
            if (state == State.DISPOSED) {
                Log.w(TAG, "start: 状态为 DISPOSED，自动复活（上次 destroy 后未重建）");
                state = State.IDLE;
            }
            resetSessionLocked(positionMs);
            if (!AdSkipConfig.isEnabled()) {
                state = State.IDLE;
                stopWorkerLocked();
                return;
            }
            state = State.ARMED;
            startWorkerLocked();
        }
    }

    /**
     * 换集 / 换源时调用：清空每集状态（已跳区间、音频时钟、PCM 缓冲、识别上下文），
     * 但<b>不释放引擎</b>——模型加载很重，进程内复用。
     */
    public void resetForNewMedia() {
        synchronized (lock) {
            // 同上：换集时也要能从 DISPOSED 复活
            if (state == State.DISPOSED) {
                Log.w(TAG, "resetForNewMedia: 状态为 DISPOSED，自动复活");
                state = State.IDLE;
            }
            resetSessionLocked(0);
            if (!AdSkipConfig.isEnabled()) {
                state = State.IDLE;
                stopWorkerLocked();
                return;
            }
            state = State.ARMED;
            startWorkerLocked();
        }
    }

    /**
     * 播放位置发生跳转时调用。
     *
     * @param positionMs    跳转后的位置
     * @param userInitiated true 表示用户手动拖动（清空已跳区间，允许在广告区重新命中）；
     *                      false 表示本功能触发的自动跳转（保留已跳区间，保证幂等）
     */
    public void onSeekTo(long positionMs, boolean userInitiated) {
        synchronized (lock) {
            if (state == State.DISPOSED) return;
            if (userInitiated) {
                skipRanges.clear();
            }
            anchorPositionMs = positionMs;
            samplesTapped.set(0);
            drainPcmLocked();
            resetPendingLocked();
            if (engine != null) {
                engine.resetStream();
            }
            if (state == State.COOLDOWN && positionMs >= cooldownEndMs) {
                state = AdSkipConfig.isEnabled() ? State.ARMED : State.IDLE;
            } else if (state == State.COOLDOWN && userInitiated) {
                // 用户主动跳转，提前解除冷却
                state = AdSkipConfig.isEnabled() ? State.ARMED : State.IDLE;
                cooldownEndMs = 0;
            }
        }
    }

    /**
     * 播放位置更新（主线程）。用于退出冷却与校正音频时钟漂移。
     */
    public void onPositionUpdate(long positionMs) {
        synchronized (lock) {
            if (state == State.DISPOSED) return;
            if (!AdSkipConfig.isEnabled() || state == State.IDLE) {
                // 功能未启用或未开始工作时，音频时钟锚点本就没有意义，
                // 不做漂移校正（否则会每几秒空转一次"重锚+引擎重置"并刷屏日志）
                return;
            }
            long clock = audioClockMsLocked();
            long drift = clock - positionMs;
            if (Math.abs(drift) > CLOCK_DRIFT_MS) {
                // 发生了"未通知状态机的跳转"：遥控器快退/快进、手势滑动 seek 等
                // 走的是播放器自己的 seek，不会回调 onSeek。若不校正，音频时钟会与真实
                // 位置长期脱节，导致检测点算错、命中后跳到错误位置，甚至看似"没检测"。
                // 这里统一重锚，并按"用户主动跳转"处理：清空已跳区间，允许重新命中。
                if (System.currentTimeMillis() - lastDriftLogMs > 3000) {
                    lastDriftLogMs = System.currentTimeMillis();
                    Log.i(TAG, "位置校正: 音频时钟=" + clock + "ms 实际=" + positionMs
                            + "ms 偏差=" + drift + "ms -> 重锚并重置识别上下文");
                }
                anchorPositionMs = positionMs;
                samplesTapped.set(0);
                skipRanges.clear();
                drainPcmLocked();
                resetPendingLocked();
                if (engine != null) {
                    engine.resetStream();
                }
            }
            if (state == State.COOLDOWN && positionMs >= cooldownEndMs) {
                state = AdSkipConfig.isEnabled() ? State.ARMED : State.IDLE;
                cooldownEndMs = 0;
                resetPendingLocked();
                if (engine != null) {
                    engine.resetStream();
                }
            }
        }
    }

    /** 关键词或跳过时长被修改后调用：重建识别上下文，但保留已跳区间（已跳过的不再跳） */
    public void onConfigChanged() {
        synchronized (lock) {
            if (state == State.DISPOSED) return;
            drainPcmLocked();
            resetPendingLocked();
            if (engine != null) {
                engine.resetStream();
            }
            if (!AdSkipConfig.isEnabled()) {
                state = State.IDLE;
                stopWorkerLocked();
                return;
            }
            if (state == State.IDLE) {
                state = State.ARMED;
                startWorkerLocked();
            } else if (state == State.COOLDOWN) {
                state = State.ARMED;
                cooldownEndMs = 0;
            }
        }
    }

    /** 暂停：停止投喂 PCM，但不丢失每集状态 */
    public void onPaused() {
        synchronized (lock) {
            drainPcmLocked();
            resetPendingLocked();
            if (engine != null) {
                engine.resetStream();
            }
        }
    }

    /**
     * 彻底销毁：退出播放页时调用。
     * 只释放本状态机的资源，引擎的释放由持有者（进程级）决定。
     */
    public void destroy() {
        synchronized (lock) {
            state = State.DISPOSED;
            stopWorkerLocked();
            drainPcmLocked();
            resetPendingLocked();
            skipRanges.clear();
            engine = null;
            callback = null;
        }
    }

    public State getState() {
        return state;
    }

    // ---------------------------------------------------------------- 音频输入

    /**
     * 播放线程调用：送入 16kHz 单声道 PCM。
     * 此方法必须极轻（只做累加与入队），识别发生在工作线程。
     */
    public void onPcm(float[] mono16k, int length) {
        if (state == State.DISPOSED || state == State.IDLE) {
            if (pcmLogCount < 3) {
                pcmLogCount++;
                Log.w(TAG, "onPcm 被丢弃: state=" + state + " (需 ARMED/DETECTING 才处理)");
            }
            return;
        }
        if (pcmLogCount < 3) {
            pcmLogCount++;
            Log.i(TAG, "onPcm 收到音频: len=" + length + " state=" + state
                    + " 时钟=" + (anchorPositionMs + samplesTapped.get() * 1000L / KWS_SAMPLE_RATE) + "ms");
        }

        // 看护：工作线程若已死亡而状态机仍需工作，就地重启，避免"跑着跑着不跳了"
        if (!running || workerThread == null || !workerThread.isAlive()) {
            synchronized (lock) {
                if (state == State.ARMED || state == State.DETECTING) {
                    Log.w(TAG, "检测到工作线程已死，自动重启");
                    startWorkerLocked();
                }
            }
        }

        // 时钟始终推进，即使处于冷却期（否则无法判断何时解除冷却）
        long total = samplesTapped.addAndGet(length);

        recordRing(mono16k, length);

        // 周期性快照：失败时刻的状态全靠它复盘
        if (total - lastSnapshotSamples >= SNAPSHOT_INTERVAL_SAMPLES) {
            lastSnapshotSamples = total;
            Log.i(TAG, "[快照] state=" + state
                    + " 时钟=" + audioClockMsLocked() + "ms"
                    + " 冷却至=" + cooldownEndMs + "ms"
                    + " 已跳区间数=" + skipRanges.size()
                    + " 引擎=" + (engine != null ? (engine.isReady() ? "就绪" : "未就绪") : "空")
                    + " 工作线程=" + (workerThread != null && workerThread.isAlive() ? "存活" : "已死")
                    + " 队列=" + pcmQueue.size());
        }

        if (state == State.COOLDOWN) {
            // 自愈：不依赖外部位置回调，靠音频时钟自行解除冷却，
            // 保证播放位置越过跳过终点后能重新进入可命中状态
            synchronized (lock) {
                if (state == State.COOLDOWN && audioClockMsLocked() >= cooldownEndMs) {
                    Log.i(TAG, "冷却解除(音频时钟已越过 " + cooldownEndMs + "ms)");
                    state = State.ARMED;
                    cooldownEndMs = 0;
                    resetPendingLocked();
                    if (engine != null) {
                        engine.resetStream();
                    }
                } else if (state == State.COOLDOWN
                        && System.currentTimeMillis() - cooldownStartWallMs > COOLDOWN_MAX_WALL_MS) {
                    // 兜底：无论时钟为何没推进（seek 未生效、时钟锚点异常等），
                    // 冷却都不允许无限期存在，否则会表现为"之后再也不跳过"
                    Log.w(TAG, "冷却超时(" + COOLDOWN_MAX_WALL_MS + "ms)，强制解除，避免卡死");
                    state = AdSkipConfig.isEnabled() ? State.ARMED : State.IDLE;
                    cooldownEndMs = 0;
                    resetPendingLocked();
                    if (engine != null) {
                        engine.resetStream();
                    }
                }
            }
            return;
        }

        for (int i = 0; i < length; i++) {
            pendingChunk[pendingLen++] = mono16k[i];
            if (pendingLen == CHUNK_SAMPLES) {
                float[] chunk = pendingChunk;
                pendingChunk = new float[CHUNK_SAMPLES];
                pendingLen = 0;
                // 这一块音频所对应的播放位置（块末尾）
                long blockEndMs = anchorPositionMs + total * 1000L / KWS_SAMPLE_RATE;
                if (!pcmQueue.offer(chunk)) {
                    // 队列满：丢弃最旧的一块，保证实时性优先（宁可漏检也不要滞后十几秒）
                    pcmQueue.poll();
                    pcmPosQueue.poll();
                    droppedChunks++;
                    pcmQueue.offer(chunk);
                }
                if (!pcmPosQueue.offer(blockEndMs)) {
                    pcmPosQueue.poll();
                    pcmPosQueue.offer(blockEndMs);
                }
            }
        }
    }

    // ---------------------------------------------------------------- 内部实现

    private long audioClockMsLocked() {
        return anchorPositionMs + samplesTapped.get() * 1000L / KWS_SAMPLE_RATE;
    }

    private void resetSessionLocked(long positionMs) {
        skipRanges.clear();
        anchorPositionMs = positionMs;
        samplesTapped.set(0);
        // 诊断计数器必须与样本计数同步归零，否则 total 变小后快照会长时间不再打印
        lastSnapshotSamples = 0;
        cooldownEndMs = 0;
        cooldownStartWallMs = 0;
        drainPcmLocked();
        resetPendingLocked();
        if (engine != null) {
            engine.resetStream();
        }
    }

    private void resetPendingLocked() {
        pendingLen = 0;
    }

    private void drainPcmLocked() {
        pcmQueue.clear();
        pcmPosQueue.clear();
    }

    private void startWorkerLocked() {
        // 用线程真实存活状态判断，而不是 running 标志：
        // 线程可能因中断/异常提前退出，此时 running 仍为 true，若只看标志就永远补不上新线程
        if (workerThread != null && workerThread.isAlive()) return;
        running = true;
        workerThread = new Thread(this::workerLoop, "adskip-kws");
        workerThread.setDaemon(true);
        workerThread.start();
    }

    private void stopWorkerLocked() {
        running = false;
        if (workerThread != null) {
            workerThread.interrupt();
            workerThread = null;
        }
    }

    private void workerLoop() {
        Log.i(TAG, "工作线程启动");
        try {
            while (running) {
                float[] chunk;
                long blockEndMs = -1;
                try {
                    chunk = pcmQueue.take();
                    Long pos = pcmPosQueue.poll();
                    if (pos != null) blockEndMs = pos;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    Log.w(TAG, "工作线程被中断，退出");
                    return;
                }
                if (!running) return;

                KwsEngine engineRef;
                synchronized (lock) {
                    if (state == State.DISPOSED || state == State.IDLE) continue;
                    if (state == State.ARMED) {
                        state = State.DETECTING;
                    }
                    engineRef = engine;
                }
                if (engineRef == null) {
                    if (workerLogCount < 3) {
                        workerLogCount++;
                        Log.e(TAG, "工作线程: 引擎为空，无法识别");
                    }
                    continue;
                }
                if (!engineRef.isReady()) {
                    if (workerLogCount < 5) {
                        workerLogCount++;
                        Log.e(TAG, "工作线程: 引擎未就绪(isReady=false)，跳过识别。"
                                + " 可能是关键词未设置或模型未加载");
                    }
                    continue;
                }

                long t0 = System.currentTimeMillis();
                String keyword = engineRef.accept(chunk);
                long cost = System.currentTimeMillis() - t0;

                statChunks++;
                statCostMs += cost;
                long lagMs = blockEndMs >= 0 ? (audioClockMsLocked() - blockEndMs) : -1;
                if (statChunks % 20 == 0) {
                    Log.i(TAG, "[识别] 块耗时=" + cost + "ms 均耗时=" + (statCostMs / statChunks)
                            + "ms 滞后=" + lagMs + "ms 队列=" + pcmQueue.size()
                            + "/" + PCM_QUEUE_CAPACITY + " 累计丢块=" + droppedChunks);
                }

                if (keyword != null) {
                    Log.i(TAG, "引擎返回关键词: [" + keyword + "] 该块位置=" + blockEndMs
                            + "ms 当前时钟=" + audioClockMsLocked() + "ms 滞后=" + lagMs + "ms");
                    handleHit(keyword, blockEndMs);
                }
            }
        } catch (Throwable t) {
            // 绝不能让工作线程因意外异常而静默死亡
            Log.e(TAG, "工作线程异常", t);
        } finally {
            running = false;
            Log.w(TAG, "工作线程结束（若仍在播放，看护逻辑会自动重启）");
        }
    }

    /**
     * @param blockEndMs 命中那一块音频对应的播放位置；小于 0 表示未知，退回用当前时钟
     */
    private void handleHit(String keyword, long blockEndMs) {
        final long fromMs;
        final long toMs;
        synchronized (lock) {
            if (state == State.DISPOSED || state == State.IDLE) return;

            // 用"命中块自己的位置"而不是当前时钟：识别有滞后，用当前时钟会把起点算到播报之后
            long clock = blockEndMs >= 0 ? blockEndMs : audioClockMsLocked();
            long detectPos = clock - HIT_BACKOFF_MS;
            if (detectPos < 0) detectPos = 0;

            // 幂等：落点已在某个已跳区间内则忽略
            if (isCoveredLocked(detectPos)) {
                Log.i(TAG, "幂等忽略: 检测点 " + detectPos + "ms 已落在已跳区间内");
                drainPcmLocked();
                resetPendingLocked();
                if (engine != null) {
                    engine.resetStream();
                }
                state = State.ARMED;
                return;
            }

            long skipMillis = AdSkipConfig.getSkipMillis();
            if (skipMillis <= 0) return;

            toMs = detectPos + skipMillis;
            fromMs = detectPos;

            skipRanges.add(new long[]{fromMs, toMs});
            if (skipRanges.size() > MAX_SKIP_RANGES) {
                skipRanges.remove(0);
            }

            cooldownEndMs = toMs;
            cooldownStartWallMs = System.currentTimeMillis();
            state = State.COOLDOWN;
            Log.i(TAG, "准备跳过: " + fromMs + "ms -> " + toMs + "ms (跳过 " + skipMillis + "ms)");

            // 立刻清掉跳跃前残留的音频与识别上下文，避免把跳跃前后混在一起再误判
            drainPcmLocked();
            resetPendingLocked();
            if (engine != null) {
                engine.resetStream();
            }
        }

        // 取证：锁外落盘，避免 IO 阻塞状态机。录的是命中前最近 120 秒，
        // 因此通常也包含"上一次漏检"的那次播报。
        dumpRingPcm(fromMs);

        // seek 必须回主线程
        final String hitKeyword = keyword;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                Callback cb;
                synchronized (lock) {
                    cb = callback;
                }
                if (cb != null && state != State.DISPOSED) {
                    cb.onAdDetected(fromMs, toMs, hitKeyword);
                }
            }
        });
    }

    private boolean isCoveredLocked(long positionMs) {
        for (long[] range : skipRanges) {
            if (positionMs >= range[0] - COVER_TOLERANCE_MS && positionMs <= range[1]) {
                return true;
            }
        }
        return false;
    }
}
