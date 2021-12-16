package org.hswebframework.reactor.excel.poi;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.hswebframework.reactor.excel.BoundedCell;
import org.hswebframework.reactor.excel.InSheetCell;
import org.hswebframework.reactor.excel.CellDataType;

import java.util.Optional;

@AllArgsConstructor
@Getter
class NullCell implements BoundedCell {

    private int sheetIndex;
    private long rowIndex;
    private int columnIndex;
    private boolean endOfRow;
    private int numberOfRows;
    private int numberOfColumns;
    private int numberOfSheets;

    @Override
    public Optional<Object> value() {
        return Optional.empty();
    }

    @Override
    public CellDataType getType() {
        return CellDataType.AUTO;
    }

}
