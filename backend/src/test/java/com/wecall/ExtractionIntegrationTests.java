package com.wecall;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import com.wecall.dataset.DatasetService;
import com.wecall.recall.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MultipartFile;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.wecall.recall.RecallModels.*;

@SpringBootTest @AutoConfigureMockMvc @Import(PostgresTestConfiguration.class)
@WithMockUser(username="test-reviewer",roles="REVIEWER")
class ExtractionIntegrationTests {
    static final ObjectMapper WIRE=new ObjectMapper();
    static final AtomicReference<String> MODE=new AtomicReference<>("OK");
    static final AtomicReference<String> HEADER=new AtomicReference<>();
    static final java.util.concurrent.ExecutorService EXECUTOR=java.util.concurrent.Executors.newCachedThreadPool();
    static final HttpServer SERVER=startServer();
    static HttpServer startServer() {
        try {
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/v1/extractions",exchange->{
                try {
                    HEADER.set(exchange.getRequestHeaders().getFirst("X-Service-Token"));
                    var request=WIRE.readTree(exchange.getRequestBody());String mode=MODE.get();
                    if(mode.equals("SLOW")) Thread.sleep(500);
                    String source=request.get("sourceText").asText();
                    var definition=WIRE.readTree(Files.readString(Path.of("../samples/recall-001/condition-request.json")));
                    var body=WIRE.createObjectNode();body.set("requestId",request.get("requestId"));
                    body.put("sourceSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8))));
                    body.put("schemaVersion","v1");body.put("mode","MOCK");body.put("provider","fixture");body.put("model","fixture-v1");body.put("promptVersion","fixture-extract-v1");
                    body.set("rule",definition.get("rule"));body.set("sourceQuote",definition.get("sourceQuote"));body.putArray("warnings").add("모의 응답");
                    if(mode.equals("WRONG_ID"))body.put("requestId",UUID.randomUUID().toString());
                    if(mode.equals("WRONG_HASH"))body.put("sourceSha256","wrong");
                    if(mode.equals("BAD_RULE"))((ObjectNode)body.get("rule")).put("op","SQL");
                    if(mode.equals("BAD_QUOTE"))body.put("sourceQuote","없는 문장");
                    if(mode.startsWith("FAIL:")) {
                        var parts=mode.split(":",3);
                        byte[] failure=WIRE.writeValueAsBytes(Map.of("detail",Map.of("code",parts[2],"message","private upstream body")));
                        exchange.sendResponseHeaders(Integer.parseInt(parts[1]),failure.length);exchange.getResponseBody().write(failure);return;
                    }
                    byte[] bytes=mode.equals("HUGE")?new byte[262145]:mode.equals("INVALID")?"not json".getBytes():WIRE.writeValueAsBytes(body);
                    exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(mode.equals("ERROR")?503:200,bytes.length);exchange.getResponseBody().write(bytes);
                }catch(Exception ignored){}finally{exchange.close();}
            });server.setExecutor(EXECUTOR);server.start();return server;
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("wecall.ai.base-url",()->"http://127.0.0.1:"+SERVER.getAddress().getPort());
        r.add("wecall.ai.service-token",()->"test-service-token");r.add("wecall.ai.allow-mock",()->true);r.add("wecall.ai.read-timeout-ms",()->200);
    }
    @AfterAll static void stop(){SERVER.stop(0);EXECUTOR.shutdownNow();}
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired DatasetService datasets;@Autowired RecallService recalls;
    @Autowired ExtractionService jobs;@Autowired ExtractionClient client;@Autowired ClosureService closures;@Autowired JdbcTemplate jdbc;
    UUID caseId,datasetId;NewCondition condition;
    @BeforeEach void setup() throws Exception {
        MODE.set("OK");jdbc.execute("TRUNCATE recall_case, dataset CASCADE");
        var root=Path.of("../samples/recall-001");Map<String,MultipartFile> files=new HashMap<>();
        for(String n:List.of("products","receipts","inventory","shipments","shipment_allocations"))files.put(n,new MockMultipartFile(n,Files.readAllBytes(root.resolve(n+".csv"))));
        datasetId=(UUID)datasets.importFiles(OffsetDateTime.parse("2026-09-09T18:00:00+09:00"),files).get("datasetId");
        caseId=(UUID)recalls.createCase(new NewCase("조건 추출",SourceType.SUPPLIER,Files.readString(root.resolve("notice.md")))).get("id");
        var node=(ObjectNode)json.readTree(Files.readString(root.resolve("condition-request.json")));node.put("datasetId",datasetId.toString());condition=json.treeToValue(node,NewCondition.class);
    }
    UUID enqueue() {return (UUID)jobs.enqueue(caseId,"test-reviewer").get("id");}
    void poll(){new ExtractionWorker(jobs,client).poll();}
    JsonNode postJson(String path,Object body,int expected) throws Exception {
        return json.readTree(mvc.perform(post(path).with(csrf()).contentType("application/json").content(json.writeValueAsBytes(body)))
            .andExpect(status().is(expected)).andReturn().getResponse().getContentAsByteArray());
    }
    String prefix(){return "/api/v1/recalls/"+caseId+"/extractions";}
    @Test void workerStoresVerifiedDraftThenHumanCreatesUnapprovedCondition() throws Exception {
        var accepted=postJson(prefix(),Map.of(),202);UUID id=UUID.fromString(accepted.get("id").asText());
        assertThat(accepted.get("status").asText()).isEqualTo("QUEUED");poll();
        var result=jobs.get(caseId,id);assertThat(result.get("status")).isEqualTo("SUCCEEDED");assertThat(result.get("reviewStatus")).isEqualTo("PENDING");
        assertThat(HEADER.get()).isEqualTo("test-service-token");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM recall_condition",Long.class)).isZero();
        var original=result.get("output");
        var converted=postJson(prefix()+"/"+id+"/condition",new ExtractionModels.Conversion(condition,"상품과 원문 조건 확인"),201);
        UUID conditionId=UUID.fromString(converted.get("conditionId").asText());
        assertThat(recalls.getCondition(caseId,conditionId).get("status")).isEqualTo("DRAFT");
        assertThat(jobs.get(caseId,id).get("output")).isEqualTo(original);
        assertThatThrownBy(()->recalls.assess(caseId,conditionId)).isInstanceOf(RecallService.Failure.class);
        recalls.approve(caseId,conditionId,new Approval("test-reviewer"));
        var run=recalls.assess(caseId,conditionId);assertThat(run.inventoryTotals().target()).isEqualTo(110);assertThat(run.shipmentTotals().needsReview()).isEqualTo(20);
        postJson(prefix()+"/"+id+"/condition",new ExtractionModels.Conversion(condition,"중복"),409);
    }
    @Test void oldSourceVersionCannotBecomeNewConditionEvenAfterTextRestored() throws Exception {
        UUID id=enqueue();poll();
        String original=recalls.getCase(caseId).get("sourceText").toString();
        String revisions="/api/v1/recalls/"+caseId+"/source/revisions";
        postJson(revisions,Map.of("expectedVersion",1,"sourceText",original+"\n검토 메모","note","문서 재검토"),201);
        postJson(revisions,Map.of("expectedVersion",2,"sourceText",original,"note","원문 복원"),201);
        postJson(prefix()+"/"+id+"/condition",new ExtractionModels.Conversion(condition,"이전 분석 시도"),409);
        assertThat(jobs.get(caseId,id).get("sourceText")).isEqualTo(original);
        assertThat(jobs.get(caseId,id).get("reviewStatus")).isEqualTo("PENDING");
        UUID latest=enqueue();poll();
        assertThat(jobs.get(caseId,latest).get("sourceVersion")).isEqualTo(3L);
        postJson(prefix()+"/"+latest+"/condition",new ExtractionModels.Conversion(condition,"최신 원문 확인"),201);
    }
    @Test void invalidOrUnboundResponsesFailAndNeverCreateConditions() {
        for(String mode:List.of("WRONG_ID","WRONG_HASH","BAD_RULE","BAD_QUOTE","INVALID","HUGE")) {
            MODE.set(mode);UUID id=enqueue();poll();
            assertThat(jobs.get(caseId,id).get("status")).isEqualTo("FAILED");
            assertThat(jobs.get(caseId,id).get("errorCode")).isIn("INVALID_AI_RESPONSE","AI_RESPONSE_TOO_LARGE");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM recall_condition",Long.class)).isZero();
    }
    @Test void errorAndTimeoutRemainReviewableFailures() {
        MODE.set("ERROR");UUID id=enqueue();poll();assertThat(jobs.get(caseId,id).get("errorCode")).isEqualTo("AI_UNAVAILABLE");
        MODE.set("SLOW");id=enqueue();poll();assertThat(jobs.get(caseId,id).get("errorCode")).isEqualTo("AI_TIMEOUT");
    }
    @Test void providerErrorsAreAllowlistedAndNeverRelayPrivateBodies() {
        var cases=Map.ofEntries(
            Map.entry("422:LOCAL_SOURCE_TOO_LONG","LOCAL_SOURCE_TOO_LONG"),
            Map.entry("422:MANUAL_REVIEW_REQUIRED","MANUAL_REVIEW_REQUIRED"),
            Map.entry("503:LOCAL_MODEL_BUSY","LOCAL_MODEL_BUSY"),
            Map.entry("503:LOCAL_MODEL_UNAVAILABLE","LOCAL_MODEL_UNAVAILABLE"),
            Map.entry("503:NON_LOCAL_MODEL_BLOCKED","NON_LOCAL_MODEL_BLOCKED"),
            Map.entry("503:MODEL_NOT_CONFIGURED","MODEL_NOT_CONFIGURED"),
            Map.entry("503:SERVICE_NOT_CONFIGURED","SERVICE_NOT_CONFIGURED"),
            Map.entry("504:LOCAL_MODEL_TIMEOUT","AI_TIMEOUT"),
            Map.entry("401:UNAUTHORIZED_SERVICE","AI_SERVICE_AUTH_FAILED"),
            Map.entry("502:INVALID_MODEL_OUTPUT","INVALID_AI_RESPONSE"),
            Map.entry("502:INVALID_SOURCE_QUOTE","INVALID_AI_RESPONSE"),
            Map.entry("502:INCOMPLETE_MODEL_RESPONSE","INVALID_AI_RESPONSE"),
            Map.entry("502:MODEL_RESPONSE_TOO_LARGE","INVALID_AI_RESPONSE"),
            Map.entry("503:private unknown code","AI_UNAVAILABLE"),
            Map.entry("503:MANUAL_REVIEW_REQUIRED","AI_UNAVAILABLE"));
        for(var entry:cases.entrySet()) {
            MODE.set("FAIL:"+entry.getKey());UUID id=enqueue();poll();var job=jobs.get(caseId,id);
            assertThat(job.get("errorCode")).isEqualTo(entry.getValue());
            assertThat(job.get("rawResponse")).isNull();assertThat(job.get("output")).isNull();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM recall_condition",Long.class)).isZero();
    }
    @Test void oversizedSourcesNeverEnqueueAndSavedOriginalIsPreserved() throws Exception {
        for(String source:List.of("가".repeat(2001),"😀".repeat(1501),"a\n".repeat(81),"a\u2028".repeat(81))) {
            UUID oversized=UUID.fromString(postJson("/api/v1/recalls",new NewCase("입력 한도",SourceType.INTERNAL,source),201).get("id").asText());
            postJson("/api/v1/recalls/"+oversized+"/extractions",Map.of(),422);
            assertThat(jobs.list(oversized)).isEmpty();assertThat(recalls.getCase(oversized).get("sourceText")).isEqualTo(source);
        }
        for(String source:List.of("가".repeat(2000),"😀".repeat(1500),"a\r\n".repeat(80))) {
            UUID valid=UUID.fromString(postJson("/api/v1/recalls",new NewCase("경계 입력",SourceType.INTERNAL,source),201).get("id").asText());
            postJson("/api/v1/recalls/"+valid+"/extractions",Map.of(),202);
        }
    }
    @Test void mockResultsRequireExplicitOptIn() {
        UUID id=enqueue();var work=jobs.claim().orElseThrow();
        var strict=new FastApiExtractionClient(json,"http://127.0.0.1:"+SERVER.getAddress().getPort(),"test-service-token",false,1000,1000);
        assertThatThrownBy(()->strict.extract(id,work.source(),work.sha())).isInstanceOf(ExtractionClient.Failed.class).hasMessage("MOCK_RESPONSE_DISABLED");
    }
    @Test void activeJobsAreNotDuplicatedOrDiscardedAndTerminalReviewBlocksClosure() throws Exception {
        UUID id=enqueue();assertThatThrownBy(this::enqueue).isInstanceOf(RecallService.Failure.class);
        postJson(prefix()+"/"+id+"/dismissal",new ExtractionModels.Dismissal("아직 실행 전"),409);
        assertThat(closures.check(caseId,null).get("blockers").toString()).contains("UNREVIEWED_EXTRACTIONS");
        poll();postJson(prefix()+"/"+id+"/dismissal",new ExtractionModels.Dismissal("모의 결과 사용하지 않음"),200);
        assertThat(closures.check(caseId,null).get("blockers").toString()).doesNotContain("UNREVIEWED_EXTRACTIONS");
    }
    @Test void interruptedClaimIsFailedAndLateResultCannotOverwriteIt() {
        UUID id=enqueue();var work=jobs.claim().orElseThrow();
        jdbc.update("UPDATE extraction_job SET started_at=now()-interval '3 minutes' WHERE id=?",id);jobs.recoverInterrupted();
        assertThat(jobs.get(caseId,id).get("errorCode")).isEqualTo("WORKER_INTERRUPTED");
        jobs.finish(work,null,"OTHER",null,1);assertThat(jobs.get(caseId,id).get("errorCode")).isEqualTo("WORKER_INTERRUPTED");
    }
    @Test void failedConversionRollsBackConditionAndReview() {
        UUID id=enqueue();poll();jdbc.execute("ALTER TABLE extraction_job ADD CONSTRAINT extraction_test_failure CHECK (review_status <> 'ACCEPTED')");
        try {
            assertThatThrownBy(()->jobs.convert(caseId,id,new ExtractionModels.Conversion(condition,"검토"),"test-reviewer")).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM recall_condition",Long.class)).isZero();assertThat(jobs.get(caseId,id).get("reviewStatus")).isEqualTo("PENDING");
        }finally{jdbc.execute("ALTER TABLE extraction_job DROP CONSTRAINT extraction_test_failure");}
    }
    @Test @WithMockUser(username="test-operator",roles="OPERATOR")
    void operatorsCannotRequestOrConvertExtractions() throws Exception {postJson(prefix(),Map.of(),403);}
}
