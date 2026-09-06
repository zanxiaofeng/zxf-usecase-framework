package com.example.myapp.framework.core.exception;

import lombok.Getter;

/**
 * HttpRequester 步骤收到 4xx/5xx 下游响应时抛出，携带下游状态码与响应摘要。
 * 传输层统一映射为 502（DOWNSTREAM_ERROR），不回显下游报文细节。
 */
@Getter
public class HttpStepException extends RuntimeException {

    private final String stepName;
    private final int downstreamStatus;
    private final String responseSnippet;

    public HttpStepException(String stepName, int downstreamStatus, String responseSnippet) {
        super("http step [%s] got downstream status %d".formatted(stepName, downstreamStatus));
        this.stepName = stepName;
        this.downstreamStatus = downstreamStatus;
        this.responseSnippet = responseSnippet;
    }
}
