package org.hswebframework.reactor.excel.csv;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.hswebframework.reactor.excel.Cell;
import org.hswebframework.reactor.excel.ExcelOption;
import org.hswebframework.reactor.excel.WritableCell;
import org.hswebframework.reactor.excel.spi.ExcelWriter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.util.Objects;

@Slf4j
public class CsvWriter implements ExcelWriter {

    @Override
    public String[] getSupportFormat() {
        return new String[]{"csv"};
    }

    @SneakyThrows
    private void doWrite(CSVPrinter printer, Cell cell) {

        printer.print(cell.valueAsText().orElse(""));
        if (cell.isEndOfRow()) {
            printer.println();
        }
    }

    private void closePrinter(CSVPrinter printer) {
        try {
            printer.close();
        } catch (Throwable err) {
            log.warn("close CSVPrinter error", err);
        }
    }

    @Override
    public Mono<Void> write(Flux<WritableCell> dataStream,
                            OutputStream outputStream,
                            ExcelOption... options) {
        Objects.requireNonNull(dataStream, "dataStream");
        Objects.requireNonNull(outputStream, "outputStream");
        CSVFormat format = getFormat(options);
        Charset charset = getCharset(options);
        return Mono.defer(() -> {
            try {
                outputStream.write("\ufeff".getBytes(charset));
            } catch (IOException e) {
                return Mono.error(e);
            }
            return Mono.using(
                () -> new CSVPrinter(new OutputStreamWriter(outputStream, charset), format),
                printer -> dataStream
                    .doOnNext(cell -> doWrite(printer, cell))
                    .then(),
                this::closePrinter
            );
        });
    }

    @Override
    public Flux<ByteBuf> write(Flux<WritableCell> dataStream,
                               ByteBufAllocator allocator,
                               int bufferSize,
                               ExcelOption... options) {
        return new CsvByteBufFlux(
            dataStream,
            allocator,
            bufferSize,
            getFormat(options),
            getCharset(options)
        );
    }

    private CSVFormat getFormat(ExcelOption... options) {
        CSVFormat format = CSVFormat.EXCEL;
        for (ExcelOption option : options) {
            if (option instanceof FormatOption) {
                format = Objects.requireNonNull(((FormatOption) option).getFormat(), "format");
            }
        }
        return format;
    }

    private Charset getCharset(ExcelOption... options) {
        Charset charset = Charset.defaultCharset();
        for (ExcelOption option : options) {
            if (option instanceof CharsetOption) {
                charset = Objects.requireNonNull(((CharsetOption) option).getCharset(), "charset");
            }
        }
        return charset;
    }

    @Override
    public boolean isSupportMultiSheet() {
        return false;
    }
}
