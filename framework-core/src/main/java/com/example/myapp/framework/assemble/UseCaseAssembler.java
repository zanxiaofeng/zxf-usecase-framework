package com.example.myapp.framework.assemble;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.http.HttpMethod;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import com.example.myapp.framework.core.Step;
import com.example.myapp.framework.core.StepContext;
import com.example.myapp.framework.core.UseCase.EndpointSpec;
import com.example.myapp.framework.core.UseCase;
import com.example.myapp.framework.core.UseCaseRegistry;
import com.example.myapp.framework.core.UseCaseTrace;
import com.example.myapp.framework.core.exception.UseCaseAssemblyException;
import com.example.myapp.framework.steps.StarterStepFactory;
import com.example.myapp.framework.steps.SubUseCaseStepFactory;

/**
 * 用例装配器：启动期把 {@code usecase.definitions} 配置翻译成 {@link UseCaseRegistry}。
 *
 * <p>三遍装配（定义校验全部 fail-fast，键级分析只 WARN）：</p>
 * <ol>
 *   <li><b>定义校验</b>：id 非空且唯一；非 shared 用例必须有合法 endpoint；steps 非空；
 *       starter 的 keys 不得写保留 biz 键（{@code traceId}）；</li>
 *   <li><b>子用例引用图</b>：type=usecase 的 step 其 ref 必须指向已定义用例；DFS 检测循环引用；
 *       随后基于已无环的引用图做 vars 键静态分析（{@link VarsWriteIndex}）——as 键多写入点打 WARN；</li>
 *   <li><b>逐 step 构建</b>：ref → Step Bean；type → StepFactory；type=usecase → 子用例 step。</li>
 * </ol>
 *
 * <p>装配完成后按 {@code usecase.report}（默认开）输出每用例数据流报告（{@link DataflowReporter}）。</p>
 *
 * <p>step 的 ref/type 规则：</p>
 * <ul>
 *   <li>{@code type: usecase} → {@code ref} 必填，指向目标用例 id（允许 type+ref 共存，这是唯一特例）；</li>
 *   <li>其余情况 → ref（Step Bean 名）与 type（内置/扩展类型）必须二选一。</li>
 * </ul>
 */
@Slf4j
public final class UseCaseAssembler {

    /** endpoint.method 白名单：SF7 的 HttpMethod.valueOf 对未知方法名静默构造自定义实例，装配期需显式拦截拼写错误 */
    private static final Set<HttpMethod> STANDARD_HTTP_METHODS = Set.of(HttpMethod.values());

    /** starter 不得写入的 biz 保留键（traceId 由 Web 入口白名单校验后种子化，starter 可写会绕过校验） */
    private static final Set<String> RESERVED_BIZ_KEYS = Set.of(StepContext.TRACE_ID_KEY);

    private final BeanFactory beanFactory;
    private final Map<String, StepFactory> factories;
    private final UseCaseTrace trace;
    private final boolean reportEnabled;

    /** 便捷构造：无 dev trace、无数据流报告（测试与手工装配用） */
    public UseCaseAssembler(BeanFactory beanFactory, List<StepFactory> factoryList) {
        this(beanFactory, factoryList, UseCaseTrace.DISABLED, false);
    }

