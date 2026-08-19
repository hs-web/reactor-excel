package org.hswebframework.reactor.excel.csv;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import lombok.SneakyThrows;
import org.apache.commons.csv.CSVFormat;
import org.hswebframework.reactor.excel.CellDataType;
import org.hswebframework.reactor.excel.ReactorExcel;
import org.hswebframework.reactor.excel.WritableCell;
import org.hswebframework.reactor.excel.utils.StreamUtils;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvWriterTest {


    @Test
    @SneakyThrows
    void testWrite() {

        FileOutputStream outputStream = new FileOutputStream("./target/test.csv");

        ReactorExcel
                .writer("csv")
                .header("id", "ID")
                .header("name", "name")
                .write(Flux.range(0, 1000)
                           .map(i -> new HashMap<String, Object>() {{
                               put("id", i);
                               put("name", "test-中文" + i);
                           }}), outputStream)
                .as(StepVerifier::create)
                .expectComplete()
                .verify();
        outputStream.close();

        ReactorExcel
                .readToMap(new FileInputStream("./target/test.csv"),"csv")
                .doOnNext(System.out::println)
                .as(StepVerifier::create)
                .expectNextCount(1000)
                .verifyComplete();

    }

    @Test
    @SneakyThrows
    void testWriteSpec() {

        ReactorExcel
                .writeFor("csv")
                .justWrite()
                .sheet(spec -> {
                    spec.header("id", "ID")
                        .header("name", "name")
                        .rows(
                                Flux.range(0, 1000)
                                    .map(i -> new HashMap<String, Object>() {{
                                        put("id", i);
                                        put("name", "test" + i);
                                    }})
                        );
                })
                .write(new FileOutputStream("./target/test.csv"))
                .as(StepVerifier::create)
                .expectComplete()
                .verify();

    }

    @Test
    void byteBufWriteShouldAggregateCellsWithoutRequestingAheadOfOutputDemand() {
        AtomicLong sourceRequests = new AtomicLong();
        Flux<WritableCell> source = Flux
            .just(
                cell(0, "first", false),
                cell(1, "second", true)
            )
            .doOnRequest(sourceRequests::addAndGet);

        Flux<ByteBuf> output = new CsvWriter().write(
            source,
            UnpooledByteBufAllocator.DEFAULT,
            64,
            new CharsetOption(StandardCharsets.UTF_8)
        );

        StepVerifier.create(output, 0)
                    .thenRequest(1)
                    .assertNext(buffer -> {
                        assertArrayEquals(
                            new byte[]{(byte) 0xef, (byte) 0xbb, (byte) 0xbf},
                            StreamUtils.releaseToByteArray(buffer)
                        );
                    })
                    .then(() -> assertEquals(0, sourceRequests.get()))
                    .thenRequest(1)
                    .assertNext(buffer -> {
                        assertEquals(2, sourceRequests.get());
                        assertEquals("first,second\r\n", new String(
                            StreamUtils.releaseToByteArray(buffer),
                            StandardCharsets.UTF_8
                        ));
                    })
                    .expectComplete()
                    .verify();
    }

    @Test
    void byteBufWriteShouldPreserveCsvFormatCharsetAndOwnership() {
        Flux<WritableCell> source = Flux.just(
            cell(0, "a,b", false),
            cell(1, "line\nx", true)
        );

        byte[] bytes = new CsvWriter()
            .write(
                source,
                UnpooledByteBufAllocator.DEFAULT,
                3,
                FormatOption.of(CSVFormat.EXCEL),
                new CharsetOption(StandardCharsets.UTF_8)
            )
            .map(buffer -> {
                assertTrue(buffer.refCnt() > 0);
                return StreamUtils.releaseToByteArray(buffer);
            })
            .reduce(new byte[0], CsvWriterTest::concat)
            .block();

        assertEquals("\ufeff\"a,b\",\"line\nx\"\r\n", new String(bytes, StandardCharsets.UTF_8));
    }

    @Test
    void bothWritePathsShouldEmitExactlyOneUtf16Bom() {
        CsvWriter writer = new CsvWriter();
        Flux<WritableCell> source = Flux.just(
            cell(0, "first", false),
            cell(1, "second", true)
        );
        CharsetOption charset = new CharsetOption(StandardCharsets.UTF_16);
        byte[] expected = "first,second\r\n".getBytes(StandardCharsets.UTF_16);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        writer.write(source, output, charset).block();
        byte[] byteBufOutput = writer
            .write(source, UnpooledByteBufAllocator.DEFAULT, 3, charset)
            .map(StreamUtils::releaseToByteArray)
            .reduce(new byte[0], CsvWriterTest::concat)
            .block();

        assertArrayEquals(expected, output.toByteArray());
        assertArrayEquals(expected, byteBufOutput);
    }

    @Test
    void nonUnicodeCharsetShouldNotReceiveReplacementBomBytes() {
        CsvWriter writer = new CsvWriter();
        Charset gbk = Charset.forName("GBK");
        CharsetOption charset = new CharsetOption(gbk);
        Flux<WritableCell> source = Flux.just(cell(0, "中文", true));
        byte[] expected = "中文\r\n".getBytes(gbk);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        writer.write(source, output, charset).block();
        byte[] byteBufOutput = writer
            .write(source, UnpooledByteBufAllocator.DEFAULT, 3, charset)
            .map(StreamUtils::releaseToByteArray)
            .reduce(new byte[0], CsvWriterTest::concat)
            .block();

        assertArrayEquals(expected, output.toByteArray());
        assertArrayEquals(expected, byteBufOutput);
    }

    @Test
    void writerOperatorByteBufWriteShouldUseNativeCsvPath() {
        byte[] bytes = ReactorExcel
            .writer("csv")
            .header("name", "Name")
            .option(new CharsetOption(StandardCharsets.UTF_8))
            .writeByteBufs(Flux.just(new HashMap<String, Object>() {{
                put("name", "value");
            }}), 64)
            .map(StreamUtils::releaseToByteArray)
            .reduce(new byte[0], CsvWriterTest::concat)
            .block();

        assertEquals("\ufeffName\r\nvalue\r\n", new String(bytes, StandardCharsets.UTF_8));
    }

    private static WritableCell cell(int column, String value, boolean endOfRow) {
        return WritableCell.of(
            0,
            0,
            column,
            CellDataType.STRING,
            value,
            endOfRow
        );
    }

    private static byte[] concat(byte[] left, byte[] right) {
        byte[] result = new byte[left.length + right.length];
        System.arraycopy(left, 0, result, 0, left.length);
        System.arraycopy(right, 0, result, left.length, right.length);
        return result;
    }
}
