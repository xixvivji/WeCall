package com.wecall;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @Import(PostgresTestConfiguration.class)
@WithMockUser(roles="OPERATOR")
class WorkspaceQueryTests {
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired ObjectMapper json;
    UUID first=UUID.fromString("00000000-0000-0000-0000-000000000001"), second=UUID.fromString("00000000-0000-0000-0000-000000000002");
    @BeforeEach void setup() {
        jdbc.execute("TRUNCATE recall_case,dataset CASCADE");
        jdbc.update("INSERT INTO recall_case(id,title,source_type,source_text,created_at) VALUES (?,?,'SUPPLIER','원문','2026-09-10T00:00:00Z')",first,"크래커 100% 회수");
        jdbc.update("INSERT INTO recall_case(id,title,source_type,source_text,created_at) VALUES (?,?,'OFFICIAL','원문','2026-09-10T00:00:00Z')",second,"크래커 회수");
    }
    @Test void stablePaginationAndLiteralSearch() throws Exception {
        mvc.perform(get("/api/v1/recalls").param("size","1")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2)).andExpect(jsonPath("$.items[0].id").value(second.toString()));
        mvc.perform(get("/api/v1/recalls").param("page","1").param("size","1")).andExpect(jsonPath("$.items[0].id").value(first.toString()));
        mvc.perform(get("/api/v1/recalls").param("q","%")).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/v1/recalls").param("q","' OR 1=1 --")).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/v1/recalls").param("sourceType","OFFICIAL").param("status","OPEN")).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/v1/recalls").param("page","100")).andExpect(jsonPath("$.items").isEmpty());
    }
    @Test void rejectsInvalidQueryParameters() throws Exception {
        for(var params:List.of(Map.of("page","-1"),Map.of("size","101"),Map.of("status","DONE"),Map.of("sourceType","OTHER"),Map.of("q","a".repeat(201)))) {
            var request=get("/api/v1/recalls");params.forEach(request::param);mvc.perform(request).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/v1/recalls").param("page","bad")).andExpect(status().isBadRequest());
    }
    @Test void readsDatasetProductsAndScopesAssessmentHistory() throws Exception {
        jdbc.update("INSERT INTO dataset(id,as_of) VALUES (?,now())",first);
        jdbc.update("INSERT INTO product VALUES (?,'P1','크래커','제조사','100g','EA')",first);
        mvc.perform(get("/api/v1/datasets")).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(first.toString()));
        mvc.perform(get("/api/v1/datasets/"+first+"/products")).andExpect(jsonPath("$.items[0].name").value("크래커"));
        mvc.perform(get("/api/v1/datasets/"+first+"/products").param("q","없음")).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/v1/datasets/"+second+"/products")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/datasets").param("size","0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/recalls/"+first+"/assessments")).andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/api/v1/recalls/"+UUID.randomUUID()+"/assessments")).andExpect(status().isNotFound());
    }
    @Test @org.springframework.security.test.context.support.WithAnonymousUser
    void listRequiresLogin() throws Exception {mvc.perform(get("/api/v1/recalls")).andExpect(status().isUnauthorized());}
}
