package org.hswebframework.reactor.excel.csv;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.reactor.excel.Cell;
import org.hswebframework.reactor.excel.CellDataType;

import java.util.Optional;

@Getter
@Setter
@AllArgsConstructor
public class CsvCell implements Cell {

    private long rowIndex;

    private int columnIndex;

    private String value;

    private boolean end;
    @Override
    public int getSheetIndex() {
        return 0;
    }

    @Override
    public Optional<Object> value() {
        return Optional.ofNullable(value);
    }

    @Override
    public CellDataType getType() {
        return CellDataType.STRING;
    }

}
