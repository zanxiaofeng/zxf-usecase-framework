package com.example.datatransfer.core.spec;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 批量路径改写（设计文档 §3.3；第一版未实现执行语义，引擎构造期 fail-fast）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PathRewrite {

    @NotBlank
    private String pattern;

    /** 替换模板，$1 为捕获组反向引用 */
    private String replace;
}
