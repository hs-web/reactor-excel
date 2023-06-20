package org.hswebframework.reactor.excel;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

@Getter
@Setter
@NoArgsConstructor
public class ExcelHeader implements OptionSupport {

    private String key;

    private String text;

    private CellDataType type;

    private Options options = Options.of();

    public ExcelHeader(String key, String text, CellDataType type) {
        this.key = key;
        this.text = text;
        this.type = type;
    }

    public ExcelHeader options(Consumer<Options> optionsConsumer){
        optionsConsumer.accept(options);
        return this;
    }

    @Override
    public Options options() {
        return options;
    }
}
