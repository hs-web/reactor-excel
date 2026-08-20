package org.hswebframework.reactor.excel.csv;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.util.ReferenceCountUtil;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.hswebframework.reactor.excel.WritableCell;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Operators;
import reactor.util.context.Context;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.BooleanSupplier;

/**
 * Incremental CSV encoder that couples cell requests to {@link ByteBuf} demand.
 *
 * <p>Each subscription owns one continuous charset encoder and a lock-free drain loop. A reusable
 * staging buffer keeps one cell private until its size has been validated. Small cells are copied
 * into fixed-size aggregate chunks; cells larger than one output chunk transfer their staging
 * buffer and are sliced lazily without copying. Reactive demand bounds source requests, while
 * {@code maxEncodedCellBytes} bounds the encoded representation of the current cell.</p>
 */
final class CsvByteBufFlux extends Flux<ByteBuf> {

    private final Flux<WritableCell> source;

    private final ByteBufAllocator allocator;

    private final int bufferSize;

    private final int maxEncodedCellBytes;

    private final CSVFormat format;

    private final Charset charset;

    CsvByteBufFlux(Flux<WritableCell> source,
                   ByteBufAllocator allocator,
                   int bufferSize,
                   int maxEncodedCellBytes,
                   CSVFormat format,
                   Charset charset) {
        this.source = Objects.requireNonNull(source, "source");
        this.allocator = Objects.requireNonNull(allocator, "allocator");
        if (bufferSize <= 0) {
            throw new IllegalArgumentException("bufferSize must be greater than zero");
        }
        if (maxEncodedCellBytes <= 0) {
            throw new IllegalArgumentException("maxEncodedCellBytes must be greater than zero");
        }
        this.bufferSize = bufferSize;
        this.maxEncodedCellBytes = maxEncodedCellBytes;
        this.format = Objects.requireNonNull(format, "format");
        this.charset = Objects.requireNonNull(charset, "charset");
    }

    @Override
    public void subscribe(CoreSubscriber<? super ByteBuf> actual) {
        final CsvSubscription subscription;
        try {
            subscription = new CsvSubscription(
                actual,
                allocator,
                bufferSize,
                maxEncodedCellBytes,
                format,
                charset
            );
        } catch (Throwable error) {
            Operators.error(actual, error);
            return;
        }

        try {
            actual.onSubscribe(subscription);
        } catch (Throwable error) {
            subscription.cancel();
            Operators.onErrorDropped(error, actual.currentContext());
            return;
        }
        if (subscription.isCancelled()) {
            return;
        }
        try {
            source.subscribe(subscription);
        } catch (Throwable error) {
            subscription.onError(error);
        }
    }

    private static final class CsvSubscription implements CoreSubscriber<WritableCell>, Subscription {

        private static final AtomicReferenceFieldUpdater<CsvSubscription, Subscription> UPSTREAM =
            AtomicReferenceFieldUpdater.newUpdater(
                CsvSubscription.class,
                Subscription.class,
                "upstream"
            );

        private static final AtomicLongFieldUpdater<CsvSubscription> REQUESTED =
            AtomicLongFieldUpdater.newUpdater(CsvSubscription.class, "requested");

        private final CoreSubscriber<? super ByteBuf> downstream;

        private final AtomicInteger wip = new AtomicInteger();

        private final ChunkedByteBufOutputStream output;

        private final CSVPrinter printer;

        private volatile Subscription upstream;

        private volatile WritableCell pendingCell;

        private volatile long requested;

        private volatile boolean sourceDone;

        private volatile boolean cancelled;

        private volatile boolean terminated;

        private volatile Throwable error;

        // The following fields are only accessed by the drain owner.
        private boolean sourceRequested;

        private boolean outputClosed;

        private CsvSubscription(CoreSubscriber<? super ByteBuf> downstream,
                                ByteBufAllocator allocator,
                                int bufferSize,
                                int maxEncodedCellBytes,
                                CSVFormat format,
                                Charset charset) throws IOException {
            this.downstream = Objects.requireNonNull(downstream, "downstream");
            ChunkedByteBufOutputStream output = new ChunkedByteBufOutputStream(
                allocator,
                bufferSize,
                maxEncodedCellBytes,
                () -> cancelled
            );
            try {
                // The BOM remains a separate chunk so no source cell is requested for its demand.
                byte[] bom = CsvBom.bytes(charset);
                output.write(bom, 0, bom.length);
                output.sealAggregate();
                output.beginCell();
                CSVPrinter printer = new CSVPrinter(new OutputStreamWriter(output, charset), format);
                printer.flush();
                output.endCell();
                this.printer = printer;
                this.output = output;
            } catch (IOException | RuntimeException | Error creationError) {
                output.abort();
                throw creationError;
            }
        }

