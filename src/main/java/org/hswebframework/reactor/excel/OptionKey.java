package org.hswebframework.reactor.excel;

public interface OptionKey<T> {

    String getKey();

    Class<T> getType();

    default T cast(Object value) {
        return getType().cast(value);
    }
}
