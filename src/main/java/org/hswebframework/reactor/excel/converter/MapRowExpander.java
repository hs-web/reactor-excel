package org.hswebframework.reactor.excel.converter;

import lombok.Getter;
import org.apache.commons.beanutils.BeanUtilsBean;
import org.apache.commons.beanutils.BeanUtilsBean2;
import org.hswebframework.reactor.excel.CellDataType;
import org.hswebframework.reactor.excel.ExcelHeader;
import org.hswebframework.reactor.excel.WritableCell;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

public class MapRowExpander implements BiFunction<Long, Map<String, Object>, Flux<WritableCell>> {

    @Getter
    private final List<ExcelHeader> headers = new ArrayList<>();

    private final int sheetIndex;

    public MapRowExpander() {
        this(0);
    }

    public MapRowExpander(int sheetIndex) {
        this.sheetIndex = sheetIndex;
    }

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

    public MapRowExpander headers(Collection<ExcelHeader> headers) {
        this.headers.addAll(headers);
        return this;
    }

    public Flux<WritableCell> headers(long rowIndex) {
        return Flux
                .fromIterable(getHeaders())
                .index((index, header) -> {
                    WritableCell cell = WritableCell
                            .of(sheetIndex,
                                rowIndex,
                                index.intValue(),
                                header.getType(),
                                header.getText(),
                                index == getHeaders().size() - 1
                            );
                    cell.options().merge(header.options());
                    return cell;
                });
    }

    @Override
    public Flux<WritableCell> apply(Long rowIndex, Map<String, Object> val) {
        return Flux
                .fromIterable(headers)
                .index((index, header) -> {
                    WritableCell cell = WritableCell.of(
                            sheetIndex,
                            rowIndex,
                            index.intValue(),
                            header.getType(),
                            getValue(header.getKey(), val),
                            index == headers.size() - 1
                    );
                    cell.options().merge(header.options());
                    return cell;
                });
    }

    protected Object getValue(String key, Map<String, Object> map) {
        Object val = map.get(key);
        if (val != null) {
            return val;
        }
        if (key.contains(".") || key.contains("[")) {
            try {
                return BeanUtilsBean.getInstance().getPropertyUtils().getProperty(map, key);
            } catch (Exception ignore) {
            }
        }
        return null;
    }
}
