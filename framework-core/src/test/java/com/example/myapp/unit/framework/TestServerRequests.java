package com.example.myapp.unit.framework;

import lombok.experimental.UtilityClass;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.servlet.function.ServerRequest;

import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ServerRequest} 测试替身工厂（Mockito mock）：为单元测试构造带路径变量/请求头的
 * StepContext，支撑框架核心的零容器测试。
 *
 * <p>stub 面与 {@code StepContext} 的请求读取面对齐：method(GET) / pathVariables / params(空) /
 * headers；body 相关（contentType、body(Class)）仅 POST 场景读取，当前测试均为 GET 故不 stub。</p>
 */
@UtilityClass
public class TestServerRequests {

    /**
     * 构造 GET 请求 mock。
     *
     * @param pathVariables 路径变量（SpEL {@code #path} 引用面）
     * @param headers       请求头（经 HttpHeaders 包装，大小写语义与生产一致）
     * @return stub 完成的 ServerRequest
     */
    public ServerRequest getRequest(Map<String, String> pathVariables, Map<String, String> headers) {
        HttpHeaders httpHeaders = new HttpHeaders();
        headers.forEach(httpHeaders::add);
        ServerRequest.Headers requestHeaders = mock(ServerRequest.Headers.class);
        when(requestHeaders.asHttpHeaders()).thenReturn(httpHeaders);
        ServerRequest request = mock(ServerRequest.class);
        when(request.method()).thenReturn(HttpMethod.GET);
        when(request.pathVariables()).thenReturn(pathVariables);
        when(request.params()).thenReturn(new LinkedMultiValueMap<>());
        when(request.headers()).thenReturn(requestHeaders);
        return request;
    }
}
