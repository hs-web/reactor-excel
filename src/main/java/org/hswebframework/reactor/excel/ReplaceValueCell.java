package org.hswebframework.reactor.excel;

import lombok.AllArgsConstructor;

import java.util.Optional;

@AllArgsConstructor
class ReplaceValueCell implements BoundedCell {
    private final BoundedCell cell;

    private final Object value;

    @Override
    public int getNumberOfRows() {
        return cell.getNumberOfRows();
    }

    @Override
    public int getNumberOfColumns() {
        return cell.getNumberOfColumns();
    }

    @Override
    public int getNumberOfSheets() {
        return cell.getNumberOfSheets();
    }

    @Override
    public long getRowIndex() {
        return cell.getRowIndex();
    }

    @Override
    public int getColumnIndex() {
        return cell.getColumnIndex();
    }

    @Override
    public boolean isEndOfRow() {
        return cell.isEndOfRow();
    }

    @Override
    public Optional<Object> value() {
        return Optional.ofNullable(value);
    }

    @Override
    public CellDataType getType() {
        return cell.getType();
    }

    @Override
    public int getSheetIndex() {
        return cell.getSheetIndex();
    }
}
