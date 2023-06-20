package org.hswebframework.reactor.excel;

import org.hswebframework.reactor.excel.converter.SimpleWritableCell;

public interface WritableCell extends InSheetCell,OptionSupport {


    static WritableCell of(int sheetIndex,
                           long rowIndex,
                           int columnIndex,
                           CellDataType dataType,
                           Object value,
                           boolean endOfRow) {

        return new SimpleWritableCell(dataType, value, rowIndex, columnIndex, endOfRow, sheetIndex);
    }

    static WritableCell of(Cell cell, int sheetIndex) {
        return new SimpleWritableCell(cell, sheetIndex);
    }
}
