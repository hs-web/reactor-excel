package org.hswebframework.reactor.excel.poi;

import org.hswebframework.reactor.excel.ReactorExcel;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.HashMap;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.*;

class PoiExcelReaderTest {


    @Test
    void testXls() {
        ReactorExcel
                .mapReader( "xls")
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
    void testXlsx() {
        ReactorExcel
                .mapReader( "xlsx")
                .read(PoiExcelReaderTest.class.getResourceAsStream("/test.xlsx"))
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

}