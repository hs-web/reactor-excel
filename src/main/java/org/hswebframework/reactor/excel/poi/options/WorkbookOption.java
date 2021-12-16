package org.hswebframework.reactor.excel.poi.options;

import org.apache.poi.ss.usermodel.Workbook;
import org.hswebframework.reactor.excel.ExcelOption;

import java.util.function.Consumer;

public interface WorkbookOption extends ExcelOption {

    void workbook(Workbook workBook);

    static WorkbookOption of(Consumer<Workbook> consumer){
        return consumer::accept;
    }
}
