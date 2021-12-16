package org.hswebframework.reactor.excel.spec;

import org.hswebframework.reactor.excel.BoundedCell;
import org.hswebframework.reactor.excel.Cell;
import org.hswebframework.reactor.excel.ExcelOption;
import org.hswebframework.reactor.excel.InSheetCell;
import org.hswebframework.reactor.excel.spi.ExcelReader;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.function.Consumer3;

import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Supplier;

class DefaultReaderSpec<T> implements ReaderSpec.MultiSheetHeaderReaderSpec<T>,
        ReaderSpec.MultiSheetCellReaderSpec<T>,
        ReaderSpec.SheetHeaderReaderSpec<T>,
        ReaderSpec.SheetReaderSpec<T>,
        ReaderSpec.ReaderSpecSelector<T> {

    private final Object GLOBAL_WRAPPER = new Object();

    private final Map<Object, Consumer3<T, String, Cell>> wrappers = new HashMap<>();

    private final ExcelReader reader;

    private final Supplier<? extends T> instanceSupplier;

    private final Set<Long> skipRows = new HashSet<>();

    private final Map<String, String> headers = new HashMap<>();

    private int headerRow = 0;
    private int firstHeaderColumnIndex = 0;

    private BiPredicate<T, InSheetCell> newInstancePredicate = (t, cell) -> cell.isEndOfRow();

    private boolean headerMode = false;

    public DefaultReaderSpec(ExcelReader reader, Supplier<? extends T> instanceSupplier) {
        this.reader = reader;
        this.instanceSupplier = instanceSupplier;
    }

    @Override
    public Flux<T> read(InputStream stream, ExcelOption... options) {
        if (wrappers.size() == 0) {
            //未设置包装器,调用wrapper方法或者sheet方法进行设置
            throw new UnsupportedOperationException("wrapper can not be empty.");
        }
        return transfer(
                reader.read(stream, options)
        );
    }

    Flux<T> transfer(Flux<? extends Cell> cells) {

        AtomicReference<T> current = new AtomicReference<>();
        Map<Integer, InSheetCell> headerMapping = new HashMap<>();

        return cells
                .cast(InSheetCell.class)
                .<T>handle((cell, sink) -> {
                    if (skipRows.contains(cell.getRowIndex())) {
                        return;
                    }
                    //表头模式时,填充表头
                    if (headerMode) {
                        if (cell.getRowIndex() == headerRow) {
                            headerMapping.put(cell.getColumnIndex(), cell);
                            return;
                        }
                    }

                    T instance = current.get();
                    if (instance == null) {
                        current.set(instance = instanceSupplier.get());
                    }
                    //获取包装器
                    Consumer3<T, String, Cell> wrapper = wrappers.get(GLOBAL_WRAPPER);
                    if (wrapper == null) {
                        wrapper = wrappers.get(cell.getSheetIndex());
                    }
                    if (wrapper == null) {
                        wrapper = wrappers.get(cell.getSheetName());
                    }
                    //没有包装器则忽略
                    if (wrapper == null) {
                        return;
                    }
                    if (headerMode) {
                        InSheetCell header = headerMapping.get(cell.getColumnIndex());
                        String headerText = null;
                        if (header != null) {
                            headerText = header.valueAsText().orElse(null);
                            if (headerText != null) {
                                //如果指定了表头映射,则必须满足映射
                                if (headers.size() > 0) {
                                    headerText = headers.get(headerText);
                                }
                            }
                        }
                        //表头不为null才包装
                        if (headerText != null) {
                            wrapper.accept(instance, headerText, cell);
                        }
                    } else {
                        wrapper.accept(instance, null, cell);
                    }

                    if (newInstancePredicate.test(instance, cell)) {
                        sink.next(instance);
                        current.set(null);
                    }
                })
                .concatWith(Mono.fromSupplier(current::get));

    }

    @Override
    public DefaultReaderSpec<T> sheet(int index, BiConsumer<T, BoundedCell> wrapper) {
        return addBoundedWrapper(index, (instance, header, cell) -> wrapper.accept(instance, cell));
    }

    @Override
    public DefaultReaderSpec<T> sheet(String name, BiConsumer<T, BoundedCell> wrapper) {
        return addBoundedWrapper(name, (instance, header, cell) -> wrapper.accept(instance, cell));
    }

    @Override
    public DefaultReaderSpec<T> header(String from, String to) {
        headerMode = true;
        this.headers.put(from, to);
        return this;
    }

    @Override
    public DefaultReaderSpec<T> headers(Map<String, String> headers) {
        headerMode = true;
        this.headers.putAll(headers);
        return this;
    }

    @Override
    public DefaultReaderSpec<T> sheet(int index, Consumer3<T, String, BoundedCell> wrapper) {
        headerMode = true;
        return addBoundedWrapper(index, wrapper);
    }

    @Override
    public DefaultReaderSpec<T> sheet(String name, Consumer3<T, String, BoundedCell> wrapper) {
        headerMode = true;
        return addBoundedWrapper(name, wrapper);
    }

    private DefaultReaderSpec<T> addWrapper(Object key, Consumer3<T, String, Cell> wrapper) {
        wrappers.put(key, wrapper);
        return this;
    }

    private DefaultReaderSpec<T> addBoundedWrapper(Object key, Consumer3<T, String, BoundedCell> wrapper) {
        checkReaderSupportMultiSheet();
        wrappers.put(key, (t, header, cell) -> {
            wrapper.accept(t, header, ((BoundedCell) cell));
        });
        return this;
    }


    @Override
    public DefaultReaderSpec<T> oneInstanceEachRow() {
        return instanceCondition((instance, cell) -> ((Cell) cell).isEndOfRow());
    }

    @Override
    public DefaultReaderSpec<T> oneInstanceEachSheet() {
        checkReaderSupportMultiSheet();
        return instanceCondition((instance, cell) -> ((BoundedCell) cell).isLastRow() && ((BoundedCell) cell).isLastColumn());
    }

    @Override
    public DefaultReaderSpec<T> oneInstanceAllSheets() {
        return instanceCondition((instance, cell) -> false);
    }


    @Override
    public DefaultReaderSpec<T> wrapper(BiConsumer<T, Cell> wrapper) {
        return addWrapper(GLOBAL_WRAPPER, (t, header, cell) -> wrapper.accept(t, cell));
    }

    @Override
    public DefaultReaderSpec<T> wrapper(Consumer3<T, String, Cell> wrapper) {
        headerMode = true;
        return addWrapper(GLOBAL_WRAPPER, wrapper);
    }


    @Override
    @SuppressWarnings("all")
    public DefaultReaderSpec<T> instanceCondition(BiPredicate predicate) {
        this.newInstancePredicate = predicate;
        return this;
    }

    @Override
    public DefaultReaderSpec<T> skipRow(int rowIndex) {
        skipRows.add((long) rowIndex);
        return this;
    }

    @Override
    public DefaultReaderSpec<T> headerRow(int rowIndex) {
        headerMode = true;
        this.headerRow = rowIndex;
        return this;
    }

    @Override
    public SheetReaderSpec<T> justRead() {
        return this;
    }

    @Override
    public SheetHeaderReaderSpec<T> justReadByHeader() {
        headerMode = true;
        return this;
    }

    @Override
    public MultiSheetCellReaderSpec<T> multiSheet() {
        return this;
    }

    @Override
    public MultiSheetHeaderReaderSpec<T> multiSheetByHeader() {
        headerMode = true;
        return this;
    }

    public void checkReaderSupportMultiSheet() {
        if (!reader.isSupportMultiSheet()) {
            throw new UnsupportedOperationException("reader " + reader + " not supported multi sheet");
        }
    }
}
