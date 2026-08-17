package org.hswebframework.reactor.excel.utils;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamUtilsTest {

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
    void byteBufBufferShouldWaitForDemandAndReleaseOnCancel() throws InterruptedException {
        CountDownLatch writerFinished = new CountDownLatch(1);
        Flux<ByteBuf> buffers = StreamUtils.buffer(
            4,
            UnpooledByteBufAllocator.DEFAULT,
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
}
