package org.example.proect.lavka.service.folio;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Low-overhead watchdog for long Folio accounting-price jobs.
 *
 * <p>The monitored worker can be blocked inside the legacy JDBC driver. The
 * watchdog therefore runs from Spring's scheduler, keeps no database
 * transaction open and reads only JVM/Hikari management counters. A bounded
 * thread dump is emitted once when one checkpoint remains unchanged longer
 * than the configured stall threshold.</p>
 */
@Component
@Slf4j
public class FolioAccountingPriceRuntimeMonitor {

    private static final long MIB = 1024L * 1024L;
    private static final int MAX_DUMPED_HTTP_THREADS = 20;
    private static final int MAX_STACK_FRAMES = 80;
    private static final FolioAccountingPriceRuntimeMonitor NOOP =
            new FolioAccountingPriceRuntimeMonitor();

    private final boolean enabled;
    private final long stallNanos;
    private final long intervalMillis;
    private final DataSource folioDataSource;
    private final DataSource wpDataSource;
    private final ScheduledExecutorService watchdogExecutor;
    private final AtomicReference<Checkpoint> active = new AtomicReference<>();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong dumpedSequence = new AtomicLong(-1);

    private FolioAccountingPriceRuntimeMonitor() {
        this.enabled = false;
        this.stallNanos = Long.MAX_VALUE;
        this.intervalMillis = Long.MAX_VALUE;
        this.folioDataSource = null;
        this.wpDataSource = null;
        this.watchdogExecutor = null;
    }

    @Autowired
    public FolioAccountingPriceRuntimeMonitor(
            @Qualifier("folioDataSource") DataSource folioDataSource,
            @Qualifier("wpDataSource") DataSource wpDataSource,
            @Value("${lavka.folio.accounting-prices.diagnostics-enabled:true}")
            boolean enabled,
            @Value("${lavka.folio.accounting-prices.diagnostics-stall-seconds:120}")
            int stallSeconds,
            @Value("${lavka.folio.accounting-prices.diagnostics-interval-ms:30000}")
            int intervalMillis) {
        this.enabled = enabled;
        this.stallNanos = Math.max(30, stallSeconds) * 1_000_000_000L;
        this.intervalMillis = Math.max(5_000, intervalMillis);
        this.folioDataSource = folioDataSource;
        this.wpDataSource = wpDataSource;
        this.watchdogExecutor = enabled
                ? Executors.newSingleThreadScheduledExecutor(task -> {
                    Thread thread = new Thread(
                            task, "folio-accounting-price-watchdog");
                    thread.setDaemon(true);
                    return thread;
                })
                : null;
    }

    FolioAccountingPriceRuntimeMonitor(
            DataSource folioDataSource,
            DataSource wpDataSource,
            boolean enabled,
            int stallSeconds) {
        this(folioDataSource, wpDataSource, enabled, stallSeconds, 30_000);
    }

    @PostConstruct
    void startWatchdog() {
        if (watchdogExecutor != null) {
            watchdogExecutor.scheduleWithFixedDelay(
                    this::safeHeartbeat,
                    intervalMillis,
                    intervalMillis,
                    TimeUnit.MILLISECONDS);
        }
    }

    @PreDestroy
    void stopWatchdog() {
        if (watchdogExecutor != null) {
            watchdogExecutor.shutdownNow();
        }
    }

    static FolioAccountingPriceRuntimeMonitor noop() {
        return NOOP;
    }

    public void start(String jobId, String database, int warehouseId,
                      String operation, boolean previewOnly) {
        if (!enabled) return;
        long now = System.nanoTime();
        long currentSequence = sequence.incrementAndGet();
        active.set(new Checkpoint(
                currentSequence, jobId, database, warehouseId, operation,
                previewOnly, "QUEUED", "QUEUED", null,
                0, 0, 0, 0, now, now));
        dumpedSequence.set(-1);
        logSnapshot("started", active.get(), now, false);
    }

    public void checkpoint(String jobId, String phase, String stage, String sku,
                           int processed, int total, int procedureCalls,
                           int committedChunks) {
        if (!enabled) return;
        long now = System.nanoTime();
        active.updateAndGet(previous -> {
            if (previous == null || !previous.jobId().equals(jobId)) return previous;
            boolean sameStage = java.util.Objects.equals(previous.phase(), phase)
                    && java.util.Objects.equals(previous.stage(), stage)
                    && java.util.Objects.equals(previous.sku(), sku);
            return new Checkpoint(
                    sameStage ? previous.sequence() : sequence.incrementAndGet(),
                    previous.jobId(), previous.database(), previous.warehouseId(),
                    previous.operation(), previous.previewOnly(), phase, stage, sku,
                    processed, total, procedureCalls, committedChunks,
                    previous.jobStartedNanos(),
                    sameStage ? previous.stageStartedNanos() : now);
        });
    }

