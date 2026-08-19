package com.example.myapp.framework.steps;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.event.Level;

import com.example.myapp.framework.core.Step;
import com.example.myapp.framework.core.StepContext;
import com.example.myapp.framework.expression.StepExpressionEvaluator;

/**
 * 日志步骤：在管道任意位置输出一条日志，不产生/修改任何数据（payload、vars、biz 均不变）。
 *
 * <p>配置示例：</p>
 * <pre>{@code
 * - name: logCredit
 *   type: logging
 *   config:
 *     level: INFO                                        # TRACE/DEBUG/INFO/WARN/ERROR，缺省 INFO
 *     message: "用户 #{biz.businessId} 信用分 #{vars.credit.score}"   # 缺省打印 payload
 *     logContext: true                                   # 可选；true 时以 DEBUG 输出上下文全部内容
 * }</pre>
 *
 * <p>日志 category 为 {@code usecase.<useCaseId>.step.<stepName>}，可按用例或步骤名定向治理日志级别；
 * starter 写入的 MDC（biz.*）会随日志 pattern 自动携带。{@code logContext} 的 DEBUG 输出同样走该
 * category，可用 {@code logging.level.usecase.<useCaseId>=DEBUG} 按需开启。</p>
 */
@RequiredArgsConstructor
public final class LoggingStep implements Step {

    private final String name;
    private final String messageTemplate;
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

        Object raw = messageTemplate == null
                ? context.getPayload()
                : evaluator.resolve(messageTemplate, context);
        String rendered = String.valueOf(raw);
        log.atLevel(level).log(rendered);
    }
}
