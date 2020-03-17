package org.hswebframework.reactor.excel;

import java.util.Optional;

public interface Cell {

    int getSheetIndex();

    long getRowIndex();

    int getColumnIndex();

    Optional<Object> value();

    default Optional<String> valueAsText() {
        return value()
                .map(String::valueOf);
    }

    CellDataType getType();

    boolean isEnd();
}
