package com.example.myapp.framework.assemble;

import java.util.List;
import java.util.Map;
import java.util.Set;


import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;

import com.example.myapp.framework.core.Step;
import com.example.myapp.framework.core.dataflow.Dataflow;
import com.example.myapp.framework.core.dataflow.DataflowKey;
import com.example.myapp.framework.core.exception.UseCaseAssemblyException;
import com.example.myapp.framework.expression.ExpressionInspector;
import com.example.myapp.framework.steps.EventPublisherStepFactory;
import com.example.myapp.framework.steps.StarterStepFactory;
import com.example.myapp.framework.steps.SubUseCaseStepFactory;
import com.example.myapp.framework.steps.ValidatorStepFactory;

/**
 * 内置 step 的数据流声明推导：从 {@link StepDefinition} 的 raw config 推导读写键
 * （与 {@code VarsWriteIndex}/{@code DataflowReporter} 同源），ref/扩展类型透传 {@code Step#dataflow()}。
 *
 * <p>推导边界（配置缺省值显式补齐）：</p>
 * <ul>
 *   <li>codec（encoder/decoder）{@code source} 缺省 {@code #payload} → 补读 payload；</li>
 *   <li>usecase step 的 {@code input} 缺省 {@code #payload} → 补读 payload；</li>
 *   <li>表达式读取经 {@link ExpressionInspector} 首段静态分析，{@code #body/#path/#query/#headers}
 *       等 request 视图读取不属于 context 三通道，不进声明（运行期录制照常记录）；</li>
 *   <li>动态键读取（{@code #vars[k]}）保守记为通道通配（{@code vars.*}）。</li>
 * </ul>
 *
 * <p>声明键一律以运行期 step 名（{@code Step#name()}）登记——由调用方（UseCaseAssembler 第三遍）
 * 在拿到 step 实例后传入，天然与执行期对齐。</p>
 */
@UtilityClass
public class DataflowDeclarationResolver {

    private static final String TYPE_HTTP_REQUESTER = "httpRequester";
    private static final String TYPE_LOGGING = "logging";
    private static final String TYPE_ENCODER = "encoder";
    private static final String TYPE_DECODER = "decoder";

    private static final Set<String> PRODUCING_TYPES =
            Set.of("dataLoader", "dataTransformer", "dataSaver", TYPE_HTTP_REQUESTER);
    private static final Set<String> READ_ONLY_TYPES =
            Set.of(ValidatorStepFactory.TYPE, TYPE_LOGGING, EventPublisherStepFactory.TYPE);

    /**
     * 推导一个 step 的数据流声明。
     *
     * @param definition step 定义（raw config）
     * @param step       已构建的 step 实例（ref/扩展类型取其 {@code dataflow()} 声明）
     * @throws UseCaseAssemblyException 声明键非法（如 as 键含非法字符）
     */
    public Dataflow derive(StepDefinition definition, Step step) {
        String type = definition.type();
        if (type == null) {
            // ref 自定义 step（无 type）：透传实例声明
            return step.dataflow();
        }
        try {
            if (StarterStepFactory.TYPE.equals(type)) {
                return starterDeclaration(definition.config());
            }
            if (SubUseCaseStepFactory.TYPE.equals(type)) {
                return subUseCaseDeclaration(definition.config());
            }
            if (TYPE_ENCODER.equals(type) || TYPE_DECODER.equals(type)) {
                return producingDeclaration(definition.config(), true);
            }
            if (PRODUCING_TYPES.contains(type)) {
                return producingDeclaration(definition.config(), false);
            }
            if (READ_ONLY_TYPES.contains(type)) {
                return readOnlyDeclaration(definition.config());
            }
            return step.dataflow();
        } catch (IllegalArgumentException e) {
            throw new UseCaseAssemblyException(
                    "usecase [%s] step [%s]: illegal dataflow declaration: %s"
                            .formatted(definition.useCaseId(), step.name(), e.getMessage()), e);
        }
    }

