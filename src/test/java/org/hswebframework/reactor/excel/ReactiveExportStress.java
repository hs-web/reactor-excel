package org.hswebframework.reactor.excel;

import io.netty.buffer.AbstractByteBufAllocator;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.util.ReferenceCountUtil;
import org.hswebframework.reactor.excel.csv.CharsetOption;
import org.hswebframework.reactor.excel.csv.CsvWriter;
import org.hswebframework.reactor.excel.csv.MaxEncodedCellBytesOption;
import org.hswebframework.reactor.excel.utils.StreamUtils;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Explicitly-run stress coverage for reactive export backpressure and lifecycle behavior.
 *
 * <p>The class deliberately does not match the project's default Surefire include patterns.
 * Run it with {@code mvn -Dtest=ReactiveExportStress test}.</p>
 */
class ReactiveExportStress {

    private static final int MEBIBYTE = 1024 * 1024;

    private static final int STREAM_BUFFER_SIZE = 64 * 1024;

    private static final int CSV_BUFFER_SIZE = 256;

    private static final Duration TIMEOUT = Duration.ofSeconds(
        positiveLongProperty("reactor.excel.stress.timeoutSeconds", 120)
    );

    @Test
    void streamUtilsShouldKeepHeapBoundedWithSlowConsumer() throws InterruptedException {
        int totalMiB = positiveIntProperty("reactor.excel.stress.streamMiB", 256);
        long expectedBytes = (long) totalMiB * MEBIBYTE;
        int chunks = Math.toIntExact(expectedBytes / STREAM_BUFFER_SIZE);
        long maxHeapGrowth = maxHeapGrowthBytes();
        AtomicLong producedBytes = new AtomicLong();
        AtomicLong consumedBytes = new AtomicLong();
        AtomicLong maxProducerAhead = new AtomicLong();
        CountDownLatch writerFinished = new CountDownLatch(1);
        byte[] sourceChunk = new byte[STREAM_BUFFER_SIZE];
        HeapProbe heap = new HeapProbe(64);

        Flux<byte[]> output = StreamUtils.buffer(
            STREAM_BUFFER_SIZE,
            stream -> Mono.fromRunnable(() -> {
                try {
                    for (int i = 0; i < chunks; i++) {
                        stream.write(sourceChunk);
                        long produced = producedBytes.addAndGet(sourceChunk.length);
                        updateMax(maxProducerAhead, produced - consumedBytes.get());
                    }
                } catch (IOException error) {
                    throw new UncheckedIOException(error);
                } finally {
                    StreamUtils.safeClose(stream);
                    writerFinished.countDown();
                }
            })
        );

        OneByOneSubscriber<byte[]> subscriber = new OneByOneSubscriber<>(
            bytes -> {
                heap.sample();
                consumedBytes.addAndGet(bytes.length);
            },
            64,
            Duration.ofMillis(2),
            Long.MAX_VALUE
        );

        try {
            output.subscribe(subscriber);
            assertTrue(subscriber.await(TIMEOUT), "slow StreamUtils export timed out");
            assertTrue(writerFinished.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
                       "blocking writer did not finish");
        } finally {
            subscriber.cancel();
        }

        assertTrue(subscriber.isComplete(), "output should complete normally");
        assertNull(subscriber.error(), "output should not fail");
        assertEquals(expectedBytes, producedBytes.get());
        assertEquals(expectedBytes, consumedBytes.get());
        assertTrue(
            maxProducerAhead.get() <= STREAM_BUFFER_SIZE,
            () -> "producer escaped downstream demand by " + maxProducerAhead.get() + " bytes"
        );
        heap.finish();
        assertHeapBounded(heap, maxHeapGrowth, "StreamUtils slow-consumer export");
        report(
            "StreamUtils",
            "logical=" + totalMiB + " MiB, retainedHeapGrowth=" + toMiB(heap.growth())
                + " MiB, maxProducerAhead=" + maxProducerAhead.get() + " bytes"
        );
    }

