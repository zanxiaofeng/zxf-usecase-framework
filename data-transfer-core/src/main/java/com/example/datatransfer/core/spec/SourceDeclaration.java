package com.example.datatransfer.core.spec;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 多源合并声明（设计文档 §6.4；第一版未实现执行语义，引擎构造期 fail-fast）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SourceDeclaration {

    @NotBlank
    private String alias;

    /** 该源在输入报文中的根路径 */
    @NotBlank
    private String path;
}
