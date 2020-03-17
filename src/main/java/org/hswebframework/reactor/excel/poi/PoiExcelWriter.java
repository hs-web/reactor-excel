package org.hswebframework.reactor.excel.poi;

import lombok.SneakyThrows;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFDialogsheet;
import org.hswebframework.reactor.excel.ExcelOption;
import org.hswebframework.reactor.excel.WritableCell;
import org.hswebframework.reactor.excel.spi.ExcelWriter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.OutputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.Map;

public class PoiExcelWriter implements ExcelWriter {


    @Override
    public String[] getSupportFormat() {
        return new String[]{"xlsx"};
    }


    protected Workbook createWorkBook() {
        return new SXSSFWorkbook();
    }

    @SneakyThrows
    protected void writeAndClose(Workbook workbook, OutputStream stream) {
        workbook.write(stream);
        stream.close();
    }

    @Override
    public Mono<Void> write(Flux<WritableCell> dataStream,
                            OutputStream outputStream,
                            ExcelOption<?>... options) {


        return Mono.defer(() -> {

            Workbook workbook = createWorkBook();
            return dataStream
                    .doOnNext(cell -> {
                        Sheet sheet;
                        try {
                            sheet = workbook.getSheetAt(cell.getSheetIndex());
                            ((SXSSFSheet) sheet).trackAllColumnsForAutoSizing();
                        } catch (IllegalArgumentException e) {
                            sheet = workbook.createSheet();
                        }
                        if (sheet == null) {
                            sheet = workbook.createSheet();
                        }
                        int rowIndex = (int) cell.getRowIndex();
                        Row row = sheet.getRow(rowIndex);
                        if (row == null) {
                            row = sheet.createRow(rowIndex);
                        }
                        Cell poiCell = row.getCell(cell.getColumnIndex());
                        if (poiCell == null) {
                            poiCell = row.createCell(cell.getColumnIndex());
                        }
                        wrapCell(poiCell, cell);
                        if (rowIndex == 0) {
                            sheet.autoSizeColumn(cell.getColumnIndex());
                        }
                    })
                    .doFinally(s -> writeAndClose(workbook, outputStream))
                    .then();
        });
    }

    protected void wrapCell(Cell poiCell, WritableCell cell) {

        Object val = cell.value().orElse(null);
        if (val == null) {
            poiCell.setBlank();
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
}
