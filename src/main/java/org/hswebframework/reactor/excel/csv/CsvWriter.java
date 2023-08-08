package org.hswebframework.reactor.excel.csv;

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

@Slf4j
public class CsvWriter implements ExcelWriter {
    private static final byte[] BOM = "\ufeff".getBytes();
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


        return Mono.defer(() -> {
            try {
                outputStream.write(BOM);
                CSVPrinter printer = new CSVPrinter(new OutputStreamWriter(outputStream), CSVFormat.EXCEL);
                return dataStream
                        .doOnNext(cell -> doWrite(printer, cell))
                        .doFinally((f) -> closePrinter(printer))
                        .then();
            } catch (IOException e) {
                return Mono.error(e);
            }

        });
    }

    @Override
    public boolean isSupportMultiSheet() {
        return false;
    }
}
