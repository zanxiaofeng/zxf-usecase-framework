package com.example.myapp.e2e;

import java.util.List;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import com.example.myapp.framework.core.dataflow.DataflowRecorder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 对照检查端到端：{@code usecase.dataflow.record=true} 下经真实 Web 入口执行，
 * root 收尾输出声明-实测对照 WARN（demo 现存真实的「声明 biz 键无人读取」案例）与 INFO 汇总。
 */
@SpringBootTest(properties = "usecase.dataflow.record=true")
@AutoConfigureMockMvc
class UseCaseDataflowConformanceE2eTest {

    @Autowired
    MockMvc mockMvc;

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger recorderLogger = (Logger) LoggerFactory.getLogger(DataflowRecorder.class);

    @AfterEach
    void detach() {
        recorderLogger.detachAppender(appender);
    }

    @Test
    void conformanceWarnsOnUnreadDeclaredBizKeyAndLogsSummary() throws Exception {
        appender.start();
        recorderLogger.addAppender(appender);

        mockMvc.perform(post("/api/v1/user-snapshots")
                        .contentType("application/json")
                        .content("{\"userId\":\"u1\",\"name\":\"alice-snap\",\"tags\":[\"vip\"]}"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(201));

        List<ILoggingEvent> events = appender.list;
        // 规则 2：start 声明写 biz.businessId，全链无人读取（真实配置缺陷，WARN 可见；
        // logContext 的 biz.* 批量 dump 属观测，不豁免）
        assertThat(events).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
            assertThat(event.getFormattedMessage()).contains("biz.businessId").contains("无人读取");
        });
        // INFO 汇总
        assertThat(events).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.INFO);
            assertThat(event.getFormattedMessage()).contains("dataflow conformance");
        });
    }
}
