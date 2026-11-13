package com.pilipala.marcomovie;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Bounded global search. A late or cancelled native plugin can never post a new result. */
public final class SearchCoordinator implements AutoCloseable {
    public enum Outcome {
        RESULT,
        EMPTY,
        FAILED,
        VERIFICATION_REQUIRED,
        TIMEOUT,
        BUSY
    }

    public static final class VerificationRequiredException extends java.io.IOException {
        public VerificationRequiredException() {
            super("此片源需要完成网站验证码");
        }
    }

    public interface Runner {
        List<Cms.Video> search(Cms.Site site, String query) throws Exception;
    }

    public interface Listener {
        void source(Cms.Site site, List<Cms.Video> videos, Outcome outcome);
    }

    private static int baseThreads = 6;
    private static int wedgedWorkers = 0;
    private static final ConcurrentHashMap<String, AtomicInteger> WEDGED_SITES =
            new ConcurrentHashMap<>();
    private static final ThreadPoolExecutor NETWORK =
            new ThreadPoolExecutor(
                    6,
                    6,
                    30,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<Runnable>(64),
                    r -> {
                        Thread t = new Thread(r, "movie-global-search");
                        t.setDaemon(true);
                        return t;
                    });
    // Host-managed HTTP requests can be cancelled. Keep them out of the pool
    // used by native plugins, which may ignore both interruption and timeouts.
    private static final ThreadPoolExecutor HTTP_NETWORK =
            new ThreadPoolExecutor(
                    4,
                    4,
                    30,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<Runnable>(64),
                    r -> {
                        Thread t = new Thread(r, "movie-http-search");
                        t.setDaemon(true);
                        return t;
                    });
    private static final ScheduledExecutorService TIMER =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        Thread t = new Thread(r, "movie-search-deadline");
                        t.setDaemon(true);
                        return t;
                    });
    private final List<Job> jobs = new ArrayList<>();
    private final long requestMs, sessionMs;
    private final Runner runner;
    private final Listener listener;
    private volatile boolean closed;
    private ScheduledFuture<?> deadline;
    private int nextJob, inFlight;

    public SearchCoordinator(Runner runner, Listener listener) {
        this(runner, listener, 20000, 90000);
    }

    SearchCoordinator(Runner runner, Listener listener, long requestMs, long sessionMs) {
        this.runner = runner;
        this.listener = listener;
        this.requestMs = requestMs;
        this.sessionMs = sessionMs;
    }

    static synchronized void configureThreads(int count) {
        baseThreads = Math.max(2, Math.min(6, count));
        syncPoolCapacity();
    }

    private static synchronized void syncPoolCapacity() {
        int target =
                Math.max(2, Math.min(baseThreads + 24, baseThreads + Math.max(0, wedgedWorkers)));
        if (target > NETWORK.getMaximumPoolSize()) {
            NETWORK.setMaximumPoolSize(target);
            NETWORK.setCorePoolSize(target);
        } else {
            NETWORK.setCorePoolSize(target);
            NETWORK.setMaximumPoolSize(target);
        }
    }

    private static synchronized void onJobWedged(String siteKey) {
        wedgedWorkers++;
        if (siteKey != null && !siteKey.isEmpty()) {
            AtomicInteger counter = WEDGED_SITES.get(siteKey);
            if (counter == null) {
                AtomicInteger created = new AtomicInteger();
                AtomicInteger prev = WEDGED_SITES.putIfAbsent(siteKey, created);
                counter = prev != null ? prev : created;
            }
            counter.incrementAndGet();
        }
        syncPoolCapacity();
    }

    private static synchronized void onWedgedJobExited(String siteKey) {
        if (wedgedWorkers > 0) wedgedWorkers--;
        if (siteKey != null && !siteKey.isEmpty()) {
            AtomicInteger count = WEDGED_SITES.get(siteKey);
            if (count != null && count.decrementAndGet() <= 0) {
                WEDGED_SITES.remove(siteKey, count);
            }
        }
        syncPoolCapacity();
    }

    static boolean isSiteWedged(String siteKey) {
        if (siteKey == null || siteKey.isEmpty()) return false;
        AtomicInteger count = WEDGED_SITES.get(siteKey);
        return count != null && count.get() > 0;
    }

    /**
     * 过滤「静态噪音」片源批次：部分资源站（实测「建安资源」）的搜索接口完全忽略 wd 参数，
     * 无论搜什么都返回同一批默认列表（20 条、total=6 万+，标题与关键字零相关）。
     *
     * <p>正常 MacCMS 站点对中文关键字返回的标题全部包含关键字（演员名返回空，已实测验证），
     * 因此对含 CJK 的关键字做标题相关性校验：一条都不沾边 → 整批视为噪音丢弃（Outcome.EMPTY）；
     * 部分相关 → 只保留相关条目。匹配时容忍「第二季」后缀与标点分隔的主副标题
     * （如「黑鸽 第二季」→「黑鸽」、「相遇骤雨中：温柔的细雨」→「相遇骤雨中」）。
     * 拼音/字母关键字无法用标题包含性验证，跳过过滤。
     */
    static List<Cms.Video> filterNoise(String query, List<Cms.Video> videos) {
        if (query == null || videos == null || videos.isEmpty()) return videos;
        String q = normalizeForMatch(query);
        if (q.isEmpty() || !hasCjk(q)) return videos;
        String core = stripSeasonSuffix(q);
        String head = firstSegment(q);
        List<Cms.Video> out = new ArrayList<>();
        for (Cms.Video v : videos) {
            if (v == null) continue;
            String t = normalizeForMatch(v.title);
            if (t.isEmpty()) continue;
            if (t.contains(q)
                    || (core.length() >= 2 && t.contains(core))
                    || (head.length() >= 2 && t.contains(head))) {
                out.add(v);
            }
        }
        return out;
    }

    private static String normalizeForMatch(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).replaceAll("[\\s　]+", "");
    }

    private static boolean hasCjk(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) return true;
        }
        return false;
    }

    private static String stripSeasonSuffix(String s) {
        return s.replaceFirst("(第[0-9一二三四五六七八九十百]+[季部]|[Ss]eason[0-9]+)$", "");
    }

    private static String firstSegment(String s) {
        String[] parts = s.split("[：:·，,。．.!！?？（(）)【】\\[\\]「」『』《》]");
        return parts.length > 0 ? parts[0] : s;
    }

    public synchronized void start(List<Cms.Site> sites, String query) {
        if (closed || !jobs.isEmpty()) throw new IllegalStateException("Search already started");
        for (Cms.Site site : sites) jobs.add(new Job(site, query));
        deadline =
                TIMER.schedule(
                        () -> {
                            for (Job job : jobs)
                                job.finish(
                                        Collections.emptyList(),
                                        job.started ? Outcome.TIMEOUT : Outcome.BUSY);
                        },
                        sessionMs,
                        TimeUnit.MILLISECONDS);
        pump();
    }

    private synchronized void pump() {
        while (!closed && inFlight < baseThreads && nextJob < jobs.size()) {
            Job job = jobs.get(nextJob++);
            if (job.done.get()) continue;
            if (isSiteWedged(job.site.key)) {
                job.finish(Collections.emptyList(), Outcome.BUSY);
                continue;
            }
            job.allocated = true;
            inFlight++;
            try {
                job.future =
                        RequestScope.submit(
                                job.site.supported()
                                                || HttpT4Engine.eligible(job.site)
                                                || (job.site.api != null
                                                        && job.site.api.startsWith(
                                                                "emby://account/"))
                                        ? HTTP_NETWORK
                                        : NETWORK,
                                job);
            } catch (RejectedExecutionException busy) {
                job.finish(Collections.emptyList(), Outcome.BUSY);
            }
        }
    }

    final class Job implements Runnable {
        final Cms.Site site;
        final String query;
        final AtomicBoolean done = new AtomicBoolean();
        final AtomicBoolean exited = new AtomicBoolean();
        final AtomicBoolean wedged = new AtomicBoolean();
        volatile boolean started;
        volatile Thread runningThread;
        boolean allocated;
        volatile Future<?> future;
        volatile ScheduledFuture<?> timeout;

        Job(Cms.Site site, String query) {
            this.site = site;
            this.query = query;
        }

        public void run() {
            if (closed || done.get()) {
                exited.set(true);
                return;
            }
            if (isSiteWedged(site.key)) {
                exited.set(true);
                finish(Collections.emptyList(), Outcome.BUSY);
                return;
            }
            runningThread = Thread.currentThread();
            started = true;
            timeout =
                    TIMER.schedule(
                            () -> finish(Collections.emptyList(), Outcome.TIMEOUT),
                            requestMs,
                            TimeUnit.MILLISECONDS);
            try {
                List<Cms.Video> raw = runner.search(site, query);
                List<Cms.Video> videos = new ArrayList<>();
                Set<String> seen = new HashSet<>();
                if (raw != null)
                    for (Cms.Video v : raw)
                        if (v != null
                                && v.id != null
                                && !v.id.isEmpty()
                                && !v.id.startsWith("msearch:")
                                && seen.add(v.id)) {
                            videos.add(v);
                            if (videos.size() == 24) break;
                        }
                List<Cms.Video> relevant = filterNoise(query, videos);
                finish(relevant, relevant.isEmpty() ? Outcome.EMPTY : Outcome.RESULT);
            } catch (Exception error) {
                finish(
                        Collections.emptyList(),
                        Thread.currentThread().isInterrupted()
                                ? Outcome.TIMEOUT
                                : error instanceof VerificationRequiredException
                                        ? Outcome.VERIFICATION_REQUIRED
                                        : Outcome.FAILED);
            } finally {
                exited.set(true);
                runningThread = null;
                if (wedged.compareAndSet(true, false)) {
                    onWedgedJobExited(site.key);
                }
            }
        }

        void markWedgedIfRunning() {
            if (started && !exited.get() && wedged.compareAndSet(false, true)) {
                onJobWedged(site.key);
                if (exited.get() && wedged.compareAndSet(true, false)) {
                    onWedgedJobExited(site.key);
                }
            }
        }

        void finish(List<Cms.Video> videos, Outcome outcome) {
            if (!done.compareAndSet(false, true)) return;
            if (timeout != null) timeout.cancel(false);
            if (outcome == Outcome.TIMEOUT || outcome == Outcome.BUSY) {
                if (future != null) future.cancel(true);
                markWedgedIfRunning();
            }
            synchronized (SearchCoordinator.this) {
                if (allocated) {
                    allocated = false;
                    inFlight--;
                }
            }
            if (!closed) {
                listener.source(site, Collections.unmodifiableList(videos), outcome);
                TIMER.execute(() -> pump());
            }
        }

        void cancel() {
            done.set(true);
            if (timeout != null) timeout.cancel(false);
            if (future != null) future.cancel(true);
            markWedgedIfRunning();
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (deadline != null) deadline.cancel(false);
        for (Job job : jobs) job.cancel();
        NETWORK.purge();
        HTTP_NETWORK.purge();
    }
}
