package org.hswebframework.reactor.excel.poi.options;

import org.apache.poi.ss.usermodel.Row;
import org.hswebframework.reactor.excel.ExcelOption;

import java.util.function.Consumer;

public interface RowOption extends ExcelOption {

    void row(Row row);

    static RowOption of(Consumer<Row> consumer){
        return consumer::accept;
    }
}
