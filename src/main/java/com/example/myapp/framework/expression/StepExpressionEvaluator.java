package com.example.myapp.framework.expression;

import com.example.myapp.framework.core.ExchangeRequest;
import com.example.myapp.framework.core.StepContext;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.context.expression.BeanFactoryResolver;
import org.springframework.context.expression.MapAccessor;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.expression.common.TemplateParserContext;
import org.springframework.lang.Nullable;

/**
 * 步骤配置中的表达式求值器（SpEL）。
 *
 * <p>求值上下文中暴露的变量：</p>
 * <ul>
 *   <li>{@code #path}    —— 路径变量 Map，如 {@code #path.id}</li>
 *   <li>{@code #query}   —— 查询参数 Map</li>
 *   <li>{@code #headers} —— 请求头 Map</li>
 *   <li>{@code #body}    —— 请求体（JSON → Map/List）</li>
 *   <li>{@code #payload} —— 当前管道主数据</li>
 *   <li>{@code #vars}    —— 命名旁路结果，如 {@code #vars.credit.score}</li>
 *   <li>{@code @beanName}—— 应用上下文中的任意 Bean（出端口、服务等）</li>
 *   <li>{@code T(com.example.Foo)} —— 类型引用，用于静态工厂/构造</li>
 * </ul>
 */
public final class StepExpressionEvaluator {

    private static final SpelExpressionParser RAW_PARSER = new SpelExpressionParser();
    private static final TemplateParserContext TEMPLATE_CONTEXT = new TemplateParserContext();

    @Nullable
    private final BeanFactory beanFactory;

    public StepExpressionEvaluator(@Nullable BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    /** 求值一个完整 SpEL 表达式（step config.expression / body 使用）。 */
    public Object evaluate(String rawExpression, StepContext context) {
        return RAW_PARSER.parseExpression(rawExpression).getValue(newEvaluationContext(context));
    }

    /**
     * 智能求值（header 值、uriVariable 值等内嵌场景使用）：
     * <ul>
     *   <li>含 {@code #{...}} → 模板拼接，如 {@code "Bearer #{vars.token}"}</li>
     *   <li>以 {@code #} / {@code @} / {@code T(} 开头 → 完整 SpEL 表达式</li>
     *   <li>其余 → 原样字面量</li>
     * </ul>
     */
    public Object resolve(String value, StepContext context) {
        if (value == null) {
            return null;
        }
        if (value.contains("#{")) {
            return RAW_PARSER.parseExpression(value, TEMPLATE_CONTEXT).getValue(newEvaluationContext(context));
        }
        String trimmed = value.strip();
        if (trimmed.startsWith("#") || trimmed.startsWith("@") || trimmed.startsWith("T(") || trimmed.startsWith("T (")) {
            return evaluate(trimmed, context);
        }
        return value;
    }

    public EvaluationContext newEvaluationContext(StepContext context) {
        // 根对象 = StepContext：模板表达式可直接写 #{path.id} / #{payload.name} / #{vars.credit.score}
        StandardEvaluationContext evaluationContext = new StandardEvaluationContext(context);
        // 宽容 Map 访问在前：缺失 key 返回 null（探测可选字段不抛错）；Spring 内置 MapAccessor 兜底
        evaluationContext.addPropertyAccessor(new LenientMapAccessor());
        evaluationContext.addPropertyAccessor(new MapAccessor());
        if (beanFactory != null) {
            evaluationContext.setBeanResolver(new BeanFactoryResolver(beanFactory));
        }
        ExchangeRequest request = context.getRequest();
        evaluationContext.setVariable("path", request.pathVariables());
        evaluationContext.setVariable("query", request.queryParams());
        evaluationContext.setVariable("headers", request.headers());
        evaluationContext.setVariable("body", request.body());
        evaluationContext.setVariable("payload", context.getPayload());
        evaluationContext.setVariable("vars", context.getVars());
        evaluationContext.setVariable("biz", context.getBiz());
        return evaluationContext;
    }
}
