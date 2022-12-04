package org.hswebframework.reactor.excel.poi;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.hswebframework.reactor.excel.ExcelOption;
import org.hswebframework.reactor.excel.OptionSupport;
import org.hswebframework.reactor.excel.Options;
import org.hswebframework.reactor.excel.WritableCell;
import org.hswebframework.reactor.excel.poi.options.CellOption;
import org.hswebframework.reactor.excel.poi.options.RowOption;
import org.hswebframework.reactor.excel.poi.options.SheetOption;
import org.hswebframework.reactor.excel.poi.options.WorkbookOption;
import org.hswebframework.reactor.excel.spi.ExcelWriter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.OutputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.function.Consumer;

@Slf4j
public class PoiExcelWriter implements ExcelWriter {

    @Override
    public String[] getSupportFormat() {
        return new String[]{ExcelWriter.FORMAT_XLSX};
    }

    protected Workbook createWorkBook() {
        return new SXSSFWorkbook();
    }

    @SneakyThrows
    protected void writeAndClose(Workbook workbook, OutputStream stream) {
        try {
            workbook.write(stream);
            workbook.close();
            stream.flush();
            stream.close();
        } catch (Throwable e) {
            log.error(e.getMessage(), e);
            throw e;
        }
    }

    private void handleWriteOption(Workbook workbook, Options... options) {
        for (Options opts : options) {
            opts.handleOptions(WorkbookOption.class, opt -> opt.workbook(workbook));
        }
    }

    private void handleWriteOption(Sheet sheet, Options... options) {
        for (Options opts : options) {
            opts.handleOptions(SheetOption.class, opt -> opt.sheet(sheet));
        }
    }

    private void handleWriteOption(Row row, Options... options) {
        for (Options opts : options) {
            opts.handleOptions(RowOption.class, opt -> opt.row(row));
        }
    }

    private void handleWriteOption(Cell poiCell, WritableCell cell, Options... options) {
        for (Options opts : options) {
            opts.handleOptions(CellOption.class, opt -> opt.cell(poiCell, cell));
        }
    }

    static Comparator<WritableCell> comparator = Comparator
            .comparing(WritableCell::getSheetIndex)
            .thenComparing(WritableCell::getRowIndex)
            .thenComparing(WritableCell::getColumnIndex);

    @Override
    public Mono<Void> write(Flux<WritableCell> dataStream,
                            OutputStream outputStream,
                            ExcelOption... options) {

        return Mono.defer(() -> {
            Options opts = options.length > 0 ? Options.of(Arrays.asList(options)) : Options.empty();

            Workbook workbook = createWorkBook();
            handleWriteOption(workbook, opts);

            return dataStream
                    .sort(comparator)
                    .doOnNext(cell -> {
                        Sheet sheet;
                        Options cellOpts = cell instanceof OptionSupport ? ((OptionSupport) cell).options() : Options.empty();
                        try {
                            sheet = workbook.getSheetAt(cell.getSheetIndex());
                        } catch (IllegalArgumentException e) {
                            sheet = workbook.createSheet();
                            handleWriteOption(sheet, opts, cellOpts);
                        }
                        int rowIndex = (int) cell.getRowIndex();
                        Row row = sheet.getRow(rowIndex);
                        if (row == null) {
                            row = sheet.createRow(rowIndex);
                            handleWriteOption(row, opts, cellOpts);
                        }
                        Cell poiCell = row.getCell(cell.getColumnIndex());
                        if (poiCell == null) {
                            poiCell = row.createCell(cell.getColumnIndex());
                        }
                        wrapCell(poiCell, cell);
                        handleWriteOption(poiCell, cell, opts, cellOpts);
                    })
                    .then(Mono.fromRunnable(() -> writeAndClose(workbook, outputStream)))
                    .then();
        });
    }

    protected void wrapCell(Cell poiCell, WritableCell cell) {

        Object val = cell.value().orElse(null);
        if (val == null) {
            poiCell.setBlank();
            return;
        }

        switch (cell.getType()) {
            case BOOLEAN:
                poiCell.setCellValue((Boolean) val);
                break;
            case NUMBER:
                if (val instanceof Number) {
                    poiCell.setCellValue(((Number) val).doubleValue());
                }
                poiCell.setCellValue(String.valueOf(val));
                break;
            case DATE_TIME:
                if (val instanceof Long) {
                    val = new Date();
                }
                if (val instanceof Date) {
                    poiCell.setCellValue((Date) val);
                } else if (val instanceof LocalDate) {
                    poiCell.setCellValue((LocalDate) val);
                } else if (val instanceof LocalDateTime) {
                    poiCell.setCellValue((LocalDateTime) val);
                }
                poiCell.setCellValue(String.valueOf(val));
                break;
            case FORMULA:
                poiCell.setCellFormula(String.valueOf(val));
                break;
            default:
                poiCell.setCellValue(String.valueOf(val));
                break;
        }

    }

    @Override
    public boolean isSupportMultiSheet() {
        return true;
    }
}
