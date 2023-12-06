package org.hswebframework.reactor.excel.poi.options;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFDataValidationHelper;

public class AddNormalPullDownSheetOption implements SheetOption {
    private final int index;
    //下拉选项
    private final String[] data;
    //起始行(从0开始)
    private final int firstRow;
    //终止行
    private final int endRow;
    //起始列(从0开始)
    private final int firstCol;
    //终止列
    private final int endCol;
    private final int total;

    //excel最大行
    public static final int MAX_ROW = 1048575;

    public AddNormalPullDownSheetOption(int index, int firstRow, int endRow, int firstCol, int endCol, String... data) {
        this.index = index;
        this.data = data;
        this.firstRow = firstRow;
        this.endRow = endRow;
        this.firstCol = firstCol;
        this.endCol = endCol;
        this.total = initTotal();
    }


    @Override
    public void sheet(Sheet sheet) {
        if (sheet.getWorkbook().getSheetIndex(sheet) == index) {
            DataValidationHelper helper = sheet.getDataValidationHelper();
            if (!(helper instanceof XSSFDataValidationHelper)) {
                return;
            }
            if (total <= 255) {
                addValidationData(sheet, helper.createExplicitListConstraint(data));
            } else {
                // FIXME: 2023/12/6 注意:此方法会生成新sheet，目前只适用于单sheet模式
                if (index > 0) {
                    return;
                }
                String listFormulaSheet = toString();
                Workbook workbook = sheet.getWorkbook();
                //填充数据
                Sheet hidden = createSheet(workbook, 1, listFormulaSheet);
                for (int i = 0, length = data.length; i < length; i++) {
                    //从第一列第一行开始往下填充
                    hidden.createRow(i).createCell(0).setCellValue(data[i]);
                }
                workbook.setSheetHidden(1, true);
                //关联公式
                createListFormula(workbook, listFormulaSheet, listFormulaSheet + "!$A$1:$A$" + (data.length));
                addValidationData(sheet, helper.createFormulaListConstraint(listFormulaSheet));
            }
        }
    }


    public void addValidationData(Sheet sheet, DataValidationConstraint dataValidationConstraint) {
        DataValidationHelper helper = sheet.getDataValidationHelper();
        DataValidation validation = helper.createValidation(dataValidationConstraint, new CellRangeAddressList(firstRow, endRow, firstCol, endCol));
        validation.setSuppressDropDownArrow(true);
        validation.setShowErrorBox(true);
        sheet.addValidationData(validation);
    }

    private void createListFormula(Workbook workbook, String hiddenName, String formulaText) {
        Name listFormula = workbook.createName();
        listFormula.setNameName(hiddenName);
        listFormula.setRefersToFormula(formulaText);
    }

    private Sheet createSheet(Workbook workbook, int index, String name) {
        try {
            return workbook.getSheetAt(index);
        } catch (IllegalArgumentException e) {
            return workbook.createSheet(name);
        }
    }

    /**
     * 计算下拉项配置的长度，excel要求默认配置方式不得大于255
     *
     * @return 下拉项配置的长度
     */
    private int initTotal() {
        int total = 0;
        for (String str : data) {
            total += str.length();
        }
        return total + data.length - 1;
    }

    @Override
    public String toString() {
        return "sheet" + index + "_" + firstRow + "." + endRow + "_" + firstCol + "." + endCol;
    }

}
