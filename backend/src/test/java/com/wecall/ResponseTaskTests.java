package com.wecall;
import org.springframework.security.test.context.support.WithMockUser;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wecall.dataset.DatasetService;
import com.wecall.recall.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MultipartFile;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.wecall.recall.RecallModels.*;
import static com.wecall.recall.TaskModels.*;

@WithMockUser(username="test-reviewer",roles="REVIEWER")
@SpringBootTest @AutoConfigureMockMvc @Import(PostgresTestConfiguration.class)
class ResponseTaskTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired DatasetService datasets;
    @Autowired RecallService recalls;
    @Autowired TaskService tasks;
    @Autowired JdbcTemplate jdbc;
    UUID caseId,datasetId;
    Assessment assessment;
    static final Path SAMPLE=Path.of("../samples/recall-001");
    @BeforeEach void setup() throws Exception {
        jdbc.execute("TRUNCATE recall_case, dataset CASCADE");
        Map<String,MultipartFile> files=new HashMap<>();
        for (String name:List.of("products","receipts","inventory","shipments","shipment_allocations"))
            files.put(name,new MockMultipartFile(name,Files.readAllBytes(SAMPLE.resolve(name+".csv"))));
        datasetId=(UUID)datasets.importFiles(OffsetDateTime.parse("2026-09-09T18:00:00+09:00"),files).get("datasetId");
        caseId=(UUID)recalls.createCase(new NewCase("회수 작업 시연",SourceType.SUPPLIER,Files.readString(SAMPLE.resolve("notice.md")))).get("id");
        ObjectNode definition=(ObjectNode)json.readTree(Files.readString(SAMPLE.resolve("condition-request.json")));
        definition.put("datasetId",datasetId.toString());
        UUID condition=(UUID)recalls.createCondition(caseId,json.treeToValue(definition,NewCondition.class)).get("id");
        recalls.approve(caseId,condition,new Approval("검토자")); assessment=recalls.assess(caseId,condition);
    }
    String prefix() { return "/api/v1/recalls/"+caseId+"/tasks"; }
    JsonNode postJson(String url,Object body,int status) throws Exception {
        return json.readTree(mvc.perform(post(url).with(csrf()).contentType("application/json").content(json.writeValueAsBytes(body)))
            .andExpect(status().is(status)).andReturn().getResponse().getContentAsByteArray());
    }
    JsonNode create() throws Exception {
        return postJson(prefix(),new NewTask(TaskType.QUARANTINE,TargetType.INVENTORY,assessment.id(),"I4","R4 재고 임시 격리","정보 확인 전 30개 임시 격리","test-operator","품질 담당"),201);
    }
    JsonNode transition(JsonNode t,String action,int status) throws Exception {
        return postJson(prefix()+"/"+t.get("id").asText()+"/transitions",Map.of("expectedVersion",t.get("version").asLong(),"action",action,"actor","품질 담당","note","처리 사유 기록"),status);
    }
    JsonNode proof(JsonNode t) throws Exception {
        return postJson(prefix()+"/"+t.get("id").asText()+"/proofs",Map.of("expectedVersion",t.get("version").asLong(),"evidenceText","합성 증빙: I4 30개 격리 구역 확인","actor","test-operator"),201);
    }
    JsonNode review(JsonNode t,int proofIndex,String decision) throws Exception {
        return postJson(prefix()+"/"+t.get("id").asText()+"/proofs/"+t.get("proofs").get(proofIndex).get("id").asText()+"/review",
            Map.of("expectedVersion",t.get("version").asLong(),"decision",decision,"actor","품질 담당","note","수량과 작업 범위 확인"),200);
    }
    @Test void completesReviewedTaskWithoutChangingRecallOrInventory() throws Exception {
        JsonNode t=create(); t=transition(t,"START",200); t=proof(t); t=review(t,0,"ACCEPTED"); t=transition(t,"COMPLETE",200);
        assertThat(t.get("status").asText()).isEqualTo("COMPLETED"); assertThat(t.get("completedAt").isNull()).isFalse();
        assertThat(t.get("events").size()).isEqualTo(5);
        for (int i=0;i<5;i++) assertThat(t.get("events").get(i).get("version").asInt()).isEqualTo(i);
        assertThat(recalls.getAssessment(caseId,assessment.id())).isEqualTo(assessment);
        assertThat(jdbc.queryForObject("SELECT hold_status FROM inventory WHERE dataset_id=? AND id='I4'",String.class,datasetId)).isEqualTo("NONE");
        assertThat(assessment.receipts().stream().filter(r->r.receiptId().equals("R4")).findFirst().orElseThrow().decision()).isEqualTo(Decision.NEEDS_REVIEW);
        mvc.perform(get(prefix())).andExpect(status().isOk()).andExpect(jsonPath("$[0].status").value("COMPLETED"));
        mvc.perform(get(prefix()+"/"+t.get("id").asText())).andExpect(status().isOk()).andExpect(jsonPath("$.proofs[0].status").value("ACCEPTED"));
    }
    @Test void emergencyTaskCanPrecedeAnyAssessmentButNeedsAssignee() throws Exception {
        UUID fresh=(UUID)recalls.createCase(new NewCase("긴급 통보",SourceType.INTERNAL,"임시 보류 지시")).get("id");
        String url="/api/v1/recalls/"+fresh+"/tasks";
        JsonNode t=postJson(url,new NewTask(TaskType.SALES_HOLD,TargetType.CASE,null,null,"판매보류 확인","긴급 통보 범위 확인",null,"품질"),201);
        String action=url+"/"+t.get("id").asText();
        postJson(action+"/transitions",Map.of("expectedVersion",0,"action","START","actor","품질","note","시작"),409);
        t=postJson(action+"/assignment",Map.of("expectedVersion",0,"assignee","test-operator","actor","품질","note","배정"),200);
        postJson(action+"/transitions",Map.of("expectedVersion",1,"action","START","actor","품질","note","시작"),200);
        assertThat(t.get("events").get(1).get("type").asText()).isEqualTo("ASSIGNED");
    }
    @Test void pendingOrRejectedProofCannotCompleteTask() throws Exception {
        var t=transition(create(),"START",200); transition(t,"COMPLETE",409);
        t=proof(t); transition(t,"COMPLETE",409);
        t=review(t,0,"REJECTED"); transition(t,"COMPLETE",409);
        t=proof(t); t=review(t,1,"ACCEPTED"); t=proof(t); transition(t,"COMPLETE",409);
    }
    @Test void reopeningRequiresNewRoundEvidenceAndPreservesOldReviews() throws Exception {
        var t=transition(create(),"START",200); t=proof(t); t=review(t,0,"ACCEPTED"); t=transition(t,"COMPLETE",200);
        t=transition(t,"REOPEN",200);
        assertThat(t.get("reviewRound").asInt()).isEqualTo(2); assertThat(t.get("completedAt").isNull()).isTrue();
        transition(t,"COMPLETE",409);
        assertThat(t.get("proofs").get(0).get("status").asText()).isEqualTo("ACCEPTED");
        t=proof(t); t=review(t,1,"ACCEPTED"); t=transition(t,"COMPLETE",200);
        assertThat(t.get("proofs").get(1).get("reviewRound").asInt()).isEqualTo(2);
    }
    @Test void cancellationIsNotCompletionAndLocksFurtherEdits() throws Exception {
        var t=transition(create(),"CANCEL",200);
        assertThat(t.get("status").asText()).isEqualTo("CANCELLED"); assertThat(t.get("completedAt").isNull()).isTrue();
        transition(t,"START",409); transition(t,"REOPEN",409);
        postJson(prefix()+"/"+t.get("id").asText()+"/assignment",new Assignment(t.get("version").asLong(),"test-operator","품질","변경"),409);
    }
    @Test void validatesTargetAndCaseOwnership() throws Exception {
        UUID other=(UUID)recalls.createCase(new NewCase("다른 사건",SourceType.INTERNAL,"통보")).get("id");
        postJson(prefix(),new NewTask(TaskType.QUARANTINE,TargetType.INVENTORY,assessment.id(),"MISSING","격리","확인",null,"품질"),400);
        postJson("/api/v1/recalls/"+other+"/tasks",new NewTask(TaskType.QUARANTINE,TargetType.INVENTORY,assessment.id(),"I1","격리","확인",null,"품질"),404);
        var t=create(); mvc.perform(get("/api/v1/recalls/"+other+"/tasks/"+t.get("id").asText())).andExpect(status().isNotFound());
        postJson(prefix(),new NewTask(TaskType.QUARANTINE,TargetType.INVENTORY,null,"I1","격리","확인",null,"품질"),400);
    }
    @Test void cannotUseAnotherTasksProofOrReviewTwice() throws Exception {
        var a=proof(transition(create(),"START",200)); var b=transition(create(),"START",200);
        postJson(prefix()+"/"+b.get("id").asText()+"/proofs/"+a.get("proofs").get(0).get("id").asText()+"/review",new ProofReview(b.get("version").asLong(),ProofDecision.ACCEPTED,"품질","검토"),404);
        a=review(a,0,"ACCEPTED");
        postJson(prefix()+"/"+a.get("id").asText()+"/proofs/"+a.get("proofs").get(0).get("id").asText()+"/review",new ProofReview(a.get("version").asLong(),ProofDecision.REJECTED,"품질","변경"),409);
    }
    @Test void staleConcurrentAssignmentHasOneWinner() throws Exception {
        UUID id=UUID.fromString(create().get("id").asText());
        try (var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            Callable<Boolean> operation=()->{start.await();try {tasks.assign(caseId,id,new Assignment(0L,"담당","품질","배정"));return true;} catch (RecallService.Failure e) {assertThat(e.status.value()).isEqualTo(409);return false;}};
            var a=pool.submit(operation);var b=pool.submit(operation);start.countDown();
            assertThat(List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM response_task_event WHERE task_id=?",Long.class,id)).isEqualTo(2);
    }
    @Test void eventWriteFailureRollsBackStateChange() throws Exception {
        UUID id=UUID.fromString(create().get("id").asText());
        jdbc.execute("ALTER TABLE response_task_event ADD CONSTRAINT event_test_failure CHECK (event_type <> 'START')");
        try {
            assertThatThrownBy(()->tasks.transition(caseId,id,new Transition(0L,Action.START,"품질","시작"))).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(tasks.get(caseId,id).get("status")).isEqualTo("OPEN");
            assertThat(((Number)tasks.get(caseId,id).get("version")).longValue()).isZero();
        } finally {jdbc.execute("ALTER TABLE response_task_event DROP CONSTRAINT event_test_failure");}
    }
}
