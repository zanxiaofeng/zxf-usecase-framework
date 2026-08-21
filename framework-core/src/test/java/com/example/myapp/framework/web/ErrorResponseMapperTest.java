package com.example.myapp.framework.web;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import com.example.myapp.framework.core.exception.StepExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 错误映射单测（与 main 同包以触达包私有的 {@code toErrorResponse}）：
 * 裸 {@link IllegalArgumentException}（VO 紧凑构造器等参数/格式校验）→ 400 VALIDATION_ERROR
 * （null-check-governance 误区#10：格式错误走校验通道）；未知异常 → 500 固定文案兜底不回显内部消息。
 */
class ErrorResponseMapperTest {

    private final ErrorResponseMapper mapper = new ErrorResponseMapper(Map.of());

    private ServerRequest requestWithoutContext() {
        ServerRequest request = mock(ServerRequest.class);
        when(request.attributes()).thenReturn(Map.of());
        return request;
    }

    private MockHttpServletResponse write(ServerResponse response) throws Exception {
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();
        response.writeTo(new MockHttpServletRequest(), servletResponse,
                () -> List.of(new JacksonJsonHttpMessageConverter()));
        return servletResponse;
    }

    @Test
    void illegalArgumentMapsTo400ValidationError() throws Exception {
        // VO 紧凑构造器抛出的裸 IAE 是参数格式错误，走校验通道而非兜底 500
        MockHttpServletResponse written = write(mapper.toErrorResponse(
                new IllegalArgumentException("UserId must not be blank"), requestWithoutContext()));

        assertThat(written.getStatus()).isEqualTo(400);
        assertThat(written.getContentAsString())
                .contains("\"code\":\"VALIDATION_ERROR\"")
                .contains("UserId must not be blank");
    }

    @Test
    void wrappedIllegalArgumentIsUnwrappedBeforeMapping() throws Exception {
        // 管道内抛出的 IAE 经 StepExecutionException 包装链还原后仍映射 400
        MockHttpServletResponse written = write(mapper.toErrorResponse(
                new StepExecutionException("uc", "loadUser", new IllegalArgumentException("bad id")),
                requestWithoutContext()));

        assertThat(written.getStatus()).isEqualTo(400);
        assertThat(written.getContentAsString()).contains("bad id");
    }

    @Test
    void unknownErrorFallsBackTo500WithFixedMessage() throws Exception {
        MockHttpServletResponse written = write(mapper.toErrorResponse(
                new IllegalStateException("internal detail must not leak"), requestWithoutContext()));

        assertThat(written.getStatus()).isEqualTo(500);
        assertThat(written.getContentAsString())
                .contains("\"code\":\"INTERNAL_ERROR\"")
                .doesNotContain("internal detail");
    }
}
