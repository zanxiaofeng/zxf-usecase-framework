package com.example.myapp.framework.config;

import java.util.List;

/**
 * 单个用例的配置定义（对应 YAML 中 usecase.definitions 的一个元素）。
 *
 * @param id          用例唯一标识
 * @param description 描述（仅用于启动日志与治理）
 * @param shared      是否共享用例：true 时不绑定 endpoint、不参与路由，只能被 type=usecase 的 step 嵌入引用
 * @param endpoint    对外端点（shared 用例可缺省）
 * @param steps       有序步骤列表
 */
public record UseCaseDefinition(String id, String description, Boolean shared, Endpoint endpoint,
                                List<StepDefinition> steps) {

    public boolean isShared() {
        return shared != null && shared;
    }

    /**
     * @param method HTTP 方法（GET/POST/PUT/DELETE/PATCH）
     * @param path   URI 模板，支持 {var}
     * @param status 成功状态码，缺省 200
     */
    public record Endpoint(String method, String path, Integer status) {
        public int statusOrDefault() {
            return status == null ? 200 : status;
        }
    }
}
