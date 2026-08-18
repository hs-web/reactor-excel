package org.hswebframework.reactor.excel.spi;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import org.hswebframework.reactor.excel.ExcelOption;
import org.hswebframework.reactor.excel.WritableCell;
import org.hswebframework.reactor.excel.utils.StreamUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.OutputStream;

/**
 * Excel/CSV serialization service provider.
 *
 * <p>Implementations that can encode incrementally should override the reactive
 * {@link #write(Flux, ByteBufAllocator, int, ExcelOption...)} method so downstream demand and
 * cancellation are propagated to the cell source. Blocking serializers may rely on the default
 * bounded {@link OutputStream} adapter.</p>
 *
 * @since 1.0
 */
public interface ExcelWriter {

    String FORMAT_XLSX = "xlsx";

    int DEFAULT_BUFFER_SIZE = 64 * 1024;

    String[] getSupportFormat();

    Mono<Void> write(Flux<WritableCell> dataStream,
                     OutputStream outputStream,
                     ExcelOption... options);

    /**
     * Serialize cells into reference-counted buffers using the default allocator and chunk size.
     *
     * <p>Ownership of every emitted buffer is transferred to the subscriber. Implementations must
     * release buffers that have not been delivered when the sequence is cancelled or fails.</p>
     *
     * @param dataStream ordered cells to serialize
     * @param options writer-specific options
     * @return cold serialized buffer stream
     * @since 1.0.7
     */
    default Flux<ByteBuf> write(Flux<WritableCell> dataStream,
                                ExcelOption... options) {
        return write(dataStream, ByteBufAllocator.DEFAULT, DEFAULT_BUFFER_SIZE, options);
    }

    /**
     * Serialize cells into bounded reference-counted chunks.
     *
     * <p>The default implementation adapts the blocking {@link OutputStream} contract and may
     * block a bounded-elastic worker while waiting for demand. Serialization starts on the first
     * downstream request; request and cancellation callbacks do not wait for the blocking writer.
     * Native reactive writers should override this method and directly coordinate demand with
     * {@code dataStream}.</p>
     *
     * <p>A blocking serializer may need to consume its cell source before it can produce the first
     * byte chunk, so this adapter bounds encoded bytes but cannot add cell-level backpressure to
     * such a format. Implementations whose source callbacks can hop threads must also keep all
     * {@link OutputStream} access off Reactor non-blocking threads.</p>
     *
     * @param dataStream ordered cells to serialize
     * @param allocator allocator for output buffers
     * @param bufferSize maximum size of each emitted buffer
     * @param options writer-specific options
     * @return cold serialized buffer stream
     * @since 1.0.7
     */
    default Flux<ByteBuf> write(Flux<WritableCell> dataStream,
                                ByteBufAllocator allocator,
                                int bufferSize,
                                ExcelOption... options) {
        return StreamUtils.buffer(
            bufferSize,
            allocator,
            output -> write(dataStream, output, options)
        );
    }

    boolean isSupportMultiSheet();
}
