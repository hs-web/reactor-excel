package org.hswebframework.reactor.excel;

/**
 * 有界的单元格,提供整个表格的信息: 总行号,总列数,总sheet数.
 *
 * @author zhouhao
 * @since 1.0.2
 */
public interface BoundedCell extends InSheetCell {

    int getNumberOfRows();

    int getNumberOfColumns();

    int getNumberOfSheets();

    default boolean isLastColumn() {
        return getColumnIndex() == getNumberOfColumns() - 1;
    }

    default boolean isLastRow() {
        return getRowIndex() == getNumberOfRows() - 1;
    }

    default boolean isFirstColumn() {
        return getColumnIndex() == 0;
    }

    default boolean isLastSheet() {
        return getSheetIndex() == getNumberOfSheets() - 1;
    }


    static BoundedCell of(BoundedCell source, Object value) {
        return new ReplaceValueCell(source, value);
    }
}