        @Override
        public Context currentContext() {
            return downstream.currentContext();
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            if (Operators.setOnce(UPSTREAM, this, subscription)) {
                drain();
            }
        }

        @Override
        public void onNext(WritableCell cell) {
            if (cell == null) {
                onError(new NullPointerException("CSV source emitted null cell"));
                return;
            }
            if (cancelled || sourceDone) {
                Operators.onNextDropped(cell, currentContext());
                return;
            }
            if (pendingCell != null) {
                onError(new IllegalStateException("CSV source emitted more cells than requested"));
                return;
            }
            pendingCell = cell;
            drain();
        }

        @Override
        public void onError(Throwable sourceError) {
            Objects.requireNonNull(sourceError, "sourceError");
            if (cancelled || sourceDone) {
                Operators.onErrorDropped(sourceError, currentContext());
                return;
            }
            error = sourceError;
            sourceDone = true;
            drain();
        }

        @Override
        public void onComplete() {
            if (cancelled || sourceDone) {
                return;
            }
            sourceDone = true;
            drain();
        }

        @Override
        public void request(long count) {
            if (count <= 0) {
                fail(new IllegalArgumentException("request amount must be greater than zero"));
                return;
            }
            if (cancelled || terminated) {
                return;
            }
            Operators.addCap(REQUESTED, this, count);
            drain();
        }

        @Override
        public void cancel() {
            if (cancelled || terminated) {
                return;
            }
            cancelled = true;
            Operators.terminate(UPSTREAM, this);
            drain();
        }

        private boolean isCancelled() {
            return cancelled;
        }

        private void fail(Throwable failure) {
            if (cancelled || terminated) {
                Operators.onErrorDropped(failure, currentContext());
                return;
            }
            error = failure;
            sourceDone = true;
            Operators.terminate(UPSTREAM, this);
            drain();
        }

        /**
         * Serializes all CSVPrinter and queue access without making request or cancel wait for the
         * current encoder invocation. Upstream and downstream callbacks are always made unlocked.
         */
        private void drain() {
            if (wip.getAndIncrement() != 0) {
                return;
            }

            int missed = 1;
            for (; ; ) {
                if (!terminated) {
                    drainAvailable();
                }
                missed = wip.addAndGet(-missed);
                if (missed == 0) {
                    return;
                }
            }
        }

        private void drainAvailable() {
            for (; ; ) {
                if (cancelled) {
                    terminateCancelled();
                    return;
                }

                Throwable failure = error;
                if (failure != null) {
                    terminateError(failure);
                    return;
                }

                WritableCell cell = pendingCell;
                if (cell != null) {
                    pendingCell = null;
                    sourceRequested = false;
                    encodeCell(cell);
                    continue;
                }

                if (sourceDone && !outputClosed) {
                    finishOutput();
                    continue;
                }

                if (requested > 0) {
                    ByteBuf buffer = output.pollChunk();
                    if (buffer != null) {
                        Operators.produced(REQUESTED, this, 1);
                        emit(buffer);
                        continue;
                    }
                }

                if (sourceDone && outputClosed && output.isEmpty()) {
                    terminateComplete();
                    return;
                }

                if (requested > 0 && output.isEmpty() && !sourceRequested) {
                    Subscription subscription = upstream;
                    if (subscription != null
                        && subscription != Operators.cancelledSubscription()) {
                        sourceRequested = true;
                        try {
                            subscription.request(1);
                        } catch (Throwable requestError) {
                            sourceRequested = false;
                            error = Operators.onOperatorError(
                                subscription,
                                requestError,
                                currentContext()
                            );
                            sourceDone = true;
                            Operators.terminate(UPSTREAM, this);
                        }
                        continue;
                    }
                }
                return;
            }
        }

