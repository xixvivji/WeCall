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
    @Test @WithMockUser(roles="REVIEWER")
    void reviewInboxCountsOnlyActionableItemsAndRemovesReviewedOnRefresh() throws Exception {
        UUID condition=UUID.randomUUID(),run=UUID.randomUUID(),evidence=UUID.randomUUID(),task=UUID.randomUUID(),proof=UUID.randomUUID();
        jdbc.update("INSERT INTO dataset(id,as_of) VALUES (?,now())",first);
        jdbc.update("INSERT INTO product VALUES (?,'P1','상품','제조사','100g','EA')",first);
        jdbc.update("INSERT INTO receipt VALUES (?,'R1','P1',NULL,NULL,10,current_date)",first);
        jdbc.update("INSERT INTO recall_condition(id,case_id,dataset_id,version,definition) VALUES (?,?,?,1,'{}')",condition,first,first);
        jdbc.update("INSERT INTO assessment_run(id,case_id,condition_id,dataset_id,result) VALUES (?,?,?,?,'{}')",run,first,condition,first);
        jdbc.update("INSERT INTO receipt_evidence(id,case_id,base_assessment_id,base_dataset_id,receipt_id,document_text,document_sha256,proposal) VALUES (?,?,?,?,'R1','근거',?,'{}')",evidence,first,run,first,"a".repeat(64));
        jdbc.update("INSERT INTO response_task(id,case_id,task_type,target_type,title,instructions,status) VALUES (?,?,'QUARANTINE','CASE','작업','지시','CANCELLED')",task,first);
        jdbc.update("INSERT INTO response_task_proof(id,task_id,review_round,evidence_text,sha256,submitted_by) VALUES (?,?,1,'증빙',?,'담당자')",proof,task,"b".repeat(64));
        mvc.perform(get("/api/v1/workspace/reviews")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(3))
            .andExpect(jsonPath("$.counts.CONDITION").value(1)).andExpect(jsonPath("$.counts.EVIDENCE").value(1)).andExpect(jsonPath("$.counts.PROOF").value(1));
        mvc.perform(get("/api/v1/workspace/reviews?kind=PROOF&size=1")).andExpect(jsonPath("$.items[0].taskId").value(task.toString())).andExpect(jsonPath("$.totalPages").value(1));
        mvc.perform(get("/api/v1/workspace/reviews?q=없는사건")).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/v1/workspace/reviews?page=1&size=2")).andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get("/api/v1/workspace/reviews?kind=INVALID")).andExpect(status().isBadRequest());
        jdbc.update("UPDATE response_task SET review_round=2 WHERE id=?",task);
        mvc.perform(get("/api/v1/workspace/reviews?kind=PROOF")).andExpect(jsonPath("$.totalElements").value(0));
        jdbc.update("UPDATE recall_condition SET status='APPROVED',approved_by='검토자',approved_at=now() WHERE id=?",condition);
        mvc.perform(get("/api/v1/workspace/reviews")).andExpect(jsonPath("$.totalElements").value(2)).andExpect(jsonPath("$.counts.SOURCE").value(1));
        // This SQL fixture initially has unknown provenance; bind it explicitly to simulate a current-source condition.
        jdbc.update("UPDATE recall_condition SET source_version=1 WHERE id=?",condition);
        mvc.perform(get("/api/v1/workspace/reviews")).andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.counts.SOURCE").value(0));
        jdbc.update("UPDATE recall_case SET status='CLOSED',closed_at=now() WHERE id=?",first);
        mvc.perform(get("/api/v1/workspace/reviews")).andExpect(jsonPath("$.totalElements").value(0));
    }
    @Test void operatorCannotReadReviewInbox() throws Exception {
        mvc.perform(get("/api/v1/workspace/reviews")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/workspace/reviews").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous())).andExpect(status().isUnauthorized());
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