    public void finish(String jobId, String status, String error) {
        if (!enabled) return;
        long now = System.nanoTime();
        Checkpoint finished = active.getAndSet(null);
        if (finished == null || !finished.jobId().equals(jobId)) return;
        logSnapshot("finished status=" + safe(status)
                        + (error == null ? "" : " error=" + safe(error)),
                finished, now, false);
    }

    public void heartbeat() {
        if (!enabled) return;
        Checkpoint checkpoint = active.get();
        if (checkpoint == null) return;
        long now = System.nanoTime();
        boolean stalled = now - checkpoint.stageStartedNanos() >= stallNanos;
        logSnapshot(stalled ? "stalled" : "heartbeat", checkpoint, now, stalled);
        if (stalled && dumpedSequence.getAndSet(checkpoint.sequence())
                != checkpoint.sequence()) {
            dumpRelevantThreads(checkpoint);
        }
    }

    private void safeHeartbeat() {
        try {
            heartbeat();
        } catch (Throwable error) {
            log.warn("[folio.accounting-price.runtime] watchdog_error type={} message={}",
                    error.getClass().getSimpleName(), safe(error.getMessage()));
        }
    }

    Checkpoint currentCheckpoint() {
        return active.get();
    }

    private void logSnapshot(String event, Checkpoint checkpoint, long now,
                             boolean stalled) {
        RuntimeSnapshot runtime = runtimeSnapshot();
        String template = "[folio.accounting-price.runtime] event={} job={} database={} warehouse={} operation={} preview={} phase={} stage={} sku={} stageAgeMs={} jobAgeMs={} processed={}/{} calls={} committed={} heapMiB={}/{}/{} nonHeapMiB={} threads={}/{} gcCount={} gcMs={} processCpu={} systemCpu={} systemLoad={} accountingThread={} tomcatPool={} folioPool={} wpPool={}";
        Object[] values = {
                event, checkpoint.jobId(), checkpoint.database(),
                checkpoint.warehouseId(), checkpoint.operation(),
                checkpoint.previewOnly(), checkpoint.phase(), checkpoint.stage(),
                checkpoint.sku(), millis(now - checkpoint.stageStartedNanos()),
                millis(now - checkpoint.jobStartedNanos()), checkpoint.processed(),
                checkpoint.total(), checkpoint.procedureCalls(),
                checkpoint.committedChunks(), runtime.heapUsedMiB(),
                runtime.heapCommittedMiB(), runtime.heapMaxMiB(),
                runtime.nonHeapUsedMiB(), runtime.liveThreads(),
                runtime.peakThreads(), runtime.gcCount(), runtime.gcMillis(),
                runtime.processCpu(), runtime.systemCpu(), runtime.systemLoad(),
                runtime.accountingThread(), runtime.tomcatPool(),
                runtime.folioPool(), runtime.wpPool()
        };
        if (stalled) log.warn(template, values);
        else log.info(template, values);
    }

    private RuntimeSnapshot runtimeSnapshot() {
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = memory.getHeapMemoryUsage();
        MemoryUsage nonHeap = memory.getNonHeapMemoryUsage();
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        long gcCount = 0;
        long gcMillis = 0;
        for (GarbageCollectorMXBean collector
                : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (collector.getCollectionCount() > 0) {
                gcCount += collector.getCollectionCount();
            }
            if (collector.getCollectionTime() > 0) {
                gcMillis += collector.getCollectionTime();
            }
        }
        java.lang.management.OperatingSystemMXBean baseOs =
                ManagementFactory.getOperatingSystemMXBean();
        String processCpu = "n/a";
        String systemCpu = "n/a";
        if (baseOs instanceof com.sun.management.OperatingSystemMXBean os) {
            processCpu = percent(os.getProcessCpuLoad());
            systemCpu = percent(os.getCpuLoad());
        }
        return new RuntimeSnapshot(
                mib(heap.getUsed()), mib(heap.getCommitted()), mib(heap.getMax()),
                mib(nonHeap.getUsed()), threads.getThreadCount(),
                threads.getPeakThreadCount(), gcCount, gcMillis,
                processCpu, systemCpu,
                String.format(Locale.ROOT, "%.2f", baseOs.getSystemLoadAverage()),
                accountingThreadSnapshot(threads), tomcatPoolSnapshot(),
                poolSnapshot(folioDataSource), poolSnapshot(wpDataSource));
    }

