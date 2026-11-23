package com.example.usecase.framework.expression;

import java.util.concurrent.ConcurrentHashMap;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.context.expression.BeanFactoryResolver;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.EvaluationException;
import org.springframework.expression.Expression;
import org.springframework.expression.ParseException;
import org.springframework.expression.common.TemplateParserContext;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import com.example.usecase.framework.core.context.StepContext;
import com.example.usecase.framework.core.exception.StepValidationException;
import com.example.usecase.framework.core.exception.UseCaseAssemblyException;

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
@Slf4j
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
    public @Nullable Object evaluate(String rawExpression, StepContext context) {
        return expressionCache.computeIfAbsent(rawExpression, RAW_PARSER::parseExpression)
                .getValue(newEvaluationContext(context));
    }

    /**
     * 带 step 名的求值收口（内置 step 统一入口）：SpEL 自身求值失败（属性访问/类型错误等）
     * 包装为 400 语义的 {@link StepValidationException} 并附 step 名——多为「输入不满足表达式前提」
     * （如非 JSON 文本体上取 {@code #body.xxx}），属客户端可修正的 4xx 而非系统故障。
     * Bean 方法抛出的领域异常不经 SpEL 包装、原样传播，仍走领域异常映射。
     *
     * <p><b>消息脱敏</b>：SpEL 求值错误原文（EL1008E 等）可能携带内部类名、Bean 名与表达式
     * 结构，不进入客户端可见的异常消息——此处 WARN 记录完整求值错误（配置作者排错落点，
     * MDC 携带 traceId），对外仅抛固定文案。</p>
     */
    public @Nullable Object evaluate(String rawExpression, StepContext context, String stepName) {
        try {
            return evaluate(rawExpression, context);
        } catch (EvaluationException e) {
            log.warn("step [{}] expression evaluation failed: {} | expression: {}", stepName, e.getMessage(),
                    rawExpression, e);
            throw new StepValidationException(StepValidationException.DEFAULT_CODE,
                    "step [%s] expression evaluation failed（详见服务端日志，可凭响应 traceId 定位）".formatted(stepName));
        }
    }

    /**
     * 智能求值（header 值、uriVariable 值等内嵌场景使用）：
     * <ul>
     *   <li>含 {@code #{...}} → 模板拼接，如 {@code "Bearer #{vars.token}"}</li>
     *   <li>以 {@code #} / {@code @} / {@code T(} 开头 → 完整 SpEL 表达式</li>
     *   <li>其余 → 原样字面量</li>
     * </ul>
     */
    public @Nullable Object resolve(@Nullable String value, StepContext context) {
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

    /** {@link #resolve} 的 step 名收口变体：求值失败同样映射 400 并附 step 名（消息脱敏，详见 {@link #evaluate}）。 */
    public @Nullable Object resolve(@Nullable String value, StepContext context, String stepName) {
        try {
            return resolve(value, context);
        } catch (EvaluationException e) {
            log.warn("step [{}] expression evaluation failed: {} | expression: {}", stepName, e.getMessage(), value, e);
            throw new StepValidationException(StepValidationException.DEFAULT_CODE,
                    "step [%s] expression evaluation failed（详见服务端日志，可凭响应 traceId 定位）".formatted(stepName));
        }
    }

    /**
     * 装配期语法预解析（启动 fail-fast 防线）：按 {@link #resolve} 的三规则分类——含 {@code #{}}
     * 走模板、以 {@code #} / {@code @} / {@code T(} 开头走完整 SpEL、其余为字面量直接放行——
     * 仅 parse 不求值（求值依赖运行期数据形状，语法正确不代表求值成功），并把解析结果
     * 填入缓存（运行期首求值零解析开销）。
     *
     * <p>语法错误抛 {@link ParseException}，由 {@link #precompileForStep} 统一转
     * {@link UseCaseAssemblyException} 启动期 fail-fast——配置缺陷不该拖延到首个请求。</p>
     */
    void precompile(@Nullable String rawExpression) {
        if (rawExpression == null) {
            return;
        }
        if (rawExpression.contains("#{")) {
            templateCache.computeIfAbsent(rawExpression, v -> RAW_PARSER.parseExpression(v, TEMPLATE_CONTEXT));
            return;
        }
        String trimmed = rawExpression.strip();
        if (trimmed.startsWith("#") || trimmed.startsWith("@") || trimmed.startsWith("T(") || trimmed.startsWith("T (")) {
            expressionCache.computeIfAbsent(trimmed, RAW_PARSER::parseExpression);
        }
    }

    /**
     * 装配期语法预解析（各 StepFactory 调用入口）：任一表达式语法错误即抛
     * {@link UseCaseAssemblyException}（含 step 名与表达式原文）——启动期 fail-fast。
     * null 元素与字面量（不满足三规则前缀的值）静默放行。
     */
    public void precompileForStep(String stepName, @Nullable String... rawExpressions) {
        for (String raw : rawExpressions) {
            try {
                precompile(raw);
            } catch (ParseException e) {
                throw new UseCaseAssemblyException(
                        "step [%s]: invalid expression syntax: %s".formatted(stepName, raw), e);
            }
        }
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
