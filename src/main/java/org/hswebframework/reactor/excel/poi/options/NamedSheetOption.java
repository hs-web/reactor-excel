package org.hswebframework.reactor.excel.poi.options;

import lombok.AllArgsConstructor;
import org.apache.poi.ss.usermodel.Sheet;

@AllArgsConstructor
class NamedSheetOption implements SheetOption {
    private final int index;
    private final String name;

    @Override
    public void sheet(Sheet sheet) {
        if (sheet.getWorkbook().getSheetIndex(sheet) == index) {
            sheet.getWorkbook().setSheetName(index, name);
        }
    }
}
