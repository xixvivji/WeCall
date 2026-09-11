package com.wecall.auth;

import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.wecall.recall.RecallService;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class UserDirectory implements UserDetailsService {
    public enum Role { REVIEWER, OPERATOR }
    public record NewUser(@NotNull @Pattern(regexp="[a-z][a-z0-9._-]{2,63}") String username,
        @NotBlank @Size(max=200) String displayName,
        @NotNull @Size(min=12,max=72) String password, @NotNull Role role) {}
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    public UserDirectory(JdbcTemplate jdbc,PasswordEncoder encoder) {this.jdbc=jdbc;this.encoder=encoder;}
    @Override public UserDetails loadUserByUsername(String username) {
        var rows=jdbc.queryForList("SELECT username,password_hash,role,enabled FROM app_user WHERE username=?",username);
        if(rows.isEmpty()) throw new UsernameNotFoundException("Invalid credentials");
        var r=rows.getFirst();
        return User.withUsername((String)r.get("username")).password((String)r.get("password_hash")).roles((String)r.get("role")).disabled(!(Boolean)r.get("enabled")).build();
    }
    @Transactional public Map<String,Object> create(NewUser body) {
        if(body.password().getBytes(StandardCharsets.UTF_8).length>72)
            throw new RecallService.Failure(HttpStatus.BAD_REQUEST,"비밀번호는 UTF-8 기준 72바이트 이하여야 합니다");
        int inserted=jdbc.update("INSERT INTO app_user(username,display_name,password_hash,role) VALUES (?,?,?,?) ON CONFLICT DO NOTHING",
            body.username(),body.displayName(),encoder.encode(body.password()),body.role().name());
        if(inserted!=1) throw new RecallService.Failure(HttpStatus.CONFLICT,"이미 존재하는 사용자 이름입니다");
        return Map.of("username",body.username(),"displayName",body.displayName(),"role",body.role());
    }
    public List<Map<String,Object>> list() {
        return jdbc.query("SELECT username,display_name,role,enabled FROM app_user ORDER BY username",(rs,n)->Map.of("username",rs.getString(1),"displayName",rs.getString(2),"role",rs.getString(3),"enabled",rs.getBoolean(4)));
    }
    public void requireActive(String username) {
        if(username!=null && jdbc.queryForObject("SELECT count(*) FROM app_user WHERE username=? AND enabled",Long.class,username)!=1)
            throw new RecallService.Failure(HttpStatus.BAD_REQUEST,"담당자는 활성 계정의 username으로 지정하세요");
    }
}
