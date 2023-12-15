package org.hswebframework.reactor.excel.context;

import lombok.SneakyThrows;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

class SimpleContext implements Context {
    private final Map<Object, Object> context = new ConcurrentHashMap<>();

    @Override
    @SneakyThrows
    public <K, V> V computeIfAbsent(K key, Function<K, V> mapper) {
        return (V) context.computeIfAbsent(key, (k) -> mapper.apply((K) k));
    }

    @Override
    public <V> Optional<V> get(Object key) {
        return Optional.ofNullable((V) context.get(key));
    }
}
