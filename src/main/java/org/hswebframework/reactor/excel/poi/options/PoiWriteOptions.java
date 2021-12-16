package org.hswebframework.reactor.excel.poi.options;

import org.hswebframework.reactor.excel.ExcelOption;

public interface PoiWriteOptions {

    static ExcelOption width(int columnIndex, int width) {
        return new ColumnWidthOption(columnIndex, width);
    }

    static ExcelOption sheetName(int index, String name) {
        return new NamedSheetOption(index, name);
    }
}