        private void encodeCell(WritableCell cell) {
            output.beginCell();
            try {
                printer.print(cell.valueAsText().orElse(""));
                if (cell.isEndOfRow()) {
                    printer.println();
                }
                printer.flush();
                output.endCell();
            } catch (Throwable encodingError) {
                output.abort();
                if (!cancelled) {
                    error = Operators.onOperatorError(
                        upstream,
                        encodingError,
                        cell,
                        currentContext()
                    );
                    sourceDone = true;
                    Operators.terminate(UPSTREAM, this);
                }
            }
        }

        private void finishOutput() {
            outputClosed = true;
            try {
                printer.close();
            } catch (Throwable closeError) {
                error = Operators.onOperatorError(closeError, currentContext());
                output.abort();
            }
        }

        private void emit(ByteBuf buffer) {
            if (cancelled) {
                ReferenceCountUtil.safeRelease(buffer);
                return;
            }
            try {
                downstream.onNext(buffer);
            } catch (Throwable downstreamError) {
                ReferenceCountUtil.safeRelease(buffer);
                cancelled = true;
                Operators.terminate(UPSTREAM, this);
                Operators.onErrorDropped(downstreamError, currentContext());
            }
        }

        private void terminateCancelled() {
            terminated = true;
            pendingCell = null;
            output.abort();
        }

        private void terminateError(Throwable failure) {
            terminated = true;
            pendingCell = null;
            Operators.terminate(UPSTREAM, this);
            output.abort();
            try {
                downstream.onError(failure);
            } catch (Throwable downstreamError) {
                Operators.onErrorDropped(downstreamError, currentContext());
            }
        }

        private void terminateComplete() {
            terminated = true;
            try {
                downstream.onComplete();
            } catch (Throwable downstreamError) {
                Operators.onErrorDropped(downstreamError, currentContext());
            }
        }
    }

    /**
     * OutputStream facade used by the continuous OutputStreamWriter. One staging buffer validates a
     * complete cell before it becomes visible, while one fixed-size aggregate buffer combines small
     * cells. Large staging buffers are queued and retained-sliced lazily as demand arrives. Only the
     * drain owner mutates these buffers; cancellation is observed through the supplied atomic flag.
     */
    private static final class ChunkedByteBufOutputStream extends OutputStream {

        private final ByteBufAllocator allocator;

        private final int bufferSize;

        private final int maxEncodedCellBytes;

        private final BooleanSupplier cancelled;

        private final Queue<ByteBuf> ready = new ArrayDeque<>();

        private ByteBuf staging;

        private ByteBuf aggregate;

        private int encodedCellBytes;

        private boolean cellActive;

        private boolean closed;

        private ChunkedByteBufOutputStream(ByteBufAllocator allocator,
                                           int bufferSize,
                                           int maxEncodedCellBytes,
                                           BooleanSupplier cancelled) {
            this.allocator = allocator;
            this.bufferSize = bufferSize;
            this.maxEncodedCellBytes = maxEncodedCellBytes;
            this.cancelled = cancelled;
        }

        @Override
        public void write(int value) throws IOException {
            ensureWritable();
            reserveCellBytes(1);
            if (cellActive) {
                ensureStaging().writeByte(value);
            } else {
                writeAggregateByte(value);
            }
        }

        @Override
        public void write(byte[] source, int offset, int length) throws IOException {
            Objects.requireNonNull(source, "source");
            if (offset < 0 || length < 0 || offset > source.length - length) {
                throw new IndexOutOfBoundsException(
                    "offset=" + offset + ", length=" + length + ", source.length=" + source.length
                );
            }
            if (length == 0) {
                return;
            }
            ensureWritable();
            reserveCellBytes(length);
            if (cellActive) {
                ensureStaging().writeBytes(source, offset, length);
                return;
            }
            writeAggregate(source, offset, length);
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            ensureWritable();
            sealAggregate();
            ReferenceCountUtil.safeRelease(staging);
            staging = null;
            closed = true;
        }

        private void beginCell() {
            if (cellActive) {
                throw new IllegalStateException("previous CSV cell encoding did not finish");
            }
            cellActive = true;
            encodedCellBytes = 0;
        }

