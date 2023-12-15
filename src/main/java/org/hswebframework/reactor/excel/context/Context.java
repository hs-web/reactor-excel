package org.hswebframework.reactor.excel.context;

import java.util.Optional;
import java.util.function.Function;

public interface Context {

    <K, V> V computeIfAbsent(K key, Function<K, V> mapper);

    <V> Optional<V> get(Object key);


    static Context create() {
        return new SimpleContext();
    }
}
