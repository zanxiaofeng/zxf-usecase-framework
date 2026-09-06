package com.example.myapp.application.port.out;

import java.util.Optional;

import com.example.myapp.domain.exception.UserNotFoundException;
import com.example.myapp.domain.model.User;
import com.example.myapp.domain.model.UserId;

/**
 * 用户仓库出端口（唯一定义处，领域层不重复定义）。
 */
public interface UserRepository {

    Optional<User> findById(UserId id);

    /** 供 SpEL 表达式便捷调用：@userRepository.getById(...) */
    default User getById(UserId id) {
        return findById(id).orElseThrow(() -> new UserNotFoundException(id.value()));
    }
}
