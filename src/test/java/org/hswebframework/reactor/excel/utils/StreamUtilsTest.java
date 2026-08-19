package org.hswebframework.reactor.excel.utils;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.netty.buffer.AbstractByteBufAllocator;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.util.ReferenceCountUtil;
import org.hswebframework.reactor.excel.BlockHoundTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import reactor.test.publisher.TestPublisher;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamUtilsTest {

    @BeforeAll
    static void installBlockHound() {
        BlockHoundTestSupport.install();
    }

    @Test
    void bufferShouldKeepEmittedBytesStableUntilConsumed() {
        int bufferSize = 64 * 1024;
        int chunkSize = 8 * 1024;
        byte[] expected = new byte[3 * bufferSize];
        Arrays.fill(expected, 0, bufferSize, (byte) 1);
        Arrays.fill(expected, bufferSize, 2 * bufferSize, (byte) 2);
        Arrays.fill(expected, 2 * bufferSize, expected.length, (byte) 3);

        List<byte[]> buffers = StreamUtils
            .buffer(bufferSize, output -> Mono.fromRunnable(() -> {
                try {
                    for (int offset = 0; offset < expected.length; offset += chunkSize) {
                        output.write(expected, offset, chunkSize);
                    }
                } catch (IOException error) {
                    throw new UncheckedIOException(error);
                } finally {
                    StreamUtils.safeClose(output);
                }
            }))
            .collectList()
            .block();

        assertNotNull(buffers);
        ByteArrayOutputStream actual = new ByteArrayOutputStream();
        for (byte[] buffer : buffers) {
            actual.write(buffer, 0, buffer.length);
        }

        assertArrayEquals(expected, actual.toByteArray());
    }

    @Test
    void bufferShouldUseCallerProvidedBlockingScheduler() {
        Scheduler scheduler = Schedulers.newBoundedElastic(1, 16, "stream-utils-dedicated");
        AtomicReference<String> writerThread = new AtomicReference<>();
        try {
            byte[] bytes = StreamUtils
                .buffer(4, scheduler, output -> Mono.fromRunnable(() -> {
                    writerThread.set(Thread.currentThread().getName());
                    try {
                        output.write(new byte[]{1, 2, 3});
                    } catch (IOException error) {
                        throw new UncheckedIOException(error);
                    }
                }))
                .reduce(new byte[0], StreamUtilsTest::concat)
                .block(java.time.Duration.ofSeconds(5));

            assertArrayEquals(new byte[]{1, 2, 3}, bytes);
            assertTrue(writerThread.get().startsWith("stream-utils-dedicated-"));
        } finally {
            scheduler.dispose();
        }
    }

    @Test
    void bufferShouldRejectNonBlockingSchedulerBeforeInvokingWriter() {
        AtomicBoolean writerInvoked = new AtomicBoolean();

        StepVerifier
            .create(StreamUtils.buffer(4, Schedulers.parallel(), output -> {
                writerInvoked.set(true);
                return Mono.empty();
            }), 1)
            .expectErrorMatches(error -> error instanceof IllegalStateException
                && error.getMessage().contains("non-blocking thread"))
            .verify(java.time.Duration.ofSeconds(5));

        assertFalse(writerInvoked.get(), "writer ran on a non-blocking scheduler");
    }

    @Test
    void genuineWriterErrorAfterCancellationShouldBeDropped() throws InterruptedException {
        IllegalStateException expected = new IllegalStateException("writer failed after cancel");
        AtomicReference<Throwable> dropped = new AtomicReference<>();
        AtomicReference<Subscription> subscription = new AtomicReference<>();
        CountDownLatch writerSubscribed = new CountDownLatch(1);
        CountDownLatch errorDropped = new CountDownLatch(1);
        TestPublisher<Void> writer = TestPublisher.createNoncompliant(
            TestPublisher.Violation.DEFER_CANCELLATION
        );

        Hooks.onErrorDropped(error -> {
            dropped.set(error);
            errorDropped.countDown();
        });
        try {
            StreamUtils
                .buffer(4, output -> writer.mono()
                    .doOnSubscribe(ignore -> writerSubscribed.countDown()))
                .subscribe(new Subscriber<byte[]>() {
                    @Override
                    public void onSubscribe(Subscription value) {
                        subscription.set(value);
                        value.request(1);
                    }

                    @Override
                    public void onNext(byte[] value) {
                    }

                    @Override
                    public void onError(Throwable error) {
                    }

                    @Override
                    public void onComplete() {
                    }
                });

            assertTrue(writerSubscribed.await(5, TimeUnit.SECONDS),
                       "blocking writer publisher was not subscribed");
            subscription.get().cancel();
            writer.error(expected);
            assertTrue(errorDropped.await(5, TimeUnit.SECONDS),
                       "writer error was not routed to onErrorDropped");
            assertEquals(expected, dropped.get());
        } finally {
            Hooks.resetOnErrorDropped();
        }
    }

    private static byte[] concat(byte[] left, byte[] right) {
        byte[] result = Arrays.copyOf(left, left.length + right.length);
        System.arraycopy(right, 0, result, left.length, right.length);
        return result;
    }

    @Test
    void byteArrayBufferShouldWaitForEveryChunkDemand() throws InterruptedException {
        CountDownLatch writerFinished = new CountDownLatch(1);
        Flux<byte[]> buffers = StreamUtils.buffer(
            4,
            output -> Mono.fromRunnable(() -> {
                try {
                    output.write(new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
                } catch (IOException error) {
                    throw new UncheckedIOException(error);
                } finally {
                    StreamUtils.safeClose(output);
                    writerFinished.countDown();
                }
            })
        );

        StepVerifier.create(buffers, 0)
                    .expectSubscription()
                    .expectNoEvent(java.time.Duration.ofMillis(100))
                    .then(() -> assertEquals(1, writerFinished.getCount()))
                    .thenRequest(1)
                    .assertNext(actual -> assertArrayEquals(new byte[]{1, 2, 3, 4}, actual))
                    .then(() -> assertEquals(1, writerFinished.getCount()))
                    .thenRequest(1)
                    .assertNext(actual -> assertArrayEquals(new byte[]{5, 6, 7, 8}, actual))
                    .expectComplete()
                    .verify(java.time.Duration.ofSeconds(5));

        assertTrue(writerFinished.await(5, TimeUnit.SECONDS));
    }

    @Test
    void writerShouldStartOnlyAfterFirstDemand() throws InterruptedException {
        CountDownLatch writerStarted = new CountDownLatch(1);
        Flux<byte[]> buffers = StreamUtils.buffer(
            4,
            output -> Mono.fromRunnable(() -> {
                writerStarted.countDown();
                try {
                    output.write(new byte[]{1, 2, 3});
                } catch (IOException error) {
                    throw new UncheckedIOException(error);
                }
            })
        );

        StepVerifier.create(buffers, 0)
                    .expectSubscription()
                    .expectNoEvent(java.time.Duration.ofMillis(100))
                    .then(() -> assertEquals(1, writerStarted.getCount()))
                    .thenRequest(1)
                    .assertNext(actual -> assertArrayEquals(new byte[]{1, 2, 3}, actual))
                    .expectComplete()
                    .verify(java.time.Duration.ofSeconds(5));

        assertTrue(writerStarted.await(5, TimeUnit.SECONDS));
    }

    @Test
    void asyncWriterShouldFailInsteadOfWaitingOnNonBlockingThread() {
        Flux<byte[]> buffers = StreamUtils.buffer(
            4,
            output -> Mono
                .delay(java.time.Duration.ofMillis(10))
                .then(Mono.fromRunnable(() -> {
                    try {
                        output.write(new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
                    } catch (IOException error) {
                        throw new UncheckedIOException(error);
                    }
                }))
        );

        StepVerifier.create(buffers, 1)
                    .assertNext(actual -> assertArrayEquals(new byte[]{1, 2, 3, 4}, actual))
                    .expectErrorMatches(error -> error instanceof IllegalStateException
                        && error.getMessage().contains("cannot wait for downstream demand"))
                    .verify(java.time.Duration.ofSeconds(5));
    }

    @Test
    void asyncWriterShouldWarnOnceAndCompleteWhenDemandIsAvailable() {
        Logger logger = (Logger) LoggerFactory.getLogger(StreamUtils.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            Flux<byte[]> buffers = StreamUtils.buffer(
                4,
                output -> Mono
                    .delay(java.time.Duration.ofMillis(10))
                    .then(Mono.fromRunnable(() -> {
                        try {
                            output.write(new byte[]{1, 2, 3});
                        } catch (IOException error) {
                            throw new UncheckedIOException(error);
                        }
                    }))
            );

            StepVerifier.create(buffers, 1)
                        .assertNext(actual -> assertArrayEquals(new byte[]{1, 2, 3}, actual))
                        .expectComplete()
                        .verify(java.time.Duration.ofSeconds(5));

            List<ILoggingEvent> warnings = appender.list
                .stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .filter(event -> event
                    .getFormattedMessage()
                    .contains("Blocking OutputStream accessed from Reactor non-blocking thread"))
                .collect(java.util.stream.Collectors.toList());
            assertEquals(1, warnings.size(),
                "non-blocking access should warn once per subscription");
            ILoggingEvent warning = warnings.get(0);
            assertNotNull(warning.getThrowableProxy(),
                "warning should include the complete runtime access stack");
            assertTrue(Arrays
                .stream(warning.getThrowableProxy().getStackTraceElementProxyArray())
                .anyMatch(frame -> frame
                    .getStackTraceElement()
                    .getClassName()
                    .equals(StreamUtilsTest.class.getName())),
                "diagnostic stack should include the external writer call");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void byteBufAsyncWriterShouldFailBeforeWaitingAndReleaseBuffers() {
        TrackingAllocator allocator = new TrackingAllocator();

        StepVerifier
            .create(StreamUtils.buffer(
                4,
                allocator,
                output -> Mono
                    .delay(java.time.Duration.ofMillis(10))
                    .then(Mono.fromRunnable(() -> {
                        try {
                            output.write(new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
                        } catch (IOException error) {
                            throw new UncheckedIOException(error);
                        }
                    }))
            ), 1)
            .assertNext(ReferenceCountUtil::safeRelease)
            .expectErrorMatches(error -> error instanceof IllegalStateException
                && error.getMessage().contains("cannot wait for downstream demand"))
            .verify(java.time.Duration.ofSeconds(5));

        assertTrue(allocator.allReleased(), "failed non-blocking emission leaked a ByteBuf");
    }

    @Test
    void cancellationMustNotWaitForSlowDownstreamCallback() throws InterruptedException {
        CountDownLatch onNextEntered = new CountDownLatch(1);
        CountDownLatch releaseOnNext = new CountDownLatch(1);
        CountDownLatch writerFinished = new CountDownLatch(1);
        CountDownLatch cancellationFinished = new CountDownLatch(1);
        AtomicReference<Subscription> subscription = new AtomicReference<>();
        AtomicReference<Throwable> cancellationError = new AtomicReference<>();
        AtomicBoolean cancellationWasNonBlocking = new AtomicBoolean();

        StreamUtils
            .buffer(4, output -> Mono.fromRunnable(() -> {
                try {
                    output.write(new byte[]{1, 2, 3, 4});
                } catch (IOException error) {
                    throw new UncheckedIOException(error);
                } finally {
                    writerFinished.countDown();
                }
            }))
            .subscribe(new Subscriber<byte[]>() {
                @Override
                public void onSubscribe(Subscription value) {
                    subscription.set(value);
                    value.request(1);
                }

                @Override
                public void onNext(byte[] value) {
                    onNextEntered.countDown();
                    awaitUninterruptibly(releaseOnNext);
                }

                @Override
                public void onError(Throwable error) {
                }

                @Override
                public void onComplete() {
                }
            });

        assertTrue(onNextEntered.await(5, TimeUnit.SECONDS), "downstream callback did not start");
        Mono
            .fromRunnable(() -> {
                cancellationWasNonBlocking.set(Schedulers.isInNonBlockingThread());
                subscription.get().cancel();
            })
            .subscribeOn(Schedulers.parallel())
            .subscribe(
                ignore -> {
                },
                error -> {
                    cancellationError.set(error);
                    cancellationFinished.countDown();
                },
                cancellationFinished::countDown
            );

        try {
            assertTrue(cancellationFinished.await(2, TimeUnit.SECONDS),
                       "cancel waited for the downstream callback");
            assertNull(cancellationError.get());
            assertTrue(cancellationWasNonBlocking.get());
        } finally {
            releaseOnNext.countDown();
        }
        assertTrue(writerFinished.await(5, TimeUnit.SECONDS));
    }

    @Test
    void byteBufBufferShouldWaitForDemandAndReleaseOnCancel() throws InterruptedException {
        CountDownLatch writerFinished = new CountDownLatch(1);
        TrackingAllocator allocator = new TrackingAllocator();
        Flux<ByteBuf> buffers = StreamUtils.buffer(
            4,
            allocator,
            output -> Mono.fromRunnable(() -> {
                try {
                    output.write(new byte[]{1, 2, 3, 4, 5, 6});
                } catch (IOException error) {
                    throw new UncheckedIOException(error);
                } finally {
                    StreamUtils.safeClose(output);
                    writerFinished.countDown();
                }
            })
        );

        StepVerifier.create(buffers, 0)
                    .expectSubscription()
                    .expectNoEvent(java.time.Duration.ofMillis(100))
                    .thenRequest(1)
                    .assertNext(buffer -> {
                        assertEquals(4, buffer.readableBytes());
                        assertEquals(1, buffer.getByte(0));
                        StreamUtils.releaseToByteArray(buffer);
                    })
                    .thenCancel()
                    .verify(java.time.Duration.ofSeconds(5));

        // Cancellation must wake the bounded-elastic writer even while it is waiting for
        // the second chunk's demand.
        assertTrue(writerFinished.await(5, TimeUnit.SECONDS),
                   "cancel should wake the blocked writer");
        assertTrue(allocator.allReleased(), "cancel path leaked a ByteBuf");
    }

    @Test
    void cancellationBeforeFirstDemandShouldNotStartWriterOrAllocateBuffer() {
        AtomicBoolean writerStarted = new AtomicBoolean();
        TrackingAllocator allocator = new TrackingAllocator();

        StepVerifier
            .create(StreamUtils.buffer(
                4,
                allocator,
                output -> Mono.fromRunnable(() -> writerStarted.set(true))
            ), 0)
            .expectSubscription()
            .thenCancel()
            .verify(java.time.Duration.ofSeconds(5));

        assertFalse(writerStarted.get(), "writer started without downstream demand");
        assertEquals(0, allocator.allocationCount(), "buffer allocated without downstream demand");
    }

    @Test
    void byteBufBufferShouldReleaseCurrentBufferWhenWriterFails() {
        TrackingAllocator allocator = new TrackingAllocator();
        IllegalStateException expected = new IllegalStateException("writer failed");

        StepVerifier
            .create(StreamUtils.buffer(
                4,
                allocator,
                output -> Mono
                    .fromRunnable(() -> {
                        try {
                            output.write(new byte[]{1, 2, 3});
                        } catch (IOException error) {
                            throw new UncheckedIOException(error);
                        }
                    })
                    .then(Mono.error(expected))
            ))
            .expectErrorMatches(error -> error == expected)
            .verify(java.time.Duration.ofSeconds(5));

        assertTrue(allocator.allReleased(), "writer error leaked the current ByteBuf");
    }

    @Test
    void byteBufBufferShouldReleaseChunkWhenDownstreamOnNextThrows()
        throws InterruptedException {
        TrackingAllocator allocator = new TrackingAllocator();
        CountDownLatch onNextCalled = new CountDownLatch(1);
        CountDownLatch errorDropped = new CountDownLatch(1);
        CountDownLatch writerFinished = new CountDownLatch(1);
        AtomicInteger terminalSignals = new AtomicInteger();
        AtomicReference<Throwable> droppedError = new AtomicReference<>();

        Hooks.onErrorDropped(error -> {
            droppedError.compareAndSet(null, error);
            errorDropped.countDown();
        });
        try {
            StreamUtils
                .buffer(
                    4,
                    allocator,
                    output -> Mono.fromRunnable(() -> {
                        try {
                            output.write(new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
                        } catch (IOException error) {
                            throw new UncheckedIOException(error);
                        } finally {
                            writerFinished.countDown();
                        }
                    })
                )
                .subscribe(new Subscriber<ByteBuf>() {
                    @Override
                    public void onSubscribe(Subscription subscription) {
                        subscription.request(1);
                    }

                    @Override
                    public void onNext(ByteBuf value) {
                        onNextCalled.countDown();
                        throw new IllegalStateException("downstream failed");
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

            assertTrue(onNextCalled.await(5, TimeUnit.SECONDS), "downstream callback did not run");
            assertTrue(errorDropped.await(5, TimeUnit.SECONDS), "callback failure was not dropped");
            assertTrue(writerFinished.await(5, TimeUnit.SECONDS),
                       "writer did not stop after callback failure");
        } finally {
            Hooks.resetOnErrorDropped();
        }

        assertEquals("downstream failed", droppedError.get().getMessage());
        assertEquals(0, terminalSignals.get(), "failing subscriber received another terminal signal");
        assertTrue(allocator.allReleased(), "downstream callback failure leaked a ByteBuf");
    }

    @Test
    void byteBufCancellationMustNotWaitForSlowDownstreamCallback() throws InterruptedException {
        TrackingAllocator allocator = new TrackingAllocator();
        CountDownLatch onNextEntered = new CountDownLatch(1);
        CountDownLatch releaseOnNext = new CountDownLatch(1);
        CountDownLatch writerFinished = new CountDownLatch(1);
        CountDownLatch cancellationFinished = new CountDownLatch(1);
        AtomicReference<Subscription> subscription = new AtomicReference<>();

        StreamUtils
            .buffer(
                4,
                allocator,
                output -> Mono.fromRunnable(() -> {
                    try {
                        output.write(new byte[]{1, 2, 3, 4});
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
                    value.request(1);
                }

                @Override
                public void onNext(ByteBuf value) {
                    onNextEntered.countDown();
                    try {
                        awaitUninterruptibly(releaseOnNext);
                    } finally {
                        ReferenceCountUtil.safeRelease(value);
                    }
                }

                @Override
                public void onError(Throwable error) {
                }

                @Override
                public void onComplete() {
                }
            });

        assertTrue(onNextEntered.await(5, TimeUnit.SECONDS), "downstream callback did not start");
        Mono
            .fromRunnable(subscription.get()::cancel)
            .subscribeOn(Schedulers.parallel())
            .doFinally(ignored -> cancellationFinished.countDown())
            .subscribe();

        try {
            assertTrue(cancellationFinished.await(2, TimeUnit.SECONDS),
                       "cancel waited for the ByteBuf callback");
        } finally {
            releaseOnNext.countDown();
        }
        assertTrue(writerFinished.await(5, TimeUnit.SECONDS));
        assertTrue(allocator.allReleased(), "slow callback cancellation leaked a ByteBuf");
    }

    @Test
    void bufferShouldPropagateErrorAfterStreamClose() {
        Flux<byte[]> buffers = StreamUtils.buffer(
            4,
            output -> Mono.fromRunnable(() -> {
                try {
                    output.write(1);
                    output.close();
                } catch (IOException error) {
                    throw new UncheckedIOException(error);
                }
            }).then(Mono.error(new IllegalStateException("write failed")))
        );

        StepVerifier.create(buffers)
                    .expectErrorMatches(error -> error instanceof IllegalStateException
                        && "write failed".equals(error.getMessage()))
                    .verify(java.time.Duration.ofSeconds(5));
    }

    @Test
    void bufferShouldFinalizeStreamWhenConsumerCompletes() {
        Flux<byte[]> buffers = StreamUtils.buffer(
            4,
            output -> Mono.fromRunnable(() -> {
                try {
                    output.write(new byte[]{1, 2, 3});
                } catch (IOException error) {
                    throw new UncheckedIOException(error);
                }
            })
        );

        StepVerifier.create(buffers)
                    .assertNext(actual -> assertArrayEquals(new byte[]{1, 2, 3}, actual))
                    .expectComplete()
                    .verify(java.time.Duration.ofSeconds(5));
    }

    @Test
    void byteBufBufferShouldReleaseEveryDeliveredChunkExactlyOnce() {
        AtomicInteger chunks = new AtomicInteger();
        List<byte[]> result = StreamUtils
            .buffer(
                2,
                UnpooledByteBufAllocator.DEFAULT,
                output -> Mono.fromRunnable(() -> {
                    try {
                        output.write(new byte[]{7, 8, 9});
                    } catch (IOException error) {
                        throw new UncheckedIOException(error);
                    } finally {
                        StreamUtils.safeClose(output);
                    }
                })
            )
            .doOnNext(buffer -> chunks.incrementAndGet())
            .map(StreamUtils::releaseToByteArray)
            .collectList()
            .block();

        assertNotNull(result);
        assertEquals(2, chunks.get());
        assertArrayEquals(new byte[]{7, 8}, result.get(0));
        assertArrayEquals(new byte[]{9}, result.get(1));
    }

    @Test
    void byteBufChunksShouldRemainStableAndIndependentWhenAccumulated() {
        TrackingAllocator allocator = new TrackingAllocator();
        List<ByteBuf> buffers = StreamUtils
            .buffer(
                4,
                allocator,
                output -> Mono.fromRunnable(() -> {
                    try {
                        output.write(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12});
                    } catch (IOException error) {
                        throw new UncheckedIOException(error);
                    }
                })
            )
            // The source is deliberately fixed at three chunks so delayed inspection is bounded.
            .collectList()
            .block(java.time.Duration.ofSeconds(5));

        try {
            assertNotNull(buffers);
            assertEquals(3, buffers.size());
            assertArrayEquals(new byte[]{1, 2, 3, 4}, copyReadableBytes(buffers.get(0)));
            assertArrayEquals(new byte[]{5, 6, 7, 8}, copyReadableBytes(buffers.get(1)));
            assertArrayEquals(new byte[]{9, 10, 11, 12}, copyReadableBytes(buffers.get(2)));

            buffers.get(0).setByte(buffers.get(0).readerIndex(), 99);

            assertArrayEquals(new byte[]{99, 2, 3, 4}, copyReadableBytes(buffers.get(0)));
            assertArrayEquals(new byte[]{5, 6, 7, 8}, copyReadableBytes(buffers.get(1)));
            assertArrayEquals(new byte[]{9, 10, 11, 12}, copyReadableBytes(buffers.get(2)));
        } finally {
            if (buffers != null) {
                buffers.forEach(ReferenceCountUtil::safeRelease);
            }
        }

        assertTrue(allocator.allReleased(), "accumulated chunks retained a backing buffer");
    }

    private static byte[] copyReadableBytes(ByteBuf buffer) {
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), bytes);
        return bytes;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for test signal");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test worker was interrupted", error);
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        for (; ; ) {
            try {
                if (!latch.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("timed out waiting for test signal");
                }
                break;
            } catch (InterruptedException error) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
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
