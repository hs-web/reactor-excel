package org.hswebframework.reactor.excel.poi;

import lombok.SneakyThrows;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.hswebframework.reactor.excel.ReactorExcel;
import org.hswebframework.reactor.excel.utils.StreamUtils;
import org.hswebframework.reactor.excel.poi.options.AddNormalPullDownSheetOption;
import org.hswebframework.reactor.excel.poi.options.PoiWriteOptions;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PoiExcelWriterTest {

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

    private static byte[] concat(byte[] left, byte[] right) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(left.length + right.length);
        output.write(left, 0, left.length);
        output.write(right, 0, right.length);
        return output.toByteArray();
    }
}
