package org.hswebframework.reactor.excel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;

class DefaultOptions implements Options {
    private final List<ExcelOption> options = new ArrayList<>();

    DefaultOptions(){

    }
    DefaultOptions(List<ExcelOption> options) {
        this.options.addAll(Objects.requireNonNull(options, "options can not be null"));
    }

    @Override
    public List<ExcelOption> getOptions() {
        return Collections.unmodifiableList(options);
    }

    public <T extends ExcelOption> List<T> getOptions(Class<T> type) {
        List<T> opts = new ArrayList<>();
        handleOptions(type,opts::add);
        return opts;
    }

    @Override
    public <T extends ExcelOption> void handleOptions(Class<T> type, Consumer<T> consumer) {
        for (ExcelOption option : options) {
            if(option.isWrapFor(type)){
                consumer.accept(option.unwrap(type));
            }
        }
    }

    @Override
    public Options option(ExcelOption option) {
        options.add(option);
        return this;
    }

    @Override
    public Options merge(Options options) {
        return merge(options.getOptions());
    }

    public Options merge(List<ExcelOption> options) {
        this.options.addAll(options);
        return this;
    }
}
