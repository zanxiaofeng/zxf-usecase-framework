package com.example.datatransfer.core;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import com.example.datatransfer.core.spec.TransferSpec;

/** name → 引擎实例的注册表（Spring Boot 自动配置的落点，设计文档 §8.9）。 */
public final class TransferSpecRegistry {

    private final Map<String, TransferEngine> engines = new ConcurrentHashMap<>();

    public void register(TransferSpec spec) {
        Objects.requireNonNull(spec, "spec must not be null");
        register(spec.getName(), new TransferEngine(spec));
    }

    public void register(String name, TransferEngine engine) {
        engines.put(name, Objects.requireNonNull(engine, "engine must not be null"));
    }

    /** @throws IllegalArgumentException 名称未注册 */
    public TransferEngine engineOf(String name) {
        TransferEngine engine = engines.get(name);
        if (engine == null) {
            throw new IllegalArgumentException("no TransferSpec registered under name: " + name);
        }
        return engine;
    }

    public boolean contains(String name) {
        return engines.containsKey(name);
    }
}
