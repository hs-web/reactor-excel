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
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import reactor.test.publisher.TestPublisher;

import java.time.Duration;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
    void smallCellsShouldShareBufferSizedOutputChunks() {
        TrackingAllocator allocator = new TrackingAllocator();
        AtomicInteger chunkCount = new AtomicInteger();
        byte[] bytes = new CsvWriter()
            .write(
                Flux.range(0, 100).map(ignore -> cell("x")),
                allocator,
                64,
                new CharsetOption(java.nio.charset.StandardCharsets.UTF_8)
            )
            .map(buffer -> {
                chunkCount.incrementAndGet();
                byte[] chunk = copyReadableBytes(buffer);
                ReferenceCountUtil.safeRelease(buffer);
                return chunk;
            })
            .reduce(new ByteArrayOutputStream(), (output, chunk) -> {
                output.write(chunk, 0, chunk.length);
                return output;
            })
            .map(ByteArrayOutputStream::toByteArray)
            .block(Duration.ofSeconds(5));

        StringBuilder expected = new StringBuilder("\ufeff");
        for (int i = 0; i < 100; i++) {
            expected.append("x\r\n");
        }
        assertNotNull(bytes);
        assertArrayEquals(
            expected.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
            bytes
        );
        assertEquals(6, chunkCount.get(), "small cells were not aggregated by buffer size");
        assertTrue(allocator.allocationCount() <= 8, "small cells allocated one buffer per cell");
        assertTrue(allocator.allReleased(), "aggregated chunks leaked a ByteBuf");
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
    void cancellationFromOnSubscribeShouldReleaseInitialBuffersWithoutSubscribingSource() {
        TrackingAllocator allocator = new TrackingAllocator();
        AtomicBoolean sourceSubscribed = new AtomicBoolean();
        AtomicInteger terminalSignals = new AtomicInteger();

        new CsvWriter()
            .write(
                Flux.just(cell("value")).doOnSubscribe(ignored -> sourceSubscribed.set(true)),
                allocator,
                64,
                new CharsetOption(java.nio.charset.StandardCharsets.UTF_8)
            )
            .subscribe(new Subscriber<ByteBuf>() {
                @Override
                public void onSubscribe(Subscription subscription) {
                    subscription.cancel();
                }

                @Override
                public void onNext(ByteBuf value) {
                    ReferenceCountUtil.safeRelease(value);
                }

                @Override
                public void onError(Throwable error) {
                    terminalSignals.incrementAndGet();
                }

                @Override
                public void onComplete() {
                    terminalSignals.incrementAndGet();
                }
            });

        assertFalse(sourceSubscribed.get(), "source subscribed after synchronous cancellation");
        assertEquals(0, terminalSignals.get(), "cancelled subscriber received a terminal signal");
        assertTrue(allocator.allReleased(), "synchronous cancellation leaked the initial buffer");
    }

    @Test
    void downstreamOnNextFailureShouldReleaseBufferWithoutSubscribingSource() {
        TrackingAllocator allocator = new TrackingAllocator();
        AtomicBoolean sourceSubscribed = new AtomicBoolean();
        AtomicInteger terminalSignals = new AtomicInteger();
        AtomicReference<Throwable> droppedError = new AtomicReference<>();
        IllegalStateException expected = new IllegalStateException("downstream failed");

        Hooks.onErrorDropped(droppedError::set);
        try {
            new CsvWriter()
                .write(
                    Flux.just(cell("value")).doOnSubscribe(ignored -> sourceSubscribed.set(true)),
                    allocator,
                    64,
                    new CharsetOption(java.nio.charset.StandardCharsets.UTF_8)
                )
                .subscribe(new Subscriber<ByteBuf>() {
                    @Override
                    public void onSubscribe(Subscription subscription) {
                        subscription.request(1);
                    }

                    @Override
                    public void onNext(ByteBuf value) {
                        throw expected;
                    }

                    @Override
                    public void onError(Throwable error) {
                        terminalSignals.incrementAndGet();
                    }

                    @Override
                    public void onComplete() {
                        terminalSignals.incrementAndGet();
                    }
                });
        } finally {
            Hooks.resetOnErrorDropped();
        }

        assertSame(expected, droppedError.get(), "callback failure was not dropped");
        assertFalse(sourceSubscribed.get(), "source subscribed after downstream callback failure");
        assertEquals(0, terminalSignals.get(), "failing subscriber received another terminal signal");
        assertTrue(allocator.allReleased(), "downstream callback failure leaked a ByteBuf");
    }

    @Test
    void sourceErrorShouldReleaseEncodedChunksThatWereNotDelivered() throws InterruptedException {
        TrackingAllocator allocator = new TrackingAllocator();
        IllegalStateException expected = new IllegalStateException("source failed");
        TestPublisher<WritableCell> source = TestPublisher.create();
        AtomicReference<Subscription> subscription = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicInteger received = new AtomicInteger();
        CountDownLatch terminated = new CountDownLatch(1);

        new CsvWriter()
            .write(
                source.flux(),
                allocator,
                1,
                new CharsetOption(java.nio.charset.StandardCharsets.UTF_8),
                MaxEncodedCellBytesOption.of(2_048)
            )
            .subscribe(new Subscriber<ByteBuf>() {
                @Override
                public void onSubscribe(Subscription value) {
                    subscription.set(value);
                }

                @Override
                public void onNext(ByteBuf value) {
                    received.incrementAndGet();
                    ReferenceCountUtil.safeRelease(value);
                }

                @Override
                public void onError(Throwable error) {
                    failure.set(error);
                    terminated.countDown();
                }

                @Override
                public void onComplete() {
                    terminated.countDown();
                }
            });

        // bufferSize=1 splits the three-byte UTF-8 BOM into three chunks. The fourth demand
        // reaches the cell source and leaves the rest of the encoded cell queued for cleanup.
        subscription.get().request(4);
        source.assertMinRequested(1);
        source.next(cell(repeat('x', 1_024)));
        assertEquals(4, received.get(), "fixture did not leave encoded chunks queued");
        source.error(expected);

        assertTrue(terminated.await(5, TimeUnit.SECONDS), "source error did not terminate output");
        assertTrue(failure.get() == expected, "source error identity was not preserved");

        assertTrue(allocator.allReleased(), "source error leaked queued ByteBuf instances");
    }

    @Test
    void retainedSlicesShouldNotCorruptEachOtherDuringDelayedConsumption() {
        TrackingAllocator allocator = new TrackingAllocator();
        List<ByteBuf> buffers = new CsvWriter()
            .write(
                Flux.just(cell("abcdefgh")),
                allocator,
                4,
                new CharsetOption(java.nio.charset.StandardCharsets.UTF_8),
                MaxEncodedCellBytesOption.of(64)
            )
            // BOM plus one fixed-size cell keeps delayed inspection strictly bounded.
            .collectList()
            .block(Duration.ofSeconds(5));

        try {
            assertNotNull(buffers);
            assertEquals(4, buffers.size());
            assertArrayEquals(
                new byte[]{(byte) 0xef, (byte) 0xbb, (byte) 0xbf},
                copyReadableBytes(buffers.get(0))
            );
            assertArrayEquals(new byte[]{'a', 'b', 'c', 'd'}, copyReadableBytes(buffers.get(1)));
            assertArrayEquals(new byte[]{'e', 'f', 'g', 'h'}, copyReadableBytes(buffers.get(2)));
            assertArrayEquals(new byte[]{'\r', '\n'}, copyReadableBytes(buffers.get(3)));
            assertSame(buffers.get(1).unwrap(), buffers.get(2).unwrap(),
                       "fixture did not exercise retained slices from the same parent");

            buffers.get(1).setByte(buffers.get(1).readerIndex(), 'X');

            assertArrayEquals(new byte[]{'X', 'b', 'c', 'd'}, copyReadableBytes(buffers.get(1)));
            assertArrayEquals(new byte[]{'e', 'f', 'g', 'h'}, copyReadableBytes(buffers.get(2)));
            assertArrayEquals(new byte[]{'\r', '\n'}, copyReadableBytes(buffers.get(3)));
        } finally {
            if (buffers != null) {
                buffers.forEach(ReferenceCountUtil::safeRelease);
            }
        }

        assertTrue(allocator.allReleased(), "retained slices kept their parent buffer alive");
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

    private static byte[] copyReadableBytes(ByteBuf buffer) {
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), bytes);
        return bytes;
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
