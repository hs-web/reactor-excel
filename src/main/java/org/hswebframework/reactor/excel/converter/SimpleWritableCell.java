package org.hswebframework.reactor.excel.converter;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.hswebframework.reactor.excel.CellDataType;
import org.hswebframework.reactor.excel.ExcelHeader;
import org.hswebframework.reactor.excel.WritableCell;

import java.util.Optional;

@AllArgsConstructor
public class SimpleWritableCell implements WritableCell {

    @Getter
    private ExcelHeader header;

    public Object value;

    @Getter
    private long rowIndex;

    @Getter
    private int columnIndex;

    @Getter
    private boolean end;


    @Override
    public int getSheetIndex() {
        // TODO: 2020/3/17
        return 0;
    }

    @Override
    public Optional<Object> value() {
        return Optional.ofNullable(value);
    }

    @Override
    public CellDataType getType() {
        return header.getType();
    }
}
