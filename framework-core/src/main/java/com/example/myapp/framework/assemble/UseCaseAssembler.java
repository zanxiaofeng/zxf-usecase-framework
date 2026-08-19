package com.example.myapp.framework.assemble;

import com.example.myapp.framework.config.StepDefinition;
import com.example.myapp.framework.config.UseCaseDefinition;
import com.example.myapp.framework.core.EndpointSpec;
import com.example.myapp.framework.core.Step;
import com.example.myapp.framework.core.UseCase;
import com.example.myapp.framework.core.UseCaseAssemblyException;
import com.example.myapp.framework.core.UseCaseRegistry;
import com.example.myapp.framework.steps.SubUseCaseStepFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.http.HttpMethod;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 用例装配器：启动期把 {@code usecase.definitions} 配置翻译成 {@link UseCaseRegistry}。
 *
 * <p>三遍装配（全部 fail-fast）：</p>
 * <ol>
 *   <li><b>定义校验</b>：id 非空且唯一；非 shared 用例必须有合法 endpoint；steps 非空；</li>
 *   <li><b>子用例引用图</b>：type=usecase 的 step 其 ref 必须指向已定义用例；DFS 检测循环引用；</li>
 *   <li><b>逐 step 构建</b>：ref → Step Bean；type → StepFactory；type=usecase → 子用例 step。</li>
 * </ol>
 *
 * <p>step 的 ref/type 规则：</p>
 * <ul>
 *   <li>{@code type: usecase} → {@code ref} 必填，指向目标用例 id（允许 type+ref 共存，这是唯一特例）；</li>
 *   <li>其余情况 → ref（Step Bean 名）与 type（内置/扩展类型）必须二选一。</li>
 * </ul>
 */
public final class UseCaseAssembler {

    private static final Logger log = LoggerFactory.getLogger(UseCaseAssembler.class);

    private final BeanFactory beanFactory;
    private final Map<String, StepFactory> factories;

    public UseCaseAssembler(BeanFactory beanFactory, List<StepFactory> factoryList) {
        this.beanFactory = beanFactory;
        Map<String, StepFactory> map = new LinkedHashMap<>();
        for (StepFactory factory : factoryList) {
            if (map.put(factory.type(), factory) != null) {
                throw new UseCaseAssemblyException("duplicate step factory for type: " + factory.type());
            }
        }
        this.factories = Collections.unmodifiableMap(map);
    }

    public UseCaseRegistry assemble(List<UseCaseDefinition> definitions) {
        // 第一遍：定义校验 + id 收集（非空且唯一）
        Set<String> ids = new LinkedHashSet<>();
        for (UseCaseDefinition definition : definitions) {
            validateUseCase(definition);
            if (!ids.add(definition.id())) {
                throw new UseCaseAssemblyException("duplicate usecase id: " + definition.id());
            }
        }
        // 第二遍：子用例引用存在性 + 循环引用检测
        detectSubUseCaseCycles(buildSubUseCaseRefGraph(definitions, ids));
        // 第三遍：构建
        List<UseCase> assembled = new ArrayList<>();
        for (UseCaseDefinition definition : definitions) {
            List<Step> steps = new ArrayList<>();
            List<StepDefinition> stepDefinitions = definition.steps();
            for (int i = 0; i < stepDefinitions.size(); i++) {
                steps.add(resolveStep(definition.id(), i, stepDefinitions.get(i)));
            }
            UseCaseDefinition.Endpoint endpoint = definition.endpoint();
            EndpointSpec endpointSpec = definition.isShared()
                    ? null
                    : new EndpointSpec(endpoint.method().toUpperCase(Locale.ROOT), endpoint.path(),
                            endpoint.statusOrDefault());
            assembled.add(new UseCase(definition.id(), definition.description(), endpointSpec, steps,
                    definition.isShared()));
        }
        UseCaseRegistry registry = new UseCaseRegistry(assembled);
        log.info("assembled {} usecase(s) from configuration 'usecase.definitions'", registry.size());
        return registry;
    }