    @Test
    void streamUtilsConcurrentRequestAndCancelShouldNotDeadlockOrLeak() throws Exception {
        int iterations = positiveIntProperty("reactor.excel.stress.raceIterations", 1_000);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < iterations; i++) {
                TrackingAllocator allocator = new TrackingAllocator();
                AtomicReference<Subscription> subscription = new AtomicReference<>();
                AtomicReference<Throwable> raceError = new AtomicReference<>();
                CountDownLatch firstChunk = new CountDownLatch(1);
                CountDownLatch writerFinished = new CountDownLatch(1);
                CountDownLatch start = new CountDownLatch(1);

                StreamUtils
                    .buffer(
                        64,
                        allocator,
                        output -> Mono.fromRunnable(() -> {
                            try {
                                output.write(new byte[128]);
                            } catch (IOException error) {
                                throw new UncheckedIOException(error);
                            } finally {
                                writerFinished.countDown();
                            }
                        })
                    )
                    .subscribe(new Subscriber<ByteBuf>() {
                        @Override
                        public void onSubscribe(Subscription value) {
                            subscription.set(value);
                        }

                        @Override
                        public void onNext(ByteBuf value) {
                            ReferenceCountUtil.safeRelease(value);
                            firstChunk.countDown();
                        }

                        @Override
                        public void onError(Throwable error) {
                            raceError.compareAndSet(null, error);
                        }

                        @Override
                        public void onComplete() {
                        }
                    });

                subscription.get().request(1);
                assertTrue(firstChunk.await(5, TimeUnit.SECONDS),
                           "first StreamUtils chunk timed out at iteration " + i);

                Future<?> request = executor.submit(() -> {
                    await(start);
                    subscription.get().request(1);
                });
                Future<?> cancel = executor.submit(() -> {
                    await(start);
                    subscription.get().cancel();
                });
                start.countDown();
                request.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                cancel.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                assertTrue(writerFinished.await(5, TimeUnit.SECONDS),
                           "StreamUtils writer did not stop at iteration " + i);
                assertNull(raceError.get(), "request/cancel race failed at iteration " + i);
                assertTrue(allocator.allReleased(),
                           "request/cancel race leaked a ByteBuf at iteration " + i);
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void csvShouldKeepRequestsAndByteBufMemoryBoundedWithSlowConsumer() throws InterruptedException {
        int rows = positiveIntProperty("reactor.excel.stress.csvRows", 100_000);
        long maxHeapGrowth = maxHeapGrowthBytes();
        AtomicLong requestedRows = new AtomicLong();
        AtomicLong producedRows = new AtomicLong();
        AtomicLong maxOutstandingRows = new AtomicLong();
        AtomicLong maxRequestBatch = new AtomicLong();
        AtomicLong outputBytes = new AtomicLong();
        AtomicBoolean sourceCompleted = new AtomicBoolean();
        CountingAllocator allocator = new CountingAllocator();
        HeapProbe heap = new HeapProbe(4_096);

        Flux<Integer> source = Flux
            .range(0, rows)
            .doOnRequest(count -> {
                updateMax(maxRequestBatch, count);
                long requested = requestedRows.addAndGet(count);
                updateMax(maxOutstandingRows, requested - producedRows.get());
            })
            .doOnNext(ignore -> producedRows.incrementAndGet())
            .doOnComplete(() -> sourceCompleted.set(true));

        OneByOneSubscriber<ByteBuf> subscriber = new OneByOneSubscriber<>(
            buffer -> {
                heap.sample();
                outputBytes.addAndGet(buffer.readableBytes());
                allocator.release(buffer);
            },
            256,
            Duration.ofMillis(1),
            Long.MAX_VALUE
        );
        long startedAt = System.nanoTime();

        try {
            csvWriter()
                .writeByteBufs(source, allocator, CSV_BUFFER_SIZE)
                .subscribe(subscriber);
            assertTrue(subscriber.await(TIMEOUT), "slow CSV export timed out");
        } finally {
            subscriber.cancel();
        }
        long elapsedNanos = System.nanoTime() - startedAt;

        assertTrue(subscriber.isComplete(), "CSV output should complete normally");
        assertNull(subscriber.error(), "CSV output should not fail");
        assertTrue(sourceCompleted.get(), "row source should complete");
        assertEquals(rows, producedRows.get());
        assertEquals(1, maxRequestBatch.get(), "CSV must request one source row at a time");
        assertTrue(maxOutstandingRows.get() <= 1, "more than one source row was in flight");
        long unfulfilledRequests = requestedRows.get() - producedRows.get();
        assertTrue(unfulfilledRequests >= 0 && unfulfilledRequests <= 1,
                   "CSV retained more than one unfulfilled source request");
        assertTrue(outputBytes.get() > rows, "CSV output should contain encoded row data");
        long maximumChunks = 1 + (outputBytes.get() + CSV_BUFFER_SIZE - 1) / CSV_BUFFER_SIZE;
        assertTrue(subscriber.receivedCount() <= maximumChunks,
                   "small CSV cells were not aggregated into bounded chunks");
        assertTrue(subscriber.receivedCount() < rows / 2,
                   "CSV output still emitted close to one buffer per row");
        assertTrue(allocator.maxLiveBytes() <= 2L * CSV_BUFFER_SIZE,
                   "slow consumer exceeded the aggregate and staging buffer bound");
        assertEquals(0, allocator.liveBytes(), "all delivered ByteBuf instances must be released");
        heap.finish();
        assertHeapBounded(heap, maxHeapGrowth, "CSV slow-consumer export");
        report(
            "CSV",
            "rows=" + rows + ", retainedHeapGrowth=" + toMiB(heap.growth())
                + " MiB, maxOutstandingRows=" + maxOutstandingRows.get()
                + ", outputChunks=" + subscriber.receivedCount()
                + ", rowsPerSecond=" + rowsPerSecond(rows, elapsedNanos)
                + ", maxLiveByteBuf=" + allocator.maxLiveBytes() + " bytes"
        );
    }

    @Test
    void csvShouldPropagateSourceErrorAndReleaseBuffers() throws InterruptedException {
        int rowsBeforeError = positiveIntProperty("reactor.excel.stress.errorRows", 10_000);
        IllegalStateException expected = new IllegalStateException("stress source failure");
        CountingAllocator allocator = new CountingAllocator();
        Flux<Integer> source = Flux.concat(
            Flux.range(0, rowsBeforeError),
            Flux.error(expected)
        );
        OneByOneSubscriber<ByteBuf> subscriber = new OneByOneSubscriber<>(
            allocator::release,
            128,
            Duration.ofMillis(1),
            Long.MAX_VALUE
        );

        try {
            csvWriter()
                .writeByteBufs(source, allocator, CSV_BUFFER_SIZE)
                .subscribe(subscriber);
            assertTrue(subscriber.await(TIMEOUT), "CSV error scenario timed out");
        } finally {
            subscriber.cancel();
        }

        assertFalse(subscriber.isComplete(), "error must not be converted to completion");
        assertSame(expected, subscriber.error());
        assertEquals(0, allocator.liveBytes(), "error path leaked a delivered ByteBuf");
    }

    @Test
    void csvShouldCancelSourceAndReleaseBuffers() throws InterruptedException {
        int rows = positiveIntProperty("reactor.excel.stress.cancelRows", 1_000_000);
        long cancelAfterBuffers = positiveLongProperty(
            "reactor.excel.stress.cancelAfterBuffers",
            2_048
        );
        AtomicBoolean sourceCancelled = new AtomicBoolean();
        AtomicLong producedRows = new AtomicLong();
        AtomicLong maxRequestBatch = new AtomicLong();
        CountingAllocator allocator = new CountingAllocator();
        Flux<Integer> source = Flux
            .range(0, rows)
            .doOnRequest(count -> updateMax(maxRequestBatch, count))
            .doOnNext(ignore -> producedRows.incrementAndGet())
            .doOnCancel(() -> sourceCancelled.set(true));
        OneByOneSubscriber<ByteBuf> subscriber = new OneByOneSubscriber<>(
            allocator::release,
            128,
            Duration.ofMillis(1),
            cancelAfterBuffers
        );

        try {
            csvWriter()
                .writeByteBufs(source, allocator, CSV_BUFFER_SIZE)
                .subscribe(subscriber);
            assertTrue(subscriber.await(TIMEOUT), "CSV cancellation scenario timed out");
        } finally {
            subscriber.cancel();
        }

        assertTrue(subscriber.isCancelled(), "subscriber should cancel at the configured boundary");
        assertFalse(subscriber.isComplete(), "cancelled output must not complete");
        assertNull(subscriber.error(), "cancel should not be reported as an error");
        assertTrue(sourceCancelled.get(), "cancellation was not propagated to the row source");
        assertTrue(producedRows.get() < rows, "source was consumed after downstream cancellation");
        assertEquals(1, maxRequestBatch.get(), "CSV must retain one-at-a-time source demand");
        assertEquals(0, allocator.liveBytes(), "cancel path leaked a delivered ByteBuf");
    }

    @Test
    void csvShouldRejectOversizedCellAndReleaseInternalBuffers() throws InterruptedException {
        int cellMiB = positiveIntProperty("reactor.excel.stress.oversizedCellMiB", 32);
        int maximum = MaxEncodedCellBytesOption.DEFAULT_MAX_ENCODED_CELL_BYTES;
        TrackingAllocator allocator = new TrackingAllocator();
        OneByOneSubscriber<ByteBuf> subscriber = new OneByOneSubscriber<>(
            ReferenceCountUtil::safeRelease,
            0,
            Duration.ZERO,
            Long.MAX_VALUE
        );
        String value = repeat('x', Math.multiplyExact(cellMiB, MEBIBYTE));

        try {
            new CsvWriter()
                .write(
                    Flux.just(WritableCell.of(
                        0,
                        0,
                        0,
                        CellDataType.STRING,
                        value,
                        true
                    )),
                    allocator,
                    STREAM_BUFFER_SIZE,
                    new CharsetOption(StandardCharsets.UTF_8)
                )
                .subscribe(subscriber);
            assertTrue(subscriber.await(TIMEOUT), "oversized CSV cell timed out");
        } finally {
            subscriber.cancel();
        }

        assertFalse(subscriber.isComplete(), "oversized cell must not complete normally");
        assertTrue(subscriber.error() instanceof IllegalStateException,
                   "oversized cell must fail with a size-bound error");
        assertTrue(subscriber.error().getMessage().contains(String.valueOf(maximum)));
        assertTrue(allocator.allReleased(), "oversized cell retained internal ByteBuf instances");
    }

    @Test
    void csvConcurrentRequestAndCancelShouldNotDeadlockOrLeak() throws Exception {
        int iterations = positiveIntProperty("reactor.excel.stress.raceIterations", 1_000);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < iterations; i++) {
                TrackingAllocator allocator = new TrackingAllocator();
                AtomicReference<Subscription> subscription = new AtomicReference<>();
                AtomicReference<Throwable> raceError = new AtomicReference<>();
                CountDownLatch start = new CountDownLatch(1);

                new CsvWriter()
                    .write(
                        Flux.just(WritableCell.of(
                            0,
                            i,
                            0,
                            CellDataType.STRING,
                            "race-" + i + '-' + repeat('x', 1_024),
                            true
                        )),
                        allocator,
                        64,
                        new CharsetOption(StandardCharsets.UTF_8)
                    )
                    .subscribe(new Subscriber<ByteBuf>() {
                        @Override
                        public void onSubscribe(Subscription value) {
                            subscription.set(value);
                        }

                        @Override
                        public void onNext(ByteBuf value) {
                            ReferenceCountUtil.safeRelease(value);
                        }

                        @Override
                        public void onError(Throwable error) {
                            raceError.compareAndSet(null, error);
                        }

                        @Override
                        public void onComplete() {
                        }
                    });

                Future<?> request = executor.submit(() -> {
                    await(start);
                    subscription.get().request(Long.MAX_VALUE);
                });
                Future<?> cancel = executor.submit(() -> {
                    await(start);
                    subscription.get().cancel();
                });
                start.countDown();
                request.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                cancel.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                assertNull(raceError.get(), "request/cancel race failed at iteration " + i);
                assertTrue(allocator.allReleased(),
                           "request/cancel race leaked a ByteBuf at iteration " + i);
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void streamUtilsCancelAndTerminalRaceShouldSignalAtMostOnceAndNotLeak() throws Exception {
        int iterations = positiveIntProperty("reactor.excel.stress.raceIterations", 1_000);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        IllegalStateException expected = new IllegalStateException("writer race failure");
        Queue<Throwable> unexpectedDroppedErrors = new ConcurrentLinkedQueue<>();
        Hooks.onErrorDropped(error -> {
            if (error != expected) {
                unexpectedDroppedErrors.add(error);
            }
        });
        try {
            for (int i = 0; i < iterations; i++) {
                TrackingAllocator allocator = new TrackingAllocator();
                AtomicReference<Subscription> subscription = new AtomicReference<>();
                AtomicReference<MonoSink<Void>> writerSink = new AtomicReference<>();
                AtomicReference<Throwable> terminalError = new AtomicReference<>();
                AtomicInteger terminalSignals = new AtomicInteger();
                CountDownLatch writerReady = new CountDownLatch(1);
                CountDownLatch start = new CountDownLatch(1);
                StreamUtils
                    .buffer(
                        64,
                        allocator,
                        output -> Mono.create(sink -> {
                            try {
                                output.write(new byte[]{1, 2, 3});
                            } catch (IOException error) {
                                sink.error(error);
                                return;
                            }
                            writerSink.set(sink);
                            writerReady.countDown();
                        })
                    )
                    .subscribe(new Subscriber<ByteBuf>() {
                        @Override
                        public void onSubscribe(Subscription value) {
                            subscription.set(value);
                            value.request(1);
                        }

                        @Override
                        public void onNext(ByteBuf value) {
                            ReferenceCountUtil.safeRelease(value);
                        }

                        @Override
                        public void onError(Throwable error) {
                            terminalError.compareAndSet(null, error);
                            terminalSignals.incrementAndGet();
                        }

                        @Override
                        public void onComplete() {
                            terminalSignals.incrementAndGet();
                        }
                    });

                assertTrue(writerReady.await(5, TimeUnit.SECONDS),
                           "StreamUtils writer did not reach terminal race at iteration " + i);
                final boolean failWriter = (i & 1) == 0;
                Future<?> terminal = executor.submit(() -> {
                    await(start);
                    if (failWriter) {
                        writerSink.get().error(expected);
                    } else {
                        writerSink.get().success();
                    }
                });
                Future<?> cancel = executor.submit(() -> {
                    await(start);
                    subscription.get().cancel();
                });
                start.countDown();
                terminal.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                cancel.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

                assertTrue(terminalSignals.get() <= 1,
                           "StreamUtils emitted multiple terminal signals at iteration " + i);
                if (terminalSignals.get() == 1 && failWriter) {
                    assertSame(expected, terminalError.get());
                }
                assertTrue(allocator.allReleased(),
                           "StreamUtils terminal race leaked a ByteBuf at iteration " + i);
            }
        } finally {
            try {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            } finally {
                Hooks.resetOnErrorDropped();
            }
        }
        assertTrue(unexpectedDroppedErrors.isEmpty(),
                   () -> "unexpected dropped error: " + unexpectedDroppedErrors.peek());
    }

    @Test
    void csvCancelAndSourceTerminalRaceShouldSignalAtMostOnceAndNotLeak() throws Exception {
        int iterations = positiveIntProperty("reactor.excel.stress.raceIterations", 1_000);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        IllegalStateException expected = new IllegalStateException("source race failure");
        Queue<Throwable> unexpectedDroppedErrors = new ConcurrentLinkedQueue<>();
        Hooks.onErrorDropped(error -> {
            if (error != expected) {
                unexpectedDroppedErrors.add(error);
            }
        });
        try {
            for (int i = 0; i < iterations; i++) {
                TrackingAllocator allocator = new TrackingAllocator();
                AtomicReference<Subscription> subscription = new AtomicReference<>();
                AtomicReference<FluxSink<WritableCell>> sourceSink = new AtomicReference<>();
                AtomicReference<Throwable> terminalError = new AtomicReference<>();
                AtomicInteger terminalSignals = new AtomicInteger();
                CountDownLatch sourceReady = new CountDownLatch(1);
                CountDownLatch start = new CountDownLatch(1);
                Flux<WritableCell> source = Flux.create(sink -> {
                    sourceSink.set(sink);
                    sink.onRequest(ignored -> sourceReady.countDown());
                });

                new CsvWriter()
                    .write(
                        source,
                        allocator,
                        64,
                        new CharsetOption(StandardCharsets.UTF_8)
                    )
                    .subscribe(new Subscriber<ByteBuf>() {
                        @Override
                        public void onSubscribe(Subscription value) {
                            subscription.set(value);
                            value.request(Long.MAX_VALUE);
                        }

                        @Override
                        public void onNext(ByteBuf value) {
                            ReferenceCountUtil.safeRelease(value);
                        }

                        @Override
                        public void onError(Throwable error) {
                            terminalError.compareAndSet(null, error);
                            terminalSignals.incrementAndGet();
                        }

                        @Override
                        public void onComplete() {
                            terminalSignals.incrementAndGet();
                        }
                    });

                assertTrue(sourceReady.await(5, TimeUnit.SECONDS),
                           "CSV source did not reach terminal race at iteration " + i);
                final boolean failSource = (i & 1) == 0;
                Future<?> terminal = executor.submit(() -> {
                    await(start);
                    if (failSource) {
                        sourceSink.get().error(expected);
                    } else {
                        sourceSink.get().complete();
                    }
                });
                Future<?> cancel = executor.submit(() -> {
                    await(start);
                    subscription.get().cancel();
                });
                start.countDown();
                terminal.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                cancel.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

                assertTrue(terminalSignals.get() <= 1,
                           "CSV emitted multiple terminal signals at iteration " + i);
                if (terminalSignals.get() == 1 && failSource) {
                    assertSame(expected, terminalError.get());
                }
                assertTrue(allocator.allReleased(),
                           "CSV terminal race leaked a ByteBuf at iteration " + i);
            }
        } finally {
            try {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            } finally {
                Hooks.resetOnErrorDropped();
            }
        }
        assertTrue(unexpectedDroppedErrors.isEmpty(),
                   () -> "unexpected dropped error: " + unexpectedDroppedErrors.peek());
    }

    private static WriterOperator<Integer> csvWriter() {
        return ReactorExcel
            .<Integer>writer("csv")
            .header("value", "Value")
            .converter(value -> {
                Map<String, Object> row = Collections.singletonMap(
                    "value",
                    "row-" + value + "-abcdefghijklmnopqrstuvwxyz0123456789"
                );
                return row;
            });
    }

    private static void assertHeapBounded(HeapProbe heap, long maximumGrowth, String scenario) {
        assertTrue(
            heap.growth() <= maximumGrowth,
            () -> scenario + " grew used heap by " + toMiB(heap.growth())
                + " MiB; configured limit is " + toMiB(maximumGrowth) + " MiB"
        );
    }

    private static long maxHeapGrowthBytes() {
        return positiveLongProperty("reactor.excel.stress.maxHeapGrowthMiB", 96) * MEBIBYTE;
    }

    private static int positiveIntProperty(String name, int defaultValue) {
        int value = Integer.getInteger(name, defaultValue);
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be greater than zero");
        }
        return value;
    }

    private static long positiveLongProperty(String name, long defaultValue) {
        long value = Long.getLong(name, defaultValue);
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be greater than zero");
        }
        return value;
    }

