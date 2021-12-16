package org.hswebframework.reactor.excel;

public interface ExcelOption {

    @Deprecated
    default Class<?> getType() {
        return getClass().getInterfaces()[0];
    }

    default boolean isWrapFor(Class<?> type) {
        return type.isInstance(this);
    }

    default <R> R unwrap(Class<R> type) {
        return type.cast(this);
    }
}
