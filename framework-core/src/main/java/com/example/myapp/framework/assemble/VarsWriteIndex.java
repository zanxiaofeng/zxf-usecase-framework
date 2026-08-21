package com.example.myapp.framework.assemble;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;
import org.springframework.util.StringUtils;

import com.example.myapp.framework.steps.SubUseCaseStepFactory;

/**
 * 每个用例<b>静态可见</b>的 vars 写入键索引：声明式 {@code as} 键 + 串联（非 isolate）子用例的递归合并。
 *
 * <p>边界：自定义 ref step 与 SpEL 动态键（{@code #vars[k]}）的运行期写入静态不可见，不在索引内——
 * 因此「索引无碰撞」不等于「运行期无碰撞」，告警文案已标注该边界。</p>
 *
 * <p>要求子用例引用图已无环（装配器第二遍检测之后构建），记忆化递归因此必然终止。</p>
 */
final class VarsWriteIndex {

    private final Map<String, UseCaseDefinition> byId = new LinkedHashMap<>();
    private final Map<String, Map<String, List<String>>> memo = new HashMap<>();

    VarsWriteIndex(List<UseCaseDefinition> definitions) {
        for (UseCaseDefinition definition : definitions) {
            byId.put(definition.getId(), definition);
        }
    }

    /** 用例的 vars 写入键 → 写入点列表（自身 step 记名；子用例合并的写入点带 {@code childId.} 路径前缀） */
    Map<String, List<String>> writersOf(String useCaseId) {
        return memo.computeIfAbsent(useCaseId, this::compute);
    }

    private Map<String, List<String>> compute(String useCaseId) {
        Map<String, List<String>> writers = new LinkedHashMap<>();
        // useCaseId 均来自已装配 definitions（递归路径有 containsKey 守卫），get 必命中
        UseCaseDefinition definition = Objects.requireNonNull(byId.get(useCaseId));
        List<StepDefinition> steps = definition.getSteps();
        for (int i = 0; i < steps.size(); i++) {
            StepDefinition step = steps.get(i);
            String as = asKey(step);
            if (as != null) {
                writers.computeIfAbsent(as, key -> new ArrayList<>()).add(stepLabel(step, i));
            }
            // 串联（非 isolate）子用例共享父 vars：子的写入键合并进父（isolate 子的写入落在子自己的 vars，不合并）
            String ref = step.ref();
            if (SubUseCaseStepFactory.TYPE.equals(step.type()) && !isIsolate(step) && ref != null && byId.containsKey(ref)) {
                writersOf(ref).forEach((key, producers) ->
                        writers.computeIfAbsent(key, k -> new ArrayList<>()).addAll(
                                producers.stream().map(producer -> ref + "." + producer).toList()));
            }
        }
        return writers;
    }

    /** 声明式 as 键（原始 config Map 读取；类型化绑定在工厂内发生，装配器只见 raw config） */
    private static @Nullable String asKey(StepDefinition step) {
        if (step.config().get("as") instanceof String as && StringUtils.hasText(as)) {
            return as;
        }
        return null;
    }

    private static boolean isIsolate(StepDefinition step) {
        return Boolean.TRUE.equals(step.config().get("isolate"));
    }

    private static String stepLabel(StepDefinition step, int index) {
        return StringUtils.hasText(step.name()) ? step.name() : "#" + index;
    }
}