    private static long usedHeap() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private static long toMiB(long bytes) {
        return bytes / MEBIBYTE;
    }

    private static long rowsPerSecond(long rows, long elapsedNanos) {
        return Math.round(rows * 1_000_000_000D / Math.max(1L, elapsedNanos));
    }

    private static void report(String scenario, String measurements) {
        System.out.println("[reactor-excel-stress] " + scenario + ": " + measurements);
    }

    private static void updateMax(AtomicLong maximum, long candidate) {
        long current = maximum.get();
        while (candidate > current && !maximum.compareAndSet(current, candidate)) {
            current = maximum.get();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting to start race workers");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("race worker was interrupted", error);
        }
    }

    private static String repeat(char value, int count) {
        char[] chars = new char[count];
        java.util.Arrays.fill(chars, value);
        return new String(chars);
    }

    private static final class HeapProbe {

        private final int sampleEvery;

        private final AtomicLong observations = new AtomicLong();

        private final long baseline;

        private final AtomicLong peak;

        private HeapProbe(int sampleEvery) {
            this.sampleEvery = sampleEvery;
            System.gc();
            this.baseline = usedHeap();
            this.peak = new AtomicLong(baseline);
        }

        private void sample() {
            if (observations.incrementAndGet() % sampleEvery == 0) {
                sampleRetainedHeap();
            }
        }

