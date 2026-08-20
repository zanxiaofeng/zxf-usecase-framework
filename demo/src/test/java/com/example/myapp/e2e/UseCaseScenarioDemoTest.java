package com.example.myapp.e2e;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.ObjectMapper;

import com.example.myapp.application.dto.UserDto;
import com.example.myapp.domain.event.SnapshotCreatedEvent;
import com.example.myapp.framework.core.UseCaseRegistry;
import com.example.myapp.framework.test.RecordingEventPublisher;
import com.example.myapp.framework.test.UseCaseScenario;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * framework-test harness（UseCaseScenario）在真实装配产物上的示范：
 * 不经 HTTP 层，直接驱动 YAML 定义的管道，断言 payload / vars / biz / 事件发布。
 * 与 UseCaseRouterE2eTest（MockMvc 全链路 + 信封/状态码断言）互补并存。
 */
@SpringBootTest
class UseCaseScenarioDemoTest {

    /**
     * 事件探针注册为 @Primary：eventPublisher 步骤运行期解析（getIfAvailable 尊重 @Primary）
     * 命中探针，真实出站适配器（InMemoryEventPublisherAdapter）在本测试上下文被旁路。
     */
    @TestConfiguration
    static class EventProbeConfig {

        @Bean
        @Primary
        RecordingEventPublisher recordingEventPublisher() {
            return new RecordingEventPublisher();
        }
    }

    @Autowired
    UseCaseRegistry registry;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    RecordingEventPublisher eventRecorder;

    @Test
    void getUser_pipelineStateAssertions() {
        UseCaseScenario.given(registry, objectMapper)
                .request("GET", "/api/v1/users/{id}")
                .pathVar("id", "u1")
                .expectBiz("businessId", "u1")
                .expectPayload(UserDto.class, dto -> {
                    assertThat(dto.id()).isEqualTo("u1");
                    assertThat(dto.name()).isEqualTo("Alice");
                })
                .run();
    }

    @Test
    void getUserByToken_decodesTokenThroughPipeline() {
        UseCaseScenario.given(registry, objectMapper)
                .request("GET", "/api/v1/users/token/{token}")
                .pathVar("token", "dTE=")           // "u1" 的 base64url
                .expectBiz("token", "dTE=")
                .expectPayload(UserDto.class, dto -> assertThat(dto.name()).isEqualTo("Alice"))
                .run();
    }

    @Test
    void createUserSnapshot_sideBranchAndEventAssertions() {
        UseCaseScenario.given(registry, objectMapper)
                .request("POST", "/api/v1/user-snapshots")
                .body("{\"userId\":\"u1\",\"name\":\"alice-snap\",\"tags\":[\"vip\"]}")
                .recordingEventsTo(eventRecorder)
                .expectBiz("businessId", "u1")
                .expectVar("userDto", userDto -> assertThat(((UserDto) userDto).name()).isEqualTo("Alice"))
                .expectPayload(payload -> {
                    Map<?, ?> snapshot = (Map<?, ?>) payload;
                    assertThat(String.valueOf(snapshot.get("snapshotId"))).startsWith("snap-");
                    assertThat(snapshot.get("ownerName")).isEqualTo("Alice");
                })
                .expectEventPublished(SnapshotCreatedEvent.class, event -> {
                    assertThat(event.snapshotId()).startsWith("snap-");
                    assertThat(event.userId()).isEqualTo("u1");
                })
                .run();
    }
}
