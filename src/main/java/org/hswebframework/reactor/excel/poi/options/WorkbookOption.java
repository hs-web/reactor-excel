package org.hswebframework.reactor.excel.poi.options;

import org.apache.poi.ss.usermodel.Workbook;
import org.hswebframework.reactor.excel.ExcelOption;

public interface WorkbookOption extends ExcelOption {

    void workbook(Workbook workBook);

}
