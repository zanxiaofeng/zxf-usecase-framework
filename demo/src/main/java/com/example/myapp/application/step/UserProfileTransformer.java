package com.example.myapp.application.step;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.example.myapp.application.dto.UserDto;
import com.example.myapp.framework.core.DataTransformer;
import com.example.myapp.framework.core.StepContext;

/**
 * 自定义业务 step 示例：合并用户主数据（payload）与信用分旁路数据（#vars.credit），生成用户画像。
 *
 * <p>复杂转换逻辑用 Java 实现（可单测），YAML 中经 {@code ref: userProfileTransformer} 引用，
 * 兼顾「配置驱动编排」与「复杂逻辑可工程化」。
 */
@Component("userProfileTransformer")
public class UserProfileTransformer implements DataTransformer {

    @Override
    public String name() {
        return "userProfileTransformer";
    }

    @Override
    public void execute(StepContext context) {
        UserDto user = context.getPayload(UserDto.class);
        Object credit = context.getVar("credit");

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("id", user.id());
        profile.put("name", user.name());
        if (credit instanceof Map<?, ?> creditMap) {
            profile.put("creditScore", creditMap.get("score"));
            profile.put("creditLevel", creditMap.get("level"));
        }
        // encoder 步骤的旁路输出（#vars.encodedUserId）
        Object encodedUserId = context.getVar("encodedUserId");
        if (encodedUserId != null) {
            profile.put("encodedId", encodedUserId);
        }
        context.setPayload(profile);
    }
}
