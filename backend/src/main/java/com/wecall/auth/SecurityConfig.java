package com.wecall.auth;

import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() {return new BCryptPasswordEncoder();}
    @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth->auth
            .requestMatchers("/api/auth/csrf","/api/auth/login","/actuator/health","/error").permitAll()
            .requestMatchers("/api/auth/me").authenticated()
            .requestMatchers(HttpMethod.GET,"/api/v1/**").hasAnyRole("REVIEWER","OPERATOR")
            .requestMatchers(HttpMethod.POST,"/api/v1/recalls/*/tasks/*/transitions","/api/v1/recalls/*/tasks/*/proofs","/api/v1/recalls/*/evidence").hasAnyRole("REVIEWER","OPERATOR")
            .requestMatchers("/api/**").hasRole("REVIEWER")
            .anyRequest().denyAll())
            .requestCache(cache->cache.disable())
            .exceptionHandling(errors->errors
                .authenticationEntryPoint((req,res,e)->{res.setStatus(401);res.setContentType("application/json");res.getWriter().write("{\"code\":\"UNAUTHENTICATED\"}");})
                .accessDeniedHandler((req,res,e)->{res.setStatus(403);res.setContentType("application/json");res.getWriter().write("{\"code\":\"FORBIDDEN\"}");}))
            .formLogin(login->login.loginProcessingUrl("/api/auth/login")
                .successHandler((req,res,a)->{res.setContentType("application/json");res.getWriter().write("{\"status\":\"authenticated\"}");})
                .failureHandler((req,res,e)->{res.setStatus(401);res.setContentType("application/json");res.getWriter().write("{\"code\":\"INVALID_CREDENTIALS\"}");}))
            .logout(logout->logout.logoutUrl("/api/auth/logout").deleteCookies("JSESSIONID")
                .logoutSuccessHandler((req,res,a)->res.setStatus(204)));
        // Keep Spring Security's session fixation protection and CSRF protection enabled.
        return http.build();
    }
}
