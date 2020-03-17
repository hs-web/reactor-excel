package org.hswebframework.reactor.excel.converter;

import org.hswebframework.reactor.excel.Cell;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

public abstract class RowWrapper<T> implements Function<Cell, Mono<T>> {

    protected T current;

    protected abstract int getHeaderIndex();

    volatile long currentRow;

    Map<Integer, Cell> headerMapping = new HashMap<>();

    protected abstract T newInstance();

    protected abstract T wrap(T instance, Cell header, Cell dataCell);

    @Override
    public Mono<T> apply(Cell cell) {
        //表头不处理
        if (cell.getRowIndex() == getHeaderIndex()) {
            headerMapping.put(cell.getColumnIndex(), cell);
            return Mono.empty();
        }
        if (headerMapping.isEmpty()) {
            return Mono.empty();
        }
        if (current == null) {
            current = newInstance();
        }
        currentRow = cell.getRowIndex();
        current = wrap(current, headerMapping.get(cell.getColumnIndex()), cell);
        //新的一行
        if (cell.isEnd()) {
            Mono<T> result = Mono.justOrEmpty(current);
            current = newInstance();
            return result;
        }
        return Mono.empty();
    }

}
