package org.hswebframework.reactor.excel.spec;


import org.hswebframework.reactor.excel.BoundedCell;
import org.hswebframework.reactor.excel.Cell;
import org.hswebframework.reactor.excel.ExcelOption;
import org.hswebframework.reactor.excel.spi.ExcelReader;
import org.hswebframework.reactor.excel.utils.StreamUtils;
import reactor.core.publisher.Flux;
import reactor.function.Consumer3;

import java.io.InputStream;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Supplier;

public interface ReaderSpec<T> {

    /**
     * 设置每个sheet使用同一个实例
     *
     * @return this
     */
    ReaderSpec<T> oneInstanceEachSheet();

    /**
     * 设置所有sheet使用同一个实例
     *
     * @return this
     */
    ReaderSpec<T> oneInstanceAllSheets();

    /**
     * 设置一行使用一个实例
     *
     * @return this
     */
    ReaderSpec<T> oneInstanceEachRow();

    /**
     * 跳过读取行
     *
     * @param rowIndex 行号
     * @return this
     */
    ReaderSpec<T> skipRow(int rowIndex);

    /**
     * 从输入流中读取数据,并按描述解析.此操作不会关闭输入流
     *
     * @param stream  输入流
     * @param options 自定义读取选项
     * @return 数据流
     */
    Flux<T> read(InputStream stream,
                 ExcelOption... options);

    /**
     * 从输入流中读取数据,并按描述解析.此操作在读取完毕后自动关闭流
     *
     * @param stream  输入流
     * @param options 自定义读取选项
     * @return 数据流
     */
    default Flux<T> readAndClose(InputStream stream,
                                 ExcelOption... options) {
        return Flux.using(() -> stream, _stream -> read(_stream, options), StreamUtils::safeClose);
    }


    /**
     * 指定读取器和实例提供器来创建一个描述选择器,如:
     * <pre>
     *
     * </pre>
     *
     * @param reader           读取器
     * @param instanceSupplier 实例提供器
     * @return 数据流
     */
    static <T> ReaderSpecSelector<T> readFor(ExcelReader reader, Supplier<T> instanceSupplier) {
        return new DefaultReaderSpec<>(reader, instanceSupplier);
    }

    /**
     * 读取方式选择器
     *
     * @param <T>
     */
    interface ReaderSpecSelector<T> {

        /**
         * 直接读取
         *
         * @return SheetReaderSpec
         */
        SheetReaderSpec<T> justRead();

        /**
         * 基于header直接读取
         *
         * @return SheetHeaderReaderSpec
         */
        SheetHeaderReaderSpec<T> justReadByHeader();

        /**
         * 读取多个sheet,可指定不同sheet的包装方式
         *
         * @return SheetHeaderReaderSpec
         */
        MultiSheetCellReaderSpec<T> multiSheet();

        /**
         * 根据表头来读取多个sheet
         *
         * @return MultiSheetHeaderReaderSpec
         */
        MultiSheetHeaderReaderSpec<T> multiSheetByHeader();

    }

    interface SheetReaderSpec<T> extends ReaderSpec<T> {

        /**
         * 自定义包装器,将单元格的数据填充到实例中
         *
         * @param wrapper 包装器
         * @return SheetReaderSpec
         */
        SheetReaderSpec<T> wrapper(BiConsumer<T, Cell> wrapper);

        @Override
        SheetReaderSpec<T> oneInstanceEachSheet();

        @Override
        SheetReaderSpec<T> oneInstanceAllSheets();

        @Override
        SheetReaderSpec<T> oneInstanceEachRow();

        SheetReaderSpec<T> instanceCondition(BiPredicate<T, Cell> predicate);

        @Override
        SheetReaderSpec<T> skipRow(int rowIndex);
    }

    interface SheetHeaderReaderSpec<T> extends ReaderSpec<T> {

        /**
         * 指定表头映射，将excel中的表头映射为新的表头，如:
         * <pre>
         *      spec
         *      .header("ID","id")
         *      .header("名称","name")
         * </pre>
         *
         * @param from excel中的表头
         * @param to   新的表头
         * @return this
         */
        SheetHeaderReaderSpec<T> header(String from, String to);

