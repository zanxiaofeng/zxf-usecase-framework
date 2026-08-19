package com.example.myapp.framework.core;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import com.example.myapp.framework.core.exception.UseCaseAssemblyException;

/**
 * 已装配用例的注册表。装配期对重复 id 直接报错（fail-fast）。
 */
public final class UseCaseRegistry {

    private final Map<String, UseCase> useCases;

    public UseCaseRegistry(Collection<UseCase> useCases) {
        Map<String, UseCase> map = new LinkedHashMap<>();
        for (UseCase useCase : useCases) {
            if (map.put(useCase.getId(), useCase) != null) {
                throw new UseCaseAssemblyException("duplicate usecase id: " + useCase.getId());
            }
        }
        this.useCases = Collections.unmodifiableMap(map);
    }

    public Optional<UseCase> find(String id) {
        return Optional.ofNullable(useCases.get(id));
    }

    public UseCase require(String id) {
        return find(id).orElseThrow(() -> new IllegalArgumentException("no usecase registered: " + id));
    }

    public Collection<UseCase> all() {
        return useCases.values();
    }

    public int size() {
        return useCases.size();
    }
}
