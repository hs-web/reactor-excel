package org.hswebframework.reactor.excel.csv;

import org.hswebframework.reactor.excel.ReactorExcel;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.HashMap;

class CsvReaderTest {


    @Test
    void testCsvReader() {

        ReactorExcel
                .readAsMap(CsvReaderTest.class.getResourceAsStream("/test.csv"), "csv")
                .as(StepVerifier::create)
                .expectNext(
                        new HashMap<String, Object>() {{
                            put("id", "1");
                            put("name", "test");
                        }},
                        new HashMap<String, Object>() {{
                            put("id", "2");
                            put("name", "test2");
                        }})
                .verifyComplete();

    }

}