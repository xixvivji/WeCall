package com.wecall.auth;

import jakarta.validation.Validator;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class BootstrapUsers implements ApplicationRunner {
    private final Environment env; private final UserDirectory users; private final JdbcTemplate jdbc; private final Validator validator;
    public BootstrapUsers(Environment env,UserDirectory users,JdbcTemplate jdbc,Validator validator) {this.env=env;this.users=users;this.jdbc=jdbc;this.validator=validator;}
    @Override public void run(ApplicationArguments args) {
        seed("WECALL_BOOTSTRAP",UserDirectory.Role.REVIEWER);
        seed("WECALL_BOOTSTRAP_OPERATOR",UserDirectory.Role.OPERATOR);
    }
    private void seed(String prefix,UserDirectory.Role role) {
        String password=env.getProperty(prefix+"_PASSWORD");
        if(password==null || password.isBlank()) return;
        String username=env.getProperty(prefix+"_USERNAME");
        var body=new UserDirectory.NewUser(username,username,password,role);
        if(!validator.validate(body).isEmpty()) throw new IllegalStateException("Invalid bootstrap account configuration: "+prefix);
        if(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE username=?",Long.class,username)==0) users.create(body);
        // Existing passwords/roles are never overwritten on startup.
    }
}