    public UseCaseAssembler(BeanFactory beanFactory, List<StepFactory> factoryList,
                            UseCaseTrace trace, boolean reportEnabled) {
        this.beanFactory = beanFactory;
        this.trace = trace;
        this.reportEnabled = reportEnabled;
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
            validateStarterSteps(definition);
            if (!ids.add(definition.getId())) {
                throw new UseCaseAssemblyException("duplicate usecase id: " + definition.getId());
            }
        }
        // 第二遍：子用例引用存在性 + 循环引用检测
        detectSubUseCaseCycles(buildSubUseCaseRefGraph(definitions, ids));
        // 第二遍半：vars 键静态分析（引用图已无环）——as 键碰撞 WARN
        VarsWriteIndex writeIndex = new VarsWriteIndex(definitions);
        warnVarsKeyCollisions(definitions, writeIndex);
        // 第三遍：构建
        List<UseCase> assembled = new ArrayList<>();
        for (UseCaseDefinition definition : definitions) {
            // id/steps/endpoint 非空已由第一遍 validateUseCase 保证（含编程式装配入口——该路径不经 Bean Validation）
            String id = definition.getId();
            List<StepDefinition> stepDefinitions = definition.getSteps();
            List<Step> steps = new ArrayList<>();
            for (int i = 0; i < stepDefinitions.size(); i++) {
                steps.add(resolveStep(id, i, stepDefinitions.get(i)));
            }
            EndpointSpec endpointSpec = definition.isShared() ? null : requireEndpointSpec(definition);
            assembled.add(new UseCase(id, definition.getDescription(), endpointSpec, List.copyOf(steps),
                    definition.isShared(), trace));
        }
        UseCaseRegistry registry = new UseCaseRegistry(assembled);
        log.info("assembled {} usecase(s) from configuration 'usecase.definitions'", registry.size());
        if (reportEnabled) {
            DataflowReporter.render(definitions, writeIndex).forEach(line -> log.info("{}", line));
        }
        return registry;
    }

    /**
     * vars 键静态碰撞检测（Fix 5）：同一键存在多个写入点 → WARN。
     * 只 WARN 不 fail（存在有意覆盖的合法用法）；静态可见范围仅声明式 as 键（文案已标注）。
     */
    private void warnVarsKeyCollisions(List<UseCaseDefinition> definitions, VarsWriteIndex writeIndex) {
        for (UseCaseDefinition definition : definitions) {
            String id = definition.getId();   // 第一遍校验已保证非空
            writeIndex.writersOf(id).forEach((key, producers) -> {
                if (producers.size() > 1) {
                    log.warn("usecase [{}]: vars key '{}' has {} writers {} —— 若非有意覆盖请改名"
                                    + "（静态可见范围：声明式 as 键；自定义 step 的运行期 vars 写入不在其列）",
                            id, key, producers.size(), producers);
                }
            });
        }
    }

    /** 构建 EndpointSpec：非 shared 用例的 endpoint/method/path 非空已由 {@link #validateUseCase} 保证 */
    private static EndpointSpec requireEndpointSpec(UseCaseDefinition definition) {
        UseCaseDefinition.Endpoint endpoint = Objects.requireNonNull(definition.getEndpoint());
        return new EndpointSpec(Objects.requireNonNull(endpoint.getMethod()),
                endpoint.getPath(), endpoint.getStatus());
    }

    private void validateUseCase(UseCaseDefinition definition) {
        if (!StringUtils.hasText(definition.getId())) {
            throw new UseCaseAssemblyException("usecase id must not be blank");
        }
        if (CollectionUtils.isEmpty(definition.getSteps())) {
            throw new UseCaseAssemblyException("usecase [%s]: at least one step is required".formatted(definition.getId()));
        }
        if (definition.isShared()) {
            return;   // shared 用例不绑定 endpoint，跳过端点校验
        }
        UseCaseDefinition.Endpoint endpoint = definition.getEndpoint();
        // method 为强类型 HttpMethod；但 SF7 的 HttpMethod.valueOf 对未知方法名构造自定义实例（支持扩展方法）
        // 而非报错——拼写错误（如 GTE）会静默绑定成功、路由永不匹配，故装配期加标准方法白名单校验
        if (endpoint == null || endpoint.getMethod() == null || !StringUtils.hasText(endpoint.getPath())) {
            throw new UseCaseAssemblyException(
                    "usecase [%s]: endpoint.method and endpoint.path are required (unless shared: true)"
                            .formatted(definition.getId()));
        }
        if (!STANDARD_HTTP_METHODS.contains(endpoint.getMethod())) {
            throw new UseCaseAssemblyException(
                    "usecase [%s]: endpoint.method [%s] is not a standard HTTP method (expected one of %s)"
                            .formatted(definition.getId(), endpoint.getMethod(), STANDARD_HTTP_METHODS));
        }
    }

    /**
     * starter 步骤的配置前置校验（类型化 config 绑定在工厂内发生，此处基于原始 config Map）：
     * keys 命中保留 biz 键（{@code traceId}）→ fail-fast；shared 用例内含 starter → WARN
     * （串联嵌入时共享上下文，子的 starter 会覆写父管道 biz）。
     */
    private void validateStarterSteps(UseCaseDefinition definition) {
        String id = definition.getId();   // 第一遍校验已保证非空
        List<StepDefinition> steps = definition.getSteps();
        for (int i = 0; i < steps.size(); i++) {
            StepDefinition step = steps.get(i);
            if (!StarterStepFactory.TYPE.equals(step.type())) {
                continue;
            }
            checkReservedBizKeys(id, i, step);
            if (definition.isShared()) {
                log.warn("shared usecase [{}] step #{}: starter 在串联嵌入时会覆写父管道 biz"
                        + "（若非预期请改用 isolate: true 或移除该 starter）", id, i);
            }
        }
    }

    private void checkReservedBizKeys(String useCaseId, int index, StepDefinition step) {
        if (!(step.config().get("keys") instanceof Map<?, ?> keys)) {
            return;
        }
        for (Object key : keys.keySet()) {
            if (RESERVED_BIZ_KEYS.contains(String.valueOf(key))) {
                throw new UseCaseAssemblyException(
                        "usecase [%s] step #%d: starter must not write reserved biz key '%s'"
                                .formatted(useCaseId, index, key));
            }
        }
    }

    // ------------------------------------------------------------------
    // 子用例引用：存在性校验 + 环检测
    // ------------------------------------------------------------------

    private Map<String, Set<String>> buildSubUseCaseRefGraph(List<UseCaseDefinition> definitions, Set<String> ids) {
        Map<String, Set<String>> refGraph = new HashMap<>();
        for (UseCaseDefinition definition : definitions) {
            Set<String> refs = new LinkedHashSet<>();
            List<StepDefinition> steps = definition.getSteps();
            for (int i = 0; i < steps.size(); i++) {
                StepDefinition step = steps.get(i);
                if (!SubUseCaseStepFactory.TYPE.equals(step.type())) {
                    continue;
                }
                String ref = step.ref();
                if (!StringUtils.hasText(ref)) {
                    throw new UseCaseAssemblyException(
                            "usecase [%s] step #%d: type 'usecase' requires 'ref' (target usecase id)"
                                    .formatted(definition.getId(), i));
                }
                if (!ids.contains(ref)) {
                    throw new UseCaseAssemblyException(
                            "usecase [%s] step #%d: unknown sub-usecase ref '%s', available usecases: %s"
                                    .formatted(definition.getId(), i, ref, ids));
                }
                refs.add(ref);
            }
            refGraph.put(definition.getId(), refs);
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
        boolean hasRef = StringUtils.hasText(definition.ref());
        boolean hasType = StringUtils.hasText(definition.type());

        if (SubUseCaseStepFactory.TYPE.equals(definition.type())) {
            // 子用例 step：ref 为目标用例 id（存在性与环已在第二遍校验）
            return invokeFactory(useCaseId, index, definition);
        }
        if (hasRef == hasType) {
            throw new UseCaseAssemblyException(
                    "usecase [%s] step #%d: exactly one of 'ref' or 'type' must be set".formatted(useCaseId, index));
        }
        if (hasRef) {
            String ref = Objects.requireNonNull(definition.ref());   // hasText 校验已保证非空
            try {
                return beanFactory.getBean(ref, Step.class);
            } catch (BeansException e) {
                throw new UseCaseAssemblyException(
                        "usecase [%s] step #%d: no Step bean named '%s'".formatted(useCaseId, index, ref), e);
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
            return factory.create(definition.withUseCaseId(useCaseId));
        } catch (UseCaseAssemblyException e) {
            throw new UseCaseAssemblyException(
                    "usecase [%s] step #%d (%s): %s".formatted(useCaseId, index, definition.type(), e.getMessage()), e);
        }
    }
}
