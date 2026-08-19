package com.example.myapp.framework.steps;

import java.util.List;
import java.util.stream.Collectors;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import lombok.RequiredArgsConstructor;
import org.springframework.expression.EvaluationException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.example.myapp.framework.core.Step;
import com.example.myapp.framework.core.StepContext;
import com.example.myapp.framework.core.exception.StepValidationException;
import com.example.myapp.framework.expression.StepExpressionEvaluator;

/**
 * 校验步骤（一般放在管道入口，target 常用 {@code #body}；缺省校验 {@code #payload}）。
 *
 * <p>两种互斥模式：</p>
 * <ul>
 *   <li><b>schema 模式</b>：JSON Schema（装配期预编译为 networknt {@link Schema}）。
 *       失败时收集全部字段错误明细；</li>
 *   <li><b>expression 模式</b>：函数式校验，表达式返回 {@code false} 即失败，其余结果视为通过；
 *       表达式自身抛异常（如业务 Bean 抛领域异常）则原样传播，走领域异常映射。</li>
 * </ul>
 *
 * <pre>{@code
 * # schema 模式
 * - name: validateBody
 *   type: validator
 *   config:
 *     target: "#body"
 *     schema:
 *       type: object
 *       required: [userId]
 *       properties:
 *         userId: { type: string, minLength: 1 }
 *
 * # 函数模式
 * - name: checkCredit
 *   type: validator
 *   config:
 *     expression: "#vars.credit.score >= 600"
 *     message: "用户 #{biz.businessId} 信用分不足"
 *     errorCode: "CREDIT_TOO_LOW"
 * }</pre>
 */
@RequiredArgsConstructor
public final class ValidatorStep implements Step {

    /** schema 失败明细最多展示的条数 */
    private static final int MAX_DETAIL_ITEMS = 5;

    private final String name;
    private final String targetExpression;
    private final String expression;
    private final Schema schema;
    private final String messageTemplate;
    private final String errorCode;
    private final StepExpressionEvaluator evaluator;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return name;
    }

    @Override
    public void execute(StepContext context) {
        if (expression != null) {
            Object result;
            try {
                result = evaluator.evaluate(expression, context);
            } catch (EvaluationException e) {
                // SpEL 求值失败（如 #vars.credit 为 null 时取 .score）语义上是"校验无法通过"，
                // 映射 400 而非 500。领域异常不经 SpEL 包装（bean 方法抛出时原样传播），仍走领域映射。
                throw new StepValidationException(errorCode,
                        renderMessage(context, null) + " (expression evaluation failed: " + e.getMessage() + ")");
            }
            if (Boolean.FALSE.equals(result)) {
                throw new StepValidationException(errorCode, renderMessage(context, null));
            }
            return;
        }
        Object target = evaluator.evaluate(targetExpression, context);
        JsonNode node = objectMapper.valueToTree(target);
        List<Error> errors = schema.validate(node);
        if (!errors.isEmpty()) {
            String detail = errors.stream()
                    .map(Error::getMessage)
                    .limit(MAX_DETAIL_ITEMS)
                    .collect(Collectors.joining("; "));
            throw new StepValidationException(errorCode, renderMessage(context, detail));
        }
    }

    private String renderMessage(StepContext context, String detail) {
        String base = messageTemplate != null
                ? String.valueOf(evaluator.resolve(messageTemplate, context))
                : "validation failed in step [" + name + "]";
        return detail == null || detail.isBlank() ? base : base + ": " + detail;
    }
}
