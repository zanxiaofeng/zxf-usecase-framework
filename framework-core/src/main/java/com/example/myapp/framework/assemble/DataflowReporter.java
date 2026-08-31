package com.example.myapp.framework.assemble;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;

import com.example.myapp.framework.core.dataflow.Dataflow;
import com.example.myapp.framework.core.dataflow.DataflowKey;
import com.example.myapp.framework.core.dataflow.DataflowReport;

/**
 * 启动期数据流报告（{@code usecase.report}，默认开，仅日志）：按用例渲染声明视图文本——
 * 各 step 的 biz/vars 读写（来自 {@link DataflowReport} 声明簿：内置 step 由配置推导、
 * ref step 来自 {@code Step#dataflow()}）、vars 写入跨用例合并视图（{@link VarsWriteIndex}）
 * 与 payload 读写步骤清单。
 *
 * <p>step 标签与运行期 step 名（{@code Step#name()}）一致。价值：配置审查与新人上手有据可查；
 * 运行期真实读写经 {@code usecase.dataflow.record} 录制（含声明未覆盖的动态写入）。</p>
 */
@UtilityClass
class DataflowReporter {

    /** 渲染单用例报告文本（由调用方打日志；返回字符串便于测试断言） */
    String render(DataflowReport report, VarsWriteIndex writeIndex) {
        String id = report.useCaseId();
        StringBuilder out = new StringBuilder("dataflow: ").append(id);
        Map<String, Set<String>> bizWrites = new java.util.LinkedHashMap<>();
        Map<String, Set<String>> bizReads = new java.util.LinkedHashMap<>();
        Map<String, Set<String>> varsReads = new java.util.LinkedHashMap<>();
        List<String> payloadWriters = new ArrayList<>();
        List<String> payloadReaders = new ArrayList<>();
        for (String stepName : report.steps()) {
            Dataflow dataflow = report.ofStep(stepName).orElse(null);
            if (dataflow == null || dataflow.unknown()) {
                continue;
            }
            collectByChannel(bizWrites, stepName, dataflow.writes(), DataflowKey.Channel.BIZ, true);
            collectByChannel(bizReads, stepName, dataflow.reads(), DataflowKey.Channel.BIZ, false);
            collectByChannel(varsReads, stepName, dataflow.reads(), DataflowKey.Channel.VARS, false);
            if (writesPayload(dataflow)) {
                payloadWriters.add(stepName);
            }
            if (dataflow.reads().contains(DataflowKey.payload())) {
                payloadReaders.add(stepName);
            }
        }
        appendSection(out, "biz  writes", bizWrites);
        appendSection(out, "     reads", bizReads);
        // vars 写入经索引（含串联子用例合并视图）；读取为本用例各 step 的声明
        Map<String, Set<String>> varsWrites = new java.util.LinkedHashMap<>();
        writeIndex.writersOf(id)
                .forEach((key, producers) -> varsWrites.put(key, new TreeSet<>(producers)));
        appendSection(out, "vars writes", varsWrites);
        appendSection(out, "     reads", varsReads);
        appendListSection(out, "payload writes", payloadWriters);
        appendListSection(out, "         reads", payloadReaders);
        return out.toString();
    }

    private static boolean writesPayload(Dataflow dataflow) {
        return dataflow.writes().stream().anyMatch(key -> key.channel() == DataflowKey.Channel.PAYLOAD);
    }

    /** 按通道收集键；bareName=true 时仅列裸键名（biz writes 段惯例：前缀隐含在段名中），否则保留完整形式 */
    private static void collectByChannel(Map<String, Set<String>> target, String stepName,
                                         Set<DataflowKey> keys, DataflowKey.Channel channel, boolean bareName) {
        for (DataflowKey key : keys) {
            if (key.channel() == channel) {
                target.computeIfAbsent(stepName, k -> new TreeSet<>()).add(bareName ? key.name() : key.toString());
            }
        }
    }

    private static void appendSection(StringBuilder out, String title, Map<String, Set<String>> entries) {
        out.append("\n  ").append(title).append(": ");
        if (entries.isEmpty()) {
            out.append("-");
            return;
        }
        List<String> parts = new ArrayList<>();
        entries.forEach((label, keys) -> parts.add(label + "{" + String.join(", ", keys) + "}"));
        out.append(String.join("; ", parts));
    }

    private static void appendListSection(StringBuilder out, String title, List<String> names) {
        out.append("\n  ").append(title).append(": ");
        out.append(names.isEmpty() ? "-" : String.join(", ", names));
    }
}
