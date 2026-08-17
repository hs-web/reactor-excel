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
import reactor.util.context.Context;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Incremental CSV encoder that couples cell requests to ByteBuf demand.
 *
 * <p>Each subscription keeps one continuous charset encoder, so stateful encodings are not reset
 * between cells. At most one encoded cell and the encoder's bounded final bytes are retained until
 * downstream demand arrives.</p>
 */
final class CsvByteBufFlux extends Flux<ByteBuf> {

    private final Flux<WritableCell> source;

    private final ByteBufAllocator allocator;

    private final int bufferSize;

    private final CSVFormat format;

    private final Charset charset;

    CsvByteBufFlux(Flux<WritableCell> source,
                   ByteBufAllocator allocator,
                   int bufferSize,
                   CSVFormat format,
                   Charset charset) {
        this.source = Objects.requireNonNull(source, "source");
        this.allocator = Objects.requireNonNull(allocator, "allocator");
        if (bufferSize <= 0) {
            throw new IllegalArgumentException("bufferSize must be greater than zero");
        }
        this.bufferSize = bufferSize;
        this.format = Objects.requireNonNull(format, "format");
        this.charset = Objects.requireNonNull(charset, "charset");
    }

    @Override
    public void subscribe(CoreSubscriber<? super ByteBuf> actual) {
        final CsvSubscription subscription;
        try {
            subscription = new CsvSubscription(actual, allocator, bufferSize, format, charset);
        } catch (Throwable error) {
            actual.onSubscribe(EmptySubscription.INSTANCE);
            actual.onError(error);
            return;
        }

        actual.onSubscribe(subscription);
        if (subscription.isCancelled()) {
            return;
        }
        try {
            source.subscribe(subscription);
        } catch (Throwable error) {
            subscription.onError(error);
        }
    }

    private enum EmptySubscription implements Subscription {
        INSTANCE;

        @Override
        public void request(long count) {
            // The subscription has already failed during initialization.
        }

        @Override
        public void cancel() {
            // The subscription has already failed during initialization.
        }
    }

    private static final class CsvSubscription implements CoreSubscriber<WritableCell>, Subscription {

        private final CoreSubscriber<? super ByteBuf> downstream;

        private final ByteBufAllocator allocator;

        private final int bufferSize;

        private final ReentrantLock lock = new ReentrantLock();

        private final AtomicInteger wip = new AtomicInteger();

        private final ByteArrayOutputStream encoded = new ByteArrayOutputStream();

        private final CSVPrinter printer;

        private Subscription upstream;

        private byte[] pending;

        private int pendingOffset;

        private long requested;

        private boolean sourceRequested;

        private boolean done;

        private boolean cancelled;

        private boolean terminated;

        private Throwable error;

        private CsvSubscription(CoreSubscriber<? super ByteBuf> downstream,
                                ByteBufAllocator allocator,
                                int bufferSize,
                                CSVFormat format,
                                Charset charset) throws IOException {
            this.downstream = Objects.requireNonNull(downstream, "downstream");
            this.allocator = allocator;
            this.bufferSize = bufferSize;
            byte[] bom = "\ufeff".getBytes(charset);
            encoded.write(bom, 0, bom.length);
            this.printer = new CSVPrinter(new OutputStreamWriter(encoded, charset), format);
            printer.flush();
            this.pending = takeEncoded();
        }

        @Override
        public Context currentContext() {
            return downstream.currentContext();
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            Objects.requireNonNull(subscription, "subscription");
            boolean reject;
            lock.lock();
            try {
                reject = upstream != null || cancelled || done;
                if (!reject) {
                    upstream = subscription;
                }
            } finally {
                lock.unlock();
            }
            if (reject) {
                subscription.cancel();
                return;
            }
            drain();
        }

        @Override
        public void onNext(WritableCell cell) {
            if (cell == null) {
                onError(new NullPointerException("CSV source emitted null cell"));
                return;
            }

            Subscription subscription = null;
            lock.lock();
            try {
                if (cancelled || done) {
                    return;
                }
                sourceRequested = false;
                try {
                    printer.print(cell.valueAsText().orElse(""));
                    if (cell.isEndOfRow()) {
                        printer.println();
                    }
                    printer.flush();
                    pending = takeEncoded();
                    pendingOffset = 0;
                } catch (Throwable error) {
                    pending = null;
                    pendingOffset = 0;
                    done = true;
                    this.error = error;
                    subscription = upstream;
                }
            } finally {
                lock.unlock();
            }
            if (subscription != null) {
                subscription.cancel();
            }
            drain();
        }

