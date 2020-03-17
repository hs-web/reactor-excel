package org.hswebframework.reactor.excel.converter;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.hswebframework.reactor.excel.*;

import java.util.Optional;

@AllArgsConstructor
public class HeaderCell implements WritableCell, OptionSupport {

    @Getter
    private ExcelHeader header;

    @Getter
    private int columnIndex;

    @Getter
    private boolean end;
    @Override
    public int getSheetIndex() {
        return 0;
    }

    @Override
    public long getRowIndex() {
        return 0;
    }

    @Override
    public Optional<Object> value() {
        return Optional.of(header.getText());
    }

    @Override
    public CellDataType getType() {
        return CellDataType.STRING;
    }

    @Override
    public Options options() {
        return header.options();
    }
}
