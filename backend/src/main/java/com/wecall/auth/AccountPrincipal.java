package com.wecall.auth;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import java.util.List;

public final class AccountPrincipal extends User {
    private final long securityVersion;
    public AccountPrincipal(String username,String password,String role,boolean enabled,long securityVersion) {
        super(username,password,enabled,true,true,true,List.of(new SimpleGrantedAuthority("ROLE_"+role)));
        this.securityVersion=securityVersion;
    }
    public long securityVersion(){return securityVersion;}
}
