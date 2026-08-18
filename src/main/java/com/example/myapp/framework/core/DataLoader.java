package com.example.myapp.framework.core;

/**
 * 数据加载步骤：从出端口（Repository / Gateway）读取数据，写入 payload 或命名变量。
 *
 * <p>内置实现 {@code SpelDataLoaderStep} 支持配置：
 * <pre>{@code
 * - name: loadUser
 *   type: dataLoader
 *   config:
 *     expression: "@userRepository.getById(T(com.example.UserId).of(#path.id))"
 *     as: user        # 可选：写入 #vars.user 而不占用 payload
 * }</pre>
 */
public interface DataLoader extends Step {
}
