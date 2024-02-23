package org.hswebframework.reactor.excel.poi;

import org.hswebframework.reactor.excel.ReactorExcel;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

class PoiExcelReaderTest {


    @Test
    void testXls() {
        ReactorExcel
                .<Map<String, Object>>xlsxReader(LinkedHashMap::new)
                .justReadByHeader()
                .wrapper(ReactorExcel.mapWrapper())
                .read(PoiExcelReaderTest.class.getResourceAsStream("/test.xls"))
                .as(StepVerifier::create)
                .expectNext(
                        new LinkedHashMap<String, Object>() {{
                            put("id", 1L);
                            put("name", "test");
                        }},
                        new LinkedHashMap<String, Object>() {{
                            put("id", 2L);
                            put("name", "test2");
                        }})
                .verifyComplete();
    }

    @Test
    void testNumber() {
        ReactorExcel
                .<Map<String, Object>>xlsxReader(LinkedHashMap::new)
                .justRead()
                .wrapper(ReactorExcel.cell(1, 2, (map, value) -> map.put("num", value)))
                .oneInstanceAllSheets()
                .read(PoiExcelReaderTest.class.getResourceAsStream("/simple.xlsx"))
                .as(StepVerifier::create)
                .expectNext(
                        new LinkedHashMap<String, Object>() {{
                            put("num", new BigDecimal("0.6"));
                        }})
                .verifyComplete();
    }

    @Test
    void testCell() {
        ReactorExcel
                .<Map<String, Object>>xlsxReader(LinkedHashMap::new)
                .justRead()
                .wrapper(ReactorExcel.cell(0, 1, (map, value) -> map.put("0-1", value)))
                .oneInstanceAllSheets()
                .read(PoiExcelReaderTest.class.getResourceAsStream("/random.xlsx"))
                .as(StepVerifier::create)
                .expectNext(
                        new LinkedHashMap<String, Object>() {{
                            put("0-1", "A2");
                        }})
                .verifyComplete();
    }

    @Test
    void testXlsx() {
        ReactorExcel
                .<Map<String, Object>>xlsxReader(LinkedHashMap::new)
                .justReadByHeader()
                .wrapper(ReactorExcel.mapWrapper())
                .read(PoiExcelReaderTest.class.getResourceAsStream("/test.xlsx"))
                .as(StepVerifier::create)
                .expectNext(
                        new LinkedHashMap<String, Object>() {{
                            put("id", 1L);
                            put("name", "test");
                            put("age", null);
                        }},
                        new LinkedHashMap<String, Object>() {{
                            put("id", 2L);
                            put("name", "test2");
                            put("age", null);
                        }})
                .verifyComplete();
    }

    @Test
    void testMultiSheet() {
        ReactorExcel
                .<Map<String, Object>>xlsxReader(LinkedHashMap::new)
                .multiSheetByHeader()
                .sheet(0, (map, header, cell) -> {
                    map.put("s0:" + header, cell.valueAsText().orElse(null));
                })
                .sheet("Sheet2", (map, header, cell) -> {
                    map.put("s1:" + header, cell.valueAsText().orElse(null));
                })
                .readAndClose(PoiExcelReaderTest.class.getResourceAsStream("/multi-sheet.xlsx"))
                .as(StepVerifier::create)
                .expectNext(
                        new LinkedHashMap<String, Object>() {{
                            put("s0:name", "s1");
                            put("s0:age", "1");
                        }},
                        new LinkedHashMap<String, Object>() {{
                            put("s0:name", "s1");
                            put("s0:age", "2");
                        }},
                        new LinkedHashMap<String, Object>() {{
                            put("s1:name", "s2");
                            put("s1:age", "1");
                        }},
                        new LinkedHashMap<String, Object>() {{
                            put("s1:name", "s2");
                            put("s1:age", "2");
                        }}
                )
                .verifyComplete();
    }

    @Test
    void testMultiSheetEachSheet() {

        ReactorExcel
                .<Map<String, Object>>xlsxReader(LinkedHashMap::new)
                .multiSheetByHeader()
                .oneInstanceEachSheet()
                .sheet(0, (map, header, cell) -> {
                    map.put("s0:" + header, cell.valueAsText().orElse(null));
                })
                .sheet("Sheet2", (map, header, cell) -> {
                    map.put("s1:" + header, cell.valueAsText().orElse(null));
                })
                .readAndClose(PoiExcelReaderTest.class.getResourceAsStream("/multi-sheet.xlsx"))
                .as(StepVerifier::create)
                .expectNext(
                        new LinkedHashMap<String, Object>() {{
                            put("s0:name", "s1");
                            put("s0:age", "2");
                        }},
                        new LinkedHashMap<String, Object>() {{
                            put("s1:name", "s2");
                            put("s1:age", "2");
                        }}
                )
                .verifyComplete();
    }

    @Test
    void testMultiSheetAllInOne() {
        ReactorExcel
                .<Map<String, Object>>xlsxReader(LinkedHashMap::new)
                .multiSheetByHeader()
                .oneInstanceAllSheets()
                .sheet(0, (map, header, cell) -> {
                    map.put("s0:" + header, cell
                            .valueAsText()
                            .orElse(null));
                })
                .sheet(1, (map, header, cell) -> {
                    map.put("s1:" + header, cell
                            .valueAsText()
                            .orElse(null));
                })
                .readAndClose(PoiExcelReaderTest.class.getResourceAsStream("/multi-sheet.xlsx"))
                .as(StepVerifier::create)
                .expectNext(
                        new LinkedHashMap<String, Object>() {{
                            put("s0:name", "s1");
                            put("s0:age", "2");
                            put("s1:name", "s2");
                            put("s1:age", "2");
                        }}
                )
                .verifyComplete();
    }

    @Test
    void testParseTime(){
        ReactorExcel
                .<Map<String, Object>>xlsxReader(LinkedHashMap::new)
                .justReadByHeader()
                .wrapper(ReactorExcel.mapWrapper())
                .read(PoiExcelReaderTest.class.getResourceAsStream("/test-time.xls"))
                .as(StepVerifier::create)
                .expectNext(
                        Collections.singletonMap("time", LocalTime.of(1,0,0)),
                        Collections.singletonMap("time", LocalTime.of(13,0,0)),
                        Collections.singletonMap("time", LocalTime.of(4,59,59)),
                        Collections.singletonMap("time", LocalTime.of(4,59,59))
                )
                .verifyComplete();
    }

}