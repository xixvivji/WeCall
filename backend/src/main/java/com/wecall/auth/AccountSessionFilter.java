package com.wecall.auth;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.filter.OncePerRequestFilter;

/** Checks the version captured during authentication, never a version refreshed at login completion. */
public final class AccountSessionFilter extends OncePerRequestFilter {
    private final UserDirectory users;
    public AccountSessionFilter(UserDirectory users){this.users=users;}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth!=null && auth.getPrincipal() instanceof AccountPrincipal principal && !users.sessionValid(principal)) {
            new SecurityContextLogoutHandler().logout(request,response,auth);
            var cookie=new Cookie("JSESSIONID","");cookie.setPath(request.getContextPath().isEmpty()?"/":request.getContextPath());cookie.setMaxAge(0);cookie.setHttpOnly(true);cookie.setSecure(request.isSecure());response.addCookie(cookie);
            response.setStatus(401);response.setContentType("application/json");response.getWriter().write("{\"code\":\"SESSION_REVOKED\"}");return;
        }
        chain.doFilter(request,response);
    }
}
