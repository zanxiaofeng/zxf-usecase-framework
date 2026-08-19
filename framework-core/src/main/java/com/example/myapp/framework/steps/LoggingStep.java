package com.example.myapp.framework.steps;

import com.example.myapp.framework.core.Step;
import com.example.myapp.framework.core.StepContext;
import com.example.myapp.framework.expression.StepExpressionEvaluator;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.event.Level;

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
 * }</pre>
 *
 * <p>日志 category 为 {@code usecase.<useCaseId>.step.<stepName>}，可按用例或步骤名定向治理日志级别；
 * starter 写入的 MDC（biz.*）会随日志 pattern 自动携带。</p>
 */
@RequiredArgsConstructor
public final class LoggingStep implements Step {

    private final String name;
    private final String messageTemplate;
    private final Level level;
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
        Object raw = messageTemplate == null
                ? context.getPayload()
                : evaluator.resolve(messageTemplate, context);
        String rendered = String.valueOf(raw);
        log.atLevel(level).log(rendered);
    }
}
