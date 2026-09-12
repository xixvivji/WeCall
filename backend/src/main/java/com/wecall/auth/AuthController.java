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
    @PostMapping("/api/auth/password") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void password(Authentication auth,@Valid @RequestBody UserDirectory.PasswordChange body,jakarta.servlet.http.HttpServletRequest request,jakarta.servlet.http.HttpServletResponse response) {
        users.changePassword(auth.getName(),body);
        new org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler().logout(request,response,auth);
    }
    @PostMapping("/api/users/{username}/status")
    public Map<String,Object> status(@PathVariable String username,Authentication auth,@Valid @RequestBody UserDirectory.AccountStatus body) {return users.changeStatus(username,auth.getName(),body);}
    @GetMapping("/api/users/{username}/events")
    public List<Map<String,Object>> events(@PathVariable String username) {return users.history(username);}
    @GetMapping("/api/users") public List<Map<String,Object>> list() {return users.list();}
}
