package org.hswebframework.reactor.excel;

import lombok.SneakyThrows;
import org.apache.commons.beanutils.BeanUtils;
import org.hswebframework.reactor.excel.converter.MapWrapper;
import org.hswebframework.reactor.excel.spi.ExcelReader;
import reactor.core.publisher.Flux;

import java.io.InputStream;
import java.util.*;
import java.util.function.Function;

@Deprecated
public class ReaderOperator<T> {

    private final ExcelReader reader;

    private final Class<T> targetType;

    private final MapWrapper wrapper = new MapWrapper();

    private Function<Map<String, Object>, T> converter = this::copy;

    static <T> ReaderOperator<T> of(ExcelReader reader, Class<T> targetType) {
        return new ReaderOperator<>(reader, targetType);
    }

    @SuppressWarnings("all")
    static ReaderOperator<Map<String, Object>> ofMap(ExcelReader reader) {
        return (ReaderOperator) of(reader, Map.class);
    }

    public ReaderOperator(ExcelReader reader, Class<T> targetType) {
        this.targetType = targetType;
        this.reader = reader;
        if (targetType.isAssignableFrom(Map.class)) {
            converter = v -> (T) v;
        } else {
            headers(targetType);
        }
    }

    private List<ExcelOption> options = new ArrayList<>();

    public ReaderOperator<T> headerRowIs(int headerRowNumber) {
        wrapper.setHeaderIndex(headerRowNumber);
        return this;
    }

    public ReaderOperator<T> header(String header, String key) {
        wrapper.header(header, key);
        return this;
    }


    public ReaderOperator<T> headers(Class<T> type) {
        // TODO: 2020/3/17
        return this;
    }

    public ReaderOperator<T> converter(Function<Map<String, Object>, T> converter) {
        this.converter = converter;
        return this;
    }

    @SneakyThrows
    private T copy(Map<String, Object> dest) {
        T t = targetType.newInstance();
        BeanUtils.copyProperties(t, dest);
        return t;
    }

    public ReaderOperator<T> options(ExcelOption... options) {
        this.options.addAll(Arrays.asList(options));
        return this;
    }

    public ReaderOperator<T> option(ExcelOption option) {
        options.add(option);
        return this;
    }

    public Flux<T> read(InputStream inputStream) {
        return reader
                .read(inputStream, options.toArray(new ExcelOption[0]))
                .as(wrapper::convert)
                .map(converter);
    }

}
