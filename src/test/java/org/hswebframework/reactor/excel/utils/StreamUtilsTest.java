package org.hswebframework.reactor.excel.utils;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
}
