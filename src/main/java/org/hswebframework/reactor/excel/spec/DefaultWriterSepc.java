package org.hswebframework.reactor.excel.spec;

import lombok.AllArgsConstructor;
import org.apache.poi.ss.usermodel.Sheet;
import org.hswebframework.reactor.excel.ExcelOption;
import org.hswebframework.reactor.excel.WritableCell;
import org.hswebframework.reactor.excel.context.Context;
import org.hswebframework.reactor.excel.poi.options.PoiWriteOptions;
import org.hswebframework.reactor.excel.poi.options.SheetOption;
import org.hswebframework.reactor.excel.spi.ExcelWriter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.OutputStream;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@AllArgsConstructor
class DefaultWriterSepc implements WriterSpec.MultiSheetWriterSpec, WriterSpec.WriterSpecSelector {

    private final List<ExcelOption> options = new CopyOnWriteArrayList<>();

    private final Map<Integer, Flux<WritableCell>> cells = new LinkedHashMap<>();

    private int maxSheetSize;

    private ExcelWriter writer;

    @Override
    public Mono<Void> write(OutputStream output) {
        return writer.write(
                Flux.concat(cells.values()),
                output,
                options.toArray(new ExcelOption[0])
        );
    }

    @Override
    public MultiSheetWriterSpec option(ExcelOption... options) {
        this.options.addAll(Arrays.asList(options));
        return this;
    }

    @Override
    public DefaultWriterSepc sheet(Consumer<SheetSpec.HeaderSheetSpec> consumer) {
        return sheet(cells.size(), consumer);
    }

    @Override
    public DefaultWriterSepc cellSheet(Consumer<SheetSpec.CellSheetSpec> consumer) {
        return cellSheet(cells.size(), consumer);
    }

    @Override
    public DefaultWriterSepc sheet(int index, Consumer<SheetSpec.HeaderSheetSpec> consumer) {
        checkSheetSize();

        DefaultSheetSpec sheetSpec = new DefaultSheetSpec(index);
        consumer.accept(sheetSpec);

        this.cells.put(index, sheetSpec.cells()
                                       .map(cell -> WritableCell.of(cell, index)));

        if (null != sheetSpec.getName() && !sheetSpec.getName().isEmpty()) {
            option(PoiWriteOptions.sheetName(index, sheetSpec.getName()));
        }
        addSheetOption(index, sheetSpec.getOptions());
        return this;
    }

    @Override
    public DefaultWriterSepc cellSheet(int index, Consumer<SheetSpec.CellSheetSpec> consumer) {
        checkSheetSize();
        return sheet(index, consumer::accept);
    }

    private void addSheetOption(int index, Collection<SheetOption> options) {
        this.options.add(new SheetOption() {
            @Override
            public void sheet(Sheet sheet) {
                //只处理相同sheet的操作
                if (sheet.getWorkbook().getSheetIndex(sheet) == index) {
                    for (SheetOption option : options) {
                        option.sheet(sheet);
                    }
                }
            }

            @Override
            public void sheet(Sheet sheet, Context context) {
                //只处理相同sheet的操作
                if (sheet.getWorkbook().getSheetIndex(sheet) == index) {
                    for (SheetOption option : options) {
                        option.sheet(sheet, context);
                    }
                }
            }
        });
    }

    private void checkSheetSize() {
        if (cells.size() >= maxSheetSize) {
            throw new IllegalStateException("Out of Sheet size limit : " + maxSheetSize);
        }
    }

    @Override
    public SheetWriterSpec justWrite() {
        return this;
    }

    @Override
    public MultiSheetWriterSpec multiSheet() {
        if (!writer.isSupportMultiSheet()) {
            throw new UnsupportedOperationException("Writer " + writer + " unsupported multi sheet");
        }
        return this;
    }
}
