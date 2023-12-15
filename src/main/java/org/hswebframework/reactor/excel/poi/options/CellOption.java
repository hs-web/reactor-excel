package org.hswebframework.reactor.excel.poi.options;

import org.apache.poi.ss.usermodel.Cell;
import org.hswebframework.reactor.excel.ExcelOption;
import org.hswebframework.reactor.excel.WritableCell;
import org.hswebframework.reactor.excel.context.Context;

import java.util.function.BiConsumer;

public interface CellOption extends ExcelOption {

    void cell(Cell poiCell, WritableCell cell);

    default void cell(Cell poiCell, WritableCell cell, Context context) {
        cell(poiCell, cell);
    }

    static CellOption of(BiConsumer<Cell, WritableCell> consumer) {
        return consumer::accept;
    }
}
