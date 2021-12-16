package org.hswebframework.reactor.excel.converter;

import lombok.Getter;
import lombok.Setter;
import org.hswebframework.reactor.excel.Cell;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

@Deprecated
public abstract class RowWrapper<T> implements Function<Cell, Mono<T>> {

    protected T current;

    protected boolean autoHeader = true;

    @Getter
    @Setter
    protected int headerIndex;

    protected volatile long currentRow;

    protected Map<Integer, Cell> headerMapping = new HashMap<>();

    protected boolean isWrapComplete(T instance,Cell cell) {
        return cell.isEndOfRow();
    }

    protected abstract T newInstance();

    protected abstract T wrap(T instance, Cell header, Cell dataCell);

    @Override
    public synchronized Mono<T> apply(Cell cell) {
        //表头不处理
        if (autoHeader && cell.getRowIndex() == getHeaderIndex()) {
            if (cell.getColumnIndex() == 0) {
                headerMapping.clear();
            }
            headerMapping.put(cell.getColumnIndex(), cell);
            return Mono.empty();
        }
        if (headerMapping.isEmpty()) {
            return Mono.empty();
        }
        //first
        if (current == null) {
            current = newInstance();
        }
        currentRow = cell.getRowIndex();
        Cell header = headerMapping.get(cell.getColumnIndex());
        if (!autoHeader || header != null) {
            current = wrap(current, header, cell);
        }
        //创建新的实例
        if (isWrapComplete(current,cell)) {
            Mono<T> result = Mono.justOrEmpty(current);
            current = null;
            return result;
        }
        return Mono.empty();
    }

    public Flux<T> convert(Flux<? extends Cell> source) {
        return Flux
                .concat(
                        source.flatMap(this),
                        Mono.fromSupplier(() -> current)
                );
    }


}
