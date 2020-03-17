package org.hswebframework.reactor.excel.poi.options;

import org.apache.poi.ss.usermodel.Cell;
import org.hswebframework.reactor.excel.ExcelOption;
import org.hswebframework.reactor.excel.WritableCell;

public interface CellOption extends ExcelOption {

    void cell(Cell poiCell, WritableCell cell);

}
