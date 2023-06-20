package org.hswebframework.reactor.excel.utils;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.function.Function;

@Slf4j
public class StreamUtils {

    public static Flux<byte[]> buffer(int buffer, Function<OutputStream, Mono<Void>> streamConsumer) {

        return Flux.create(sink -> {
            OutputStream stream = new BufferedOutputStream(new OutputStream() {

                @Override
                public void write(byte[] b, int off, int len) {
                    if (len == b.length) {
                        sink.next(b);
                    } else {
                        sink.next(Arrays.copyOfRange(b, off, off + len));
                    }
                }

                @Override
                public void write(byte[] b) {
                    sink.next(b);
                }

                @Override
                public void write(int b) {
                    sink.next(new byte[]{(byte) b});
                }
            }, buffer) {
                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        sink.complete();
                    }
                }
            };
            sink.onDispose(
                    streamConsumer
                            .apply(stream)
                            .subscribe(ignore -> {
                                       },
                                       sink::error,
                                       () -> {
                                       },
                                       Context.of(sink.contextView())));
        });
    }

    public static void safeClose(Closeable closeable) {
        try {
            closeable.close();
        } catch (Throwable err) {
            log.warn(err.getMessage(), err);
        }
    }
}