        @Override
        public void onError(Throwable error) {
            Objects.requireNonNull(error, "error");
            lock.lock();
            try {
                if (cancelled || done) {
                    return;
                }
                sourceRequested = false;
                pending = null;
                pendingOffset = 0;
                done = true;
                this.error = error;
            } finally {
                lock.unlock();
            }
            drain();
        }

        @Override
        public void onComplete() {
            lock.lock();
            try {
                if (cancelled || done) {
                    return;
                }
                sourceRequested = false;
                try {
                    printer.close();
                    appendPending(takeEncoded());
                } catch (Throwable error) {
                    this.error = error;
                }
                done = true;
            } finally {
                lock.unlock();
            }
            drain();
        }

        @Override
        public void request(long count) {
            if (count <= 0) {
                fail(new IllegalArgumentException("request amount must be greater than zero"));
                return;
            }
            lock.lock();
            try {
                if (cancelled || terminated) {
                    return;
                }
                requested = addCap(requested, count);
            } finally {
                lock.unlock();
            }
            drain();
        }

        @Override
        public void cancel() {
            Subscription subscription;
            lock.lock();
            try {
                if (cancelled) {
                    return;
                }
                cancelled = true;
                pending = null;
                pendingOffset = 0;
                sourceRequested = false;
                subscription = upstream;
            } finally {
                lock.unlock();
            }
            if (subscription != null) {
                subscription.cancel();
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

        private void fail(Throwable error) {
            Subscription subscription;
            lock.lock();
            try {
                if (cancelled || terminated) {
                    return;
                }
                pending = null;
                pendingOffset = 0;
                sourceRequested = false;
                done = true;
                this.error = error;
                subscription = upstream;
            } finally {
                lock.unlock();
            }
            if (subscription != null) {
                subscription.cancel();
            }
            drain();
        }

        private void drain() {
            if (wip.getAndIncrement() != 0) {
                return;
            }

            int missed = 1;
            for (; ; ) {
                for (; ; ) {
                    Subscription subscription = null;
                    lock.lock();
                    try {
                        if (cancelled || terminated) {
                            break;
                        }
                        if (requested > 0 && hasPending()) {
                            final ByteBuf buffer;
                            try {
                                buffer = takeChunk();
                            } catch (Throwable allocationError) {
                                pending = null;
                                pendingOffset = 0;
                                sourceRequested = false;
                                done = true;
                                error = allocationError;
                                if (upstream != null) {
                                    upstream.cancel();
                                }
                                continue;
                            }
                            if (requested != Long.MAX_VALUE) {
                                requested--;
                            }
                            try {
                                downstream.onNext(buffer);
                            } catch (Throwable error) {
                                ReferenceCountUtil.safeRelease(buffer);
                                cancelled = true;
                                sourceRequested = false;
                                if (upstream != null) {
                                    upstream.cancel();
                                }
                            }
                            continue;
                        }
                        if (done) {
                            terminated = true;
                            if (error == null) {
                                downstream.onComplete();
                            } else {
                                downstream.onError(error);
                            }
                            break;
                        }
                        if (requested > 0 && !sourceRequested && upstream != null) {
                            sourceRequested = true;
                            subscription = upstream;
                        } else {
                            break;
                        }
                    } finally {
                        lock.unlock();
                    }
                    if (subscription != null) {
                        try {
                            subscription.request(1);
                        } catch (Throwable error) {
                            onError(error);
                        }
                    }
                }
                missed = wip.addAndGet(-missed);
                if (missed == 0) {
                    return;
                }
            }
        }

        private boolean hasPending() {
            return pending != null && pendingOffset < pending.length;
        }

        private ByteBuf takeChunk() {
            int length = Math.min(bufferSize, pending.length - pendingOffset);
            ByteBuf buffer = allocator.buffer(length, length);
            buffer.writeBytes(pending, pendingOffset, length);
            pendingOffset += length;
            if (pendingOffset == pending.length) {
                pending = null;
                pendingOffset = 0;
            }
            return buffer;
        }

        private byte[] takeEncoded() {
            if (encoded.size() == 0) {
                return null;
            }
            byte[] bytes = encoded.toByteArray();
            encoded.reset();
            return bytes;
        }

        private void appendPending(byte[] suffix) {
            if (suffix == null) {
                return;
            }
            if (!hasPending()) {
                pending = suffix;
                pendingOffset = 0;
                return;
            }
            int remaining = pending.length - pendingOffset;
            byte[] combined = new byte[remaining + suffix.length];
            System.arraycopy(pending, pendingOffset, combined, 0, remaining);
            System.arraycopy(suffix, 0, combined, remaining, suffix.length);
            pending = combined;
            pendingOffset = 0;
        }

        private static long addCap(long current, long increment) {
            long updated = current + increment;
            return updated < 0 ? Long.MAX_VALUE : updated;
        }
    }
}
