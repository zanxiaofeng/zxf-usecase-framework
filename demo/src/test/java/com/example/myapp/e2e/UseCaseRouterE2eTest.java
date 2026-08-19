package com.example.myapp.e2e;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.example.myapp.domain.event.SnapshotCreatedEvent;
import com.example.myapp.infrastructure.adapter.out.messaging.InMemoryEventPublisherAdapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端：配置装配 → RouterFunction 绑定 → 响应信封与异常映射。
 * credit.base-url 指向不可达地址，用于验证下游失败 → 502 映射。
 */
@SpringBootTest(properties = "credit.base-url=http://127.0.0.1:1")
@AutoConfigureMockMvc
class UseCaseRouterE2eTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    InMemoryEventPublisherAdapter eventPublisher;

    @Test
    void getUser_returnsEnvelopeWithTraceId() throws Exception {
        mockMvc.perform(get("/api/v1/users/u1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("000000"))
                .andExpect(jsonPath("$.data.id").value("u1"))
                .andExpect(jsonPath("$.data.name").value("Alice"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void getUser_propagatesCallerTraceId() throws Exception {
        mockMvc.perform(get("/api/v1/users/u1").header("X-Request-Id", "trace-from-caller"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traceId").value("trace-from-caller"))
                .andExpect(header().string("X-Trace-Id", "trace-from-caller"));
    }

    @Test
    void getUser_invalidCallerTraceIdIsRejectedAndRegenerated() throws Exception {
        // 白名单 [A-Za-z0-9_-]{8,128} 之外的值（含 CRLF 注入载荷/过短值）丢弃，重新生成 UUID
        mockMvc.perform(get("/api/v1/users/u1").header("X-Request-Id", "bad id\r\ninject"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traceId").value(org.hamcrest.Matchers.matchesPattern(
                        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")));
    }

    @Test
    void getUserByToken_decodesBase64UrlToken() throws Exception {
        // "u1" 的 base64url 编码为 "dTE="
        mockMvc.perform(get("/api/v1/users/token/dTE="))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("000000"))
                .andExpect(jsonPath("$.data.id").value("u1"))
                .andExpect(jsonPath("$.data.name").value("Alice"));
    }

    @Test
    void getUser_unknownId_returns404WithDomainErrorCode() throws Exception {
        mockMvc.perform(get("/api/v1/users/unknown"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(header().exists("X-Trace-Id"));
    }

    @Test
    void getUserProfile_downstreamUnreachable_returns502() throws Exception {
        mockMvc.perform(get("/api/v1/users/u1/profile"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("DOWNSTREAM_ERROR"));
    }

    // ------------------------------------------------------------------
    // POST + schema validator + 子用例（isolate 旁路确认用户存在）
    // ------------------------------------------------------------------

    @Test
    void createUserSnapshot_validBody_returnsSnapshot() throws Exception {
        mockMvc.perform(post("/api/v1/user-snapshots")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"u1\",\"name\":\"alice-snap\",\"tags\":[\"vip\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("000000"))
                .andExpect(jsonPath("$.data.snapshotId").isNotEmpty())
                .andExpect(jsonPath("$.data.ownerName").value("Alice"))   // 子用例旁路结果参与组装
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void createUserSnapshot_publishesDomainEventAfterSave() throws Exception {
        // eventPublisher step：快照保存后发布 SnapshotCreatedEvent（经唯一 EventPublisher Bean 外发）
        mockMvc.perform(post("/api/v1/user-snapshots")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"u1\",\"name\":\"alice-snap\"}"))
                .andExpect(status().isCreated());

        assertThat(eventPublisher.publishedEvents())
                .singleElement()
                .isInstanceOfSatisfying(SnapshotCreatedEvent.class, event -> {
                    assertThat(event.snapshotId()).startsWith("snap-");
                    assertThat(event.userId()).isEqualTo("u1");
                });
    }

    @Test
    void createUserSnapshot_invalidBody_returns400WithValidationError() throws Exception {
        mockMvc.perform(post("/api/v1/user-snapshots")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))                       // 缺 userId，name 为空
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("userId")))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void createUserSnapshot_unknownUser_subUsecasePropagates404() throws Exception {
        // isolate 子用例确认用户存在性：用户不存在时领域异常穿透子用例边界映射为 404
        mockMvc.perform(post("/api/v1/user-snapshots")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"ghost\",\"name\":\"x\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    @Test
    void createUserSnapshot_malformedJson_returns400WithBadRequestCode() throws Exception {
        // 坏 JSON 不再静默为 null（导致误导性的 schema 明细），而是明确的 400 BAD_REQUEST
        mockMvc.perform(post("/api/v1/user-snapshots")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\": bad json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Malformed JSON")));
    }

    // ------------------------------------------------------------------
    // Java 代码内调用 shared 子用例（UseCaseInvoker / AbstractUseCaseClient）
    // ------------------------------------------------------------------

    @Test
    void greetUser_javaClientInvokesSubUsecase() throws Exception {
        mockMvc.perform(get("/api/v1/users/u1/greeting"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("000000"))
                .andExpect(jsonPath("$.data.greeting").value("Hello, Alice"))
                .andExpect(jsonPath("$.data.invokedFrom").value("java"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void greetUser_unknownUser_domainErrorPropagatesThroughJavaInvoke() throws Exception {
        // Java 调用子用例的异常语义与 YAML 子用例 step 一致：领域异常穿透边界映射为 404
        mockMvc.perform(get("/api/v1/users/ghost/greeting"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }
}
