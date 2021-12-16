package org.hswebframework.reactor.excel.csv;

import lombok.SneakyThrows;
import org.hswebframework.reactor.excel.ReactorExcel;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.io.FileOutputStream;
import java.util.HashMap;

class CsvWriterTest {


    @Test
    @SneakyThrows
    void testWrite() {

        ReactorExcel
                .writer("csv")
                .header("id", "ID")
                .header("name", "name")
                .write(Flux.range(0, 1000)
                           .map(i -> new HashMap<String, Object>() {{
                               put("id", i);
                               put("name", "test" + i);
                           }}), new FileOutputStream("./target/test.csv"))
                .as(StepVerifier::create)
                .expectComplete()
                .verify();

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
                .writeAndClose(new FileOutputStream("./target/test.csv"))
                .as(StepVerifier::create)
                .expectComplete()
                .verify();

    }
}