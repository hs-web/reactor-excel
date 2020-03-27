package org.hswebframework.reactor.excel.poi;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.hswebframework.reactor.excel.Cell;
import org.hswebframework.reactor.excel.CellDataType;

import java.util.Optional;

@AllArgsConstructor
public class NullCell implements Cell {

    @Getter
    private int sheetIndex;

    @Getter
    private long rowIndex;

    @Getter
    private int columnIndex;

    @Getter
    private boolean end;



    @Override
    public Optional<Object> value() {
        return Optional.empty();
    }

    @Override
    public CellDataType getType() {
        return CellDataType.AUTO;
    }

}
