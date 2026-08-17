package org.hswebframework.reactor.excel.utils;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.Context;

import java.io.Closeable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Blocking {@link OutputStream} to reactive chunks adapter.
 *
 * <p>The writer is isolated on {@link Schedulers#boundedElastic()}. A full chunk waits for
 * downstream demand instead of entering an unbounded Reactor queue. Cancellation wakes the
 * blocked writer and releases every chunk that has not been transferred to the downstream.</p>
 */
@Slf4j
public class StreamUtils {

    public static Flux<byte[]> buffer(int bufferSize,
                                      Function<OutputStream, Mono<Void>> streamConsumer) {
        checkBufferSize(bufferSize);
        return StreamUtils.<byte[]>create(
            streamConsumer,
            ignore -> {
            },
            emitter -> new ByteArrayOutputStream(bufferSize, emitter)
        );
    }

    /**
     * Adapt a blocking output writer to reference-counted Netty buffers.
     *
     * <p>Ownership is transferred to the downstream after {@code onNext}. A subscriber must
     * release consumed buffers; buffers discarded before delivery are released by this Flux.</p>
     *
     * @param bufferSize maximum size of each emitted buffer
     * @param allocator allocator used independently for every subscription
     * @param streamConsumer blocking writer callback; the adapter finalizes the stream when the
     *                       returned publisher completes
     * @return demand-aware buffer stream
     * @since 1.0.7
     */
    public static Flux<ByteBuf> buffer(int bufferSize,
                                       ByteBufAllocator allocator,
                                       Function<OutputStream, Mono<Void>> streamConsumer) {
        checkBufferSize(bufferSize);
        Objects.requireNonNull(allocator, "allocator");
        return StreamUtils.<ByteBuf>create(
            streamConsumer,
            ReferenceCountUtil::safeRelease,
            emitter -> new ByteBufOutputStream(bufferSize, allocator, emitter)
        ).doOnDiscard(ByteBuf.class, ReferenceCountUtil::safeRelease);
    }

    private static <T> Flux<T> create(Function<OutputStream, Mono<Void>> streamConsumer,
                                      Consumer<T> releaser,
                                      Function<DemandEmitter<T>, ManagedOutputStream> streamFactory) {
        Objects.requireNonNull(streamConsumer, "streamConsumer");
        return Flux.create(sink -> {
            DemandEmitter<T> emitter = new DemandEmitter<>(sink, releaser);
            ManagedOutputStream stream = streamFactory.apply(emitter);
            Disposable.Composite resources = Disposables.composite();

            sink.onRequest(emitter::request);
            resources.add(stream::cancel);
            sink.onDispose(resources);

            // The callback can write synchronously during subscription. Offloading the whole
            // subscription prevents a zero-demand subscriber from deadlocking subscribe().
            Disposable writer = Mono
                .defer(() -> Objects.requireNonNull(
                    streamConsumer.apply(stream),
                    "streamConsumer returned null"
                ))
                .onErrorResume(error -> {
                    if (emitter.isCancelled() || sink.isCancelled()) {
                        return Mono.empty();
                    }
                    return Mono.error(error);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                    ignore -> {
                    },
                    error -> {
                        boolean cancelled = emitter.isCancelled() || sink.isCancelled();
                        stream.cancel();
                        if (!cancelled && !sink.isCancelled()) {
                            sink.error(error);
                        }
                    },
                    () -> {
                        try {
                            stream.finish();
                        } catch (Throwable error) {
                            boolean cancelled = emitter.isCancelled() || sink.isCancelled();
                            stream.cancel();
                            if (!cancelled && !sink.isCancelled()) {
                                sink.error(error);
                            }
                        }
                    },
                    Context.of(sink.contextView())
                );
            resources.add(writer);
        }, FluxSink.OverflowStrategy.ERROR);
    }

    /**
     * Copy the readable bytes and release the source buffer exactly once.
     *
     * @param buffer source buffer whose ownership is transferred to this method
     * @return independent byte array
     * @since 1.0.7
     */
    public static byte[] releaseToByteArray(ByteBuf buffer) {
        Objects.requireNonNull(buffer, "buffer");
        try {
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.getBytes(buffer.readerIndex(), bytes);
            return bytes;
        } finally {
            ReferenceCountUtil.safeRelease(buffer);
        }
    }

    private static void checkBufferSize(int bufferSize) {
        if (bufferSize <= 0) {
            throw new IllegalArgumentException("bufferSize must be greater than zero");
        }
    }

    public static void safeClose(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Throwable err) {
            log.warn(err.getMessage(), err);
        }
    }

    private abstract static class ManagedOutputStream extends OutputStream {

        // close() only seals writes. The producer publisher owns the terminal signal so an
        // error after close cannot be misreported as successful completion.
        abstract void finish() throws IOException;

        abstract void cancel();
    }

    private static final class DemandEmitter<T> {

        private final FluxSink<T> sink;

        private final Consumer<T> releaser;

        private final ReentrantLock lock = new ReentrantLock();

        private final Condition demandChanged = lock.newCondition();

        private long requested;

        private boolean cancelled;

        private boolean terminated;

        private DemandEmitter(FluxSink<T> sink, Consumer<T> releaser) {
            this.sink = sink;
            this.releaser = releaser;
        }

        private void request(long count) {
            if (count <= 0) {
                return;
            }
            lock.lock();
            try {
                if (cancelled || terminated) {
                    return;
                }
                requested = addCap(requested, count);
                demandChanged.signalAll();
            } finally {
                lock.unlock();
            }
        }

        private void emit(T value) throws IOException {
            lock.lock();
            try {
                while (requested == 0 && !cancelled && !terminated) {
                    try {
                        demandChanged.await();
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        releaser.accept(value);
                        InterruptedIOException interrupted = new InterruptedIOException(
                            "Interrupted while waiting for downstream demand"
                        );
                        interrupted.initCause(error);
                        throw interrupted;
                    }
                }
                if (cancelled || terminated) {
                    releaser.accept(value);
                    throw new IOException("Output stream was cancelled");
                }
                if (requested != Long.MAX_VALUE) {
                    requested--;
                }
                // Keep cancellation serialized with sink.next. If cancellation wins between
                // the demand check and delivery, a reference-counted chunk could otherwise be
                // dropped without passing through the discard hook.
                sink.next(value);
            } finally {
                lock.unlock();
            }
        }

        private void complete() {
            lock.lock();
            try {
                if (cancelled || terminated) {
                    return;
                }
                terminated = true;
                demandChanged.signalAll();
            } finally {
                lock.unlock();
            }
            sink.complete();
        }

        private void cancel() {
            lock.lock();
            try {
                if (cancelled) {
                    return;
                }
                cancelled = true;
                demandChanged.signalAll();
            } finally {
                lock.unlock();
            }
        }

        private boolean isCancelled() {
            lock.lock();
            try {
                return cancelled;
            } finally {
                lock.unlock();
            }
        }

        private static long addCap(long current, long increment) {
            long updated = current + increment;
            return updated < 0 ? Long.MAX_VALUE : updated;
        }
    }

    private static final class ByteArrayOutputStream extends ManagedOutputStream {

        private final int bufferSize;

        private final DemandEmitter<byte[]> emitter;

        private final ReentrantLock lock = new ReentrantLock();

        private byte[] buffer;

        private int position;

        private boolean closed;

        private ByteArrayOutputStream(int bufferSize, DemandEmitter<byte[]> emitter) {
            this.bufferSize = bufferSize;
            this.emitter = emitter;
            this.buffer = new byte[bufferSize];
        }

        @Override
        public void write(int value) throws IOException {
            byte[] full;
            lock.lock();
            try {
                ensureOpen();
                buffer[position++] = (byte) value;
                full = detachIfFull();
            } finally {
                lock.unlock();
            }
            emit(full);
        }

        @Override
        public void write(byte[] source, int offset, int length) throws IOException {
            checkBounds(source, offset, length);
            while (length > 0) {
                byte[] full;
                lock.lock();
                try {
                    ensureOpen();
                    int copyLength = Math.min(length, bufferSize - position);
                    System.arraycopy(source, offset, buffer, position, copyLength);
                    position += copyLength;
                    offset += copyLength;
                    length -= copyLength;
                    full = detachIfFull();
                } finally {
                    lock.unlock();
                }
                emit(full);
            }
        }

        @Override
        public void flush() throws IOException {
            emit(detachPartial());
        }

        @Override
        public void close() {
            lock.lock();
            try {
                closed = true;
            } finally {
                lock.unlock();
            }
        }

        @Override
        void finish() throws IOException {
            byte[] partial;
            lock.lock();
            try {
                closed = true;
                partial = position == 0 ? null : Arrays.copyOf(buffer, position);
                position = 0;
                buffer = null;
            } finally {
                lock.unlock();
            }
            emit(partial);
            emitter.complete();
        }

        @Override
        void cancel() {
            emitter.cancel();
            lock.lock();
            try {
                closed = true;
                position = 0;
                buffer = null;
            } finally {
                lock.unlock();
            }
        }

        private byte[] detachIfFull() {
            if (position != bufferSize) {
                return null;
            }
            byte[] full = buffer;
            buffer = new byte[bufferSize];
            position = 0;
            return full;
        }

        private byte[] detachPartial() throws IOException {
            lock.lock();
            try {
                ensureOpen();
                if (position == 0) {
                    return null;
                }
                byte[] partial = Arrays.copyOf(buffer, position);
                position = 0;
                return partial;
            } finally {
                lock.unlock();
            }
        }

        private void ensureOpen() throws IOException {
            if (closed) {
                throw new IOException("Output stream is closed");
            }
        }

        private void emit(byte[] value) throws IOException {
            if (value != null) {
                emitter.emit(value);
            }
        }
    }

    private static final class ByteBufOutputStream extends ManagedOutputStream {

        private final int bufferSize;

        private final ByteBufAllocator allocator;

        private final DemandEmitter<ByteBuf> emitter;

        private final ReentrantLock lock = new ReentrantLock();

        private ByteBuf buffer;

        private boolean closed;

        private ByteBufOutputStream(int bufferSize,
                                    ByteBufAllocator allocator,
                                    DemandEmitter<ByteBuf> emitter) {
            this.bufferSize = bufferSize;
            this.allocator = allocator;
            this.emitter = emitter;
        }

        @Override
        public void write(int value) throws IOException {
            ByteBuf full;
            lock.lock();
            try {
                ensureOpen();
                current().writeByte(value);
                full = detachIfFull();
            } finally {
                lock.unlock();
            }
            emit(full);
        }

        @Override
        public void write(byte[] source, int offset, int length) throws IOException {
            checkBounds(source, offset, length);
            while (length > 0) {
                ByteBuf full;
                lock.lock();
                try {
                    ensureOpen();
                    ByteBuf current = current();
                    int copyLength = Math.min(length, current.writableBytes());
                    current.writeBytes(source, offset, copyLength);
                    offset += copyLength;
                    length -= copyLength;
                    full = detachIfFull();
                } finally {
                    lock.unlock();
                }
                emit(full);
            }
        }

        @Override
        public void flush() throws IOException {
            emit(detachPartial());
        }

        @Override
        public void close() {
            lock.lock();
            try {
                closed = true;
            } finally {
                lock.unlock();
            }
        }

        @Override
        void finish() throws IOException {
            ByteBuf partial;
            lock.lock();
            try {
                closed = true;
                partial = buffer;
                buffer = null;
            } finally {
                lock.unlock();
            }
            emit(partial);
            emitter.complete();
        }

        @Override
        void cancel() {
            emitter.cancel();
            ByteBuf discarded;
            lock.lock();
            try {
                closed = true;
                discarded = buffer;
                buffer = null;
            } finally {
                lock.unlock();
            }
            ReferenceCountUtil.safeRelease(discarded);
        }

        private ByteBuf current() {
            if (buffer == null) {
                buffer = allocator.buffer(bufferSize, bufferSize);
            }
            return buffer;
        }

        private ByteBuf detachIfFull() {
            if (buffer == null || buffer.isWritable()) {
                return null;
            }
            ByteBuf full = buffer;
            buffer = null;
            return full;
        }

        private ByteBuf detachPartial() throws IOException {
            lock.lock();
            try {
                ensureOpen();
                ByteBuf partial = buffer;
                buffer = null;
                return partial;
            } finally {
                lock.unlock();
            }
        }

        private void ensureOpen() throws IOException {
            if (closed) {
                throw new IOException("Output stream is closed");
            }
        }

        private void emit(ByteBuf value) throws IOException {
            if (value == null) {
                return;
            }
            if (!value.isReadable()) {
                ReferenceCountUtil.safeRelease(value);
                return;
            }
            emitter.emit(value);
        }
    }

    private static void checkBounds(byte[] source, int offset, int length) {
        Objects.requireNonNull(source, "source");
        if ((offset | length) < 0 || length > source.length - offset) {
            throw new IndexOutOfBoundsException();
        }
    }
}