        private void finish() {
            sampleRetainedHeap();
        }

        private void sampleRetainedHeap() {
            // The logical in-flight assertions detect backpressure directly. A GC before sampling
            // keeps this secondary metric focused on retained data instead of allocation rate.
            System.gc();
            updateMax(peak, usedHeap());
        }

        private long growth() {
            return Math.max(0, peak.get() - baseline);
        }
    }

    private static final class CountingAllocator extends AbstractByteBufAllocator {

        private final ByteBufAllocator delegate = UnpooledByteBufAllocator.DEFAULT;

        private final AtomicLong maxLiveBytes = new AtomicLong();

        private final List<ByteBuf> allocated = Collections.synchronizedList(new ArrayList<>());

        private CountingAllocator() {
            super(false);
        }

        @Override
        protected ByteBuf newHeapBuffer(int initialCapacity, int maxCapacity) {
            return allocated(delegate.heapBuffer(initialCapacity, maxCapacity));
        }

        @Override
        protected ByteBuf newDirectBuffer(int initialCapacity, int maxCapacity) {
            return allocated(delegate.directBuffer(initialCapacity, maxCapacity));
        }

        @Override
        public boolean isDirectBufferPooled() {
            return false;
        }

        private ByteBuf allocated(ByteBuf buffer) {
            allocated.add(buffer);
            sampleLiveBytes();
            return buffer;
        }

