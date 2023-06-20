package org.hswebframework.reactor.excel.spec;

import lombok.Getter;
import org.hswebframework.reactor.excel.*;
import org.hswebframework.reactor.excel.converter.MapRowExpander;
import org.hswebframework.reactor.excel.poi.options.SheetOption;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.function.Consumer;

class DefaultSheetSpec implements SheetSpec.HeaderSheetSpec, SheetSpec {

    private final MapRowExpander expander;

    private long firstRowIndex = 0;
    private Flux<Cell> cells = Flux.empty();

    private final List<Cell> fixedCells = new ArrayList<>();

    private Flux<Map<String, Object>> rows = Flux.empty();

    private final List<SheetOption> options = new ArrayList<>();

    @Getter
    private String name;

    public DefaultSheetSpec(int sheetIndex) {
        this.expander = new MapRowExpander(sheetIndex);
    }

    @Override
    public CellSheetSpec cells(Flux<Cell> cells) {
        this.cells = Flux.concat(this.cells, cells);
        return this;
    }

    @Override
    public CellSheetSpec cells(Iterable<Cell> cells) {
        for (Cell cell : cells) {
            fixedCells.add(cell);
        }
        return this;
    }

    @Override
    public CellSheetSpec cell(Cell cell) {
        fixedCells.add(cell);
        return this;
    }

    @Override
    public DefaultSheetSpec name(String name) {
        this.name = name;
        return this;
    }

    @Override
    public DefaultSheetSpec rows(Flux<Map<String, Object>> rows) {
        this.rows = rows;
        return this;
    }

    @Override
    public DefaultSheetSpec firstRowIndex(int index) {
        this.firstRowIndex = index;
        return this;
    }

    public DefaultSheetSpec header(String key, String header) {
        expander.header(key, header);
        return this;
    }

    public DefaultSheetSpec header(ExcelHeader header) {
        expander.header(header);
        return this;
    }

    public DefaultSheetSpec headers(Collection<ExcelHeader> headers) {
        expander.headers(headers);
        return this;
    }

    @Override
    public DefaultSheetSpec handle(Consumer<HeaderSheetSpec> handler) {
        handler.accept(this);
        return this;
    }

    @Override
    public DefaultSheetSpec option(SheetOption... options) {
        this.options.addAll(Arrays.asList(options));
        return this;
    }

    List<SheetOption> getOptions() {
        return options;
    }

    Flux<Cell> cells() {
        fixedCells.sort(Comparator.comparingLong(Cell::getRowIndex));

        return Flux.concat(
                expander.headers(firstRowIndex),
                Flux.fromIterable(fixedCells),
                rows.index().concatMap((idx) -> expander.apply(idx.getT1() + firstRowIndex + 1, idx.getT2())),
                cells);
    }

}
