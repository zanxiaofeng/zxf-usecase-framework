package com.example.myapp.application.step;

import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.example.myapp.application.dto.UserDto;
import com.example.myapp.framework.core.DataTransformer;
import com.example.myapp.framework.core.StepContext;
import com.example.myapp.framework.core.dataflow.Dataflow;

/**
 * 自定义业务 step 示例：合并用户主数据（payload）与信用分旁路数据（#vars.credit），生成用户画像。
 *
 * <p>复杂转换逻辑用 Java 实现（可单测），YAML 中经 {@code ref: userProfileTransformer} 引用，
 * 兼顾「配置驱动编排」与「复杂逻辑可工程化」。</p>
 *
 * <p>覆写 {@link #dataflow()} 声明键级读写契约：ref step 无 config 可供装配器静态推导，
 * 显式声明后该 step 的读写进入启动期报告与运行期对照检查（未声明写入 → WARN）。</p>
 */
@Component("userProfileTransformer")
public class UserProfileTransformer implements DataTransformer {

    @Override
    public String name() {
        return "userProfileTransformer";
    }

    /** 键级数据流契约：读 payload 主数据 + 两个旁路 vars，产出覆盖 payload */
    @Override
    public Dataflow dataflow() {
        return Dataflow.declaring()
                .reads("payload", "vars.credit", "vars.encodedUserId")
                .writes("payload")
                .build();
    }

    @Override
    public void execute(StepContext context) {
        // 上游 dataLoader 已装载 UserDto（payload 可空是框架契约，此处 null 即管道配置缺陷）
        UserDto user = Objects.requireNonNull(context.getPayload(UserDto.class));
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
