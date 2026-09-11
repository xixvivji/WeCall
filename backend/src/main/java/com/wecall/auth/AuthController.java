package com.wecall.auth;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
public class AuthController {
    private final UserDirectory users;
    public AuthController(UserDirectory users) {this.users=users;}
    @GetMapping("/api/auth/csrf") public Map<String,String> csrf(CsrfToken token) {return Map.of("token",token.getToken(),"headerName",token.getHeaderName());}
    @GetMapping("/api/auth/me") public Map<String,Object> me(Authentication auth) {
        return Map.of("username",auth.getName(),"roles",auth.getAuthorities().stream().map(a->a.getAuthority().replace("ROLE_","")).toList());
    }
    @PostMapping("/api/users") @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> create(@Valid @RequestBody UserDirectory.NewUser body) {return users.create(body);}
    @GetMapping("/api/users") public List<Map<String,Object>> list() {return users.list();}
}
