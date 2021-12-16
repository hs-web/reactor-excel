package org.hswebframework.reactor.excel;

/**
 * 在sheet中的单元格
 *
 * @author zhouhao
 * @since 1.0.2
 */
public interface InSheetCell extends Cell {

    /**
     * @return sheet索引
     */
    int getSheetIndex();

    /**
     * @return sheet名称
     */
    default String getSheetName() {
        return null;
    }


}
