package org.hswebframework.reactor.excel;

import java.util.Collections;
import java.util.List;

final class EmptyOptions implements Options {

    static EmptyOptions instance = new EmptyOptions();

    @Override
    public List<ExcelOption> getOptions() {
        return Collections.emptyList();
    }

    @Override
    public <T extends ExcelOption> List<T> getOptions(Class<T> type) {
        return Collections.emptyList();
    }

    @Override
    public Options merge(Options options) {
        return options;
    }

    @Override
    public Options option(ExcelOption option) {
        return Options.of().option(option);
    }

    @Override
    public Options merge(List<ExcelOption> options) {
        return Options.of(options);
    }
}
