package com.example.myapp.framework.assemble;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import lombok.experimental.UtilityClass;
import org.springframework.util.StringUtils;

import com.example.myapp.framework.expression.ExpressionInspector;
import com.example.myapp.framework.steps.StarterStepFactory;

/**
 * 启动期数据流报告（{@code usecase.report}，默认开，仅日志）：按用例输出静态可见的读写视图——
 * biz 写入（starter keys）/ vars 写入（as 键，经 {@link VarsWriteIndex} 含串联子用例合并）/
 * 表达式读取（{@link ExpressionInspector} 的 AST 首段分析）。
 *
 * <p>价值：配置审查与新人上手有据可查；边界同 VarsWriteIndex——自定义 step 的运行期写入不可见。</p>
 */
@UtilityClass
class DataflowReporter {

    /** 渲染每个用例一段报告文本（由调用方逐条打日志；返回字符串便于测试断言） */
    List<String> render(List<UseCaseDefinition> definitions, VarsWriteIndex writeIndex) {
        List<String> report = new ArrayList<>();
        for (UseCaseDefinition definition : definitions) {
            report.add(renderUseCase(definition, writeIndex));
        }
        return report;
    }

    private String renderUseCase(UseCaseDefinition definition, VarsWriteIndex writeIndex) {
        // id/steps 非空由装配器第一遍校验保证（本报告在装配成功后渲染）
        String id = definition.getId();
        StringBuilder out = new StringBuilder("dataflow: ").append(id);
        Map<String, Set<String>> bizWrites = new LinkedHashMap<>();   // step -> biz keys
        Map<String, Set<String>> bizReads = new LinkedHashMap<>();
        Map<String, Set<String>> varsReads = new LinkedHashMap<>();
        List<StepDefinition> steps = definition.getSteps();
        for (int i = 0; i < steps.size(); i++) {
            StepDefinition step = steps.get(i);
            String label = StringUtils.hasText(step.name()) ? step.name() : "#" + i;
            if (StarterStepFactory.TYPE.equals(step.type())
                    && step.config().get("keys") instanceof Map<?, ?> keys) {
                for (Object key : keys.keySet()) {
                    bizWrites.computeIfAbsent(label, k -> new TreeSet<>()).add(String.valueOf(key));
                }
            }
            for (String read : readsOf(step)) {
                String root = rootOf(read);
                if ("biz".equals(root)) {
                    bizReads.computeIfAbsent(label, k -> new TreeSet<>()).add(read);
                }
                if ("vars".equals(root)) {
                    varsReads.computeIfAbsent(label, k -> new TreeSet<>()).add(read);
                }
            }
        }
        appendSection(out, "biz  writes", bizWrites);
        appendSection(out, "     reads", bizReads);
        // vars 写入经索引（含串联子用例合并）；读取为本用例自身 step 的静态分析
        Map<String, Set<String>> varsWrites = new LinkedHashMap<>();
        writeIndex.writersOf(id)
                .forEach((key, producers) -> varsWrites.put(key, new TreeSet<>(producers)));
        appendSection(out, "vars writes", varsWrites);
        appendSection(out, "     reads", varsReads);
        return out.toString();
    }

    /** 收集 step config 中全部字符串值（含嵌套 Map/List，如 headers/starter keys 的模板）的数据根读取 */
    private Set<String> readsOf(StepDefinition step) {
        Set<String> reads = new TreeSet<>();
        collectStrings(step.config(), reads);
        return reads;
    }

    private void collectStrings(Object node, Set<String> reads) {
        switch (node) {
            case String text -> reads.addAll(ExpressionInspector.collectReads(text));
            case Map<?, ?> map -> map.values().forEach(value -> collectStrings(value, reads));
            case List<?> list -> list.forEach(item -> collectStrings(item, reads));
            case null, default -> {
            }
        }
    }

    private String rootOf(String read) {
        int dot = read.indexOf('.');
        return dot < 0 ? read : read.substring(0, dot);
    }

    private void appendSection(StringBuilder out, String title, Map<String, Set<String>> entries) {
        out.append("\n  ").append(title).append(": ");
        if (entries.isEmpty()) {
            out.append("-");
            return;
        }
        List<String> parts = new ArrayList<>();
        entries.forEach((label, keys) -> parts.add(label + "{" + String.join(", ", keys) + "}"));
        out.append(String.join("; ", parts));
    }
}
