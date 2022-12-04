package org.hswebframework.reactor.excel.poi;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.hswebframework.reactor.excel.BoundedCell;
import org.hswebframework.reactor.excel.InSheetCell;
import org.hswebframework.reactor.excel.ExcelOption;
import org.hswebframework.reactor.excel.spi.ExcelReader;
import reactor.core.publisher.Flux;

import java.io.InputStream;

public class PoiExcelReader implements ExcelReader {
    @Override
    public String[] getSupportFormat() {
        return new String[]{"xls", "xlsx"};
    }

    @Override
    public Flux<BoundedCell> read(InputStream inputStream, ExcelOption... options) {

        return Flux.create(sink -> {
            try (Workbook wbs = WorkbookFactory.create(inputStream)){
                //获取sheets
                int sheetSize = wbs.getNumberOfSheets();
                for (int x = 0; x < sheetSize; x++) {
                    Sheet sheet = wbs.getSheetAt(x);
                    // 得到总行数
                    int rowNum = sheet.getLastRowNum();
                    if (rowNum <= 0) continue;
                    Row row = sheet.getRow(0);
                    int colNum = row.getPhysicalNumberOfCells();
                    for (int i = 0; i <= rowNum; i++) {
                        if (sink.isCancelled()) {
                            return;
                        }
                        row = sheet.getRow(i);
                        if (row == null) continue;

                        for (int j = 0; j < colNum - 1; j++) {
                            org.apache.poi.ss.usermodel.Cell cell = row.getCell(j);
                            if (cell == null) {
                                sink.next(new NullCell(x, i, j, false, rowNum, colNum, sheetSize));
                                continue;
                            }
                            sink.next(new PoiCell(x, cell, false));
                        }
                        org.apache.poi.ss.usermodel.Cell lastCell = row.getCell(colNum - 1);
                        if (lastCell == null) {
                            sink.next(new NullCell(x, i, colNum - 1, true, rowNum, colNum, sheetSize));
                            continue;
                        }
                        sink.next(new PoiCell(x, row.getCell(colNum - 1), true));
                    }
                }
                sink.complete();
            } catch (Throwable e) {
                sink.error(e);
            }
        });
    }

    @Override
    public boolean isSupportMultiSheet() {
        return true;
    }
}
