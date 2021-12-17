package org.hswebframework.reactor.excel.poi;

import lombok.SneakyThrows;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.hswebframework.reactor.excel.ReactorExcel;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.io.FileOutputStream;
import java.util.HashMap;

class PoiExcelWriterTest {

    @Test
    @SneakyThrows
    void testWrite() {

        ReactorExcel
                .writer("xlsx")
                .header("id", "ID")
                .header("name", "name").header("a", "a")
                .write(Flux.range(0, 10000)
                           .map(i -> new HashMap<String, Object>() {{
                               put("id", i);
                               put("name", "test" + i);
                               put("a", null);
                           }}), new FileOutputStream("./target/test.xlsx"))
                .as(StepVerifier::create)
                .expectComplete()
                .verify();

        Thread.sleep(1000);
    }

    @Test
    @SneakyThrows
    void testWriteMultiSheet() {
        ReactorExcel
                .xlsxWriter()
                .multiSheet()
                .sheet(sheet -> {
                    sheet.name("S1")
                         .header("id", "ID")
                         .header("name", "姓名")
                         .rows(Flux.range(0, 1000)
                                   .map(i -> new HashMap<String, Object>() {{
                                       put("id", i);
                                       put("name", "test" + i);
                                       put("a", null);
                                   }}));
                })
                .sheet(sheet -> {
                    sheet.name("S2")
                         .firstRowIndex(1)
                         .header("id", "ID")
                         .header("name", "姓名")
                         .rows(Flux.range(0, 1000)
                                   .map(i -> new HashMap<String, Object>() {{
                                       put("id", "s2:" + i);
                                       put("name", "test:" + i);
                                       put("a", null);
                                   }}))
                         .cell(0, 0, "大标题")
                         .option(sheet_ -> {
                             CellStyle style = sheet_.getWorkbook().createCellStyle();

                             style.setAlignment(HorizontalAlignment.CENTER);
                             style.setVerticalAlignment(VerticalAlignment.CENTER);

                             sheet_.createRow(0);
                             sheet_.addMergedRegion(CellRangeAddress.valueOf("A1:B1"));
                             sheet_.getRow(0).createCell(0).setCellStyle(style);

                         })
                        ;
                })
                .sheet(sheet -> {
                    sheet.cell(0, 0, "NameA")
                         .cell(1, 0, "Age")
                         .cell(0, 1, "Test")
                         .cell(1, 1, 1)
                         .option(sheet_ -> {
                             sheet_.addMergedRegion(CellRangeAddress.valueOf("A3:B3"));
                             sheet_.addMergedRegion(CellRangeAddress.valueOf("C1:C3"));
                         });
                })
                .write(new FileOutputStream("./target/test.xlsx"))
                .as(StepVerifier::create)
                .expectComplete()
                .verify();

        Thread.sleep(1000);
    }
}