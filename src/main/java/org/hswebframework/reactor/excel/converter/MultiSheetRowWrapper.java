package org.hswebframework.reactor.excel.converter;

import lombok.AllArgsConstructor;
import lombok.Setter;
import org.hswebframework.reactor.excel.BoundedCell;
import org.hswebframework.reactor.excel.Cell;
import org.hswebframework.reactor.excel.InSheetCell;
import reactor.function.Consumer3;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Supplier;


@AllArgsConstructor
@Deprecated
public class MultiSheetRowWrapper<T> extends RowWrapper<T> {

    private final Map<Object, Consumer3<T, InSheetCell, InSheetCell>> wrappers = new HashMap<>();

    private final Supplier<? extends T> instanceGetter;

    @Setter
    private BiPredicate<T, BoundedCell> newInstancePredicate;

    public static <T> MultiSheetRowWrapper<T> of(Supplier<? extends T> supplier) {
        return new MultiSheetRowWrapper<>(supplier, (instance, cell) -> cell.isEndOfRow());
    }

    public MultiSheetRowWrapper<T> sheet(int index,
                                         Consumer3<T, InSheetCell, InSheetCell> cellCellConsumer3) {
        wrappers.put(index, cellCellConsumer3);
        return this;
    }

    public MultiSheetRowWrapper<T> sheet(String sheetName,
                                         Consumer3<T, InSheetCell, InSheetCell> cellCellConsumer3) {
        wrappers.put(sheetName, cellCellConsumer3);
        return this;
    }

    public MultiSheetRowWrapper<T> newInstanceEachSheet() {
        newInstancePredicate = (instance, cell) -> cell.isLastRow() && cell.isLastColumn();
        return this;
    }


    public MultiSheetRowWrapper<T> allInOneInstance() {
        newInstancePredicate = (instance, cell) -> cell.isLastSheet() && cell.isLastRow() && cell.isLastColumn();
        return this;
    }

    public MultiSheetRowWrapper<T> ignoreHeader() {
        this.autoHeader = false;
        return this;
    }

    @Override
    protected boolean isWrapComplete(T instance, Cell cell) {
        if (cell instanceof BoundedCell) {
            return newInstancePredicate.test(instance, (BoundedCell) cell);
        }
        return super.isWrapComplete(instance, cell);
    }

    @Override
    protected T newInstance() {
        return instanceGetter.get();
    }

    @Override
    protected final T wrap(T instance, Cell _header, Cell _dataCell) {
        if (_dataCell instanceof InSheetCell) {
            InSheetCell header = ((InSheetCell) _header);
            InSheetCell dataCell = ((InSheetCell) _dataCell);
            Consumer3<T, InSheetCell, InSheetCell> wrapper = wrappers.get(dataCell.getSheetIndex());
            if (wrapper == null) {
                wrapper = wrappers.get(dataCell.getSheetName());
            }
            if (wrapper == null) {
                return instance;
            }
            wrapper.accept(instance, header, dataCell);
            return instance;
        }
        throw new UnsupportedOperationException("unsupported cell type : " + _header.getClass());
    }
}
