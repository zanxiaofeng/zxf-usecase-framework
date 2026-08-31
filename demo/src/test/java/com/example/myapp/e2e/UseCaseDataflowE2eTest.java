package com.example.myapp.e2e;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;

import com.example.myapp.application.dto.UserDto;
import com.example.myapp.framework.core.dataflow.AccessEvent;
import com.example.myapp.framework.core.dataflow.DataflowTrace;
import com.example.myapp.framework.core.dataflow.StepRef;
import com.example.myapp.framework.core.UseCaseRegistry;
import com.example.myapp.framework.test.UseCaseScenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * 数据链显性化的端到端示范：真实装配产物上的键级血缘断言——
 * 跨步读写（biz → 自定义 step）、Java client 子用例链归属（shared）、isolate 子用例边界。
 *
 * <p>fetchCredit（httpRequester）环节需要真实 HTTP 下游，不在本测试覆盖范围
 * （其声明推导由 framework-core 单测覆盖，运行期落地与其余 step 同一 storeResult 机制）。</p>
 */
@SpringBootTest
class UseCaseDataflowE2eTest {

    @Autowired
    UseCaseRegistry registry;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void greetUser_bizFlowThroughUndeclaredStepAndJavaClientChildChain() {
        DataflowTrace trace = UseCaseScenario.given(registry, objectMapper)
                .request("GET", "/api/v1/users/{id}/greeting")
                .pathVar("id", "u1")
                .expectPayload(payload -> {
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> greeting = (java.util.Map<String, Object>) payload;
                    assertThat(greeting.get("greeting")).isEqualTo("Hello, Alice");
                })
                .run()
                .trace();

        // 跨步键级血缘：start 写 biz.businessId → GreetingStep 读（自定义 ref step，未声明 dataflow——实测仍被记录）
        assertThat(trace.writersOf("biz.businessId")).containsExactly(StepRef.of("greetUser", "start"));
        assertThat(trace.readersOf("biz.businessId"))
                .contains(StepRef.of("greetUser", "GreetingStep"));

        // Java client 子用例链（userBaseClient.invoke）：事件归属子用例自身
        assertThat(trace.events()).extracting(AccessEvent::useCaseId, AccessEvent::stepName, AccessEvent::key)
                .contains(tuple("userBaseEnrichment", "loadUser", com.example.myapp.framework.core.dataflow.DataflowKey.payload()),
                        tuple("userBaseEnrichment", "toDto", com.example.myapp.framework.core.dataflow.DataflowKey.payload()));
        // GreetingStep 写 payload（局部变量重组后的最终产物）
        assertThat(trace.writesOf("greetUser", "GreetingStep")).contains(
                com.example.myapp.framework.core.dataflow.DataflowKey.payload());
        assertThat(trace.useCases()).contains("greetUser", "userBaseEnrichment");
    }

    @Test
    void createUserSnapshot_isolateChildChainAndSideOutput() {
        DataflowTrace trace = UseCaseScenario.given(registry, objectMapper)
                .request("POST", "/api/v1/user-snapshots")
                .body("{\"userId\":\"u1\",\"name\":\"alice-snap\",\"tags\":[\"vip\"]}")
                .run()
                .trace();

        // isolate 子用例：子链事件归子用例，最终结果经 as 键旁路落 vars（归属 usecase step 自身）
        assertThat(trace.writersOf("vars.userDto")).containsExactly(StepRef.of("createUserSnapshot", "checkUserExists"));
        assertThat(trace.readersOf("vars.userDto")).contains(StepRef.of("createUserSnapshot", "logCreation"));
        assertThat(trace.useCases()).contains("userBaseEnrichment");
    }

    @Test
    void getUserByToken_decoderChainsIntoSubUseCaseViaPayload() {
        UseCaseScenario scenario = UseCaseScenario.given(registry, objectMapper)
                .request("GET", "/api/v1/users/token/{token}")
                .pathVar("token", "dTE=")
                .expectPayload(UserDto.class, dto -> assertThat(dto.name()).isEqualTo("Alice"));

        DataflowTrace trace = scenario.run().trace();

        // decoder 未配 as → 解码结果写回 payload → 子用例 input 缺省 #payload 隐式接收（位置约定在 trace 中显形）
        List<AccessEvent> decodeWrites = trace.events().stream()
                .filter(event -> event.stepName().equals("decodeToken") && event.op() == AccessEvent.Op.WRITE)
                .toList();
        assertThat(decodeWrites).extracting(AccessEvent::key)
                .containsExactly(com.example.myapp.framework.core.dataflow.DataflowKey.payload());
        assertThat(trace.readersOf("payload"))
                .contains(StepRef.of("userBaseEnrichment", "loadUser"));
    }
}
