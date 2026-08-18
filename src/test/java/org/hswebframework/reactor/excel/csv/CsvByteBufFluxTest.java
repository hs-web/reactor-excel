package org.hswebframework.reactor.excel.csv;

import io.netty.buffer.AbstractByteBufAllocator;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.util.ReferenceCountUtil;
import org.hswebframework.reactor.excel.BlockHoundTestSupport;
import org.hswebframework.reactor.excel.CellDataType;
import org.hswebframework.reactor.excel.WritableCell;
import org.hswebframework.reactor.excel.converter.SimpleWritableCell;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvByteBufFluxTest {

    private static ExecutorService encoderExecutor;

    @BeforeAll
    static void installBlockHound() {
        BlockHoundTestSupport.install();
        encoderExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "csv-blocking-cell-test");
            thread.setDaemon(true);
            return thread;
        });
    }

    @AfterAll
    static void shutdownExecutor() throws InterruptedException {
        encoderExecutor.shutdownNow();
        assertTrue(encoderExecutor.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test
    void shouldRejectOversizedCellAndReleaseEveryBuffer() {
        TrackingAllocator allocator = new TrackingAllocator();
        String oversized = repeat('x', 2_048);

        StepVerifier
            .create(new CsvWriter().write(
                Flux.just(cell(oversized)),
                allocator,
                128,
                new CharsetOption(java.nio.charset.StandardCharsets.UTF_8),
                MaxEncodedCellBytesOption.of(1_024)
            ))
            .assertNext(ReferenceCountUtil::safeRelease)
            .expectErrorMatches(error -> error instanceof IllegalStateException
                && error.getMessage().contains("1024"))
            .verify(Duration.ofSeconds(5));

        assertTrue(allocator.allReleased(), "oversized cell leaked a ByteBuf");
    }

    @Test
    void cancellationShouldReleaseEncodedChunksThatWereNotDelivered() {
        TrackingAllocator allocator = new TrackingAllocator();
        CancellingSubscriber subscriber = new CancellingSubscriber(4);

        new CsvWriter()
            .write(
                Flux.just(cell(repeat('x', 4_096))),
                allocator,
                1,
                new CharsetOption(java.nio.charset.StandardCharsets.UTF_8),
                MaxEncodedCellBytesOption.of(8_192)
            )
            .subscribe(subscriber);

        assertTrue(subscriber.cancelled(), "subscriber did not cancel at the chunk boundary");
        assertTrue(allocator.allReleased(), "cancellation leaked queued ByteBuf instances");
        assertTrue(allocator.allocationCount() <= 5,
                   "tiny output chunks created one retained buffer per encoded byte");
    }

    @Test
    void invalidDemandShouldFailAndReleaseInitialBuffers() {
        TrackingAllocator allocator = new TrackingAllocator();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean completed = new AtomicBoolean();

        new CsvWriter()
            .write(
                Flux.just(cell("value")),
                allocator,
                64,
                new CharsetOption(java.nio.charset.StandardCharsets.UTF_8)
            )
            .subscribe(new Subscriber<ByteBuf>() {
                @Override
                public void onSubscribe(Subscription subscription) {
                    subscription.request(0);
                }

                @Override
                public void onNext(ByteBuf value) {
                    ReferenceCountUtil.safeRelease(value);
                }

                @Override
                public void onError(Throwable error) {
                    failure.set(error);
                }

                @Override
                public void onComplete() {
                    completed.set(true);
                }
            });

        assertNotNull(failure.get(), "invalid demand did not terminate with an error");
        assertTrue(failure.get() instanceof IllegalArgumentException);
        assertFalse(completed.get(), "invalid demand completed normally");
        assertTrue(allocator.allReleased(), "invalid demand leaked the initial BOM buffer");
    }

    @Test
    void cancellationMustNotBlockAReactorNonBlockingThread() throws InterruptedException {
        TrackingAllocator allocator = new TrackingAllocator();
        CountDownLatch encodingStarted = new CountDownLatch(1);
        CountDownLatch releaseEncoding = new CountDownLatch(1);
        CountDownLatch encodingFinished = new CountDownLatch(1);
        CountDownLatch sourceCancelled = new CountDownLatch(1);
        AtomicBoolean emitted = new AtomicBoolean();
        BlockingCell blockingCell = new BlockingCell(encodingStarted, releaseEncoding);
        Flux<WritableCell> source = Flux.create(sink -> {
            sink.onCancel(sourceCancelled::countDown);
            sink.onRequest(ignored -> {
                if (emitted.compareAndSet(false, true)) {
                    encoderExecutor.execute(() -> {
                        try {
                            sink.next(blockingCell);
                            sink.complete();
                        } finally {
                            encodingFinished.countDown();
                        }
                    });
                }
            });
        });
        CancellingSubscriber subscriber = new CancellingSubscriber(Long.MAX_VALUE);

        new CsvWriter()
            .write(
                source,
                allocator,
                64,
                new CharsetOption(java.nio.charset.StandardCharsets.UTF_8)
            )
            .subscribe(subscriber);

        assertTrue(encodingStarted.await(5, TimeUnit.SECONDS), "cell encoding did not start");
        try {
            assertDoesNotThrow(() -> Mono
                .fromRunnable(subscriber::cancel)
                .subscribeOn(Schedulers.parallel())
                .block(Duration.ofSeconds(2)));
            assertTrue(sourceCancelled.await(5, TimeUnit.SECONDS),
                       "cancellation was not propagated to the source");
        } finally {
            releaseEncoding.countDown();
        }

        assertTrue(encodingFinished.await(5, TimeUnit.SECONDS), "cell encoding did not finish");
        assertTrue(allocator.allReleased(), "non-blocking cancellation leaked a ByteBuf");
    }

    private static WritableCell cell(String value) {
        return WritableCell.of(0, 0, 0, CellDataType.STRING, value, true);
    }

    private static String repeat(char value, int count) {
        char[] chars = new char[count];
        java.util.Arrays.fill(chars, value);
        return new String(chars);
    }

    private static final class BlockingCell extends SimpleWritableCell {

        private final CountDownLatch started;

        private final CountDownLatch release;

        private BlockingCell(CountDownLatch started, CountDownLatch release) {
            super(CellDataType.STRING, "value", 0, 0, true, 0);
            this.started = started;
            this.release = release;
        }

        @Override
        public Optional<String> valueAsText() {
            started.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("timed out waiting to release cell encoding");
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("cell encoding was interrupted", error);
            }
            return Optional.of("value");
        }
    }

    private static final class CancellingSubscriber extends BaseSubscriber<ByteBuf> {

        private final long cancelAfter;

        private long received;

        private volatile boolean cancelled;

        private CancellingSubscriber(long cancelAfter) {
            this.cancelAfter = cancelAfter;
        }

        @Override
        protected void hookOnSubscribe(org.reactivestreams.Subscription subscription) {
            request(cancelAfter == Long.MAX_VALUE ? 2 : cancelAfter);
        }

        @Override
        protected void hookOnNext(ByteBuf value) {
            received++;
            ReferenceCountUtil.safeRelease(value);
            if (received >= cancelAfter) {
                cancelled = true;
                cancel();
            }
        }

        @Override
        protected void hookOnCancel() {
            cancelled = true;
        }

        private boolean cancelled() {
            return cancelled;
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

        private int allocationCount() {
            return allocated.size();
        }
    }
}