    private void validateUseCase(UseCaseDefinition definition) {
        if (definition.id() == null || definition.id().isBlank()) {
            throw new UseCaseAssemblyException("usecase id must not be blank");
        }
        if (definition.steps() == null || definition.steps().isEmpty()) {
            throw new UseCaseAssemblyException("usecase [%s]: at least one step is required".formatted(definition.id()));
        }
        if (definition.isShared()) {
            return;   // shared 用例不绑定 endpoint，跳过端点校验
        }
        UseCaseDefinition.Endpoint endpoint = definition.endpoint();
        if (endpoint == null || endpoint.method() == null || endpoint.method().isBlank()
                || endpoint.path() == null || endpoint.path().isBlank()) {
            throw new UseCaseAssemblyException(
                    "usecase [%s]: endpoint.method and endpoint.path are required (unless shared: true)"
                            .formatted(definition.id()));
        }
        try {
            HttpMethod.valueOf(endpoint.method().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new UseCaseAssemblyException(
                    "usecase [%s]: unsupported http method '%s'".formatted(definition.id(), endpoint.method()));
        }
    }

    // ------------------------------------------------------------------
    // 子用例引用：存在性校验 + 环检测
    // ------------------------------------------------------------------

    private Map<String, Set<String>> buildSubUseCaseRefGraph(List<UseCaseDefinition> definitions, Set<String> ids) {
        Map<String, Set<String>> refGraph = new HashMap<>();
        for (UseCaseDefinition definition : definitions) {
            Set<String> refs = new LinkedHashSet<>();
            List<StepDefinition> steps = definition.steps();
            for (int i = 0; i < steps.size(); i++) {
                StepDefinition step = steps.get(i);
                if (!SubUseCaseStepFactory.TYPE.equals(step.type())) {
                    continue;
                }
                String ref = step.ref();
                if (ref == null || ref.isBlank()) {
                    throw new UseCaseAssemblyException(
                            "usecase [%s] step #%d: type 'usecase' requires 'ref' (target usecase id)"
                                    .formatted(definition.id(), i));
                }
                if (!ids.contains(ref)) {
                    throw new UseCaseAssemblyException(
                            "usecase [%s] step #%d: unknown sub-usecase ref '%s', available usecases: %s"
                                    .formatted(definition.id(), i, ref, ids));
                }
                refs.add(ref);
            }
            refGraph.put(definition.id(), refs);
        }
        return refGraph;
    }

    private void detectSubUseCaseCycles(Map<String, Set<String>> refGraph) {
        Set<String> done = new HashSet<>();
        Deque<String> path = new ArrayDeque<>();
        for (String id : refGraph.keySet()) {
            visit(id, refGraph, done, path);
        }
    }

    private void visit(String node, Map<String, Set<String>> refGraph, Set<String> done, Deque<String> path) {
        if (done.contains(node)) {
            return;
        }
        if (path.contains(node)) {
            List<String> cycle = new ArrayList<>(path);
            cycle.add(node);
            throw new UseCaseAssemblyException("circular sub-usecase reference detected: " + cycle);
        }
        path.addLast(node);
        for (String child : refGraph.getOrDefault(node, Set.of())) {
            visit(child, refGraph, done, path);
        }
        path.removeLast();
        done.add(node);
    }

    // ------------------------------------------------------------------
    // step 解析
    // ------------------------------------------------------------------

    private Step resolveStep(String useCaseId, int index, StepDefinition definition) {
        boolean hasRef = definition.ref() != null && !definition.ref().isBlank();
        boolean hasType = definition.type() != null && !definition.type().isBlank();

        if (SubUseCaseStepFactory.TYPE.equals(definition.type())) {
            // 子用例 step：ref 为目标用例 id（存在性与环已在第二遍校验）
            return invokeFactory(useCaseId, index, definition);
        }
        if (hasRef == hasType) {
            throw new UseCaseAssemblyException(
                    "usecase [%s] step #%d: exactly one of 'ref' or 'type' must be set".formatted(useCaseId, index));
        }
        if (hasRef) {
            try {
                return beanFactory.getBean(definition.ref(), Step.class);
            } catch (BeansException e) {
                throw new UseCaseAssemblyException(
                        "usecase [%s] step #%d: no Step bean named '%s'".formatted(useCaseId, index, definition.ref()), e);
            }
        }
        return invokeFactory(useCaseId, index, definition);
    }

    private Step invokeFactory(String useCaseId, int index, StepDefinition definition) {
        StepFactory factory = factories.get(definition.type());
        if (factory == null) {
            throw new UseCaseAssemblyException(
                    "usecase [%s] step #%d: unknown step type '%s', available types: %s"
                            .formatted(useCaseId, index, definition.type(), factories.keySet()));
        }
        try {
            return factory.create(definition);
        } catch (UseCaseAssemblyException e) {
            throw new UseCaseAssemblyException(
                    "usecase [%s] step #%d (%s): %s".formatted(useCaseId, index, definition.type(), e.getMessage()), e);
        }
    }
}
