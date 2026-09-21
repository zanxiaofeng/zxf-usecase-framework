package com.example.myapp.unit.application;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.example.myapp.application.dto.UserDto;
import com.example.myapp.application.step.UserProfileTransformer;
import com.example.usecase.framework.core.context.StepContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link UserProfileTransformer} 单元测试：payload 主数据 + 旁路 vars 合并逻辑（纯 JVM，零容器）。
 */
class UserProfileTransformerTest {

    private final UserProfileTransformer transformer = new UserProfileTransformer();

    @Test
    void testExecuteMergesPayloadAndBypassVars() {
        StepContext context = StepContext.standalone();
        context.setPayload(new UserDto("u1", "Alice"));
        context.putVar("credit", Map.of("score", 760, "level", "A"));
        context.putVar("encodedUserId", "enc-1");

        transformer.execute(context);

        assertThat(context.<Object>getPayload()).isEqualTo(Map.of(
                "id", "u1",
                "name", "Alice",
                "creditScore", 760,
                "creditLevel", "A",
                "encodedId", "enc-1"));
    }

    @Test
    void testExecuteCreditMissingSkipsCreditKeys() {
        StepContext context = StepContext.standalone();
        context.setPayload(new UserDto("u1", "Alice"));
        context.putVar("encodedUserId", "enc-1");

        transformer.execute(context);

        assertThat(context.<Object>getPayload()).isEqualTo(Map.of(
                "id", "u1",
                "name", "Alice",
                "encodedId", "enc-1"));
    }

    @Test
    void testExecuteEncodedUserIdMissingSkipsEncodedId() {
        StepContext context = StepContext.standalone();
        context.setPayload(new UserDto("u1", "Alice"));
        context.putVar("credit", Map.of("score", 760, "level", "A"));

        transformer.execute(context);

        assertThat(context.<Object>getPayload()).isEqualTo(Map.of(
                "id", "u1",
                "name", "Alice",
                "creditScore", 760,
                "creditLevel", "A"));
    }

    @Test
    void testExecuteNullPayloadFailsFast() {
        StepContext context = StepContext.standalone();

        assertThatThrownBy(() -> transformer.execute(context))
                .isInstanceOf(NullPointerException.class);
    }
}
