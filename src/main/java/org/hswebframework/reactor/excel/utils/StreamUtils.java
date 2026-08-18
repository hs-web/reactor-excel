package org.hswebframework.reactor.excel.utils;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Operators;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.Context;

import java.io.Closeable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Blocking {@link OutputStream} to reactive chunks adapter.
 *
 * <p>The writer starts on first demand and its subscription is isolated on
 * {@link Schedulers#boundedElastic()}. A full chunk waits for downstream demand instead of entering
 * an unbounded Reactor queue. Asynchronous writer publishers must keep every {@link OutputStream}
 * access on a blocking-capable thread; misuse from a Reactor non-blocking thread fails fast.
 * Cancellation never waits for the writer or downstream callback and releases chunks that have not
 * been transferred to the downstream.</p>
 */
@Slf4j
public class StreamUtils {

    /**
     * Adapt a blocking output writer to bounded byte-array chunks.
     *
     * <p>The writer starts when the downstream first requests data. Synchronous callback setup is
     * moved to {@link Schedulers#boundedElastic()}, while asynchronous callbacks remain responsible
     * for keeping every {@link OutputStream} operation on a blocking-capable thread.</p>
     *
     * @param bufferSize maximum size of each emitted array
     * @param streamConsumer blocking writer callback
     * @return cold demand-aware byte stream
     */
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
     * <p>Ownership is transferred to the downstream only after its {@code onNext} callback
     * returns normally. A subscriber must release consumed buffers. If the callback throws, this
     * adapter releases the unaccepted buffer and cancels upstream; buffers discarded before
     * delivery are also released by this Flux.</p>
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
        Flux<T> output = Flux.create(sink -> {
            DemandEmitter<T> emitter = new DemandEmitter<>(sink, releaser);
            ManagedOutputStream stream = streamFactory.apply(emitter);
            Disposable.Composite resources = Disposables.composite();
            WriterTask<T> writer = new WriterTask<>(
                streamConsumer,
                sink,
                emitter,
                stream,
                resources
            );

            resources.add(stream::cancel);
            sink.onDispose(resources);
            sink.onRequest(writer::request);
        }, FluxSink.OverflowStrategy.ERROR);
        return new DownstreamGuardFlux<>(output, releaser);
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

        final void ensureBlockingThread() {
            if (Schedulers.isInNonBlockingThread()) {
                throw new IllegalStateException(
                    "Blocking OutputStream cannot be used from a Reactor non-blocking thread"
                );
            }
        }

        // close() only seals writes. The producer publisher owns the terminal signal so an
        // error after close cannot be misreported as successful completion.
        abstract void finish() throws IOException;

        abstract void cancel();
    }

    /**
     * Starts one writer subscription after the first positive request and owns its terminal path.
     */
    private static final class WriterTask<T> {

        private final Function<OutputStream, Mono<Void>> streamConsumer;

        private final FluxSink<T> sink;

        private final DemandEmitter<T> emitter;

        private final ManagedOutputStream stream;

        private final Disposable.Composite resources;

        private final AtomicBoolean started = new AtomicBoolean();

        private WriterTask(Function<OutputStream, Mono<Void>> streamConsumer,
                           FluxSink<T> sink,
                           DemandEmitter<T> emitter,
                           ManagedOutputStream stream,
                           Disposable.Composite resources) {
            this.streamConsumer = streamConsumer;
            this.sink = sink;
            this.emitter = emitter;
            this.stream = stream;
            this.resources = resources;
        }

        private void request(long count) {
            emitter.request(count);
            if (count > 0 && started.compareAndSet(false, true)) {
                start();
            }
        }

        private void start() {
            if (emitter.isCancelled() || sink.isCancelled()) {
                return;
            }
            // Synchronous callback setup belongs to boundedElastic. An asynchronous callback can
            // change threads later and must keep OutputStream access on a blocking-capable thread.
            Disposable subscription = Mono
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
                    this::error,
                    this::complete,
                    Context.of(sink.contextView())
                );
            resources.add(subscription);
        }

        private void error(Throwable error) {
            boolean cancelled = emitter.isCancelled() || sink.isCancelled();
            stream.cancel();
            if (!cancelled && !sink.isCancelled()) {
                sink.error(error);
            }
        }

        private void complete() {
            try {
                stream.finish();
            } catch (Throwable error) {
                boolean cancelled = emitter.isCancelled() || sink.isCancelled();
                stream.cancel();
                if (!cancelled && !sink.isCancelled()) {
                    sink.error(error);
                }
            }
        }
    }

    /**
     * Single-writer demand gate. Request and cancellation are lock-free; only the bounded-elastic
     * writer may park while no demand is available.
     */
    private static final class DemandEmitter<T> {

        private static final int ACTIVE = 0;

        private static final int CANCELLED = 1;

        private static final int TERMINATED = 2;

        private static final AtomicLongFieldUpdater<DemandEmitter> REQUESTED =
            AtomicLongFieldUpdater.newUpdater(DemandEmitter.class, "requested");

        private static final AtomicIntegerFieldUpdater<DemandEmitter> STATE =
            AtomicIntegerFieldUpdater.newUpdater(DemandEmitter.class, "state");

        private static final AtomicIntegerFieldUpdater<DemandEmitter> EMITTING =
            AtomicIntegerFieldUpdater.newUpdater(DemandEmitter.class, "emitting");

        private final FluxSink<T> sink;

        private final Consumer<T> releaser;

        private volatile long requested;

        private volatile int state;

        private volatile int emitting;

        private volatile Thread waiter;

        private DemandEmitter(FluxSink<T> sink, Consumer<T> releaser) {
            this.sink = sink;
            this.releaser = releaser;
        }

        private void request(long count) {
            if (count <= 0 || state != ACTIVE) {
                return;
            }
            addCap(count);
            unparkWriter();
        }

        private void emit(T value) throws IOException {
            if (!EMITTING.compareAndSet(this, 0, 1)) {
                releaser.accept(value);
                throw new IOException("Concurrent OutputStream writes are not supported");
            }
            try {
                if (Schedulers.isInNonBlockingThread()) {
                    releaser.accept(value);
                    throw new IllegalStateException(
                        "Blocking OutputStream cannot emit from a Reactor non-blocking thread"
                    );
                }
                awaitDemand(value);
                if (state != ACTIVE || sink.isCancelled()) {
                    releaser.accept(value);
                    throw new IOException("Output stream was cancelled");
                }
                // Demand is reserved before this callback. No adapter lock is held while invoking
                // downstream, so request and cancel remain non-blocking even for a slow subscriber.
                sink.next(value);
            } finally {
                EMITTING.set(this, 0);
            }
        }

        private void awaitDemand(T value) throws IOException {
            for (; ; ) {
                if (state != ACTIVE || sink.isCancelled()) {
                    return;
                }
                long current = requested;
                if (current == Long.MAX_VALUE
                    || (current > 0 && REQUESTED.compareAndSet(this, current, current - 1))) {
                    return;
                }

                waiter = Thread.currentThread();
                if (requested == 0 && state == ACTIVE && !sink.isCancelled()) {
                    LockSupport.park(this);
                }
                waiter = null;
                if (Thread.interrupted()) {
                    releaser.accept(value);
                    InterruptedIOException interrupted = new InterruptedIOException(
                        "Interrupted while waiting for downstream demand"
                    );
                    throw interrupted;
                }
            }
        }

        private void complete() {
            if (STATE.compareAndSet(this, ACTIVE, TERMINATED)) {
                unparkWriter();
                sink.complete();
            }
        }

        private void cancel() {
            if (STATE.compareAndSet(this, ACTIVE, CANCELLED)) {
                unparkWriter();
            }
        }

        private boolean isCancelled() {
            return state == CANCELLED;
        }

        private void addCap(long increment) {
            for (; ; ) {
                long current = requested;
                if (current == Long.MAX_VALUE) {
                    return;
                }
                long updated = current + increment;
                if (updated < 0) {
                    updated = Long.MAX_VALUE;
                }
                if (REQUESTED.compareAndSet(this, current, updated)) {
                    return;
                }
            }
        }

        private void unparkWriter() {
            LockSupport.unpark(waiter);
        }
    }

    /**
     * Guards the ownership handoff that FluxCreate's serialized sink otherwise hides. Reactor's
     * SerializedFluxSink cancels and swallows exceptions from onNext, so the adapter must observe
     * the real callback directly to reclaim a value whose ownership was not transferred.
     */
    private static final class DownstreamGuardFlux<T> extends Flux<T> {

        private final Flux<T> source;

        private final Consumer<T> releaser;

        private DownstreamGuardFlux(Flux<T> source, Consumer<T> releaser) {
            this.source = source;
            this.releaser = releaser;
        }

        @Override
        public void subscribe(CoreSubscriber<? super T> actual) {
            source.subscribe(new DownstreamGuardSubscriber<>(actual, releaser));
        }
    }

    private static final class DownstreamGuardSubscriber<T>
        implements CoreSubscriber<T>, Subscription {

        private final CoreSubscriber<? super T> downstream;

        private final Consumer<T> releaser;

        private Subscription upstream;

        private volatile boolean done;

        private DownstreamGuardSubscriber(CoreSubscriber<? super T> downstream,
                                          Consumer<T> releaser) {
            this.downstream = downstream;
            this.releaser = releaser;
        }

        @Override
        public Context currentContext() {
            return downstream.currentContext();
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            if (!Operators.validate(upstream, subscription)) {
                return;
            }
            upstream = subscription;
            try {
                downstream.onSubscribe(this);
            } catch (Throwable downstreamError) {
                done = true;
                subscription.cancel();
                Operators.onErrorDropped(downstreamError, currentContext());
            }
        }

        @Override
        public void onNext(T value) {
            if (done) {
                releaser.accept(value);
                return;
            }
            try {
                downstream.onNext(value);
            } catch (Throwable downstreamError) {
                // onNext did not return normally, so ownership never left this adapter.
                done = true;
                releaser.accept(value);
                upstream.cancel();
                Operators.onErrorDropped(downstreamError, currentContext());
            }
        }

        @Override
        public void onError(Throwable error) {
            if (done) {
                Operators.onErrorDropped(error, currentContext());
                return;
            }
            done = true;
            try {
                downstream.onError(error);
            } catch (Throwable downstreamError) {
                Operators.onErrorDropped(downstreamError, currentContext());
            }
        }

        @Override
        public void onComplete() {
            if (done) {
                return;
            }
            done = true;
            try {
                downstream.onComplete();
            } catch (Throwable downstreamError) {
                Operators.onErrorDropped(downstreamError, currentContext());
            }
        }

        @Override
        public void request(long count) {
            if (!done) {
                upstream.request(count);
            }
        }

        @Override
        public void cancel() {
            if (!done) {
                done = true;
                upstream.cancel();
            }
        }
    }

    private static final class ByteArrayOutputStream extends ManagedOutputStream {

        private final int bufferSize;

        private final DemandEmitter<byte[]> emitter;

        private final ReentrantLock lock = new ReentrantLock();

        private final AtomicBoolean cleanupRequested = new AtomicBoolean();

        private byte[] buffer;

        private int position;

        private volatile boolean closed;

        private ByteArrayOutputStream(int bufferSize, DemandEmitter<byte[]> emitter) {
            this.bufferSize = bufferSize;
            this.emitter = emitter;
        }

        @Override
        public void write(int value) throws IOException {
            ensureBlockingThread();
            byte[] full;
            lock.lock();
            try {
                ensureOpen();
                current()[position++] = (byte) value;
                full = detachIfFull();
            } finally {
                unlockWriter();
            }
            emit(full);
        }

        @Override
        public void write(byte[] source, int offset, int length) throws IOException {
            ensureBlockingThread();
            checkBounds(source, offset, length);
            while (length > 0) {
                byte[] full;
                lock.lock();
                try {
                    ensureOpen();
                    int copyLength = Math.min(length, bufferSize - position);
                    System.arraycopy(source, offset, current(), position, copyLength);
                    position += copyLength;
                    offset += copyLength;
                    length -= copyLength;
                    full = detachIfFull();
                } finally {
                    unlockWriter();
                }
                emit(full);
            }
        }

        @Override
        public void flush() throws IOException {
            ensureBlockingThread();
            emit(detachPartial());
        }

        @Override
        public void close() {
            ensureBlockingThread();
            lock.lock();
            try {
                closed = true;
            } finally {
                unlockWriter();
            }
        }

        @Override
        void finish() throws IOException {
            ensureBlockingThread();
            byte[] partial;
            lock.lock();
            try {
                closed = true;
                partial = position == 0 ? null : Arrays.copyOf(buffer, position);
                position = 0;
                buffer = null;
            } finally {
                unlockWriter();
            }
            emit(partial);
            emitter.complete();
        }

        @Override
        void cancel() {
            emitter.cancel();
            closed = true;
            // Never wait on a writer-owned lock from request/cancel threads. The writer retries
            // deferred cleanup immediately after leaving its short critical section.
            cleanupRequested.set(true);
            cleanupIfRequested();
        }

        private byte[] current() {
            if (buffer == null) {
                buffer = new byte[bufferSize];
            }
            return buffer;
        }

        private byte[] detachIfFull() {
            if (position != bufferSize) {
                return null;
            }
            byte[] full = buffer;
            buffer = null;
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
                unlockWriter();
            }
        }

        private void unlockWriter() {
            lock.unlock();
            cleanupIfRequested();
        }

        private void cleanupIfRequested() {
            if (!cleanupRequested.get() || !lock.tryLock()) {
                return;
            }
            try {
                if (cleanupRequested.compareAndSet(true, false)) {
                    position = 0;
                    buffer = null;
                }
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

        private final AtomicBoolean cleanupRequested = new AtomicBoolean();

        private ByteBuf buffer;

        private volatile boolean closed;

        private ByteBufOutputStream(int bufferSize,
                                    ByteBufAllocator allocator,
                                    DemandEmitter<ByteBuf> emitter) {
            this.bufferSize = bufferSize;
            this.allocator = allocator;
            this.emitter = emitter;
        }

        @Override
        public void write(int value) throws IOException {
            ensureBlockingThread();
            ByteBuf full;
            lock.lock();
            try {
                ensureOpen();
                current().writeByte(value);
                full = detachIfFull();
            } finally {
                unlockWriter();
            }
            emit(full);
        }

        @Override
        public void write(byte[] source, int offset, int length) throws IOException {
            ensureBlockingThread();
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
                    unlockWriter();
                }
                emit(full);
            }
        }

        @Override
        public void flush() throws IOException {
            ensureBlockingThread();
            emit(detachPartial());
        }

        @Override
        public void close() {
            ensureBlockingThread();
            lock.lock();
            try {
                closed = true;
            } finally {
                unlockWriter();
            }
        }

        @Override
        void finish() throws IOException {
            ensureBlockingThread();
            ByteBuf partial;
            lock.lock();
            try {
                closed = true;
                partial = buffer;
                buffer = null;
            } finally {
                unlockWriter();
            }
            emit(partial);
            emitter.complete();
        }

        @Override
        void cancel() {
            emitter.cancel();
            closed = true;
            // ByteBuf release is serialized with writes, but cancellation only uses tryLock. If a
            // write owns the lock, it observes this flag and performs the release after unlocking.
            cleanupRequested.set(true);
            cleanupIfRequested();
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
                unlockWriter();
            }
        }

        private void unlockWriter() {
            lock.unlock();
            cleanupIfRequested();
        }

        private void cleanupIfRequested() {
            if (!cleanupRequested.get() || !lock.tryLock()) {
                return;
            }
            ByteBuf discarded = null;
            try {
                if (cleanupRequested.compareAndSet(true, false)) {
                    discarded = buffer;
                    buffer = null;
                }
            } finally {
                lock.unlock();
            }
            ReferenceCountUtil.safeRelease(discarded);
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
