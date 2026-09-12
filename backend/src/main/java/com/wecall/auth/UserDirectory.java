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
        var rows=jdbc.queryForList("SELECT username,password_hash,role,enabled,security_version FROM app_user WHERE username=?",username);
        if(rows.isEmpty()) throw new UsernameNotFoundException("Invalid credentials");
        var r=rows.getFirst();
        return new AccountPrincipal((String)r.get("username"),(String)r.get("password_hash"),(String)r.get("role"),(Boolean)r.get("enabled"),((Number)r.get("security_version")).longValue());
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
        return jdbc.query("SELECT username,display_name,role,enabled,security_version FROM app_user ORDER BY username",(rs,n)->Map.of("username",rs.getString(1),"displayName",rs.getString(2),"role",rs.getString(3),"enabled",rs.getBoolean(4),"version",rs.getLong(5)));
    }
    public record PasswordChange(@NotBlank @Size(max=200) String currentPassword,
        @NotNull @Size(min=12,max=72) String newPassword) {}
    public record AccountStatus(@NotNull Boolean enabled,@NotNull @Min(0) Long expectedVersion,
        @NotBlank @Size(max=2000) String note) {}
    public boolean sessionValid(AccountPrincipal principal) {
        return jdbc.queryForObject("SELECT count(*) FROM app_user WHERE username=? AND enabled AND security_version=?",Long.class,principal.getUsername(),principal.securityVersion())==1;
    }
    private Map<String,Object> locked(String username) {
        var rows=jdbc.queryForList("SELECT * FROM app_user WHERE username=? FOR UPDATE",username);
        if(rows.isEmpty())throw new RecallService.Failure(HttpStatus.NOT_FOUND,"계정이 없습니다");
        return rows.getFirst();
    }
    private void event(String username,String actor,String type,String note) {
        jdbc.update("INSERT INTO account_security_event(id,username,actor,event_type,note) VALUES (?,?,?,?,?)",UUID.randomUUID(),username,actor,type,note);
    }
    @Transactional public void changePassword(String username,PasswordChange body) {
        var row=locked(username);
        if(!(Boolean)row.get("enabled"))throw new RecallService.Failure(HttpStatus.FORBIDDEN,"비활성 계정입니다");
        if(body.currentPassword().getBytes(StandardCharsets.UTF_8).length>72 || !encoder.matches(body.currentPassword(),(String)row.get("password_hash")))
            throw new RecallService.Failure(HttpStatus.BAD_REQUEST,"현재 비밀번호가 일치하지 않습니다");
        if(body.newPassword().getBytes(StandardCharsets.UTF_8).length>72)
            throw new RecallService.Failure(HttpStatus.BAD_REQUEST,"비밀번호는 UTF-8 기준 72바이트 이하여야 합니다");
        if(encoder.matches(body.newPassword(),(String)row.get("password_hash")))
            throw new RecallService.Failure(HttpStatus.BAD_REQUEST,"새 비밀번호는 현재 비밀번호와 달라야 합니다");
        jdbc.update("UPDATE app_user SET password_hash=?,security_version=security_version+1 WHERE username=?",encoder.encode(body.newPassword()),username);
        event(username,username,"PASSWORD_CHANGED","본인 비밀번호 변경");
    }
    @Transactional public Map<String,Object> changeStatus(String username,String actor,AccountStatus body) {
        // Serialize administrator changes so concurrent requests cannot remove all active reviewers.
        jdbc.execute("SELECT pg_advisory_xact_lock(873491205)");
        if(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE username=? AND enabled AND role='REVIEWER'",Long.class,actor)!=1)
            throw new RecallService.Failure(HttpStatus.FORBIDDEN,"활성 검토자 권한이 필요합니다");
        var row=locked(username);
        if(((Number)row.get("security_version")).longValue()!=body.expectedVersion())
            throw new RecallService.Failure(HttpStatus.CONFLICT,"계정 상태가 변경되었습니다. 다시 조회하세요");
        if(row.get("enabled").equals(body.enabled()))throw new RecallService.Failure(HttpStatus.CONFLICT,"이미 요청한 계정 상태입니다");
        if(!body.enabled() && username.equals(actor))throw new RecallService.Failure(HttpStatus.CONFLICT,"자신의 계정은 비활성화할 수 없습니다");
        if(!body.enabled() && row.get("role").equals("REVIEWER") && jdbc.queryForObject("SELECT count(*) FROM app_user WHERE enabled AND role='REVIEWER'",Long.class)<=1)
            throw new RecallService.Failure(HttpStatus.CONFLICT,"마지막 활성 검토자는 비활성화할 수 없습니다");
        jdbc.update("UPDATE app_user SET enabled=?,security_version=security_version+1 WHERE username=?",body.enabled(),username);
        event(username,actor,body.enabled()?"ENABLED":"DISABLED",body.note());
        return Map.of("username",username,"enabled",body.enabled(),"version",body.expectedVersion()+1);
    }
    public List<Map<String,Object>> history(String username) {
        if(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE username=?",Long.class,username)==0)
            throw new RecallService.Failure(HttpStatus.NOT_FOUND,"계정이 없습니다");
        return jdbc.queryForList("SELECT id,actor,event_type AS \"type\",note,created_at AS \"createdAt\" FROM account_security_event WHERE username=? ORDER BY created_at DESC,id DESC LIMIT 100",username);
    }
    public void requireActive(String username) {
        if(username!=null && jdbc.queryForObject("SELECT count(*) FROM app_user WHERE username=? AND enabled",Long.class,username)!=1)
            throw new RecallService.Failure(HttpStatus.BAD_REQUEST,"담당자는 활성 계정의 username으로 지정하세요");
    }
}
