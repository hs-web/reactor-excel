package org.hswebframework.reactor.excel.poi;

import lombok.SneakyThrows;
import org.hswebframework.reactor.excel.ReactorExcel;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.io.FileOutputStream;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

class PoiExcelWriterTest {

    @Test
    @SneakyThrows
    void testWrite() {

        ReactorExcel
                .writer("xlsx")
                .header("id", "ID")
                .header("name", "name")
                .write(Flux.range(0, 1000)
                        .map(i -> new HashMap<String, Object>() {{
                            put("id", i);
                            put("name", "test" + i);
                        }}), new FileOutputStream("./target/test.xlsx"))
                .as(StepVerifier::create)
                .expectComplete()
                .verify();
    }
}