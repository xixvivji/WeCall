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
import static com.wecall.recall.ClosureModels.*;

@WithMockUser(username="test-reviewer",roles="REVIEWER")
@SpringBootTest @AutoConfigureMockMvc @Import(PostgresTestConfiguration.class)
class CaseClosureTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired DatasetService datasets;
    @Autowired RecallService recalls;
    @Autowired TaskService tasks;
    @Autowired EvidenceService evidence;
    @Autowired ClosureService closures;
    @Autowired JdbcTemplate jdbc;
    UUID caseId,datasetId;
    Assessment run;
    NewCondition definition;
    static final Path SAMPLE=Path.of("../samples/recall-001");
    @BeforeEach void setup() throws Exception { jdbc.execute("TRUNCATE recall_case, dataset CASCADE"); load(true); }
    void load(boolean complete) throws Exception {
        Map<String,MultipartFile> files=new HashMap<>();
        for (String name:List.of("products","receipts","inventory","shipments","shipment_allocations")) {
            String text=Files.readString(SAMPLE.resolve(name+".csv"));
            // Independent synthetic complete-record fixture: explicit R4 lot and S2 allocation.
            // No production logic guesses either relationship.
            if (complete && name.equals("receipts")) text=text.replace("R4,P1,,","R4,P1,A02,");
            if (complete && name.equals("shipment_allocations")) text+="A5,S2,R2,10\n";
            files.put(name,new MockMultipartFile(name,text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        datasetId=(UUID)datasets.importFiles(OffsetDateTime.parse("2026-09-09T18:00:00+09:00"),files).get("datasetId");
        caseId=(UUID)recalls.createCase(new NewCase("종료 검증 사건",SourceType.SUPPLIER,Files.readString(SAMPLE.resolve("notice.md")))).get("id");
        var node=(ObjectNode)json.readTree(Files.readString(SAMPLE.resolve("condition-request.json"))); node.put("datasetId",datasetId.toString());
        definition=json.treeToValue(node,NewCondition.class);
        UUID condition=(UUID)recalls.createCondition(caseId,definition).get("id"); recalls.approve(caseId,condition,new Approval("검토자")); run=recalls.assess(caseId,condition);
    }
    Map<String,Object> newTask() {
        return tasks.create(caseId,new NewTask(TaskType.QUARANTINE,TargetType.CASE,run.id(),null,"가상 전체 대응","현재 판정 전체 대상에 대한 합성 대응 기록","실행자","검토자"));
    }
    long version(Map<String,Object> t) { return ((Number)t.get("version")).longValue(); }
    Map<String,Object> finishTask() {
        var t=newTask(); UUID id=(UUID)t.get("id");
        t=tasks.transition(caseId,id,new Transition(version(t),Action.START,"실행자","가상 대응 시작"));
        t=tasks.addProof(caseId,id,new NewProof(version(t),"합성 증빙: 전체 대상 범위 대응 완료","실행자"));
        UUID proof=(UUID)((Map<?,?>)((List<?>)t.get("proofs")).getFirst()).get("id");
        t=tasks.reviewProof(caseId,id,proof,new ProofReview(version(t),ProofDecision.ACCEPTED,"검토자","전체 범위 검토"));
        return tasks.transition(caseId,id,new Transition(version(t),Action.COMPLETE,"검토자","가상 대응 완료"));
    }
    CloseRequest closeBody(long version) { return new CloseRequest(run.id(),version,"검토자","대상 범위·조치·취소 사항 검토 완료",true); }
    String prefix() { return "/api/v1/recalls/"+caseId; }
    JsonNode postJson(String url,Object body,int expected) throws Exception {
        return json.readTree(mvc.perform(post(url).with(csrf()).contentType("application/json").content(json.writeValueAsBytes(body))).andExpect(status().is(expected)).andReturn().getResponse().getContentAsByteArray());
    }
    Set<String> blockers(Map<String,Object> check) {
        Set<String> codes=new HashSet<>(); for (var issue:(List<?>)check.get("blockers")) codes.add(((ClosureModels.Issue)issue).code()); return codes;
    }
    void reviseSource(long version,String source) throws Exception {
        postJson(prefix()+"/source/revisions",Map.of("expectedVersion",version,"sourceText",source,"note","합성 원문 정정"),201);
    }
    String sourceReviewUrl(UUID condition) {return prefix()+"/conditions/"+condition+"/source-review";}
    Map<String,Object> unchanged(long version) {return Map.of("expectedSourceVersion",version,"note","현재 원문·조건·상품 연결 영향 없음 확인","unchangedConfirmed",true);}
    @Test void changedSourceBlocksAssessmentAndClosureUntilExplicitReviewWithoutRewritingOldRun() throws Exception {
        finishTask();
        String original=recalls.getCase(caseId).get("sourceText").toString();
        reviseSource(1,original+"\n연락처 정정");
        assertThat(blockers(closures.check(caseId,run.id()))).contains("SOURCE_REVIEW_REQUIRED");
        postJson(prefix()+"/assessments",new NewAssessment(run.conditionId()),409);
        postJson(prefix()+"/closure",closeBody(0),409);
        mvc.perform(get("/api/v1/workspace/reviews?kind=SOURCE")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/v1/workspace/summary")).andExpect(status().isOk()).andExpect(jsonPath("$.sourceReviewCases").value(1));
        postJson(sourceReviewUrl(run.conditionId()),unchanged(1),409);
        postJson(sourceReviewUrl(run.conditionId()),unchanged(2),200);
        postJson(sourceReviewUrl(run.conditionId()),unchanged(2),409);
        assertThat(recalls.getAssessment(caseId,run.id())).isEqualTo(run);
        mvc.perform(get("/api/v1/workspace/reviews?kind=SOURCE")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        var closed=postJson(prefix()+"/closure",closeBody(0),200);
        var snapshot=closed.get("history").get(0).get("snapshot").get("check");
        assertThat(snapshot.get("sourceVersion").asInt()).isEqualTo(2);
        assertThat(snapshot.get("assessmentSourceVersion").asInt()).isEqualTo(1);
        assertThat(snapshot.get("assessmentConditionSource").get("reviews").get(0).get("reviewer").asText()).isEqualTo("test-reviewer");
        postJson(sourceReviewUrl(run.conditionId()),unchanged(2),409);
        mvc.perform(get(prefix()+"/history?kind=CONDITION")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2));
    }
    @Test void restoredTextStillNeedsNewVersionReviewAndMissingCitationCannotBeAcknowledged() throws Exception {
        String original=recalls.getCase(caseId).get("sourceText").toString();
        reviseSource(1,original+"\n정정 메모");postJson(sourceReviewUrl(run.conditionId()),unchanged(2),200);
        reviseSource(2,original);
        assertThat(blockers(closures.check(caseId,run.id()))).contains("SOURCE_REVIEW_REQUIRED");
        reviseSource(3,"모든 내용이 바뀐 합성 공문");
        postJson(sourceReviewUrl(run.conditionId()),unchanged(4),409);
        mvc.perform(post(sourceReviewUrl(run.conditionId())).with(csrf()).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("test-operator").roles("OPERATOR"))
            .contentType("application/json").content(json.writeValueAsBytes(unchanged(4)))).andExpect(status().isForbidden());
        postJson(sourceReviewUrl(UUID.randomUUID()),unchanged(4),404);
    }
    @Test void staleDraftCanBeWithdrawnAndReplacementConditionClearsSourceBlocker() throws Exception {
        UUID draft=(UUID)recalls.createCondition(caseId,definition).get("id");
        String original=recalls.getCase(caseId).get("sourceText").toString();
        reviseSource(1,original+"\n조건 재검토");
        postJson(prefix()+"/conditions/"+draft+"/approval",Map.of(),409);
        postJson(prefix()+"/conditions/"+draft+"/withdrawal",Map.of("expectedSourceVersion",1,"note","이전 초안 철회"),409);
        postJson(prefix()+"/conditions/"+draft+"/withdrawal",Map.of("expectedSourceVersion",2,"note","새 조건으로 대체"),200);
        postJson(prefix()+"/conditions/"+draft+"/approval",Map.of(),409);
        postJson(prefix()+"/assessments",new NewAssessment(draft),409);
        postJson(prefix()+"/conditions/"+run.conditionId()+"/withdrawal",Map.of("expectedSourceVersion",2,"note","승인 조건 철회 시도"),409);
        var replacement=postJson(prefix()+"/conditions",definition,201);
        UUID id=UUID.fromString(replacement.get("id").asText());
        postJson(prefix()+"/conditions/"+id+"/approval",Map.of(),200);
        var next=postJson(prefix()+"/assessments",new NewAssessment(id),201);
        var check=closures.check(caseId,UUID.fromString(next.get("id").asText()));
        assertThat(blockers(check)).doesNotContain("SOURCE_REVIEW_REQUIRED","PENDING_CONDITIONS","OUTDATED_ASSESSMENT");
        assertThat(recalls.getAssessment(caseId,run.id())).isEqualTo(run);
    }
    @Test void unknownLegacyBasisRequiresReviewInsteadOfAssumingCurrentVersion() throws Exception {
        jdbc.update("UPDATE recall_condition SET source_version=NULL WHERE id=?",run.conditionId());
        var state=(Map<?,?>)recalls.getCondition(caseId,run.conditionId()).get("sourceReview");
        assertThat(state.get("basisVersion")).isNull();assertThat(state.get("required")).isEqualTo(true);
        postJson(sourceReviewUrl(run.conditionId()),unchanged(1),200);
        assertThat(jdbc.queryForObject("SELECT source_version FROM recall_condition WHERE id=?",Long.class,run.conditionId())).isNull();
        postJson(prefix()+"/assessments",new NewAssessment(run.conditionId()),201);
    }
    @Test void closesAndReopensWhileKeepingApprovalSnapshot() throws Exception {
        finishTask(); var check=closures.check(caseId,run.id()); assertThat(check.get("ready")).isEqualTo(true);
        var closed=postJson(prefix()+"/closure",closeBody(0),200);
        assertThat(closed.get("status").asText()).isEqualTo("CLOSED"); assertThat(closed.get("closedAt").isNull()).isFalse();
        assertThat(closed.get("history").get(0).get("snapshot").get("check").get("ready").asBoolean()).isTrue();
        JsonNode originalSnapshot=closed.get("history").get(0);
        var reopened=postJson(prefix()+"/reopen",new ReopenRequest(1L,"검토자","추가 통보 검토"),200);
        assertThat(reopened.get("status").asText()).isEqualTo("OPEN");assertThat(reopened.get("closedAt").isNull()).isTrue();
        assertThat(reopened.get("history").get(0)).isEqualTo(originalSnapshot);
        postJson(prefix()+"/closure",closeBody(2),200);
        mvc.perform(get(prefix()+"/lifecycle")).andExpect(status().isOk()).andExpect(jsonPath("$.history.length()").value(3));
        assertThat(recalls.getAssessment(caseId,run.id())).isEqualTo(run);
    }
    @Test void originalSampleCannotCloseEvenWithCompletedTask() throws Exception {
        load(false);finishTask();
        assertThat(blockers(closures.check(caseId,run.id()))).contains("UNRESOLVED_RECEIPTS","UNRESOLVED_SHIPMENTS");
        var result=postJson(prefix()+"/closure",closeBody(0),409); assertThat(result.get("code").asText()).isEqualTo("CLOSURE_BLOCKED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM case_lifecycle_event",Long.class)).isZero();
    }
    @Test void pendingAndOutdatedConditionsBlockClosure() {
        finishTask(); var next=recalls.createCondition(caseId,definition); UUID nextId=(UUID)next.get("id");
        assertThat(blockers(closures.check(caseId,run.id()))).contains("PENDING_CONDITIONS","OUTDATED_ASSESSMENT");
        recalls.approve(caseId,nextId,new Approval("검토자"));
        assertThat(blockers(closures.check(caseId,run.id()))).contains("OUTDATED_ASSESSMENT");
    }
    @Test void absentAssessmentAndIncompleteTasksBlockClosure() {
        assertThat(blockers(closures.check(caseId,null))).contains("NO_ASSESSMENT");
        assertThat(blockers(closures.check(caseId,run.id()))).contains("NO_COMPLETED_RESPONSE");
        newTask();assertThat(blockers(closures.check(caseId,run.id()))).contains("INCOMPLETE_TASKS");
    }
    @Test void cancelledPendingProofStillNeedsDisposition() {
        finishTask();var t=newTask();UUID id=(UUID)t.get("id");
        t=tasks.transition(caseId,id,new Transition(version(t),Action.START,"실행자","시작"));
        t=tasks.addProof(caseId,id,new NewProof(version(t),"취소 전 등록 자료","실행자"));
        UUID proof=(UUID)((Map<?,?>)((List<?>)t.get("proofs")).getFirst()).get("id");
        t=tasks.transition(caseId,id,new Transition(version(t),Action.CANCEL,"검토자","중복 작업 취소"));
        assertThat(blockers(closures.check(caseId,run.id()))).contains("PENDING_TASK_PROOFS");
        tasks.reviewProof(caseId,id,proof,new ProofReview(version(t),ProofDecision.REJECTED,"검토자","취소 작업 자료 검토·미채택"));
        assertThat(closures.check(caseId,run.id()).get("ready")).isEqualTo(true);
    }
    @Test void pendingReceiptEvidenceMustBeReviewed() {
        finishTask(); UUID id=(UUID)evidence.create(caseId,new EvidenceModels.NewEvidence(run.id(),"R4","A02 2026-10-31","A02 2026-10-31","P1",40L,LocalDate.parse("2026-09-04"),"A02",LocalDate.parse("2026-10-31"))).get("id");
        assertThat(blockers(closures.check(caseId,run.id()))).contains("PENDING_RECEIPT_EVIDENCE");
        evidence.reject(caseId,id,new EvidenceModels.RejectEvidence("검토자","이미 확인된 값으로 추가 보완 불필요"));
        assertThat(closures.check(caseId,run.id()).get("ready")).isEqualTo(true);
    }
    @Test void closureBlocksAllExistingMutationFamilies() {
        var t=finishTask();closures.close(caseId,closeBody(0));
        assertThatThrownBy(()->recalls.createCondition(caseId,definition)).isInstanceOf(RecallService.Failure.class);
        assertThatThrownBy(()->recalls.assess(caseId,run.conditionId())).isInstanceOf(RecallService.Failure.class);
        assertThatThrownBy(()->recalls.approve(caseId,run.conditionId(),new Approval("검토자"))).isInstanceOf(RecallService.Failure.class);
        assertThatThrownBy(this::newTask).isInstanceOf(RecallService.Failure.class);
        assertThatThrownBy(()->tasks.transition(caseId,(UUID)t.get("id"),new Transition(version(t),Action.REOPEN,"검토자","재개"))).isInstanceOf(RecallService.Failure.class);
        assertThatThrownBy(()->evidence.create(caseId,new EvidenceModels.NewEvidence(run.id(),"R4","A02","A02","P1",40L,LocalDate.parse("2026-09-04"),"A02",null))).isInstanceOf(RecallService.Failure.class);
        closures.reopen(caseId,new ReopenRequest(1L,"검토자","새 자료 검토"));newTask();
    }
    @Test void validatesApprovalAndCaseOwnership() throws Exception {
        UUID other=(UUID)recalls.createCase(new NewCase("다른 사건",SourceType.INTERNAL,"통보")).get("id");
        finishTask();postJson(prefix()+"/closure",new CloseRequest(run.id(),0L,"검토자","검토",false),400);
        postJson(prefix()+"/closure",new CloseRequest(run.id(),0L," ","검토",true),400);
        postJson(prefix()+"/closure",closeBody(99),409);
        postJson("/api/v1/recalls/"+UUID.randomUUID()+"/closure",closeBody(0),404);
        mvc.perform(get("/api/v1/recalls/"+other+"/closure-check").param("assessmentId",run.id().toString())).andExpect(status().isNotFound());
    }
    @Test void concurrentClosuresHaveOneWinner() throws Exception {
        finishTask();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            Callable<Boolean> operation=()->{start.await();try {closures.close(caseId,closeBody(0));return true;} catch(RecallService.Failure e){assertThat(e.status.value()).isEqualTo(409);return false;}};
            var a=pool.submit(operation);var b=pool.submit(operation);start.countDown();
            assertThat(List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM case_lifecycle_event",Long.class)).isEqualTo(1);
    }
    @Test void concurrentNewWorkCannotSlipPastClosure() throws Exception {
        finishTask();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            var close=pool.submit(()->{start.await();try {closures.close(caseId,closeBody(0));return true;}catch(ClosureService.Blocked e){return false;}});
            var create=pool.submit(()->{start.await();try {newTask();return true;}catch(RecallService.Failure e){assertThat(e.status.value()).isEqualTo(409);return false;}});
            start.countDown();assertThat(List.of(close.get(15,TimeUnit.SECONDS),create.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
    }
    @Test void auditFailureRollsBackClosure() {
        finishTask();jdbc.execute("ALTER TABLE case_lifecycle_event ADD CONSTRAINT closure_test_failure CHECK (event_type <> 'CLOSED')");
        try {
            assertThatThrownBy(()->closures.close(caseId,closeBody(0))).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(recalls.getCase(caseId).get("status")).isEqualTo("OPEN");
            assertThat(((Number)recalls.getCase(caseId).get("lifecycleVersion")).longValue()).isZero();
        } finally {jdbc.execute("ALTER TABLE case_lifecycle_event DROP CONSTRAINT closure_test_failure");}
    }
}
