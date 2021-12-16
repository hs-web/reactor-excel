package org.hswebframework.reactor.excel.spi;

import org.hswebframework.reactor.excel.ExcelOption;
import org.hswebframework.reactor.excel.WritableCell;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.OutputStream;

public interface ExcelWriter {

    String FORMAT_XLSX = "xlsx";

    String[] getSupportFormat();

    Mono<Void> write(Flux<WritableCell> dataStream,
                     OutputStream outputStream,
                     ExcelOption... options);

    boolean isSupportMultiSheet();
}
