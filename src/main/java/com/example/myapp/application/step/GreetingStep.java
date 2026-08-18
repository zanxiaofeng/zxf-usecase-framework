package com.example.myapp.application.step;

import com.example.myapp.application.client.UserBaseClient;
import com.example.myapp.application.dto.UserDto;
import com.example.myapp.framework.core.DataTransformer;
import com.example.myapp.framework.core.StepContext;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 演示「Java 代码内调用 shared 子用例」：自定义 Step Bean 注入类型化客户端，
 * 直接以方法调用方式触发子用例（共享当前上下文：vars/biz 互通、父 payload 自动恢复）。
 */
@Component("greetingStep")
public class GreetingStep implements DataTransformer {

    private final UserBaseClient userBaseClient;

    public GreetingStep(UserBaseClient userBaseClient) {
        this.userBaseClient = userBaseClient;
    }

    @Override
    public void execute(StepContext context) {
        String businessId = String.valueOf(context.getBiz("businessId"));
        UserDto user = userBaseClient.invoke(businessId);   // 管道内共享调用，异常沿子用例边界穿透

        Map<String, Object> greeting = new LinkedHashMap<>();
        greeting.put("userId", user.id());
        greeting.put("greeting", "Hello, " + user.name());
        greeting.put("invokedFrom", "java");
        context.setPayload(greeting);
    }
}