        private void endCell() {
            if (staging != null && staging.isReadable()) {
                if (staging.readableBytes() <= bufferSize) {
                    aggregateSmallCell();
                } else {
                    sealAggregate();
                    ByteBuf largeCell = staging;
                    staging = null;
                    enqueue(largeCell);
                }
            }
            cellActive = false;
            encodedCellBytes = 0;
        }

        private void aggregateSmallCell() {
            int cellBytes = staging.readableBytes();
            ByteBuf target = ensureAggregate();
            int aggregateBytes = target.writableBytes();
            if (cellBytes <= aggregateBytes) {
                target.writeBytes(staging, staging.readerIndex(), cellBytes);
                staging.clear();
                if (!target.isWritable()) {
                    sealAggregate();
                }
                return;
            }

            // A cell crossing the chunk boundary must not allocate a third root buffer. Fill and
            // queue the old aggregate, then transfer the unconsumed staging tail as the next one.
            target.writeBytes(staging, staging.readerIndex(), aggregateBytes);
            staging.skipBytes(aggregateBytes);
            sealAggregate();
            staging.discardReadBytes();
            aggregate = staging;
            staging = null;
        }

        private void reserveCellBytes(int length) {
            if (!cellActive) {
                return;
            }
            if (length > maxEncodedCellBytes - encodedCellBytes) {
                throw new IllegalStateException(
                    "CSV cell encoded bytes exceed maxEncodedCellBytes: "
                        + maxEncodedCellBytes
                );
            }
            encodedCellBytes += length;
        }

        private void ensureWritable() throws IOException {
            if (closed) {
                throw new IOException("CSV ByteBuf output is closed");
            }
            if (cancelled.getAsBoolean()) {
                throw new CancellationException("CSV ByteBuf output was cancelled");
            }
        }

        private ByteBuf ensureStaging() {
            if (staging == null) {
                int initialCapacity = Math.min(bufferSize, maxEncodedCellBytes);
                staging = allocator.buffer(initialCapacity, maxEncodedCellBytes);
            }
            return staging;
        }

        private ByteBuf ensureAggregate() {
            if (aggregate == null) {
                aggregate = allocator.buffer(bufferSize, bufferSize);
            }
            return aggregate;
        }

        private void writeAggregateByte(int value) {
            ByteBuf target = ensureAggregate();
            target.writeByte(value);
            if (!target.isWritable()) {
                sealAggregate();
            }
        }

        private void writeAggregate(byte[] source, int offset, int length) {
            while (length > 0) {
                ByteBuf target = ensureAggregate();
                int copyLength = Math.min(length, target.writableBytes());
                target.writeBytes(source, offset, copyLength);
                offset += copyLength;
                length -= copyLength;
                if (!target.isWritable()) {
                    sealAggregate();
                }
            }
        }

        private void sealAggregate() {
            if (aggregate == null) {
                return;
            }
            ByteBuf buffer = aggregate;
            aggregate = null;
            if (!buffer.isReadable()) {
                ReferenceCountUtil.safeRelease(buffer);
                return;
            }
            enqueue(buffer);
        }

        private void enqueue(ByteBuf buffer) {
            try {
                ready.add(buffer);
            } catch (Throwable queueError) {
                ReferenceCountUtil.safeRelease(buffer);
                throw queueError;
            }
        }

        private ByteBuf pollChunk() {
            ByteBuf buffer = ready.peek();
            if (buffer == null) {
                return null;
            }
            if (buffer.readableBytes() <= bufferSize
                && buffer.readerIndex() == 0
                && buffer.capacity() <= bufferSize) {
                return ready.poll();
            }

            ByteBuf chunk = buffer.readRetainedSlice(
                Math.min(bufferSize, buffer.readableBytes())
            );
            if (!buffer.isReadable()) {
                ready.poll();
                ReferenceCountUtil.safeRelease(buffer);
            }
            return chunk;
        }

        private boolean isEmpty() {
            return ready.isEmpty();
        }

        private void abort() {
            closed = true;
            cellActive = false;
            encodedCellBytes = 0;
            ReferenceCountUtil.safeRelease(staging);
            staging = null;
            ReferenceCountUtil.safeRelease(aggregate);
            aggregate = null;
            ByteBuf buffer;
            while ((buffer = ready.poll()) != null) {
                ReferenceCountUtil.safeRelease(buffer);
            }
        }
    }
}
