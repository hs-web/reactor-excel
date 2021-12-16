package org.hswebframework.reactor.excel.converter;

import lombok.Getter;
import org.hswebframework.reactor.excel.Cell;
import org.hswebframework.reactor.excel.CellDataType;
import org.hswebframework.reactor.excel.ExcelHeader;
import org.hswebframework.reactor.excel.WritableCell;

import java.util.Optional;

public class SimpleWritableCell implements WritableCell {

    private CellDataType type;

    public Object value;

    @Getter
    private long rowIndex;

    @Getter
    private int columnIndex;

    @Getter
    private boolean endOfRow;

    @Getter
    private int sheetIndex;

    public SimpleWritableCell(ExcelHeader header, Object value, long rowIndex, int columnIndex, boolean endOfRow, int sheetIndex) {
        this(header.getType(), value, rowIndex, columnIndex, endOfRow, sheetIndex);
    }

    public SimpleWritableCell(CellDataType dataType, Object value, long rowIndex, int columnIndex, boolean endOfRow, int sheetIndex) {
        this.type = dataType;
        this.value = value;
        this.rowIndex = rowIndex;
        this.columnIndex = columnIndex;
        this.endOfRow = endOfRow;
        this.sheetIndex = sheetIndex;
    }

    public SimpleWritableCell(Cell cell, int sheetIndex) {
        this(cell.getType(),
             cell.value().orElse(null),
             cell.getRowIndex(),
             cell.getColumnIndex(),
             cell.isEndOfRow(),
             sheetIndex);
    }


    @Override
    public Optional<Object> value() {
        return Optional.ofNullable(value);
    }

    @Override
    public CellDataType getType() {
        return type;
    }
}
