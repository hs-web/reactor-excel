package org.hswebframework.reactor.excel.poi.options;

import org.apache.poi.ss.usermodel.Cell;
import org.hswebframework.reactor.excel.ExcelOption;
import org.hswebframework.reactor.excel.WritableCell;

import java.util.function.BiConsumer;

public interface CellOption extends ExcelOption {

    void cell(Cell poiCell, WritableCell cell);

    static CellOption of(BiConsumer<Cell,WritableCell> consumer){
        return consumer::accept;
    }
}
