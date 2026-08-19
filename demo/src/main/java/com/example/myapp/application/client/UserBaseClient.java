package com.example.myapp.application.client;

import com.example.myapp.application.dto.UserDto;
import com.example.myapp.framework.core.AbstractUseCaseClient;
import com.example.myapp.framework.core.UseCaseInvoker;
import org.springframework.stereotype.Component;

/**
 * shared 用例 {@code userBaseEnrichment} 的类型化 Java 客户端。
 *
 * <p>继承 {@link AbstractUseCaseClient} 即获得 invoke / invokeIsolated / invokeStandalone
 * 三种调用语义；业务代码注入本 Bean 即可像普通方法一样调用子用例。</p>
 */
@Component
public class UserBaseClient extends AbstractUseCaseClient<String, UserDto> {

    public UserBaseClient(UseCaseInvoker invoker) {
        super(invoker, "userBaseEnrichment", UserDto.class);
    }
}
