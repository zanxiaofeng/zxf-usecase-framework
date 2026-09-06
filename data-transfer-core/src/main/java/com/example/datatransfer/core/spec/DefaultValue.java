package com.example.datatransfer.core.spec;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 默认值注入（设计文档 §2.4）：仅当目标键不存在时写入。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DefaultValue {

    @NotBlank
    private String to;

    private Object value;
}
