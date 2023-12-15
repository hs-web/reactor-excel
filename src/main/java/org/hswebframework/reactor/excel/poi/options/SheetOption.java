package org.hswebframework.reactor.excel.poi.options;

import org.apache.poi.ss.usermodel.Sheet;
import org.hswebframework.reactor.excel.ExcelOption;
import org.hswebframework.reactor.excel.context.Context;

import java.util.function.Consumer;

public interface SheetOption extends ExcelOption {

    void sheet(Sheet sheet);


    default void sheet(Sheet sheet, Context context) {
        sheet(sheet);
    }

    static SheetOption of(Consumer<Sheet> consumer) {
        return consumer::accept;
    }
}
