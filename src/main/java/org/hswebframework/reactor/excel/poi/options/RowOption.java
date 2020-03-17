package org.hswebframework.reactor.excel.poi.options;

import org.apache.poi.ss.usermodel.Row;
import org.hswebframework.reactor.excel.ExcelOption;

public interface RowOption extends ExcelOption {

    void row(Row row);

}
