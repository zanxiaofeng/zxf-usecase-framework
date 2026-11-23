package com.example.usecase.framework.steps;

import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.event.Level;

import com.example.usecase.framework.core.spi.Step;
import com.example.usecase.framework.core.context.StepContext;
import com.example.usecase.framework.expression.StepExpressionEvaluator;

/**
 * 日志步骤：在管道任意位置输出一条日志，不产生/修改任何数据（payload、vars、biz 均不变）。
 *
 * <p>配置示例：</p>
 * <pre>{@code
 * - name: logCredit
 *   type: logging
 *   config:
 *     level: INFO                                        # TRACE/DEBUG/INFO/WARN/ERROR，缺省 INFO
 *     message: "用户 #{biz.businessId} 信用分 #{vars.credit.score}"   # 值输出须显式配置
 *     logContext: true                                   # 可选；true 时以 DEBUG 输出上下文全部内容（替代主消息）
 * }</pre>
 *
 * <p><b>隐私基线</b>：未配置 {@code message} 时打印<b>摘要</b>（payload 类型 + vars/biz 键集合，
 * 与数据链录制"只记键不记值"基线一致）而非整个 payload；输出值必须显式配置
 * {@code message}（模板）或 {@code logContext}（DEBUG 级全量）。</p>
 *
 * <p>日志 category 为 {@code usecase.<useCaseId>.step.<stepName>}，可按用例或步骤名定向治理日志级别；
 * starter 写入的 MDC（biz.*）会随日志 pattern 自动携带。{@code logContext} 的 DEBUG 输出同样走该
 * category，可用 {@code logging.level.usecase.<useCaseId>=DEBUG} 按需开启。</p>
 */
@RequiredArgsConstructor
public final class LoggingStep implements Step {

    private final String name;
    private final @Nullable String messageTemplate;
    private final Level level;
    private final boolean logContext;
    private final StepExpressionEvaluator evaluator;
    /**
     * 由 LoggingStepFactory 按 category 约定创建（动态 category 无法使用 @Slf4j，属手动 Logger 的合理例外）
     */
    private final Logger log;

    @Override
    public String name() {
        return name;
    }

    @Override
    public void execute(StepContext context) {
        if (logContext) {
            // SLF4J 占位符仅在实际输出时才 toString，DEBUG 关闭时零序列化开销
            log.debug("context: payload={} | vars={} | biz={}",
                    context.getPayload(), context.getVars(), context.getBiz());
            return;
        }

        if (messageTemplate == null) {
            // 缺省摘要：类型 + 键集（不记值——与 usecase.trace / dataflow.record 的隐私基线一致；
            // 值输出须显式配置 message 模板或 logContext）
            Object payload = context.getPayload();
            log.atLevel(level).log("payload={} | vars keys={} | biz keys={}",
                    payload == null ? "null" : payload.getClass().getSimpleName(),
                    context.getVars().keySet(), context.getBiz().keySet());
            return;
        }

        Object raw = evaluator.resolve(messageTemplate, context, name);
        String rendered = String.valueOf(raw);
        log.atLevel(level).log(rendered);
    }
}
