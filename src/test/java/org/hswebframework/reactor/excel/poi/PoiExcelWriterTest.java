package org.hswebframework.reactor.excel.poi;

import lombok.SneakyThrows;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.util.ReferenceCountUtil;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.hswebframework.reactor.excel.BlockHoundTestSupport;
import org.hswebframework.reactor.excel.BlockingSchedulerOption;
import org.hswebframework.reactor.excel.CellDataType;
import org.hswebframework.reactor.excel.ReactorExcel;
import org.hswebframework.reactor.excel.WritableCell;
import org.hswebframework.reactor.excel.utils.StreamUtils;
import org.hswebframework.reactor.excel.poi.options.AddNormalPullDownSheetOption;
import org.hswebframework.reactor.excel.poi.options.PoiWriteOptions;
import org.hswebframework.reactor.excel.poi.options.WorkbookOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PoiExcelWriterTest {

    @BeforeAll
    static void installBlockHound() {
        BlockHoundTestSupport.install();
    }

    @Test
    @SneakyThrows
    void testWrite() {

        ReactorExcel
                .writer("xlsx")
                .header("id", "ID")
                .header("name", "name").header("a", "a")
                .write(Flux.range(0, 10000)
                           .publishOn(Schedulers.boundedElastic())
                           .map(i -> new HashMap<String, Object>() {{
                               put("id", ThreadLocalRandom.current().nextLong(Long.MAX_VALUE - 100000, Long.MAX_VALUE));
                               put("name", "test" + i);
                               put("a", null);
                           }})
                        , Files.newOutputStream(Paths.get("./target/test.xlsx")))
                .as(StepVerifier::create)
                .expectComplete()
                .verify();

        Thread.sleep(1000);
    }

    @Test
    @SneakyThrows
    void testWriteMultiSheet() {

        Flux<Map<String, Object>> dataStream = Flux
                .range(0, 10000)
                .map(i -> new HashMap<String, Object>() {{
                    put("id", i);
                    put("name", "test" + i);
                    put("a", null);
                }});

        ReactorExcel
                .xlsxWriter()
                .multiSheet()
                .sheet(spec -> spec
                        .name("S1")
                        .header("id", "ID")
                        .header("name", "姓名")
                        .rows(dataStream))
                .sheet(spec -> spec
                        .name("S2")
                        .firstRowIndex(1)
                        .header("id", "ID")
                        .header("name", "姓名")
                        .rows(dataStream)
                        .cell(0, 0, "大标题")
                        .option(sheet -> {
                            CellStyle style = sheet.getWorkbook().createCellStyle();

                            style.setAlignment(HorizontalAlignment.CENTER);
                            style.setVerticalAlignment(VerticalAlignment.CENTER);

                            sheet.createRow(0);
                            sheet.addMergedRegion(CellRangeAddress.valueOf("A1:B1"));
                            sheet.getRow(0).createCell(0).setCellStyle(style);

                        }))
                .sheet(spec -> spec
                        .cell(0, 0, "NameA")
                        .cell(1, 0, "Age")
                        .cell(0, 1, "Test")
                        .cell(1, 1, 1)
                        .option(sheet -> {
                            sheet.addMergedRegion(CellRangeAddress.valueOf("A3:B3"));
                            sheet.addMergedRegion(CellRangeAddress.valueOf("C1:C3"));
                        }))
                .write(Files.newOutputStream(Paths.get("./target/test.xlsx")))
                .as(StepVerifier::create)
                .expectComplete()
                .verify();

        Thread.sleep(1000);
    }

    @Test
    @SneakyThrows
    void testAddValidation() {

        List<String> collect = IntStream
                .range(0, 1000)
                .boxed()
                .map(String::valueOf)
                .collect(Collectors.toList());


        ReactorExcel
                .writer("xlsx")
                .header("id", "ID")
                .header("name", "名称")
                .options(PoiWriteOptions
                                 .addNormalPullDownSheet(0, 1, AddNormalPullDownSheetOption.MAX_ROW, 1, 1, collect.toArray(new String[0])))
                .write(Flux.empty()
                        , Files.newOutputStream(Paths.get("./target/addValidation.xlsx")))
                .as(StepVerifier::create)
                .expectComplete()
                .verify();

        Thread.sleep(1000);
    }

    @Test
    @SneakyThrows
    void testByteBufWrite() {
        byte[] bytes = ReactorExcel
            .writer("xlsx")
            .header("id", "ID")
            .writeByteBufs(
                Flux.just(new HashMap<String, Object>() {{
                    put("id", 1);
                }}),
                1024
            )
            .map(StreamUtils::releaseToByteArray)
            .reduce(new byte[0], PoiExcelWriterTest::concat)
            .block();

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            assertEquals("ID", workbook.getSheetAt(0).getRow(0).getCell(0).getStringCellValue());
            assertEquals("1", workbook.getSheetAt(0).getRow(1).getCell(0).getStringCellValue());
        }
    }

    @Test
    void blockingWorkbookWriteShouldStayOffAsyncSourceThread() {
        AtomicBoolean sourceWasNonBlocking = new AtomicBoolean();
        AtomicBoolean cellMutationWasNonBlocking = new AtomicBoolean(true);
        AtomicBoolean workbookWriteWasNonBlocking = new AtomicBoolean(true);
        AtomicReference<String> cellThread = new AtomicReference<>();
        AtomicReference<String> writeThread = new AtomicReference<>();
        Scheduler scheduler = Schedulers.newBoundedElastic(1, 16, "poi-dedicated");
        PoiExcelWriter writer = new PoiExcelWriter() {
            @Override
            protected void wrapCell(Cell poiCell, WritableCell cell) {
                cellMutationWasNonBlocking.set(Schedulers.isInNonBlockingThread());
                cellThread.set(Thread.currentThread().getName());
                super.wrapCell(poiCell, cell);
            }

            @Override
            protected void writeAndClose(Workbook workbook, java.io.OutputStream stream) {
                workbookWriteWasNonBlocking.set(Schedulers.isInNonBlockingThread());
                writeThread.set(Thread.currentThread().getName());
                super.writeAndClose(workbook, stream);
            }
        };
        Flux<WritableCell> source = Mono
            .delay(java.time.Duration.ofMillis(10))
            .map(ignore -> {
                sourceWasNonBlocking.set(Schedulers.isInNonBlockingThread());
                return WritableCell.of(0, 0, 0, CellDataType.STRING, "value", true);
            })
            .flux();

        try {
            StepVerifier
                .create(writer.write(
                    source,
                    UnpooledByteBufAllocator.DEFAULT,
                    1024,
                    BlockingSchedulerOption.of(scheduler)
                ))
                .thenConsumeWhile(buffer -> {
                    ReferenceCountUtil.safeRelease(buffer);
                    return true;
                })
                .expectComplete()
                .verify(java.time.Duration.ofSeconds(10));
        } finally {
            scheduler.dispose();
        }

        assertTrue(sourceWasNonBlocking.get(), "fixture did not use an async non-blocking source");
        assertFalse(cellMutationWasNonBlocking.get(),
                    "POI cell mutation ran on a Reactor non-blocking thread");
        assertFalse(workbookWriteWasNonBlocking.get(),
                    "POI workbook.write ran on a Reactor non-blocking thread");
        assertTrue(cellThread.get().startsWith("poi-dedicated-"));
        assertTrue(writeThread.get().startsWith("poi-dedicated-"));
    }

    @Test
    void sourceFailureShouldUseCleanupHookWithoutSerializingWorkbook() {
        IllegalStateException expected = new IllegalStateException("source failed");
        AtomicInteger successfulWrites = new AtomicInteger();
        AtomicInteger errorCleanups = new AtomicInteger();
        PoiExcelWriter writer = new PoiExcelWriter() {
            @Override
            protected void writeAndClose(Workbook workbook, OutputStream stream) {
                successfulWrites.incrementAndGet();
                super.writeAndClose(workbook, stream);
            }

            @Override
            protected void closeResources(Workbook workbook, OutputStream stream) {
                errorCleanups.incrementAndGet();
                super.closeResources(workbook, stream);
            }
        };

        StepVerifier
            .create(writer.write(Flux.error(expected), new ByteArrayOutputStream()))
            .expectErrorMatches(error -> error == expected)
            .verify(java.time.Duration.ofSeconds(5));

        assertEquals(0, successfulWrites.get(), "source failure serialized a partial workbook");
        assertEquals(1, errorCleanups.get(), "source failure bypassed the cleanup hook");
    }

    @Test
    void cancellationShouldScheduleBlockingCleanup() throws InterruptedException {
        CountDownLatch sourceSubscribed = new CountDownLatch(1);
        CountDownLatch closeEntered = new CountDownLatch(1);
        CountDownLatch releaseClose = new CountDownLatch(1);
        CountDownLatch cancellationFinished = new CountDownLatch(1);
        AtomicBoolean closeWasNonBlocking = new AtomicBoolean(true);
        AtomicReference<Throwable> cancellationError = new AtomicReference<>();
        OutputStream output = new OutputStream() {
            @Override
            public void write(int value) {
            }

            @Override
            public void close() throws IOException {
                closeWasNonBlocking.set(Schedulers.isInNonBlockingThread());
                closeEntered.countDown();
                try {
                    if (!releaseClose.await(5, TimeUnit.SECONDS)) {
                        throw new IOException("timed out waiting to release test output");
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException(error);
                }
            }
        };
        reactor.core.Disposable write = new PoiExcelWriter()
            .write(Flux.<WritableCell>never().doOnSubscribe(ignore -> sourceSubscribed.countDown()), output)
            .subscribe();

        assertTrue(sourceSubscribed.await(5, TimeUnit.SECONDS), "POI source was not subscribed");
        Mono
            .fromRunnable(write::dispose)
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
                       "cancellation waited for blocking POI cleanup");
            assertTrue(closeEntered.await(5, TimeUnit.SECONDS), "output cleanup did not start");
            assertFalse(closeWasNonBlocking.get(), "output cleanup ran on a non-blocking thread");
            assertNull(cancellationError.get());
        } finally {
            releaseClose.countDown();
        }
    }

    @Test
    void cancellationDuringResourceAcquisitionShouldCloseDiscardedResource()
        throws InterruptedException {
        CountDownLatch workbookCreated = new CountDownLatch(1);
        CountDownLatch releaseCreation = new CountDownLatch(1);
        CountDownLatch outputClosed = new CountDownLatch(1);
        PoiExcelWriter writer = new PoiExcelWriter() {
            @Override
            protected Workbook createWorkBook() {
                Workbook workbook = super.createWorkBook();
                workbookCreated.countDown();
                awaitUninterruptibly(releaseCreation);
                return workbook;
            }
        };
        OutputStream output = new OutputStream() {
            @Override
            public void write(int value) {
            }

            @Override
            public void close() {
                outputClosed.countDown();
            }
        };
        reactor.core.Disposable write = writer
            .write(Flux.<WritableCell>never(), output)
            .subscribe();

        assertTrue(workbookCreated.await(5, TimeUnit.SECONDS), "POI workbook was not created");
        write.dispose();
        releaseCreation.countDown();

        assertTrue(outputClosed.await(5, TimeUnit.SECONDS),
                   "resource discarded during acquisition was not closed");
    }

    @Test
    void resourceCreationFailureShouldCloseOutput() {
        AtomicBoolean outputClosed = new AtomicBoolean();
        OutputStream output = new OutputStream() {
            @Override
            public void write(int value) {
            }

            @Override
            public void close() {
                outputClosed.set(true);
            }
        };
        PoiExcelWriter writer = new PoiExcelWriter() {
            @Override
            protected Workbook createWorkBook() {
                throw new IllegalStateException("workbook creation failed");
            }
        };

        StepVerifier
            .create(writer.write(Flux.empty(), output))
            .expectErrorMatches(error -> error instanceof IllegalStateException
                && "workbook creation failed".equals(error.getMessage()))
            .verify(java.time.Duration.ofSeconds(5));

        assertTrue(outputClosed.get(), "resource creation failure did not close the output");
    }

    @Test
    void sourceFailureShouldCloseWorkbookAndOutputExactlyOnce() {
        IllegalStateException expected = new IllegalStateException("source failed");
        AtomicReference<TrackingWorkbook> workbook = new AtomicReference<>();
        TrackingOutputStream output = new TrackingOutputStream(null);
        PoiExcelWriter writer = trackingWriter(workbook, null);

        StepVerifier
            .create(writer.write(Flux.<WritableCell>error(expected), output))
            .expectErrorMatches(error -> error == expected)
            .verify(java.time.Duration.ofSeconds(5));

        assertEquals(1, workbook.get().closeCount(), "source failure closed workbook more than once");
        assertEquals(1, output.closeCount(), "source failure closed output more than once");
    }

    @Test
    void workbookOptionFailureShouldCloseCreatedResourcesExactlyOnce() {
        IllegalStateException expected = new IllegalStateException("workbook option failed");
        AtomicReference<TrackingWorkbook> workbook = new AtomicReference<>();
        TrackingOutputStream output = new TrackingOutputStream(null);
        PoiExcelWriter writer = trackingWriter(workbook, null);

        StepVerifier
            .create(writer.write(
                Flux.empty(),
                output,
                WorkbookOption.of(ignored -> {
                    throw expected;
                })
            ))
            .expectErrorMatches(error -> error == expected)
            .verify(java.time.Duration.ofSeconds(5));

        assertEquals(1, workbook.get().closeCount(), "option failure closed workbook more than once");
        assertEquals(1, output.closeCount(), "option failure closed output more than once");
    }

    @Test
    void cellMutationFailureShouldCloseWorkbookAndOutputExactlyOnce() {
        IllegalStateException expected = new IllegalStateException("cell mutation failed");
        AtomicReference<TrackingWorkbook> workbook = new AtomicReference<>();
        TrackingOutputStream output = new TrackingOutputStream(null);
        PoiExcelWriter writer = trackingWriter(workbook, expected);

        StepVerifier
            .create(writer.write(
                Flux.just(WritableCell.of(0, 0, 0, CellDataType.STRING, "value", true)),
                output
            ))
            .expectErrorMatches(error -> error == expected)
            .verify(java.time.Duration.ofSeconds(5));

        assertEquals(1, workbook.get().closeCount(), "cell failure closed workbook more than once");
        assertEquals(1, output.closeCount(), "cell failure closed output more than once");
    }

    @Test
    void workbookWriteFailureShouldPropagateOriginalErrorAndCloseResourcesExactlyOnce() {
        IOException expected = new IOException("output failed");
        AtomicReference<TrackingWorkbook> workbook = new AtomicReference<>();
        TrackingOutputStream output = new TrackingOutputStream(expected);
        PoiExcelWriter writer = trackingWriter(workbook, null);

        StepVerifier
            .create(writer.write(Flux.empty(), output))
            .expectErrorMatches(error -> error == expected)
            .verify(java.time.Duration.ofSeconds(5));

        assertEquals(1, workbook.get().closeCount(), "write failure closed workbook more than once");
        assertEquals(1, output.closeCount(), "write failure closed output more than once");
    }

    @Test
    void cancellationDuringCellMutationShouldNotWaitForLifecycleLock()
        throws InterruptedException {
        CountDownLatch cellMutationEntered = new CountDownLatch(1);
        CountDownLatch releaseCellMutation = new CountDownLatch(1);
        CountDownLatch cancellationFinished = new CountDownLatch(1);
        AtomicReference<TrackingWorkbook> workbook = new AtomicReference<>();
        TrackingOutputStream output = new TrackingOutputStream(null);
        PoiExcelWriter writer = new PoiExcelWriter() {
            @Override
            protected Workbook createWorkBook() {
                TrackingWorkbook created = new TrackingWorkbook();
                workbook.set(created);
                return created;
            }

            @Override
            protected void wrapCell(Cell poiCell, WritableCell cell) {
                cellMutationEntered.countDown();
                awaitUninterruptibly(releaseCellMutation);
                super.wrapCell(poiCell, cell);
            }
        };
        reactor.core.Disposable write = writer
            .write(
                Flux.just(WritableCell.of(0, 0, 0, CellDataType.STRING, "value", true)),
                output
            )
            .subscribe();

        assertTrue(cellMutationEntered.await(5, TimeUnit.SECONDS), "cell mutation did not start");
        Mono
            .fromRunnable(write::dispose)
            .subscribeOn(Schedulers.parallel())
            .doFinally(ignored -> cancellationFinished.countDown())
            .subscribe();

        try {
            assertTrue(cancellationFinished.await(2, TimeUnit.SECONDS),
                       "cancellation waited for the in-flight cell mutation");
        } finally {
            releaseCellMutation.countDown();
        }
        assertTrue(output.awaitClose(), "cancelled writer did not close its output");
        assertEquals(1, workbook.get().closeCount(), "cancellation closed workbook more than once");
        assertEquals(1, output.closeCount(), "cancellation closed output more than once");
    }

    private static PoiExcelWriter trackingWriter(AtomicReference<TrackingWorkbook> workbook,
                                                 RuntimeException cellFailure) {
        return new PoiExcelWriter() {
            @Override
            protected Workbook createWorkBook() {
                TrackingWorkbook created = new TrackingWorkbook();
                workbook.set(created);
                return created;
            }

            @Override
            protected void wrapCell(Cell poiCell, WritableCell cell) {
                if (cellFailure != null) {
                    throw cellFailure;
                }
                super.wrapCell(poiCell, cell);
            }
        };
    }

    private static final class TrackingWorkbook extends SXSSFWorkbook {

        private final AtomicInteger closeCount = new AtomicInteger();

        @Override
        public void close() throws IOException {
            closeCount.incrementAndGet();
            super.close();
        }

        private int closeCount() {
            return closeCount.get();
        }
    }

    private static final class TrackingOutputStream extends OutputStream {

        private final IOException writeFailure;

        private final ByteArrayOutputStream delegate = new ByteArrayOutputStream();

        private final AtomicInteger closeCount = new AtomicInteger();

        private final CountDownLatch closed = new CountDownLatch(1);

        private TrackingOutputStream(IOException writeFailure) {
            this.writeFailure = writeFailure;
        }

        @Override
        public void write(int value) throws IOException {
            failIfConfigured();
            delegate.write(value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            failIfConfigured();
            delegate.write(bytes, offset, length);
        }

        @Override
        public void close() {
            closeCount.incrementAndGet();
            closed.countDown();
        }

        private void failIfConfigured() throws IOException {
            if (writeFailure != null) {
                throw writeFailure;
            }
        }

        private int closeCount() {
            return closeCount.get();
        }

        private boolean awaitClose() throws InterruptedException {
            return closed.await(5, TimeUnit.SECONDS);
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        for (; ; ) {
            try {
                latch.await();
                break;
            } catch (InterruptedException error) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static byte[] concat(byte[] left, byte[] right) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(left.length + right.length);
        output.write(left, 0, left.length);
        output.write(right, 0, right.length);
        return output.toByteArray();
    }
}
