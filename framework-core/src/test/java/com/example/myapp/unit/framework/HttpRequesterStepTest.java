package com.example.myapp.unit.framework;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import com.example.myapp.framework.assemble.StepDefinition;
import com.example.myapp.framework.auth.AuthHandler;
import com.example.myapp.framework.auth.BearerTokenAuthHandler;
import com.example.myapp.framework.auth.NoAuthHandler;
import com.example.myapp.framework.core.Step;
import com.example.myapp.framework.core.StepContext;
import com.example.myapp.framework.core.exception.HttpStepException;
import com.example.myapp.framework.expression.StepExpressionEvaluator;
import com.example.myapp.framework.steps.HttpRequesterStepFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * HttpRequester 步骤：URI 模板变量、Bearer 认证头发放、as 旁路输出、非 2xx 抛错。
 */
class HttpRequesterStepTest {

    private final Map<String, AuthHandler> authHandlers = Map.of(
            "none", new NoAuthHandler(),
            "bearer", new BearerTokenAuthHandler(null));
    private final StepExpressionEvaluator evaluator = new StepExpressionEvaluator(null);

    private StepContext contextWithPathId() {
        return StepContext.of(
                TestServerRequests.getRequest(Map.of("id", "u1"), Map.of()), new ObjectMapper());
    }

    @Test
    void executesGetWithBearerAuthAndStoresResultAsVar() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("method", "GET");
        config.put("url", "http://credit.internal/scores/{userId}");
        config.put("uriVariables", Map.of("userId", "#path.id"));
        config.put("auth", Map.of("scheme", "bearer", "options", Map.of("token", "t-123")));
        config.put("as", "credit");

        Step step = new HttpRequesterStepFactory(restClient, authHandlers, evaluator)
                .create(new StepDefinition("fetchCredit", "httpRequester", null, config));

        server.expect(requestTo("http://credit.internal/scores/u1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer t-123"))
                .andRespond(withSuccess("{\"score\":760,\"level\":\"A\"}", MediaType.APPLICATION_JSON));

        StepContext context = contextWithPathId();
        context.setPayload("original");
        step.execute(context);

        assertThat(context.getVar("credit")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> credit = (Map<String, Object>) context.getVar("credit");
        assertThat(credit)
                .containsEntry("score", 760)
                .containsEntry("level", "A");
        assertThat(context.getPayload()).isEqualTo("original");   // as 旁路输出，不动 payload
        server.verify();
    }

    @Test
    void non2xxResponseThrowsHttpStepException() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("method", "GET");
        config.put("url", "http://credit.internal/scores/{userId}");
        config.put("uriVariables", Map.of("userId", "#path.id"));

        Step step = new HttpRequesterStepFactory(restClient, authHandlers, evaluator)
                .create(new StepDefinition("fetchCredit", "httpRequester", null, config));

        server.expect(requestTo("http://credit.internal/scores/u1"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> step.execute(contextWithPathId()))
                .isInstanceOf(HttpStepException.class)
                .satisfies(e -> assertThat(((HttpStepException) e).getDownstreamStatus()).isEqualTo(500));
    }
}
