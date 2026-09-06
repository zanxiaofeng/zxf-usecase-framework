package com.example.myapp.framework.steps;

import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

import com.example.datatransfer.core.TransferEngine;
import com.example.myapp.framework.core.DataTransformer;
import com.example.myapp.framework.core.StepContext;
import com.example.myapp.framework.core.dataflow.Dataflow;
import com.example.myapp.framework.expression.StepExpressionEvaluator;

import tools.jackson.databind.ObjectMapper;

/**
 * dataTransfer 步骤（引用式集成，data-transfer-core 引擎的管道内包装）：
 * {@code source} 表达式取值（缺省 #payload）→ TransferSpec 声明式转换
 * （Flatten → 规则映射 → Unflatten）→ as/payload 规则落地。
 *
 * <p>spec 在装配期由工厂加载并经 JSON Schema 校验（fail-fast）；引擎实例每 step 一个，
 * 运行期零解析开销。</p>
 */
@RequiredArgsConstructor
public final class DataTransferStep implements DataTransformer {

    private static final String DEFAULT_SOURCE = "#payload";

    private final String name;
    private final TransferEngine engine;
    private final String sourceExpression;
    private final @Nullable String as;
    private final StepExpressionEvaluator evaluator;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return name;
    }

    @Override
    public void execute(StepContext context) {
        Object source = evaluator.evaluate(sourceExpression, context, name);
        Object result = source == null
                ? null
                : objectMapper.treeToValue(engine.transferNode(objectMapper.valueToTree(source)), Object.class);
        context.storeResult(result, as, true);
    }

    /**
     * 数据流声明（键级血缘）：默认 source（#payload）可静态推导读写根键；
     * 其他表达式（如 #vars.view）的键面不可知，返回 UNKNOWN——录制照常、对照豁免。
     */
    @Override
    public Dataflow dataflow() {
        if (!DEFAULT_SOURCE.equals(sourceExpression)) {
            return Dataflow.UNKNOWN;
        }
        return Dataflow.declaring()
                .reads("payload")
                .writes(as != null ? "vars." + as : "payload")
                .build();
    }
}
