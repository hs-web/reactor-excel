package org.hswebframework.reactor.excel.spi;

import org.hswebframework.reactor.excel.Cell;
import org.hswebframework.reactor.excel.ExcelOption;
import reactor.core.publisher.Flux;

import java.io.InputStream;

public interface ExcelReader {

    String[] getSupportFormat();

    Flux<? extends Cell> read(InputStream inputStream,
                              ExcelOption... options);

}
