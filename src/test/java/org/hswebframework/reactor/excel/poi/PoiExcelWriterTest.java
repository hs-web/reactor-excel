package org.hswebframework.reactor.excel.poi;

import lombok.SneakyThrows;
import org.hswebframework.reactor.excel.ReactorExcel;
import org.hswebframework.reactor.excel.poi.options.PoiWriteOptions;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class PoiExcelWriterTest {

    @Test
    @SneakyThrows
    void testWrite() {

        ReactorExcel
                .writer("xlsx")
                .header("id", "ID")
                .header("name", "name").header("a","a")
                .write(Flux.range(0, 10000)
                        .map(i -> new HashMap<String, Object>() {{
                            put("id", i);
                            put("name", "test" + i);
                            put("a",null);
                        }}), new FileOutputStream("./target/test.xlsx"))
                .as(StepVerifier::create)
                .expectComplete()
                .verify();

        Thread.sleep(1000);
    }
}