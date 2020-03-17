package org.hswebframework.reactor.excel;

import org.hswebframework.reactor.excel.converter.MapRowExpander;
import org.hswebframework.reactor.excel.converter.SimpleWritableCell;
import org.hswebframework.reactor.excel.spi.ExcelWriter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.OutputStream;
import java.util.Map;

public class WriterOperator<T> {

    ExcelWriter writer;

    WriterOperator(ExcelWriter writer) {
        this.writer = writer;
    }

    MapRowExpander expander = new MapRowExpander();

    public WriterOperator<T> header(String key,
                                    String header,
                                    CellDataType type) {
        expander.header(key, header, type);
        return this;
    }

    public WriterOperator<T> header(String key, String header) {
        expander.header(key, header);
        return this;
    }

    public WriterOperator<T> header(ExcelHeader header) {
        expander.header(header);
        return this;
    }

    protected Map<String, Object> toMap(T val) {
        if (val instanceof Map) {
            return ((Map) val);
        }

        // TODO: 2020/3/17
        return null;
    }

    public Mono<Void> write(Flux<T> dataStream,
                            OutputStream output) {
        return Flux.concat(
                Flux.fromIterable(expander.getHeaders())
                        .index((index, header) -> new SimpleWritableCell(header, header.getText(), 0, index.intValue(), index == expander.getHeaders().size() - 1)),
                dataStream.index()
                        .flatMap((idx) -> expander.apply(idx.getT1()+1, toMap(idx.getT2()))))
                .as(flux -> writer.write(flux, output));

    }

    public static <T> WriterOperator<T> of(ExcelWriter writer) {
        return new WriterOperator<>(writer);
    }
}
