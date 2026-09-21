package com.example.myapp.unit.application;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.example.myapp.application.client.UserBaseClient;
import com.example.myapp.application.dto.UserDto;
import com.example.myapp.application.step.GreetingStep;
import com.example.usecase.framework.core.context.StepContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link GreetingStep} 单元测试：类型化客户端调用 + 问候 payload 组装（Mockito mock 客户端，零容器）。
 */
class GreetingStepTest {

    @Test
    void testExecuteBuildsGreetingPayload() {
        UserBaseClient client = mock(UserBaseClient.class);
        when(client.invoke("u1")).thenReturn(new UserDto("u1", "Alice"));
        GreetingStep step = new GreetingStep(client);
        StepContext context = StepContext.standalone();
        context.putBiz("businessId", "u1");

        step.execute(context);

        assertThat(context.<Object>getPayload()).isEqualTo(Map.of(
                "userId", "u1",
                "greeting", "Hello, Alice",
                "invokedFrom", "java"));
        verify(client).invoke("u1");
    }

    @Test
    void testExecuteNullClientResultFailsFast() {
        UserBaseClient client = mock(UserBaseClient.class);
        when(client.invoke(any())).thenReturn(null);
        GreetingStep step = new GreetingStep(client);
        StepContext context = StepContext.standalone();
        context.putBiz("businessId", "u1");

        assertThatThrownBy(() -> step.execute(context))
                .isInstanceOf(NullPointerException.class);
    }
}
