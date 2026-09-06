package com.example.myapp.framework.steps.config;

import lombok.Data;
import org.jspecify.annotations.Nullable;
import org.slf4j.event.Level;

/**
 * logging 步骤的 config schema。level 直接使用 SLF4J 枚举——合法性由类型系统保证
 * （非法值在绑定期 fail-fast），无需注解。
 *
 * <p>@Data + 字段初始值模式：默认值直接写在字段上，Jackson 绑定时仅覆盖 YAML 中出现的属性
 * （带默认值的 config 类统一用本模式；无默认值的保持 record）。</p>
 */
@Data
public class LoggingConfig {

    /** 日志级别（绑定大小写不敏感），缺省 INFO */
    private Level level = Level.INFO;

    /** 消息模板（{@code #{...}} 占位）；缺省输出默认格式 */
    private @Nullable String message;

    /** true 时以 DEBUG 输出上下文全部内容（payload / vars / biz），替代主消息输出；缺省 false */
    private boolean logContext;
}
