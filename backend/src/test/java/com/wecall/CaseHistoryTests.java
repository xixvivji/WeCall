package com.wecall;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;

@SpringBootTest @AutoConfigureMockMvc @Import(PostgresTestConfiguration.class)
@WithMockUser(roles="OPERATOR")
class CaseHistoryTests {
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired ObjectMapper json;
    UUID caseId, other, task, proof, condition, evidence;
    @BeforeEach void setup() {
        jdbc.execute("TRUNCATE recall_case,dataset CASCADE");
        caseId=UUID.randomUUID(); other=UUID.randomUUID(); task=UUID.randomUUID(); proof=UUID.randomUUID(); condition=UUID.randomUUID(); evidence=UUID.randomUUID();
        for(UUID id:List.of(caseId,other))jdbc.update("INSERT INTO recall_case(id,title,source_type,source_text) VALUES (?,'합성 사건','INTERNAL','원문')",id);
        jdbc.update("INSERT INTO dataset(id,as_of) VALUES (?,now())",caseId);
        jdbc.update("INSERT INTO product VALUES (?,'P1','상품','제조사','100g','EA')",caseId);
        jdbc.update("INSERT INTO receipt VALUES (?,'R1','P1',NULL,NULL,10,current_date)",caseId);
        jdbc.update("INSERT INTO recall_condition(id,case_id,dataset_id,version,definition,status,approved_by,approved_at) VALUES (?,?,?,1,'{}','APPROVED','검토자','2026-09-01T00:00:00Z')",condition,caseId,caseId);
        jdbc.update("INSERT INTO assessment_run(id,case_id,condition_id,dataset_id,result) VALUES (?,?,?,?,'{}')",caseId,caseId,condition,caseId);
        jdbc.update("INSERT INTO receipt_evidence(id,case_id,base_assessment_id,base_dataset_id,receipt_id,document_text,document_sha256,proposal,status,reviewed_by,review_note,reviewed_at) VALUES (?,?,?,?,'R1','숨겨야 할 원문',?,'{}','REJECTED','검토자','수량 불일치','2026-09-02T00:00:00Z')",evidence,caseId,caseId,caseId,"a".repeat(64));
        jdbc.update("INSERT INTO response_task(id,case_id,task_type,target_type,title,instructions) VALUES (?,?,'SALES_HOLD','CASE','작업 제목','지시 원문')",task,caseId);
        jdbc.update("INSERT INTO response_task_proof(id,task_id,review_round,evidence_text,sha256,submitted_by,status,reviewed_by,review_note,reviewed_at) VALUES (?,?,1,'증빙 원문',?,'실행자','ACCEPTED','검토자','확인',now())",proof,task,"b".repeat(64));
        event(0,"CREATED","{\"assignee\":\"실행자\",\"instructions\":\"지시 원문\"}");
        event(1,"ASSIGNED","{\"assignee\":\"실행자2\",\"note\":\"재배정\"}");
        event(2,"START","{\"note\":\"시작 사유\"}");
        event(3,"PROOF_ADDED","{\"proofId\":\""+proof+"\",\"reviewRound\":1}");
        event(4,"PROOF_REVIEWED","{\"proofId\":\""+proof+"\",\"decision\":\"ACCEPTED\",\"note\":\"확인\"}");
        jdbc.update("INSERT INTO case_lifecycle_event(id,case_id,version,event_type,reviewer,note,snapshot,created_at) VALUES (?,?,1,'REOPENED','검토자','추가 확인','{}','2026-09-04T00:00:00Z')",UUID.randomUUID(),caseId);
    }
    void event(int version,String type,String details) {
        jdbc.update("INSERT INTO response_task_event(id,task_id,version,event_type,actor,details,created_at) VALUES (?,?,?,?,?,?::jsonb,'2026-09-03T00:00:00Z')",UUID.randomUUID(),task,version,type,"기록 처리자",details);
    }
    String url(){return "/api/v1/recalls/"+caseId+"/history";}
    JsonNode getPage(String query) throws Exception {return json.readTree(mvc.perform(get(url()+query)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());}
    @Test void mergesRecordedSourcesWithoutDuplicateProofReviewsOrInventedNotes() throws Exception {
        var result=getPage("?order=ASC");
        assertThat(result.get("totalElements").asInt()).isEqualTo(8);
        var rows=result.get("items");
        assertThat(rows.get(0).get("type").asText()).isEqualTo("CONDITION_APPROVED");
        assertThat(rows.get(0).get("note").isNull()).isTrue();
        assertThat(rows.get(0).get("conditionId").asText()).isEqualTo(condition.toString());
        assertThat(rows.get(1).get("note").asText()).isEqualTo("수량 불일치");
        assertThat(rows.get(1).get("evidenceId").asText()).isEqualTo(evidence.toString());
        assertThat(rows.get(7).get("type").asText()).isEqualTo("CASE_REOPENED");
        var proofs=getPage("?kind=PROOF").get("items");
        assertThat(proofs.size()).isEqualTo(2);
        for(var row:proofs){assertThat(row.get("proofId").asText()).isEqualTo(proof.toString());assertThat(row.get("taskId").asText()).isEqualTo(task.toString());}
        assertThat(result.toString()).doesNotContain("숨겨야 할 원문","증빙 원문","지시 원문");
    }
    @Test void stableTieOrderingPaginationAndReverseOrder() throws Exception {
        var all=getPage("?order=DESC").get("items");
        Set<String> ids=new HashSet<>();
        for(int i=0;i<8;i++) {
            var row=getPage("?size=1&page="+i).get("items").get(0);
            assertThat(row).isEqualTo(all.get(i));ids.add(row.get("id").asText());
        }
        assertThat(ids).hasSize(8);
        var asc=getPage("?order=ASC").get("items");
        for(int i=0;i<8;i++)assertThat(asc.get(i)).isEqualTo(all.get(7-i));
        assertThat(getPage("?page=8&size=1").get("items").isEmpty()).isTrue();
    }
    @Test @WithMockUser(roles="REVIEWER") void scopesCaseAndFiltersIncludingClosedCase() throws Exception {
        mvc.perform(get("/api/v1/recalls/"+other+"/history")).andExpect(jsonPath("$.totalElements").value(0));
        jdbc.update("UPDATE recall_case SET status='CLOSED',closed_at=now() WHERE id=?",caseId);
        for(var entry:Map.of("CONDITION",1,"EVIDENCE",1,"TASK",3,"PROOF",2,"LIFECYCLE",1).entrySet()) {
            var result=getPage("?kind="+entry.getKey());assertThat(result.get("totalElements").asInt()).isEqualTo(entry.getValue());
            for(var row:result.get("items"))assertThat(row.get("kind").asText()).isEqualTo(entry.getKey());
        }
    }
    @Test void validatesInputMissingCaseAndAuthentication() throws Exception {
        for(String query:List.of("?kind=UNKNOWN","?order=DROP","?size=0","?size=101","?page=-1","?page=100001","?page=abc"))mvc.perform(get(url()+query)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/recalls/"+UUID.randomUUID()+"/history")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/recalls/not-uuid/history")).andExpect(status().isBadRequest());
        mvc.perform(get(url()).with(anonymous())).andExpect(status().isUnauthorized());
    }
}