        private void release(ByteBuf buffer) {
            ReferenceCountUtil.safeRelease(buffer);
            sampleLiveBytes();
        }

        private long liveBytes() {
            long live = 0;
            synchronized (allocated) {
                for (int i = allocated.size() - 1; i >= 0; i--) {
                    ByteBuf buffer = allocated.get(i);
                    if (buffer.refCnt() == 0) {
                        allocated.remove(i);
                    } else {
                        live += buffer.capacity();
                    }
                }
            }
            return live;
        }

        private long maxLiveBytes() {
            sampleLiveBytes();
            return maxLiveBytes.get();
        }

        private void sampleLiveBytes() {
            updateMax(maxLiveBytes, liveBytes());
        }
    }

    private static final class TrackingAllocator extends AbstractByteBufAllocator {

        private final ByteBufAllocator delegate = UnpooledByteBufAllocator.DEFAULT;

        private final List<ByteBuf> allocated = new CopyOnWriteArrayList<>();

        private TrackingAllocator() {
            super(false);
        }

        @Override
        protected ByteBuf newHeapBuffer(int initialCapacity, int maxCapacity) {
            return track(delegate.heapBuffer(initialCapacity, maxCapacity));
        }

        @Override
        protected ByteBuf newDirectBuffer(int initialCapacity, int maxCapacity) {
            return track(delegate.directBuffer(initialCapacity, maxCapacity));
        }

