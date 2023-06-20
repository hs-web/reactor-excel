package org.hswebframework.reactor.excel;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

public interface Options {

    List<ExcelOption> getOptions();

    <T extends ExcelOption> List<T> getOptions(Class<T> type);

    default <T extends ExcelOption> void handleOptions(Class<T> type, Consumer<T> consumer){
        getOptions(type).forEach(consumer);
    }

    Options merge(List<ExcelOption> options);

    Options merge(Options options);

    Options option(ExcelOption option);

    static Options of() {
        return new DefaultOptions();
    }

    static Options of(ExcelOption... options) {
        return of(Arrays.asList(options));
    }


    static Options of(List<ExcelOption> options) {
        return new DefaultOptions(options);
    }

    static Options empty() {
        return EmptyOptions.instance;
    }
}
