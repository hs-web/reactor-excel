package org.hswebframework.reactor.excel.csv;

import lombok.SneakyThrows;
import org.hswebframework.reactor.excel.ReactorExcel;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.io.FileInputStream;
import java.nio.charset.Charset;
import java.util.HashMap;

class CsvReaderTest {


    @Test
    void testCsvReader() {

        ReactorExcel
                .readToMap(CsvReaderTest.class.getResourceAsStream("/test.csv"),"csv")
                .as(StepVerifier::create)
                .expectNext(
                        new HashMap<String, Object>() {{
                            put("id", "1");
                            put("name", "test");
                        }},
                        new HashMap<String, Object>() {{
                            put("id", "2");
                            put("name", "中文");
                        }})
                .verifyComplete();

    }

    @Test
    @SneakyThrows
    void testCsvReader2() {

        ReactorExcel
                .readToMap(CsvReaderTest.class.getResourceAsStream("/csv2.csv"),
                           "csv",
                           new CharsetOption(Charset.forName("gbk")))
                .doOnNext(System.out::println)
                .as(StepVerifier::create)
                .expectNextCount(1)
                .verifyComplete();

    }



}