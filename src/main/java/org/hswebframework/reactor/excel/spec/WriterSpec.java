package org.hswebframework.reactor.excel.spec;

import org.hswebframework.reactor.excel.ExcelOption;
import org.hswebframework.reactor.excel.spi.ExcelWriter;
import org.hswebframework.reactor.excel.utils.StreamUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.OutputStream;
import java.util.function.Consumer;

/**
 * excel 写出描述接口,用于描述对excel写出的逻辑
 *
 * @author zhouhao
 * @since 1.0.2
 */
public interface WriterSpec {

    /**
     * 写出到输出流,写出完成后流将被自动关闭
     *
     * @param output output
     * @return void
     */
    Mono<Void> write(OutputStream output);

    /**
     * 写出到字节,并返回字节Flux流
     *
     * @param bufferSize 缓冲区大小
     * @return 字节流
     */
    default Flux<byte[]> writeBytes(int bufferSize) {
        return StreamUtils.buffer(bufferSize, this::write);
    }

    /**
     * 其他自定义操作
     *
     * @param options 自定义操作
     * @return this
     */
    WriterSpec option(ExcelOption... options);


    static WriterSpecSelector writeFor(ExcelWriter writer) {
        return new DefaultWriterSepc(writer.isSupportMultiSheet() ? Integer.MAX_VALUE : 1, writer);
    }

    /**
     * 写出单个sheet描述,只能写出单个sheet,通常用于同时兼容csv,xlsx的写出方式.
     *
     * @param writer 写出器
     * @return SingleSheetWriterSpec
     */
    static SheetWriterSpec single(ExcelWriter writer) {
        return new DefaultWriterSepc(1, writer);
    }

    /**
     * 写出多个sheet描述,可以写出多个sheet,但是只有支持多sheet的写出器才可用.
     *
     * @param writer 写出器
     * @return MultiSheetWriterSpec
     */
    static MultiSheetWriterSpec multi(ExcelWriter writer) {
        return new DefaultWriterSepc(writer.isSupportMultiSheet() ? Integer.MAX_VALUE : 1, writer);
    }

    interface WriterSpecSelector {

        SheetWriterSpec justWrite();

        MultiSheetWriterSpec multiSheet();

    }

    /**
     * 单sheet写出描述
     */
    interface SheetWriterSpec extends WriterSpec {

        /**
         * 基于表头的写出模式,可通过指定表头以及数据行来进行写出
         *
         * @param consumer sheet描述
         * @return this
         */
        SheetWriterSpec sheet(Consumer<SheetSpec.HeaderSheetSpec> consumer);

        /**
         * 基于单元格的写出模式,可自由定义单元格所在位置
         *
         * @param consumer sheet描述
         * @return this
         */
        SheetWriterSpec cellSheet(Consumer<SheetSpec.CellSheetSpec> consumer);

        @Override
        SheetWriterSpec option(ExcelOption... options);
    }

    interface MultiSheetWriterSpec extends SheetWriterSpec {

        /**
         * 基于表头的写出模式,可通过指定表头以及数据行来进行写出
         *
         * @param index    sheet 索引号
         * @param consumer sheet描述
         * @return this
         */
        MultiSheetWriterSpec sheet(int index, Consumer<SheetSpec.HeaderSheetSpec> consumer);

        /**
         * 基于单元格的写出模式,可自由定义单元格所在位置
         *
         * @param index    sheet 索引号
         * @param consumer sheet描述
         * @return this
         */
        MultiSheetWriterSpec cellSheet(int index, Consumer<SheetSpec.CellSheetSpec> consumer);

        @Override
        MultiSheetWriterSpec option(ExcelOption... options);
    }

}
