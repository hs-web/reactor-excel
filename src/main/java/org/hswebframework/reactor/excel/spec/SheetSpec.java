package org.hswebframework.reactor.excel.spec;

import org.hswebframework.reactor.excel.Cell;
import org.hswebframework.reactor.excel.ExcelHeader;
import org.hswebframework.reactor.excel.poi.options.SheetOption;
import reactor.core.publisher.Flux;

import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Sheet描述
 *
 * @author zhouhao
 * @since 1.0.2
 */
public interface SheetSpec {

    /**
     * 定义sheet名称
     *
     * @param name 名称
     * @return this
     */
    SheetSpec name(String name);

    /**
     * 自定义sheet操作
     *
     * @param options 自定义操作
     * @return this
     */
    SheetSpec option(SheetOption... options);

    /**
     * 自由定义单元格描述
     *
     * @author zhouhao
     * @since 1.0.2
     */
    interface CellSheetSpec extends SheetSpec {

        @Override
        CellSheetSpec name(String name);

        @Override
        CellSheetSpec option(SheetOption... options);

        /**
         * 设置多个单元格
         *
         * @param cells 单元格信息
         * @return this
         */
        CellSheetSpec cells(Flux<Cell> cells);

        /**
         * 设置单个单元格
         *
         * @param cell 单元格信息
         * @return this
         */
        CellSheetSpec cell(Cell cell);

        default CellSheetSpec cells(Cell... cells) {
            return cells(Arrays.asList(cells));
        }

        default CellSheetSpec cells(Iterable<Cell> cells) {
            return cells(Flux.fromIterable(cells));
        }

        default CellSheetSpec cell(int x, int y, Object value) {
            return cell(Cell.of(x, y, value));
        }
    }

    /**
     * 基于表头描述在数据中的字段(key)以及在excel中的表头.
     * 写出时,第1行为表头,第n+1行为{@link HeaderSheetSpec#rows(Flux)} }提供的值.
     * <p>
     * 如果同时使用{@link  CellSheetSpec}的API,将会同时生效
     * <pre>
     * ----表头1--表头2--表头3--
     * ----1-1----1-2---1-3---
     * ----2-1----2-2---2-3---
     * </pre>
     */
    interface HeaderSheetSpec extends CellSheetSpec {

        /**
         * 定义表头
         *
         * @param key    数据key
         * @param header 表头,在excel中显示的值
         * @return this
         */
        HeaderSheetSpec header(String key, String header);

        /**
         * 定义表头
         *
         * @param header 表头对象
         * @return this
         */
        HeaderSheetSpec header(ExcelHeader header);

        /**
         * 定义多个表头
         *
         * @param headers 表头对象
         * @return this
         */
        HeaderSheetSpec headers(Collection<ExcelHeader> headers);

        /**
         * 使用回调来进行描述
         *
         * @param handler handler
         * @return this
         */
        HeaderSheetSpec handle(Consumer<HeaderSheetSpec> handler);

        /**
         * 首行索引,从0开始
         *
         * @param index index
         * @return this
         */
        HeaderSheetSpec firstRowIndex(int index);

        /**
         * 指定行数据,map中的key为{@link ExcelHeader#getKey()}
         *
         * @return this
         */
        HeaderSheetSpec rows(Flux<Map<String, Object>> rows);

        /**
         * 指定sheet名称
         *
         * @param name 名称
         * @return this
         */
        @Override
        HeaderSheetSpec name(String name);

        @Override
        HeaderSheetSpec option(SheetOption... options);
    }

}
