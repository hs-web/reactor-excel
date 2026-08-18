package org.hswebframework.reactor.excel.poi;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.hswebframework.reactor.excel.*;
import org.hswebframework.reactor.excel.context.Context;
import org.hswebframework.reactor.excel.poi.options.CellOption;
import org.hswebframework.reactor.excel.poi.options.RowOption;
import org.hswebframework.reactor.excel.poi.options.SheetOption;
import org.hswebframework.reactor.excel.poi.options.WorkbookOption;
import org.hswebframework.reactor.excel.spi.ExcelWriter;
import org.hswebframework.reactor.excel.utils.StreamUtils;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.core.scheduler.Schedulers;

import java.io.OutputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Apache POI based XLSX serializer.
 *
 * <p>POI workbook mutation, final serialization, and lifecycle cleanup are blocking operations and
 * are isolated on {@link Schedulers#boundedElastic()}. The writer uses a one-cell scheduler prefetch,
 * but XLSX bytes are available only after the workbook has been built, so byte demand does not
 * provide cell-level backpressure.</p>
 */
@Slf4j
public class PoiExcelWriter implements ExcelWriter {

    @Override
    public String[] getSupportFormat() {
        return new String[]{ExcelWriter.FORMAT_XLSX};
    }

    protected Workbook createWorkBook() {
        return new SXSSFWorkbook();
    }

    @SneakyThrows
    protected void writeAndClose(Workbook workbook, OutputStream stream) {
        try {
            workbook.write(stream);
            stream.flush();
        } catch (Throwable e) {
            closeQuietly(workbook, stream);
            log.error(e.getMessage(), e);
            throw e;
        }
        try {
            workbook.close();
        } finally {
            stream.close();
        }
    }

    private void closeQuietly(Workbook workbook, OutputStream stream) {
        StreamUtils.safeClose(workbook);
        StreamUtils.safeClose(stream);
    }

    private void handleWriteOption(Workbook workbook, Context context, Options... options) {
        for (Options opts : options) {
            opts.handleOptions(WorkbookOption.class, opt -> opt.workbook(workbook, context));
        }
    }

    private void handleWriteOption(Sheet sheet, Context context, Options... options) {
        for (Options opts : options) {
            opts.handleOptions(SheetOption.class, opt -> opt.sheet(sheet, context));
        }
    }

    private void handleWriteOption(Row row, Context context, Options... options) {
        for (Options opts : options) {
            opts.handleOptions(RowOption.class, opt -> opt.row(row, context));
        }
    }

    private void handleWriteOption(Cell poiCell, WritableCell cell, Context context, Options... options) {
        for (Options opts : options) {
            opts.handleOptions(CellOption.class, opt -> opt.cell(poiCell, cell, context));
        }
    }

    private void writeCell(Workbook workbook,
                           Context context,
                           Options opts,
                           WritableCell cell) {
        Sheet sheet;
        Options cellOpts = cell.options();
        try {
            sheet = workbook.getSheetAt(cell.getSheetIndex());
        } catch (IllegalArgumentException e) {
            sheet = workbook.createSheet();
            handleWriteOption(sheet, context, opts, cellOpts);
        }
        int rowIndex = (int) cell.getRowIndex();
        Row row = sheet.getRow(rowIndex);
        if (row == null) {
            row = sheet.createRow(rowIndex);
            handleWriteOption(row, context, opts, cellOpts);
        }
        Cell poiCell = row.getCell(cell.getColumnIndex());
        if (poiCell == null) {
            poiCell = row.createCell(cell.getColumnIndex());
        }
        wrapCell(poiCell, cell);
        handleWriteOption(poiCell, cell, context, opts, cellOpts);
    }

    static Comparator<WritableCell> comparator = Comparator
            .comparing(WritableCell::getSheetIndex)
            .thenComparing(WritableCell::getRowIndex)
            .thenComparing(WritableCell::getColumnIndex);

    @Override
    public Mono<Void> write(Flux<WritableCell> dataStream,
                            OutputStream outputStream,
                            ExcelOption... options) {
        return Mono.usingWhen(
            acquireWriteResource(outputStream, options),
            resource -> resource.write(dataStream),
            PoiWriteResource::closeAsync,
            (resource, error) -> resource.closeAsync(),
            PoiWriteResource::closeAsync
        );
    }

    private Mono<PoiWriteResource> acquireWriteResource(OutputStream outputStream,
                                                        ExcelOption... options) {
        return Mono.create(sink -> {
            PoiWriteResourceAcquisition acquisition =
                new PoiWriteResourceAcquisition(outputStream, options);
            sink.onCancel(acquisition::cancel);
            Schedulers
                .boundedElastic()
                .schedule(() -> acquisition.acquire(sink));
        });
    }

    @SneakyThrows
    private PoiWriteResource createWriteResource(OutputStream outputStream,
                                                 ExcelOption... options) {
        Context context = Context.create();
        Options opts = options.length > 0 ? Options.of(Arrays.asList(options)) : Options.empty();
        Workbook workbook = null;
        try {
            workbook = createWorkBook();
            handleWriteOption(workbook, context, opts);
            return new PoiWriteResource(workbook, outputStream, context, opts);
        } catch (Throwable error) {
            closeQuietly(workbook, outputStream);
            throw error;
        }
    }

    /**
     * Owns a newly created workbook until its MonoSink-to-usingWhen transfer is complete.
     * Cancellation changes state only; the bounded-elastic acquisition thread closes resources
     * when the transfer did not complete.
     */
    private final class PoiWriteResourceAcquisition {

        private static final int ACTIVE = 0;

        private static final int CANCELLED = 1;

        private static final int TRANSFERRED = 2;

        private final OutputStream outputStream;

        private final ExcelOption[] options;

        private final AtomicInteger state = new AtomicInteger(ACTIVE);

        private PoiWriteResourceAcquisition(OutputStream outputStream,
                                            ExcelOption[] options) {
            this.outputStream = outputStream;
            this.options = options;
        }

        private void acquire(MonoSink<PoiWriteResource> sink) {
            PoiWriteResource resource;
            try {
                resource = createWriteResource(outputStream, options);
            } catch (Throwable error) {
                Exceptions.throwIfFatal(error);
                if (state.compareAndSet(ACTIVE, TRANSFERRED)) {
                    sink.error(error);
                }
                return;
            }

            if (state.get() == CANCELLED) {
                resource.closeQuietly();
                return;
            }

            sink.success(resource);
            if (!state.compareAndSet(ACTIVE, TRANSFERRED)) {
                // Cancellation won before usingWhen completed the ownership transfer. This still
                // runs on the acquisition worker, so closing cannot block the cancelling thread.
                resource.closeQuietly();
            }
        }

        private void cancel() {
            state.compareAndSet(ACTIVE, CANCELLED);
        }
    }

    private final class PoiWriteResource {

        private final Workbook workbook;

        private final OutputStream outputStream;

        private final Context context;

        private final Options options;

        private final ReentrantLock lifecycleLock = new ReentrantLock();

        private boolean closed;

        private PoiWriteResource(Workbook workbook,
                                 OutputStream outputStream,
                                 Context context,
                                 Options options) {
            this.workbook = workbook;
            this.outputStream = outputStream;
            this.context = context;
            this.options = options;
        }

        private Mono<Void> write(Flux<WritableCell> dataStream) {
            // POI mutates workbook state and performs blocking file I/O. Prefetch one keeps the
            // scheduler handoff bounded while every POI callback stays off non-blocking threads.
            return dataStream
                .publishOn(Schedulers.boundedElastic(), 1)
                .doOnNext(this::writeCell)
                .then(Mono.<Void>fromRunnable(this::writeAndClose)
                          .subscribeOn(Schedulers.boundedElastic()));
        }

        private void writeCell(WritableCell cell) {
            lifecycleLock.lock();
            try {
                if (closed) {
                    throw new IllegalStateException("POI writer is closed");
                }
                PoiExcelWriter.this.writeCell(workbook, context, options, cell);
            } finally {
                lifecycleLock.unlock();
            }
        }

        private void writeAndClose() {
            lifecycleLock.lock();
            try {
                if (closed) {
                    return;
                }
                closed = true;
                PoiExcelWriter.this.writeAndClose(workbook, outputStream);
            } finally {
                lifecycleLock.unlock();
            }
        }

        private Mono<Void> closeAsync() {
            // Cancellation can originate from an event loop. Cleanup is scheduled before taking
            // the lifecycle lock, so cancel never blocks behind an in-flight POI operation.
            return Mono
                .<Void>fromRunnable(this::closeQuietly)
                .subscribeOn(Schedulers.boundedElastic());
        }

        private void closeQuietly() {
            lifecycleLock.lock();
            try {
                if (closed) {
                    return;
                }
                closed = true;
                PoiExcelWriter.this.closeQuietly(workbook, outputStream);
            } finally {
                lifecycleLock.unlock();
            }
        }
    }

    protected void wrapCell(Cell poiCell, WritableCell cell) {

        Object val = cell.value().orElse(null);
        if (val == null) {
            poiCell.setBlank();
            return;
        }

        switch (cell.getType()) {
            case BOOLEAN:
                poiCell.setCellValue((Boolean) val);
                break;
            case NUMBER:
                if (val instanceof Number) {
                    poiCell.setCellValue(((Number) val).doubleValue());
                    break;
                }
                poiCell.setCellValue(String.valueOf(val));
                break;
            case DATE_TIME:
                if (val instanceof Long) {
                    val = new Date((Long) val);
                }
                if (val instanceof Date) {
                    poiCell.setCellValue((Date) val);
                } else if (val instanceof LocalDate) {
                    poiCell.setCellValue((LocalDate) val);
                } else if (val instanceof LocalDateTime) {
                    poiCell.setCellValue((LocalDateTime) val);
                }
                poiCell.setCellValue(String.valueOf(val));
                break;
            case FORMULA:
                poiCell.setCellFormula(String.valueOf(val));
                break;
            default:
                poiCell.setCellValue(String.valueOf(val));
                break;
        }

    }

    @Override
    public boolean isSupportMultiSheet() {
        return true;
    }
}