        /**
         * 指定多个表头映射，将excel中的表头映射为新的表头，如:
         * <pre>
         *      spec
         *      .headers({"ID":"id","名称":"name"})
         * </pre>
         *
         * @param headers 映射表
         * @return this
         */
        SheetHeaderReaderSpec<T> headers(Map<String, String> headers);

        /**
         * 指定包装器
         *
         * @param wrapper 包装器
         * @return this
         */
        SheetReaderSpec<T> wrapper(Consumer3<T, String, Cell> wrapper);

        @Override
        MultiSheetReaderSpec<T> oneInstanceEachSheet();

        @Override
        MultiSheetReaderSpec<T> oneInstanceAllSheets();

        @Override
        MultiSheetReaderSpec<T> oneInstanceEachRow();

        SheetReaderSpec<T> instanceCondition(BiPredicate<T, ? extends Cell> predicate);

        @Override
        SheetReaderSpec<T> skipRow(int rowIndex);


        MultiSheetHeaderReaderSpec<T> headerRow(int rowIndex);
    }

    interface MultiSheetReaderSpec<T> extends ReaderSpec<T> {

        /**
         * 每一个sheet创建一个实例
         *
         * @return this
         */
        @Override
        MultiSheetReaderSpec<T> oneInstanceEachSheet();

        @Override
        MultiSheetReaderSpec<T> oneInstanceAllSheets();

        @Override
        MultiSheetReaderSpec<T> oneInstanceEachRow();

        MultiSheetReaderSpec<T> instanceCondition(BiPredicate<T, ? extends BoundedCell> predicate);

        @Override
        MultiSheetReaderSpec<T> skipRow(int rowIndex);

    }

    interface MultiSheetCellReaderSpec<T> extends MultiSheetReaderSpec<T> {

        MultiSheetCellReaderSpec<T> sheet(int index, BiConsumer<T, BoundedCell> wrapper);

        MultiSheetCellReaderSpec<T> sheet(String name, BiConsumer<T, BoundedCell> wrapper);

        @Override
        MultiSheetCellReaderSpec<T> oneInstanceEachRow();

        @Override
        MultiSheetCellReaderSpec<T> oneInstanceEachSheet();

        @Override
        MultiSheetCellReaderSpec<T> oneInstanceAllSheets();

        @Override
        MultiSheetCellReaderSpec<T> instanceCondition(BiPredicate<T, ? extends BoundedCell> predicate);

        @Override
        MultiSheetCellReaderSpec<T> skipRow(int rowIndex);

    }

    interface MultiSheetHeaderReaderSpec<T> extends MultiSheetReaderSpec<T> {

        /**
         * 指定表头映射，将excel中的表头映射为新的表头，如:
         * <pre>
         *      spec
         *      .header("ID","id")
         *      .header("名称","name")
         * </pre>
         *
         * @param from excel中的表头
         * @param to   新的表头
         * @return this
         */
        MultiSheetHeaderReaderSpec<T> header(String from, String to);

        /**
         * 指定多个表头映射，将excel中的表头映射为新的表头，如:
         * <pre>
         *      spec
         *      .headers({"ID":"id","名称":"name"})
         * </pre>
         *
         * @param headers 映射表
         * @return this
         */
        MultiSheetHeaderReaderSpec<T> headers(Map<String, String> headers);

        /**
         * 自定义sheet包装器
         *
         * @param index   sheet序号,从0开始
         * @param wrapper 包装器
         * @return this
         */
        MultiSheetHeaderReaderSpec<T> sheet(int index, Consumer3<T, String, BoundedCell> wrapper);


        /**
         * 自定义sheet包装器
         *
         * @param name    sheet名称,从0开始
         * @param wrapper 包装器
         * @return this
         */
        MultiSheetHeaderReaderSpec<T> sheet(String name, Consumer3<T, String, BoundedCell> wrapper);

        @Override
        MultiSheetHeaderReaderSpec<T> oneInstanceEachRow();

        @Override
        MultiSheetHeaderReaderSpec<T> oneInstanceEachSheet();

        @Override
        MultiSheetHeaderReaderSpec<T> oneInstanceAllSheets();

        @Override
        MultiSheetHeaderReaderSpec<T> instanceCondition(BiPredicate<T, ? extends BoundedCell> predicate);

        @Override
        MultiSheetHeaderReaderSpec<T> skipRow(int rowIndex);

        MultiSheetHeaderReaderSpec<T> headerRow(int rowIndex);
    }

}