    private static String accountingThreadSnapshot(ThreadMXBean threads) {
        ThreadInfo[] infos = threads.getThreadInfo(threads.getAllThreadIds(), 8);
        if (infos == null) return "unavailable";
        for (ThreadInfo info : infos) {
            if (info == null
                    || !info.getThreadName().startsWith("folio-accounting-price-")) {
                continue;
            }
            StackTraceElement[] stack = info.getStackTrace();
            String top = stack.length == 0 ? "no-stack" : stack[0].toString();
            return info.getThreadName() + ':' + info.getThreadState() + ':' + top;
        }
        return "absent";
    }

    private static String tomcatPoolSnapshot() {
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            for (ObjectName name : server.queryNames(
                    new ObjectName("Tomcat:type=ThreadPool,*"), null)) {
                Object protocolName = name.getKeyProperty("name");
                if (protocolName == null
                        || !protocolName.toString().contains("http")) {
                    continue;
                }
                return "name=" + protocolName
                        + ",busy=" + attribute(server, name, "currentThreadsBusy")
                        + ",current=" + attribute(server, name, "currentThreadCount")
                        + ",max=" + attribute(server, name, "maxThreads")
                        + ",connections=" + attribute(
                        server, name, "connectionCount");
            }
            return "not-started";
        } catch (Exception error) {
            return "error=" + error.getClass().getSimpleName();
        }
    }

    private static Object attribute(MBeanServer server,
                                    ObjectName name,
                                    String attribute) {
        try {
            return server.getAttribute(name, attribute);
        } catch (Exception ignored) {
            return "n/a";
        }
    }

    private void dumpRelevantThreads(Checkpoint checkpoint) {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        ThreadInfo[] infos = threads.dumpAllThreads(true, true);
        int httpThreads = 0;
        for (ThreadInfo info : infos) {
            if (info == null) continue;
            String name = info.getThreadName();
            boolean accounting = name.startsWith("folio-accounting-price-");
            boolean http = name.startsWith("http-nio-8080-exec-")
                    && httpThreads < MAX_DUMPED_HTTP_THREADS;
            if (!accounting && !http) continue;
            if (http) httpThreads++;
            log.warn("[folio.accounting-price.thread-dump] job={} phase={} stage={} sku={} thread={} id={} state={} lock={} owner={} stack={}",
                    checkpoint.jobId(), checkpoint.phase(), checkpoint.stage(),
                    checkpoint.sku(), name, info.getThreadId(),
                    info.getThreadState(), info.getLockName(),
                    info.getLockOwnerName(), stack(info));
        }
    }

    private static String stack(ThreadInfo info) {
        return Arrays.stream(info.getStackTrace())
                .limit(MAX_STACK_FRAMES)
                .map(frame -> "\n\tat " + frame)
                .reduce("", String::concat);
    }

    private static String poolSnapshot(DataSource dataSource) {
        if (!(dataSource instanceof HikariDataSource hikari)) return "unavailable";
        try {
            HikariPoolMXBean pool = hikari.getHikariPoolMXBean();
            if (pool == null) return "not-started";
            return "active=" + pool.getActiveConnections()
                    + ",idle=" + pool.getIdleConnections()
                    + ",total=" + pool.getTotalConnections()
                    + ",waiting=" + pool.getThreadsAwaitingConnection();
        } catch (RuntimeException error) {
            return "error=" + error.getClass().getSimpleName();
        }
    }

    private static long mib(long bytes) {
        return bytes < 0 ? -1 : bytes / MIB;
    }

    private static long millis(long nanos) {
        return Math.max(0, nanos / 1_000_000L);
    }

    private static String percent(double value) {
        return value < 0 ? "n/a"
                : String.format(Locale.ROOT, "%.1f%%", value * 100.0);
    }

    private static String safe(String value) {
        if (value == null) return "null";
        String normalized = value.replace('\n', ' ').replace('\r', ' ');
        return normalized.length() <= 300
                ? normalized : normalized.substring(0, 300) + "...";
    }

    record Checkpoint(
            long sequence,
            String jobId,
            String database,
            int warehouseId,
            String operation,
            boolean previewOnly,
            String phase,
            String stage,
            String sku,
            int processed,
            int total,
            int procedureCalls,
            int committedChunks,
            long jobStartedNanos,
            long stageStartedNanos) {
    }

    private record RuntimeSnapshot(
            long heapUsedMiB,
            long heapCommittedMiB,
            long heapMaxMiB,
            long nonHeapUsedMiB,
            int liveThreads,
            int peakThreads,
            long gcCount,
            long gcMillis,
            String processCpu,
            String systemCpu,
            String systemLoad,
            String accountingThread,
            String tomcatPool,
            String folioPool,
            String wpPool) {
    }
}
