package com.example.myapp.framework.steps;

import com.example.myapp.framework.core.Logging;
import com.example.myapp.framework.core.StepContext;
import com.example.myapp.framework.expression.StepExpressionEvaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * 日志步骤：在管道任意位置输出一条日志，不产生/修改任何数据（payload、vars、biz 均不变）。
 *
 * <p>配置示例：</p>
 * <pre>{@code
 * - name: logCredit
 *   type: logging
 *   config:
 *     level: INFO                                        # DEBUG/INFO/WARN/ERROR，缺省 INFO
 *     message: "用户 #{biz.businessId} 信用分 #{vars.credit.score}"   # 缺省打印 payload
 * }</pre>
 *
 * <p>日志 category 为 {@code usecase.step.<stepName>}，可按步骤名定向治理日志级别；
 * starter 写入的 MDC（biz.*）会随日志 pattern 自动携带。</p>
 */
public final class LoggingStep implements Logging {

    public enum Level {
        DEBUG, INFO, WARN, ERROR
    }

    private final String name;
    private final String messageTemplate;
    private final Level level;
    private final StepExpressionEvaluator evaluator;
    private final Logger log;

    public LoggingStep(String name, String messageTemplate, Level level, StepExpressionEvaluator evaluator) {
        this.name = name;
        this.messageTemplate = messageTemplate;
        this.level = level;
        this.evaluator = evaluator;
        this.log = LoggerFactory.getLogger("usecase.step." + name);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void execute(StepContext context) {
        String rendered = messageTemplate == null
                ? String.valueOf(context.getPayload())
                : String.valueOf(evaluator.resolve(messageTemplate, context));
        switch (level) {
            case DEBUG -> log.debug(rendered);
            case INFO -> log.info(rendered);
            case WARN -> log.warn(rendered);
            case ERROR -> log.error(rendered);
        }
    }

    /** 由工厂解析 level 配置，非法值在装配期报错 */
    public static Level parseLevel(String raw, String stepName) {
        try {
            return Level.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "step [%s]: invalid logging level '%s', expected DEBUG/INFO/WARN/ERROR".formatted(stepName, raw));
        }
    }
}