        @Override
        public boolean isDirectBufferPooled() {
            return false;
        }

        private ByteBuf track(ByteBuf buffer) {
            allocated.add(buffer);
            return buffer;
        }

        private boolean allReleased() {
            return allocated.stream().allMatch(buffer -> buffer.refCnt() == 0);
        }
    }

    private static final class OneByOneSubscriber<T> implements Subscriber<T> {

        private final Consumer<T> consumer;

        private final int delayEvery;

        private final long delayNanos;

        private final long cancelAfter;

        private final CountDownLatch finished = new CountDownLatch(1);

        private final AtomicLong received = new AtomicLong();

        private final AtomicInteger pendingConsumers = new AtomicInteger();

        private final AtomicReference<Throwable> error = new AtomicReference<>();

        private final AtomicBoolean publisherTerminated = new AtomicBoolean();

        private final AtomicBoolean complete = new AtomicBoolean();

        private final AtomicBoolean cancelled = new AtomicBoolean();

        private volatile Subscription subscription;

        private OneByOneSubscriber(Consumer<T> consumer,
                                   int delayEvery,
                                   Duration delay,
                                   long cancelAfter) {
            this.consumer = consumer;
            this.delayEvery = delayEvery;
            this.delayNanos = delay.toNanos();
            this.cancelAfter = cancelAfter;
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            if (this.subscription != null) {
                subscription.cancel();
                return;
            }
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(T value) {
            long index = received.incrementAndGet();
            pendingConsumers.incrementAndGet();
            if (delayEvery > 0 && index % delayEvery == 0) {
                Schedulers.parallel().schedule(
                    () -> consume(value, index),
                    delayNanos,
                    TimeUnit.NANOSECONDS
                );
            } else {
                consume(value, index);
            }
        }

        @Override
        public void onError(Throwable error) {
            this.error.compareAndSet(null, error);
            publisherTerminated.set(true);
            tryFinish();
        }

        @Override
        public void onComplete() {
            complete.set(true);
            publisherTerminated.set(true);
            tryFinish();
        }

        private void consume(T value, long index) {
            boolean cancelNow = index >= cancelAfter;
            try {
                consumer.accept(value);
            } catch (Throwable consumeError) {
                error.compareAndSet(null, consumeError);
                publisherTerminated.set(true);
                cancelNow = true;
            } finally {
                pendingConsumers.decrementAndGet();
            }

            if (cancelNow) {
                cancelled.set(true);
                Subscription current = subscription;
                if (current != null) {
                    current.cancel();
                }
                finished.countDown();
                return;
            }

            if (!publisherTerminated.get()) {
                subscription.request(1);
            }
            tryFinish();
        }

        private void tryFinish() {
            if (publisherTerminated.get() && pendingConsumers.get() == 0) {
                finished.countDown();
            }
        }

        private boolean await(Duration timeout) throws InterruptedException {
            return finished.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        private void cancel() {
            if (cancelled.compareAndSet(false, true)) {
                Subscription current = subscription;
                if (current != null && !publisherTerminated.get()) {
                    current.cancel();
                }
            }
        }

        private long receivedCount() {
            return received.get();
        }

        private Throwable error() {
            return error.get();
        }

        private boolean isComplete() {
            return complete.get();
        }

        private boolean isCancelled() {
            return cancelled.get();
        }
    }
}
