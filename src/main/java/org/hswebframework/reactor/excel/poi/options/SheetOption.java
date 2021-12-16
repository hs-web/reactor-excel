package org.hswebframework.reactor.excel.poi.options;

import org.apache.poi.ss.usermodel.Sheet;
import org.hswebframework.reactor.excel.ExcelOption;

import java.util.function.Consumer;

public interface SheetOption extends ExcelOption {

    void sheet(Sheet sheet);

    static SheetOption of(Consumer<Sheet> consumer){
        return consumer::accept;
    }
}
