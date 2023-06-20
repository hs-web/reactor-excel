package org.hswebframework.reactor.excel.converter;

import lombok.Getter;
import org.hswebframework.reactor.excel.*;

import java.util.Optional;

public class SimpleWritableCell implements WritableCell, OptionSupport {

    private final CellDataType type;

    public Object value;

    @Getter
    private final long rowIndex;

    @Getter
    private final int columnIndex;

    @Getter
    private final boolean endOfRow;

    @Getter
    private final int sheetIndex;

    private final Options options = Options.of();

    @Override
    public Options options() {
        return options;
    }

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
        if (cell instanceof WritableCell) {
            this.options().merge(((WritableCell) cell).options());
        }
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
