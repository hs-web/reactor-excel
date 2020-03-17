package org.hswebframework.reactor.excel.poi.options;

import org.apache.poi.ss.usermodel.Sheet;
import org.hswebframework.reactor.excel.ExcelOption;

public interface SheetOption extends ExcelOption {

    void sheet(Sheet sheet);

}
