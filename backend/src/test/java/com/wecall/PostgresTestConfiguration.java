package com.wecall;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestConfiguration {
    @Bean
    org.springframework.boot.ApplicationRunner testUsers(com.wecall.auth.UserDirectory users) {
        return args -> {
            if(users.list().stream().noneMatch(u->u.get("username").equals("test-operator")))
                users.create(new com.wecall.auth.UserDirectory.NewUser("test-operator","테스트 실행자","test-only-password-123",com.wecall.auth.UserDirectory.Role.OPERATOR));
        };
    }
    @Bean @ServiceConnection
    PostgreSQLContainer<?> postgres() { return new PostgreSQLContainer<>("postgres:17.6"); }
}