    /** starter：写 biz keys，读 keys 值表达式 */
    private Dataflow starterDeclaration(Map<String, Object> config) {
        Dataflow.Builder builder = Dataflow.declaring();
        if (config.get("keys") instanceof Map<?, ?> keys) {
            for (Map.Entry<?, ?> entry : keys.entrySet()) {
                builder.writes(DataflowKey.biz(String.valueOf(entry.getKey())));
                addExpressionReads(builder, String.valueOf(entry.getValue()));
            }
        }
        return builder.build();
    }

    /** usecase step：读 input（缺省 #payload），写 as 键或 payload（串联模式）；子链初始 payload 写按录制窗口归属本 step */
    private Dataflow subUseCaseDeclaration(Map<String, Object> config) {
        Dataflow.Builder builder = Dataflow.declaring();
        if (config.containsKey("input")) {
            addExpressionReads(builder, String.valueOf(config.get("input")));
        } else {
            builder.reads(DataflowKey.payload());
        }
        builder.writes(asKeyOrPayload(config));
        // 录制模型近似：子用例初始 payload 写（isolate 落子上下文 / 共享瞬态写后恢复）与恢复
        // 均落在 usecase step 的执行窗口内，声明补 payload 使对照不误报（payload 写不参与无人读取判定）
        builder.writes(DataflowKey.payload());
        return builder.build();
    }

    /** 产出型 step（三件套/httpRequester/codec）：读 config 全部表达式，写 as 键或 payload */
    private Dataflow producingDeclaration(Map<String, Object> config, boolean codec) {
        Dataflow.Builder builder = Dataflow.declaring();
        addConfigReads(builder, config);
        // codec 的 source 缺省 #payload（raw config 缺该键时补 payload 读）
        if (codec && !config.containsKey("source")) {
            builder.reads(DataflowKey.payload());
        }
        builder.writes(asKeyOrPayload(config));
        return builder.build();
    }

    /** 只读 step（validator/logging/eventPublisher）：仅读 config 全部表达式 */
    private Dataflow readOnlyDeclaration(Map<String, Object> config) {
        Dataflow.Builder builder = Dataflow.declaring();
        addConfigReads(builder, config);
        return builder.build();
    }

    private DataflowKey asKeyOrPayload(Map<String, Object> config) {
        if (config.get("as") instanceof String as && !as.isBlank()) {
            return DataflowKey.vars(as.trim());
        }
        return DataflowKey.payload();
    }

    /** 递归收集 config 中全部字符串值（含嵌套 Map/List）的表达式读取 */
    private void addConfigReads(Dataflow.Builder builder, Object node) {
        switch (node) {
            case String text -> addExpressionReads(builder, text);
            case Map<?, ?> map -> map.values().forEach(value -> addConfigReads(builder, value));
            case List<?> list -> list.forEach(item -> addConfigReads(builder, item));
            case null, default -> {
            }
        }
    }

    private void addExpressionReads(Dataflow.Builder builder, String text) {
        for (String read : ExpressionInspector.collectReads(text)) {
            DataflowKey key = toContextKey(read);
            if (key != null) {
                builder.reads(key);
            }
        }
    }

    /**
     * 表达式读取（root + 首段）→ context 三通道键；request 视图读取（body/path/query/headers）
     * 返回 null 跳过。动态键读取（root 无首段，如 {@code vars}）保守记为通道通配。
     */
    private @Nullable DataflowKey toContextKey(String read) {
        int dot = read.indexOf('.');
        String root = dot < 0 ? read : read.substring(0, dot);
        return switch (root) {
            case "payload" -> DataflowKey.payload();
            case "vars" -> dot < 0 ? DataflowKey.vars(DataflowKey.WILDCARD) : parseOrNull(read);
            case "biz" -> dot < 0 ? DataflowKey.biz(DataflowKey.WILDCARD) : parseOrNull(read);
            default -> null;
        };
    }

    private @Nullable DataflowKey parseOrNull(String expression) {
        try {
            return DataflowKey.parse(expression);
        } catch (IllegalArgumentException e) {
            // 静态分析产出的非常规形式（如含特殊字符的首段名）不进声明；运行期录制兜底
            return null;
        }
    }
}
