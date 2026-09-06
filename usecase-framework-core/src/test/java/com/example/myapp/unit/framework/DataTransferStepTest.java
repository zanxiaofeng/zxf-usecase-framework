package com.example.myapp.unit.framework;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.example.myapp.framework.assemble.StepDefinition;
import com.example.myapp.framework.core.Step;
import com.example.myapp.framework.core.StepContext;
import com.example.myapp.framework.core.dataflow.Dataflow;
import com.example.myapp.framework.expression.StepExpressionEvaluator;
import com.example.myapp.framework.steps.DataTransferStep;
import com.example.myapp.framework.steps.DataTransferStepFactory;

import tools.jackson.databind.ObjectMapper;

/** dataTransfer step 运行期行为：payload 转换 / as 旁路 / source 指向 vars / dataflow 声明。 */
class DataTransferStepTest {

    private final StepExpressionEvaluator evaluator = new StepExpressionEvaluator(null);
    private final DataTransferStepFactory factory =
            new DataTransferStepFactory(evaluator, new ObjectMapper());

    private StepContext contextWithPayload(Object payload) {
        StepContext context = StepContext.of(
                TestServerRequests.getRequest(Map.of("id", "u1"), Map.of()), new ObjectMapper());
        context.setPayload(payload);
        return context;
    }

    @Test
    void transformsPayloadByDefault() {
        Step step = factory.create(new StepDefinition("toCrm", "dataTransfer", null,
                Map.of("spec", "transfers/user-to-crm.yaml")));

        StepContext context = contextWithPayload(Map.of("id", "u1", "name", " Alice ", "email", "A@X.COM"));
        step.execute(context);

        // payload 被转换结果覆盖：{"crm": {userId, fullName, contact.email, channel}}
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) context.getPayload();
        @SuppressWarnings("unchecked")
        Map<String, Object> crm = (Map<String, Object>) payload.get("crm");
        assertThat(crm.get("userId")).isEqualTo("u1");
        assertThat(crm.get("fullName")).isEqualTo("Alice");
        assertThat(crm.get("channel")).isEqualTo("usecase-framework");
    }

    @Test
    void bypassOutputToVarsWithAs() {
        StepContext context = contextWithPayload(Map.of("id", "u1", "name", "Bob"));
        Step step = factory.create(new StepDefinition("toCrm", "dataTransfer", null,
                Map.of("spec", "transfers/user-to-crm.yaml", "as", "crmView")));

        step.execute(context);

        // payload 原样保留，转换结果旁路到 vars.crmView
        assertThat(context.getPayload()).isEqualTo(Map.of("id", "u1", "name", "Bob"));
        @SuppressWarnings("unchecked")
        Map<String, Object> crmView = context.getVar("crmView", Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> crm = (Map<String, Object>) crmView.get("crm");
        assertThat(crm.get("userId")).isEqualTo("u1");
    }

    @Test
    void sourceExpressionCanReadVars() {
        StepContext context = contextWithPayload("untouched");
        context.putVar("user", Map.of("id", "u9", "name", "Carol"));
        Step step = factory.create(new StepDefinition("fromVars", "dataTransfer", null,
                Map.of("spec", "transfers/user-to-crm.yaml", "source", "#vars.user")));

        step.execute(context);

        assertThat(context.getPayload()).isInstanceOf(Map.class);
        assertThat(context.getVar("user")).isNotNull();   // source 读取不改写 vars
    }

    @Test
    void dataflow_declaredForDefaultSource() {
        Step overwrite = factory.create(new StepDefinition("s1", "dataTransfer", null,
                Map.of("spec", "transfers/user-to-crm.yaml")));
        Dataflow declared = overwrite.dataflow();
        assertThat(declared.unknown()).isFalse();
        assertThat(declared.reads().toString()).contains("payload");
        assertThat(declared.writes().toString()).contains("payload");

        Step bypass = factory.create(new StepDefinition("s2", "dataTransfer", null,
                Map.of("spec", "transfers/user-to-crm.yaml", "as", "crmView")));
        assertThat(bypass.dataflow().writes().toString()).contains("vars.crmView");

        Step customSource = factory.create(new StepDefinition("s3", "dataTransfer", null,
                Map.of("spec", "transfers/user-to-crm.yaml", "source", "#vars.user")));
        assertThat(customSource.dataflow().unknown()).isTrue();
    }

}
