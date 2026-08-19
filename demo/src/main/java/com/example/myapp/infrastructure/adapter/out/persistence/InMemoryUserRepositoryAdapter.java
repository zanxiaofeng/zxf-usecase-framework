package com.example.myapp.infrastructure.adapter.out.persistence;

import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.example.myapp.application.port.out.UserRepository;
import com.example.myapp.domain.model.User;
import com.example.myapp.domain.model.UserId;

/**
 * 演示用内存实现。生产环境按 solution 文档替换为 JPA Adapter
 * （UserJpaEntity / UserJpaRepository / UserPersistenceMapper），出端口签名不变、YAML 不变。
 */
@Component("userRepository")
public class InMemoryUserRepositoryAdapter implements UserRepository {

    private final Map<String, User> users = Map.of(
            "u1", new User(new UserId("u1"), "Alice"),
            "u2", new User(new UserId("u2"), "Bob")
    );

    @Override
    public Optional<User> findById(UserId id) {
        return Optional.ofNullable(users.get(id.value()));
    }
}
