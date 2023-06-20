package org.hswebframework.reactor.excel;

import org.apache.commons.beanutils.BeanMap;
import org.hswebframework.reactor.excel.converter.HeaderCell;
import org.hswebframework.reactor.excel.converter.MapRowExpander;
import org.hswebframework.reactor.excel.spi.ExcelWriter;
import org.hswebframework.reactor.excel.utils.StreamUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.OutputStream;
import java.util.*;
import java.util.function.Function;

public class WriterOperator<T> {

    private final ExcelWriter writer;

    private final List<ExcelOption> options = new ArrayList<>();


    static Function<Object, Map<String, Object>> defaultConverter;

    static {
        defaultConverter = v -> (Map) new BeanMap(v);
    }

    private Function<T, Map<String, Object>> mapConverter = v -> defaultConverter.apply(v);

    private final MapRowExpander expander = new MapRowExpander();

    WriterOperator(ExcelWriter writer) {
        this.writer = writer;
    }

    public WriterOperator<T> header(String key,
                                    String header,
                                    CellDataType type) {
        expander.header(key, header, type);
        return this;
    }

    public WriterOperator<T> converter(Function<T, Map<String, Object>> mapConverter) {
        this.mapConverter = mapConverter;
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

    public WriterOperator<T> headers(Collection<ExcelHeader> headers) {
        expander.headers(headers);
        return this;
    }


    public WriterOperator<T> headers(Class<?> typeSpec) {

        // TODO: 2020/3/17
        return this;
    }

    public WriterOperator<T> options(ExcelOption... option) {
        options.addAll(Arrays.asList(option));
        return this;
    }

    public WriterOperator<T> option(ExcelOption option) {
        options.add(option);
        return this;
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> toMap(T val) {
        if (val instanceof Map) {
            return ((Map<String, Object>) val);
        }
        if (mapConverter != null) {
            return mapConverter.apply(val);
        }
        throw new UnsupportedOperationException("can not convert " + val + " to map");
    }

    public Flux<byte[]> writeBuffer(Flux<T> dataStream) {
        return writeBuffer(dataStream, 10240);
    }

    public Flux<byte[]> writeBuffer(Flux<T> dataStream, int buffer) {
        return StreamUtils.buffer(buffer, output -> write(dataStream, output));
    }

    public Mono<Void> write(Flux<T> dataStream,
                            OutputStream output) {
        return Flux.concat(
                Flux.fromIterable(expander.getHeaders())
                        .index((index, header) -> new HeaderCell(header, index.intValue(), index == expander.getHeaders().size() - 1)),
                dataStream.index()
                        .concatMap((idx) -> expander.apply(idx.getT1() + 1, toMap(idx.getT2()))))
                .as(flux -> writer.write(flux, output, options.toArray(new ExcelOption[0])));

    }

    public static <T> WriterOperator<T> of(ExcelWriter writer) {
        return new WriterOperator<>(writer);
    }
}
