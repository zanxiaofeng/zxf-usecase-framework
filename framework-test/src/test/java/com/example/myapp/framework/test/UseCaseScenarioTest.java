package com.example.myapp.framework.test;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import com.example.myapp.framework.core.Step;
import com.example.myapp.framework.core.StepContext;
import com.example.myapp.framework.core.UseCase;
import com.example.myapp.framework.core.UseCase.EndpointSpec;
import com.example.myapp.framework.core.UseCaseRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link UseCaseScenario} 自身语义：请求构造（path/query/header/body）、用例定位（端点匹配 / id 直定）、
 * traceId 种子化与 MDC 清理、payload/vars/biz/事件断言。
 */
class UseCaseScenarioTest {

    private final ObjectMapper objectMapper = JsonMapper.shared();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    /** 探针 step：把请求各视图与 biz traceId 抄进 vars，payload 置为 path id */
    private Step inspectStep() {
        return context -> {
            context.putVar("pathId", context.getPath().get("id"));
            context.putVar("queryQ", context.getQuery().get("q"));
            context.putVar("channel", context.getHeaders().get("X-Channel"));
            context.putVar("seededTraceId", context.getBiz(StepContext.TRACE_ID_KEY));
            context.setPayload(context.getPath().get("id"));
        };
    }

    private UseCaseRegistry registryWith(UseCase... useCases) {
        return new UseCaseRegistry(List.of(useCases));
    }

    private UseCase getUserUseCase() {
        return new UseCase("getUser", null,
                new EndpointSpec(HttpMethod.GET, "/api/v1/users/{id}", 200), List.of(inspectStep()), false);
    }

    @Test
    void requestViewsAreVisibleToStepsAndAssertionsPass() {
        ScenarioResult result = UseCaseScenario.given(registryWith(getUserUseCase()), objectMapper)
                .request("GET", "/api/v1/users/{id}")
                .pathVar("id", "u1")
                .queryParam("q", "x")
                .header("X-Channel", "web")
                .expectVar("pathId", "u1")
                .expectVar("queryQ", "x")
                .expectVar("channel", "web")
                .expectVar("seededTraceId", UseCaseScenario.DEFAULT_TRACE_ID)
                .expectPayload("u1")
                .expectBiz(StepContext.TRACE_ID_KEY, UseCaseScenario.DEFAULT_TRACE_ID)
                .run();

        assertThat(result.payload(String.class)).isEqualTo("u1");
    }

    @Test
    void traceIdCanBeOverridden() {
        UseCaseScenario.given(registryWith(getUserUseCase()), objectMapper)
                .useCase("getUser")
                .traceId("t-9")
                .expectVar("seededTraceId", "t-9")
                .run();
    }

    @Test
    void postBodyIsParsedAsJson() {
        Step readBody = context -> context.setPayload(((Map<?, ?>) context.getBody()).get("userId"));
        UseCase createSnapshot = new UseCase("createSnapshot", null,
                new EndpointSpec(HttpMethod.POST, "/api/v1/snapshots", 201), List.of(readBody), false);

        UseCaseScenario.given(registryWith(createSnapshot), objectMapper)
                .request("POST", "/api/v1/snapshots")
                .body("{\"userId\":\"u1\"}")
                .expectPayload("u1")
                .run();
    }

    @Test
    void eventExpectationsVerifyRecordedEvents() {
        RecordingEventPublisher recorder = new RecordingEventPublisher();
        Step publish = context -> recorder.publish("snapshot-created");
        UseCase publisher = new UseCase("publish", null,
                new EndpointSpec(HttpMethod.POST, "/events", 200), List.of(publish), false);

        UseCaseScenario.given(registryWith(publisher), objectMapper)
                .useCase("publish")
                .recordingEventsTo(recorder)
                .expectEventPublished(String.class)
                .expectEventPublished(String.class, event -> assertThat(event).isEqualTo("snapshot-created"))
                .run();
    }

    @Test
    void eventExpectationFailsWhenNoMatchingEvent() {
        RecordingEventPublisher recorder = new RecordingEventPublisher();
        UseCase noop = new UseCase("noop", null,
                new EndpointSpec(HttpMethod.GET, "/noop", 200), List.of(context -> {
                }), false);

        assertThatThrownBy(() -> UseCaseScenario.given(registryWith(noop), objectMapper)
                .useCase("noop")
                .recordingEventsTo(recorder)
                .expectEventPublished(String.class)
                .run())
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void eventExpectationWithoutRecorderFailsFast() {
        assertThatThrownBy(() -> UseCaseScenario.given(registryWith(getUserUseCase()), objectMapper)
                .useCase("getUser")
                .expectEventPublished(String.class)
                .run())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("recordingEventsTo");
    }

    @Test
    void unknownRouteFailsWithAvailableRoutes() {
        assertThatThrownBy(() -> UseCaseScenario.given(registryWith(getUserUseCase()), objectMapper)
                .request("GET", "/api/v1/nope")
                .run())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("GET /api/v1/users/{id}");
    }

    @Test
    void missingUseCaseLocationFailsFast() {
        assertThatThrownBy(() -> UseCaseScenario.given(registryWith(getUserUseCase()), objectMapper).run())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("request(method, path)");
    }

    @Test
    void expectVarMismatchRaisesAssertionError() {
        assertThatThrownBy(() -> UseCaseScenario.given(registryWith(getUserUseCase()), objectMapper)
                .request("GET", "/api/v1/users/{id}")
                .pathVar("id", "u1")
                .expectVar("pathId", "u2")
                .run())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("vars[pathId]");
    }

    @Test
    void mdcIsCleanedAfterRun() {
        Step mdcWriter = context -> MDC.put("biz.businessId", "u1");
        UseCase dirty = new UseCase("dirty", null,
                new EndpointSpec(HttpMethod.GET, "/dirty", 200), List.of(mdcWriter), false);

        UseCaseScenario.given(registryWith(dirty), objectMapper).useCase("dirty").run();

        assertThat(MDC.get("biz.businessId")).isNull();
        assertThat(MDC.get(StepContext.TRACE_ID_KEY)).isNull();
    }
}
