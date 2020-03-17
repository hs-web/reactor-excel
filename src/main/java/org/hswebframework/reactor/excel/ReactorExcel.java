package org.hswebframework.reactor.excel;

import lombok.extern.slf4j.Slf4j;
import org.hswebframework.reactor.excel.spi.ExcelReader;
import org.hswebframework.reactor.excel.converter.RowWrapper;
import org.hswebframework.reactor.excel.converter.Wrappers;
import org.hswebframework.reactor.excel.spi.ExcelWriter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

@Slf4j
public abstract class ReactorExcel {

    private static Map<String, ExcelReader> readers = new ConcurrentHashMap<>();
    private static Map<String, ExcelWriter> writers = new ConcurrentHashMap<>();

    static {
        try {
            ServiceLoader.load(ExcelReader.class)
                    .forEach(reader -> {
                        for (String excelFormat : reader.getSupportFormat()) {
                            readers.put(excelFormat, reader);
                        }
                    });

            ServiceLoader.load(ExcelWriter.class)
                    .forEach(reader -> {
                        for (String excelFormat : reader.getSupportFormat()) {
                            writers.put(excelFormat, reader);
                        }
                    });
        } catch (Exception e) {
            log.error("load excel reader error", e);
        }
    }

    static ExcelReader lookupReader(String format) {
        ExcelReader reader = readers.get(format);
        if (reader == null) {
            throw new UnsupportedOperationException("unsupported format:" + format);
        }
        return reader;
    }

    static ExcelWriter lookupWriter(String format) {
        ExcelWriter writer = writers.get(format);
        if (writer == null) {
            throw new UnsupportedOperationException("unsupported format:" + format);
        }
        return writer;
    }


    public static Flux<Map<String, Object>> readAsMap(InputStream stream, String format) {
        return read(stream, format, Wrappers.map());
    }

    public static <T> Flux<T> read(InputStream stream,
                                   String format,
                                   RowWrapper<T> wrapper) {
        return Flux.defer(() -> lookupReader(format).read(stream).flatMap(wrapper));
    }



    public static <T> WriterOperator<T> writer(String format) {
        return WriterOperator.of(lookupWriter(format));
    }


}
