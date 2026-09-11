package com.wecall;

import com.fasterxml.jackson.databind.*;
import com.wecall.auth.UserDirectory;
import com.wecall.recall.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.wecall.recall.TaskModels.*;

@SpringBootTest @AutoConfigureMockMvc @Import(PostgresTestConfiguration.class)
class AuthSecurityTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserDirectory users;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired RecallService recalls;
    @Autowired TaskService tasks;
    static final String PASSWORD="security-test-password-123";
    @BeforeEach void setup() {
        jdbc.execute("TRUNCATE recall_case, dataset CASCADE");
        jdbc.update("DELETE FROM app_user WHERE username IN ('auth-reviewer','auth-operator','auth-other','created-user')");
        users.create(new UserDirectory.NewUser("auth-reviewer","품질",PASSWORD,UserDirectory.Role.REVIEWER));
        users.create(new UserDirectory.NewUser("auth-operator","물류",PASSWORD,UserDirectory.Role.OPERATOR));
        users.create(new UserDirectory.NewUser("auth-other","다른 실행자",PASSWORD,UserDirectory.Role.OPERATOR));
    }
    record Client(MockHttpSession session,String header,String token) {}
    Client csrf(MockHttpSession session) throws Exception {
        var builder=get("/api/auth/csrf"); if(session!=null) builder.session(session);
        var result=mvc.perform(builder).andExpect(status().isOk()).andReturn();
        var body=json.readTree(result.getResponse().getContentAsByteArray());
        return new Client((MockHttpSession)result.getRequest().getSession(false),body.get("headerName").asText(),body.get("token").asText());
    }
    Client login(String user) throws Exception {
        var pre=csrf(null);String previousId=pre.session().getId();
        var result=mvc.perform(post("/api/auth/login").session(pre.session()).header(pre.header(),pre.token()).param("username",user).param("password",PASSWORD))
            .andExpect(status().isOk()).andReturn();
        var session=(MockHttpSession)result.getRequest().getSession(false);
        assertThat(session.getId()).isNotEqualTo(previousId);
        return csrf(session);
    }
    JsonNode postJson(Client client,String url,Object body,int expected) throws Exception {
        var result=mvc.perform(post(url).session(client.session()).header(client.header(),client.token()).contentType("application/json").content(json.writeValueAsBytes(body)))
            .andExpect(status().is(expected)).andReturn();
        return json.readTree(result.getResponse().getContentAsByteArray());
    }
    UUID caseId() {return (UUID)recalls.createCase(new RecallModels.NewCase("인증 검증 사건",RecallModels.SourceType.INTERNAL,"긴급 보류")).get("id");}
    Map<String,Object> task(UUID caseId,String assignee) {return tasks.create(caseId,new NewTask(TaskType.SALES_HOLD,TargetType.CASE,null,null,"판매보류","지시 확인",assignee,"trusted-fixture"));}
    @Test void requiresLoginAndDoesNotOfferPublicSignup() throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/recalls/"+UUID.randomUUID())).andExpect(status().isUnauthorized());
        var anonymous=csrf(null);
        postJson(anonymous,"/api/users",Map.of("username","created-user","displayName","사용자","password",PASSWORD,"role","REVIEWER"),401);
    }
    @Test void realLoginRotatesSessionAndLogoutInvalidatesIt() throws Exception {
        var client=login("auth-reviewer");
        mvc.perform(get("/api/auth/me").session(client.session())).andExpect(status().isOk()).andExpect(jsonPath("$.username").value("auth-reviewer"));
        mvc.perform(post("/api/auth/logout").session(client.session()).header(client.header(),client.token())).andExpect(status().isNoContent());
        assertThat(client.session().isInvalid()).isTrue();
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }
    @Test void rejectsWrongPasswordAndDisabledAccount() throws Exception {
        var c=csrf(null);
        mvc.perform(post("/api/auth/login").session(c.session()).header(c.header(),c.token()).param("username","auth-reviewer").param("password","wrong-password"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        jdbc.update("UPDATE app_user SET enabled=false WHERE username='auth-operator'");
        mvc.perform(post("/api/auth/login").session(c.session()).header(c.header(),c.token()).param("username","auth-operator").param("password",PASSWORD)).andExpect(status().isUnauthorized());
    }
    @Test void csrfProtectsLoginAndWrites() throws Exception {
        mvc.perform(post("/api/auth/login").param("username","auth-reviewer").param("password",PASSWORD)).andExpect(status().isForbidden());
        var c=login("auth-reviewer");
        mvc.perform(post("/api/v1/recalls").session(c.session()).contentType("application/json").content("{}" )).andExpect(status().isForbidden());
    }
    @Test void operatorsCannotApproveOrAdministerAccounts() throws Exception {
        var c=login("auth-operator"); UUID id=caseId();
        mvc.perform(get("/api/v1/recalls/"+id).session(c.session())).andExpect(status().isOk());
        for(String path:List.of("/api/users","/api/v1/datasets","/api/v1/recalls/"+id+"/closure","/api/v1/recalls/"+id+"/conditions/"+UUID.randomUUID()+"/approval","/api/v1/recalls/"+id+"/evidence/"+UUID.randomUUID()+"/approval"))
            postJson(c,path,Map.of(),403);
    }
    @Test void operatorCanOnlyStartAndProvideProofForAssignedTask() throws Exception {
        var c=login("auth-operator");UUID id=caseId();var own=task(id,"auth-operator");var other=task(id,"auth-other");
        String url="/api/v1/recalls/"+id+"/tasks/"+own.get("id");
        var started=postJson(c,url+"/transitions",Map.of("expectedVersion",0,"action","START","actor","auth-reviewer","note","시작"),200);
        assertThat(started.get("events").get(1).get("actor").asText()).isEqualTo("auth-operator");
        var proof=postJson(c,url+"/proofs",Map.of("expectedVersion",1,"evidenceText","격리 확인","actor","someone-else"),201);
        assertThat(proof.get("proofs").get(0).get("submittedBy").asText()).isEqualTo("auth-operator");
        postJson(c,url+"/transitions",Map.of("expectedVersion",2,"action","COMPLETE","note","완료"),403);
        postJson(c,url+"/assignment",Map.of("expectedVersion",2,"assignee","auth-operator","note","재배정"),403);
        postJson(c,url+"/proofs/"+proof.get("proofs").get(0).get("id").asText()+"/review",Map.of(),403);
        String otherUrl="/api/v1/recalls/"+id+"/tasks/"+other.get("id");
        postJson(c,otherUrl+"/transitions",Map.of("expectedVersion",0,"action","START","note","시작"),403);
        postJson(c,otherUrl+"/proofs",Map.of("expectedVersion",0,"evidenceText","잘못된 증빙"),403);
    }
    @Test void reviewerIdentityCannotBeForgedAndAssigneeMustExist() throws Exception {
        var c=login("auth-reviewer");UUID id=caseId();
        var payload=new LinkedHashMap<String,Object>(Map.of("taskType","SALES_HOLD","targetType","CASE","title","작업","instructions","지시","actor","forged-name","assignee","auth-operator"));
        var t=postJson(c,"/api/v1/recalls/"+id+"/tasks",payload,201);
        assertThat(t.get("events").get(0).get("actor").asText()).isEqualTo("auth-reviewer");
        payload.put("assignee","not-a-user");postJson(c,"/api/v1/recalls/"+id+"/tasks",payload,400);
    }
    @Test void storesOnlyPasswordHashesAndNeverReturnsThem() throws Exception {
        var c=login("auth-reviewer");
        var created=postJson(c,"/api/users",Map.of("username","created-user","displayName","새 실행자","password",PASSWORD,"role","OPERATOR"),201);
        String hash=jdbc.queryForObject("SELECT password_hash FROM app_user WHERE username='created-user'",String.class);
        assertThat(hash).isNotEqualTo(PASSWORD);assertThat(encoder.matches(PASSWORD,hash)).isTrue();
        assertThat(created.toString()).doesNotContain(PASSWORD,"password");
        String listed=mvc.perform(get("/api/users").session(c.session())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(listed).doesNotContain(PASSWORD,hash,"password");
        postJson(c,"/api/users",Map.of("username","new-user","displayName","이름","password","short","role","OPERATOR"),400);
    }
}
