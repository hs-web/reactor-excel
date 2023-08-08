package org.hswebframework.reactor.excel.csv;

import lombok.SneakyThrows;
import org.hswebframework.reactor.excel.ReactorExcel;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;

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
}