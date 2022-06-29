package org.hswebframework.reactor.excel;

import lombok.extern.slf4j.Slf4j;
import org.hswebframework.reactor.excel.converter.RowWrapper;
import org.hswebframework.reactor.excel.spec.ReaderSpec;
import org.hswebframework.reactor.excel.spec.WriterSpec;
import org.hswebframework.reactor.excel.spi.ExcelReader;
import org.hswebframework.reactor.excel.spi.ExcelWriter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.function.Consumer3;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

@Slf4j
public abstract class ReactorExcel {

    private final static Map<String, ExcelReader> readers = new ConcurrentHashMap<>();
    private final static Map<String, ExcelWriter> writers = new ConcurrentHashMap<>();

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

    public static ExcelReader lookupReader(String format) {
        ExcelReader reader = readers.get(format);
        if (reader == null) {
            throw new UnsupportedOperationException("error.unsupported_excel_format");
        }
        return reader;
    }

    public static ExcelWriter lookupWriter(String format) {
        ExcelWriter writer = writers.get(format);
        if (writer == null) {
            throw new UnsupportedOperationException("error.unsupported_excel_format");
        }
        return writer;
    }

    public static Consumer3<Map<String, Object>, String, Cell> mapWrapper() {
        return (map, header, cell) -> {
            map.put(header, cell.value().orElse(null));
        };
    }

    public static <T> BiConsumer<T, Cell> cell(int x, int y, BiConsumer<T, Object> handler) {
        return (instance, cell) -> {
            if (cell.getColumnIndex() == x && cell.getRowIndex() == y) {
                Object value = cell.value().orElse(null);
                handler.accept(instance, value);
            }
        };
    }

    public static Flux<Map<String, Object>> readToMap(InputStream input,
                                                      String format,
                                                      ExcelOption... options) {
        return ReaderSpec
                .<Map<String, Object>>readFor(ReactorExcel.lookupReader(format), LinkedHashMap::new)
                .justReadByHeader()
                .wrapper(mapWrapper())
                .read(input, options);
    }

    public static <T> ReaderSpec.ReaderSpecSelector<T> readFor(String format, Supplier<T> instance) {
        return ReaderSpec.readFor(lookupReader(format), instance);
    }

    public static <T> ReaderSpec.ReaderSpecSelector<T> xlsxReader(Supplier<T> instance) {
        return readFor(ExcelWriter.FORMAT_XLSX, instance);
    }

    public static <T> Flux<T> read(InputStream input,
                                   String format,
                                   RowWrapper<T> wrapper) {
        return read(input, format)
                .as(wrapper::convert);
    }

    public static Flux<Cell> read(InputStream input,
                                  String format) {
        return lookupReader(format)
                .read(input)
                .cast(Cell.class);
    }

    /**
     * 使用 {@link  ReactorExcel#readToMap(InputStream, String, ExcelOption...)}替代
     */
    @Deprecated
    public static ReaderOperator<Map<String, Object>> mapReader(String format) {
        return ReaderOperator.ofMap(lookupReader(format));
    }

    /**
     * 使用 {@link  ReactorExcel#readFor(String, Supplier)}替代
     */
    @Deprecated
    public static <T> ReaderOperator<T> reader(Class<T> type, String format) {
        return ReaderOperator.of(lookupReader(format), type);
    }

    public static <T> WriterOperator<T> writer(String format) {
        return WriterOperator.of(lookupWriter(format));
    }

    public static WriterSpec.WriterSpecSelector writeFor(String format) {
        return WriterSpec.writeFor(lookupWriter(format));
    }

    public static WriterSpec.WriterSpecSelector xlsxWriter() {
        return  writeFor(ExcelWriter.FORMAT_XLSX);
    }

    public static Mono<Void> write(Flux<WritableCell> dataStream,
                                   OutputStream outputStream,
                                   String format,
                                   ExcelOption... options) {
        return lookupWriter(format)
                .write(dataStream, outputStream, options);
    }

}
