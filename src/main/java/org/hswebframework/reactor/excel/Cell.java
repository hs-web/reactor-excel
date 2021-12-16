package org.hswebframework.reactor.excel;

import java.util.Optional;

/**
 * 单元格接口,表示一个单元格的信息
 *
 * @author zhouhao
 * @since 1.0
 */
public interface Cell {

    /**
     * 当前单元格所在行号,从0开始
     *
     * @return 行号
     */
    long getRowIndex();

    /**
     * 当前单元格所在列号,从0开始
     *
     * @return 列号
     */
    int getColumnIndex();

    /**
     * 当前单元格是否为当前行的最后一列
     *
     * @return 是否为最后一列
     */
    boolean isEndOfRow();

    /**
     * 获取单元格的值，如果值为<code>null</code>则返回{@link  Optional#empty()}
     *
     * @return 单元格的值
     */
    Optional<Object> value();

    /**
     * 将单元格的值转为文本
     *
     * @return 单元格的值
     */
    default Optional<String> valueAsText() {
        return value()
                .map(String::valueOf);
    }

    /**
     * 单元格数据类型
     *
     * @return 数据类型
     */
    CellDataType getType();

    /**
     * 创建一个简单的单元格对象,通常用于自定义写入时使用
     *
     * @param row    行号
     * @param column 列号
     * @param value  单元格的值
     * @return 单元格
     */
    static Cell of(int row, int column, Object value) {
        return new SimpleCell(row, column, false, value, CellDataType.AUTO);
    }

    /**
     * 创建一个简单的单元格对象,通常用于自定义写入时使用
     *
     * @param row      行号
     * @param column   列号
     * @param endOfRow 是否为最后一列
     * @param value    单元格的值
     * @return 单元格
     */
    static Cell of(int row, int column, boolean endOfRow, Object value) {
        return new SimpleCell(row, column, endOfRow, value, CellDataType.AUTO);
    }


    /**
     * 创建一个指定类型的简单的单元格对象,通常用于自定义写入时使用
     *
     * @param row      行号
     * @param column   列号
     * @param endOfRow 是否为最后一列
     * @param value    单元格的值
     * @param type     类型
     * @return 单元格
     */
    static Cell of(int row, int column, boolean endOfRow, Object value, CellDataType type) {
        return new SimpleCell(row, column, endOfRow, value, type);
    }
}
