package com.example.myapp.framework.expression;

import java.util.concurrent.ConcurrentHashMap;

import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.context.expression.BeanFactoryResolver;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.common.TemplateParserContext;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import com.example.myapp.framework.core.StepContext;

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
 *
 * <p>表达式按原文缓存解析结果（{@link Expression} 不可变且线程安全，可并发求值）。
 * 键空间仅来自 YAML 配置（step 表达式、starter keys、header/uriVariable 模板），为有限集合，
 * 缓存无界增长风险可控——避免了每个请求、每个 step 重复构建语法树的开销。</p>
 */
@RequiredArgsConstructor
public final class StepExpressionEvaluator {

    private static final SpelExpressionParser RAW_PARSER = new SpelExpressionParser();
    private static final TemplateParserContext TEMPLATE_CONTEXT = new TemplateParserContext();

    /** 完整 SpEL 表达式缓存（step 的 expression/source/target 等） */
    private final ConcurrentHashMap<String, Expression> expressionCache = new ConcurrentHashMap<>();

    /** 模板表达式缓存（含 #{...} 的内嵌值，如 starter keys、header 值、logging message） */
    private final ConcurrentHashMap<String, Expression> templateCache = new ConcurrentHashMap<>();

    @Nullable
    private final BeanFactory beanFactory;

    /** 求值一个完整 SpEL 表达式（step config.expression / body 使用）。 */
    public Object evaluate(String rawExpression, StepContext context) {
        return expressionCache.computeIfAbsent(rawExpression, RAW_PARSER::parseExpression)
                .getValue(newEvaluationContext(context));
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
            return templateCache
                    .computeIfAbsent(value, v -> RAW_PARSER.parseExpression(v, TEMPLATE_CONTEXT))
                    .getValue(newEvaluationContext(context));
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
        // 宽容 Map 访问器：对任意 Map 认领属性读取，缺失 key 返回 null（探测可选字段不抛 EL1008E）。
        // 注：Spring 内置 MapAccessor 自 Framework 7 起已 deprecated forRemoval，不再注册兜底
        evaluationContext.addPropertyAccessor(new LenientMapAccessor());
        if (beanFactory != null) {
            evaluationContext.setBeanResolver(new BeanFactoryResolver(beanFactory));
        }
        evaluationContext.setVariable("path", context.getPath());
        evaluationContext.setVariable("query", context.getQuery());
        evaluationContext.setVariable("headers", context.getHeaders());
        evaluationContext.setVariable("body", context.getBody());
        evaluationContext.setVariable("payload", context.getPayload());
        evaluationContext.setVariable("vars", context.getVars());
        evaluationContext.setVariable("biz", context.getBiz());
        return evaluationContext;
    }
}
