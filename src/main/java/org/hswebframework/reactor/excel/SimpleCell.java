package org.hswebframework.reactor.excel;

import lombok.AllArgsConstructor;

import java.util.Optional;

@AllArgsConstructor
class SimpleCell implements Cell{
    private final long rowIndex;
    private final int columnIndex;
    private final boolean endOfRow;
    private final Object valueRef;
    private final CellDataType dataType;

    @Override
    public long getRowIndex() {
        return rowIndex;
    }

    @Override
    public int getColumnIndex() {
        return columnIndex;
    }

    @Override
    public boolean isEndOfRow() {
        return endOfRow;
    }

    @Override
    public Optional<Object> value() {
        return Optional.ofNullable(valueRef);
    }

    @Override
    public CellDataType getType() {
        return dataType;
    }
}
