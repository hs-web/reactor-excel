package org.hswebframework.reactor.excel;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

class DefaultOptions implements Options {
    private List<ExcelOption> options = new ArrayList<>();

    DefaultOptions(){

    }
    DefaultOptions(List<ExcelOption> options) {
        this.options.addAll(Objects.requireNonNull(options, "options can not be null"));
    }

    @Override
    public List<ExcelOption> getOptions() {
        return options;
    }

    public <T extends ExcelOption> List<T> getOptions(Class<T> type) {
        return options
                .stream()
                .filter(opt -> type.isAssignableFrom(opt.getType()))
                .map(opt -> opt.unwrap(type))
                .collect(Collectors.toList());
    }

    @Override
    public Options option(ExcelOption option) {
        options.add(option);
        return this;
    }

    @Override
    public Options merge(Options options) {
        merge(options.getOptions());
        return this;
    }

    public Options merge(List<ExcelOption> options) {
        this.options.addAll(options);
        return this;
    }
}
