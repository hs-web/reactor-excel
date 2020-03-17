package org.hswebframework.reactor.excel;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Getter
@Setter
@NoArgsConstructor
public class ExcelHeader {

    private String key;

    private String text;

    private CellDataType type;

    public ExcelHeader(String key, String text, CellDataType type) {
        this.key = key;
        this.text = text;
        this.type = type;
    }

    private Map<String, ExcelOption<?>> options;


    public void option(ExcelOption<?> option) {
        if (options == null) {
            options = new HashMap<>();
        }
        options.put(option.getKey(), option);
    }

    @SuppressWarnings("unchecked")
    public <T> Optional<ExcelOption<T>> getOption(OptionKey<T> key) {
        return Optional.ofNullable(options)
                .map(opts -> opts.get(key.getKey()))
                .map(opt -> (ExcelOption<T>) opt);

    }

}
