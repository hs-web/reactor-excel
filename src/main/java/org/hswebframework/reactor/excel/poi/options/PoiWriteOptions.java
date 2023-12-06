package org.hswebframework.reactor.excel.poi.options;

import org.hswebframework.reactor.excel.ExcelOption;

public interface PoiWriteOptions {

    static ExcelOption width(int columnIndex, int width) {
        return new ColumnWidthOption(columnIndex, width);
    }

    static ExcelOption sheetName(int index, String name) {
        return new NamedSheetOption(index, name);
    }

    static ExcelOption addNormalPullDownSheet(int index, int firstRow, int endRow, int firstCol, int endCol, String... data) {
        return new AddNormalPullDownSheetOption(index, firstRow, endRow, firstCol, endCol, data);
    }
}
