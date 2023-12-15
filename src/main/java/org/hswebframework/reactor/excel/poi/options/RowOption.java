package org.hswebframework.reactor.excel.poi.options;

import org.apache.poi.ss.usermodel.Row;
import org.hswebframework.reactor.excel.ExcelOption;
import org.hswebframework.reactor.excel.context.Context;

import java.util.function.Consumer;

public interface RowOption extends ExcelOption {

    void row(Row row);

    default void row(Row row, Context context) {
        row(row);
    }

    static RowOption of(Consumer<Row> consumer) {
        return consumer::accept;
    }
}
