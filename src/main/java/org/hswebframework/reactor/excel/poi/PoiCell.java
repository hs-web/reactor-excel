package org.hswebframework.reactor.excel.poi;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.apache.commons.compress.utils.Sets;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.hswebframework.reactor.excel.CellDataType;
import org.hswebframework.reactor.excel.BoundedCell;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.Date;
import java.util.Optional;
import java.util.Set;

import static org.apache.poi.ss.usermodel.DateUtil.isADateFormat;

@Getter
@AllArgsConstructor
class PoiCell implements BoundedCell {

    private int sheetIndex;

    private org.apache.poi.ss.usermodel.Cell cell;

    private boolean endOfRow;

    private Object value;

    PoiCell(int sheetIndex, org.apache.poi.ss.usermodel.Cell cell, boolean end) {
        this.sheetIndex = sheetIndex;
        this.cell = cell;
        this.endOfRow = end;
        this.value = convertValue();
    }

    private Object convertToTime(double value) {
        long totalSeconds = (long) (value * 24 * 60 * 60);
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours >= 0 && hours < 24 && minutes >= 0 && seconds >= 0) {
            return LocalTime.of((int) hours, (int) minutes, (int) seconds);
        }
        return value;

    }

    private Object convertValue() {
        if (cell == null)
            return null;
        switch (cell.getCellType()) {
            case BOOLEAN:
                return cell.getBooleanCellValue();
            case NUMERIC:
                if (isCellDateFormatted()) {
                    Date date = cell.getDateCellValue();
                    if (date.getTime() > 0) {
                        return date;
                    } else {
                        return convertToTime(cell.getNumericCellValue());
                    }
                }
                return convertToNumber(cell);
            case STRING:
                return cell.getRichStringCellValue().getString();
            case FORMULA:
                FormulaEvaluator evaluator = cell.getSheet().getWorkbook().getCreationHelper().createFormulaEvaluator();
                CellValue cellValue = evaluator.evaluate(cell);
                switch (cellValue.getCellType()) {
                    case BOOLEAN:
                        return cellValue.getBooleanValue();
                    case NUMERIC:
                        if (isCellDateFormatted()) {
                            Workbook workbook = cell.getRow().getSheet().getWorkbook();
                            if (workbook instanceof XSSFWorkbook) {
                                Date date = DateUtil.getJavaDate(
                                        cellValue.getNumberValue(),
                                        ((XSSFWorkbook) workbook).isDate1904());
                                if (date.getTime() > 0) {
                                    return date;
                                }else {
                                    return convertToTime(cellValue.getNumberValue());
                                }
                            }
                            return cellValue.getNumberValue();
                        }
                        return cellValue.getNumberValue();
                    case BLANK:
                        return "";
                    default:
                        return cellValue.getStringValue();
                }
            default:
                return cell.getStringCellValue();
        }
    }

    private Number convertToNumber(Cell cell) {
        BigDecimal value = new BigDecimal(cell.toString());
        if (value.scale() == 0) {
            return value.longValue();
        }
        //小数位为0时
        BigDecimal[] result = value.divideAndRemainder(BigDecimal.ONE);
        if (result[1].equals(BigDecimal.valueOf(0.0))) {
            return value.longValue();
        }
        return value;
    }

    /**
     * @see BuiltinFormats
     */
    static final Set<Integer> dateFormats = Sets.newHashSet(
            0xe, 0xf,0x10,0x11,0x12,0x13,0x14,0x15,0x16,
            0x20,0x21,0x22,0x23,0x24,0x25,
            0x2d,0x2e,0x2f,
            0x36,0x1F,

            0xa5,//  yyyy/mm/dd
            0xa6,//  m/d/yyyy h:mm
            0xa7,//  m/d/yyyy
            0xa8,//  hh:mm AM/PM
            0xa9 //  hh:mm:ss AM/PM

    );
    public boolean isCellDateFormatted() {
        if (cell == null) return false;
        boolean bDate = false;
        double d = cell.getNumericCellValue();
        if (DateUtil.isValidExcelDate(d)) {
            CellStyle style = cell.getCellStyle();
            if (style == null) return false;
            int i = style.getDataFormat();
            if (dateFormats.contains(i)){
                return true;
            }
            String f = style.getDataFormatString();
            f = f.replaceAll("[\"|\']", "").replaceAll("[年|月|日|时|分|秒|毫秒|微秒]", "");
            bDate = isADateFormat(i, f);
        }
        return bDate;
    }

    @Override
    public int getSheetIndex() {
        return sheetIndex;
    }

    @Override
    public String getSheetName() {
        return cell.getSheet().getSheetName();
    }

    @Override
    public long getRowIndex() {
        return cell.getRowIndex();
    }

    @Override
    public int getColumnIndex() {
        return cell.getColumnIndex();
    }

    @Override
    public int getNumberOfColumns() {
        return cell.getRow().getPhysicalNumberOfCells();
    }

    @Override
    public int getNumberOfSheets() {
        return cell.getSheet().getWorkbook().getNumberOfSheets();
    }

    @Override
    public int getNumberOfRows() {
        return cell.getSheet().getPhysicalNumberOfRows();
    }

    @Override
    public Optional<Object> value() {
        return Optional.ofNullable(value);
    }

    @Override
    public CellDataType getType() {
        switch (cell.getCellType()) {
            case NUMERIC:
                return CellDataType.NUMBER;
            case FORMULA:
                return CellDataType.FORMULA;
            case BOOLEAN:
                return CellDataType.BOOLEAN;
            default:
                if (isCellDateFormatted()) {
                    return CellDataType.DATE_TIME;
                }
                return CellDataType.STRING;
        }
    }

}
