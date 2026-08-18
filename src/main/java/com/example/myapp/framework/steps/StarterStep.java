package com.example.myapp.framework.steps;

import com.example.myapp.framework.core.Starter;
import com.example.myapp.framework.core.StepContext;
import com.example.myapp.framework.expression.StepExpressionEvaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.Map;

/**
 * 起始步骤：从请求中提取关键业务标识，写入 {@code biz} 关键数据区并同步日志 MDC。
 *
 * <p>配置示例：</p>
 * <pre>{@code
 * - name: start
 *   type: starter
 *   config:
 *     keys:
 *       businessId: "#path.id"                      # 约定键：关键业务 ID
 *       tenantId: "#headers['X-Tenant-Id']"         # 完整 SpEL
 *       channel: "#{headers['X-Channel']}"          # 模板形式
 *       source: "app"                               # 字面量
 * }</pre>
 *
 * <p>每个键的解析规则同 {@link StepExpressionEvaluator#resolve}（字面量 / #{...} 模板 / SpEL）。
 * 解析结果非 null 时同步到 MDC（键名 {@code biz.<key>}），管道结束后由 Web 层统一清理。</p>
 */
public final class StarterStep implements Starter {

    /** MDC 键前缀，Web 层按此前缀清理 */
    public static final String MDC_PREFIX = "biz.";

    private static final Logger log = LoggerFactory.getLogger(StarterStep.class);

    private final String name;
    private final Map<String, String> keyExpressions;
    private final StepExpressionEvaluator evaluator;

    public StarterStep(String name, Map<String, String> keyExpressions, StepExpressionEvaluator evaluator) {
        this.name = name;
        this.keyExpressions = keyExpressions;
        this.evaluator = evaluator;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void execute(StepContext context) {
        keyExpressions.forEach((key, expression) -> {
            Object value = evaluator.resolve(expression, context);
            context.putBiz(key, value);
            if (value != null) {
                MDC.put(MDC_PREFIX + key, String.valueOf(value));
            }
        });
        log.debug("starter [{}] captured biz keys: {}", name, context.getBiz().keySet());
    }
}
