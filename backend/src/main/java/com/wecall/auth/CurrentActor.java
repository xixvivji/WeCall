package com.wecall.auth;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;

public final class CurrentActor {
    private CurrentActor() {}
    public static String username() {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth==null || !auth.isAuthenticated() || auth.getName().equals("anonymousUser")) throw new AccessDeniedException("Login required");
        return auth.getName();
    }
    public static boolean reviewer() {
        return SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_REVIEWER"));
    }
}
