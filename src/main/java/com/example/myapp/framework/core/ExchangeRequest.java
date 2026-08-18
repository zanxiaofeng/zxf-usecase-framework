package com.example.myapp.framework.core;

import java.util.Map;

/**
 * 入站请求的传输层抽象 —— 核心层不依赖 spring-web 的关键。
 *
 * <p>Web 场景由 {@code WebExchangeRequest}（framework.web）适配 ServerRequest 实现；
 * 测试或其他驱动方（CLI、消息消费者）可使用 {@link SimpleExchangeRequest}。
 */
public interface ExchangeRequest {

    /** HTTP 方法名，大写（GET/POST/...）；非 Web 场景由驱动方自定义。 */
    String method();

    /** 请求路径。 */
    String path();

    /** 路径变量（URI 模板 {id} 解析结果）。 */
    Map<String, String> pathVariables();

    default String pathVariable(String name) {
        return pathVariables().get(name);
    }

    /** 查询参数（单值视图，多值取第一个）。 */
    Map<String, String> queryParams();

    default String queryParam(String name) {
        return queryParams().get(name);
    }

    /** 请求头（单值视图）。 */
    Map<String, String> headers();

    /** 请求头查找，大小写不敏感。 */
    default String header(String name) {
        Map<String, String> headers = headers();
        String value = headers.get(name);
        if (value != null) {
            return value;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** 请求体：JSON 反序列化为 Map/List，纯文本为 String，无体为 null。 */
    Object body();
}
