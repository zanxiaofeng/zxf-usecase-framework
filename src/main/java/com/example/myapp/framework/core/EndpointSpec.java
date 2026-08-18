package com.example.myapp.framework.core;

/**
 * 用例对外暴露的端点描述。保持 String method 以避免核心层依赖 spring-web 的 HttpMethod。
 *
 * @param method HTTP 方法名（GET/POST/PUT/DELETE/PATCH）
 * @param path   URI 模板，支持 {var} 路径变量
 * @param status 成功时返回的 HTTP 状态码（默认 200）
 */
public record EndpointSpec(String method, String path, int status) {
}
