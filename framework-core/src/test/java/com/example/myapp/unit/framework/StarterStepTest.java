package com.example.myapp.unit.framework;

import com.example.myapp.framework.config.StepDefinition;
import com.example.myapp.framework.core.SimpleExchangeRequest;
import com.example.myapp.framework.core.Step;
import com.example.myapp.framework.core.StepContext;
import com.example.myapp.framework.core.UseCaseAssemblyException;
import com.example.myapp.framework.expression.StepExpressionEvaluator;
import com.example.myapp.framework.steps.StarterStepFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * starter 步骤：提取关键业务标识 → biz 关键数据区 + MDC；#biz 可被后续表达式引用。
 */
class StarterStepTest {

    private final StepExpressionEvaluator evaluator = new StepExpressionEvaluator(null);

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void capturesKeysIntoBizAreaAndMdc() {
        Map<String, Object> keys = new LinkedHashMap<>();
        keys.put("businessId", "#path.id");
        keys.put("tenantId", "#headers['X-Tenant-Id']");
        keys.put("source", "app");                       // 字面量
        keys.put("channel", "#{headers['X-Channel']}");  // 模板形式

        Step step = new StarterStepFactory(evaluator)
                .create(new StepDefinition("start", "starter", null, Map.of("keys", keys)));

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Tenant-Id", "t-42");
        headers.put("X-Channel", "mobile");
        StepContext context = new StepContext(new SimpleExchangeRequest(
                "GET", "/users/{id}", Map.of("id", "u1"), Map.of(), headers, null));

        step.execute(context);

        assertThat(context.getBiz())
                .containsEntry("businessId", "u1")
                .containsEntry("tenantId", "t-42")
                .containsEntry("source", "app")
                .containsEntry("channel", "mobile");
        // 同步 MDC，供全链路日志关联
        assertThat(MDC.get("biz.businessId")).isEqualTo("u1");
        assertThat(MDC.get("biz.tenantId")).isEqualTo("t-42");
        // 后续步骤可经 #biz 引用
        assertThat(evaluator.evaluate("#biz.businessId", context)).isEqualTo("u1");
    }

    @Test
    void missingKeysFailsFastAtAssembly() {
        assertThatThrownBy(() -> new StarterStepFactory(evaluator)
                .create(new StepDefinition("start", "starter", null, Map.of())))
                .isInstanceOf(UseCaseAssemblyException.class)
                .hasMessageContaining("keys");
    }

    @Test
    void mdcValueStripsControlCharactersWhileBizKeepsRawValue() {
        Map<String, Object> keys = Map.of("channel", "#headers['X-Channel']");
        Step step = new StarterStepFactory(evaluator)
                .create(new StepDefinition("start", "starter", null, Map.of("keys", keys)));

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Channel", "mobile\r\nFAKE-LOG");   // 外部可控值带控制字符（日志注入载荷）
        StepContext context = new StepContext(
                new SimpleExchangeRequest("GET", "/x", Map.of(), Map.of(), headers, null));

        step.execute(context);

        // MDC（日志通道）剥离控制字符；biz（数据通道）保留原始值
        assertThat(MDC.get("biz.channel")).isEqualTo("mobileFAKE-LOG");
        assertThat(context.getBiz("channel")).isEqualTo("mobile\r\nFAKE-LOG");
    }
}
