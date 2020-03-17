package org.hswebframework.reactor.excel.converter;

import lombok.Getter;
import org.hswebframework.reactor.excel.CellDataType;
import org.hswebframework.reactor.excel.ExcelHeader;
import org.hswebframework.reactor.excel.WritableCell;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

public class MapRowExpander implements BiFunction<Long, Map<String, Object>, Flux<WritableCell>> {

    @Getter
    private List<ExcelHeader> headers = new ArrayList<>();

    public MapRowExpander header(String key, String header, CellDataType type) {
        return header(new ExcelHeader(key, header, type));
    }

    public MapRowExpander header(String key, String header) {
        return header(key, header, CellDataType.STRING);
    }

    public MapRowExpander header(ExcelHeader header) {
        headers.add(header);
        return this;
    }

    @Override
    public synchronized Flux<WritableCell> apply(Long rowIndex, Map<String, Object> val) {
        return Flux
                .fromIterable(headers)
                .index()
                .map(header -> new SimpleWritableCell(
                        header.getT2(),
                        getValue(header.getT2().getKey(), val),
                        rowIndex,
                        header.getT1().intValue(),
                        header.getT1().intValue() == headers.size() - 1));
    }

    protected Object getValue(String key, Map<String, Object> map) {
        return map.get(key);
    }
}
