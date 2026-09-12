package com.wecall;
import org.springframework.security.test.context.support.WithMockUser;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wecall.dataset.DatasetService;
import com.wecall.recall.*;
import org.apache.commons.csv.CSVFormat;
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

@WithMockUser(username="test-reviewer",roles="REVIEWER")
@SpringBootTest @AutoConfigureMockMvc @Import(PostgresTestConfiguration.class)
class ReceiptEvidenceTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired DatasetService datasets;
    @Autowired RecallService recalls;
    @Autowired EvidenceService evidence;
    @Autowired JdbcTemplate jdbc;
    static final Path SAMPLE=Path.of("../samples/recall-001");
    UUID caseId, datasetId;
    Assessment before;

    @BeforeEach void setup() throws Exception {
        jdbc.execute("TRUNCATE recall_case, dataset CASCADE");
        Map<String,MultipartFile> files=new HashMap<>();
        for (String name:List.of("products","receipts","inventory","shipments","shipment_allocations"))
            files.put(name,new MockMultipartFile(name,Files.readAllBytes(SAMPLE.resolve(name+".csv"))));
        datasetId=(UUID)datasets.importFiles(OffsetDateTime.parse("2026-09-09T18:00:00+09:00"),files).get("datasetId");
        caseId=(UUID)recalls.createCase(new NewCase("가상 회수",SourceType.SUPPLIER,Files.readString(SAMPLE.resolve("notice.md")))).get("id");
        ObjectNode definition=(ObjectNode)json.readTree(Files.readString(SAMPLE.resolve("condition-request.json")));
        definition.put("datasetId",datasetId.toString());
        UUID conditionId=(UUID)recalls.createCondition(caseId,json.treeToValue(definition,NewCondition.class)).get("id");
        recalls.approve(caseId,conditionId,new Approval("test-reviewer"));
        before=recalls.assess(caseId,conditionId);
    }
    String prefix() { return "/api/v1/recalls/"+caseId+"/evidence"; }
    JsonNode postJson(String path,Object body,int expected) throws Exception {
        var response=mvc.perform(post(path).with(csrf()).contentType("application/json").content(json.writeValueAsBytes(body)))
            .andExpect(status().is(expected)).andReturn().getResponse();
        return json.readTree(response.getContentAsByteArray());
    }
    Map<String,Object> proposal() throws Exception {
        String text=Files.readString(SAMPLE.resolve("receipt-evidence.md"));
        return new LinkedHashMap<>(Map.of("baseAssessmentId",before.id(),"receiptId","R4","documentText",text,
            "sourceQuote",text.lines().filter(l->l.startsWith("입고 건")).findFirst().orElseThrow(),
            "observedProductId","P1","observedReceivedQuantity",40,"observedReceivedAt","2026-09-04",
            "lotNumber","A02","expiryDate","2026-10-31"));
    }
    JsonNode create(Map<String,Object> body) throws Exception { return postJson(prefix(),body,201); }
    Map<String,Object> approval() { return Map.of("reviewer","test-reviewer","note","R4 입고 건과 전체 40 EA의 단일 제조분 확인","receiptAndSingleLotConfirmed",true); }
    JsonNode approve(String id,int code) throws Exception { return postJson(prefix()+"/"+id+"/approval",approval(),code); }
    void originalUnchanged() {
        assertThat(jdbc.queryForObject("SELECT lot_number FROM receipt WHERE dataset_id=? AND id='R4'",String.class,datasetId)).isNull();
        assertThat(recalls.getAssessment(caseId,before.id())).isEqualTo(before);
    }
    void singleDataset() { assertThat(jdbc.queryForObject("SELECT count(*) FROM dataset",Long.class)).isEqualTo(1); }

    @Test @WithMockUser(username="test-operator",roles="OPERATOR")
    void evidenceListIsPagedFilteredAndCaseScoped() throws Exception {
        var a=create(proposal());var b=create(proposal());
        jdbc.update("UPDATE receipt_evidence SET created_at='2026-09-09T00:00:00Z' WHERE case_id=?",caseId);
        var first=mvc.perform(get(prefix()).param("size","1")).andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(2)).andReturn().getResponse();
        String firstId=json.readTree(first.getContentAsByteArray()).get("items").get(0).get("id").asText();
        mvc.perform(get(prefix()).param("size","1").param("page","1")).andExpect(jsonPath("$.items[0].id").value(firstId.equals(a.get("id").asText())?b.get("id").asText():a.get("id").asText()));
        mvc.perform(get(prefix()).param("status","APPROVED")).andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(get(prefix()).param("status","OTHER")).andExpect(status().isBadRequest());
        mvc.perform(get(prefix()).param("size","101")).andExpect(status().isBadRequest());
        mvc.perform(get(prefix()).param("page","-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/recalls/"+UUID.randomUUID()+"/evidence")).andExpect(status().isNotFound());
    }
    @Test void approvalMatchesAfterGoldenResultsWithoutInventingLinks() throws Exception {
        JsonNode created=create(proposal());
        assertThat(created.get("status").asText()).isEqualTo("PENDING");
        assertThat(created.get("issues").isEmpty()).isTrue();
        assertThat(created.get("documentSha256").asText()).hasSize(64);
        originalUnchanged(); singleDataset();
        JsonNode approved=approve(created.get("id").asText(),200);
        UUID newDataset=UUID.fromString(approved.get("resultDatasetId").asText());
        Assessment after=recalls.getAssessment(caseId,UUID.fromString(approved.get("resultAssessmentId").asText()));
        assertThat(approved.get("status").asText()).isEqualTo("APPROVED");
        assertThat(approved.get("after").get("lotNumber").asText()).isEqualTo("A02");
        assertThat(after.datasetId()).isNotEqualTo(datasetId).isEqualTo(newDataset);
        var golden=json.readTree(Files.readString(SAMPLE.resolve("expected_summary.json"))).get("after");
        assertThat(after.inventoryTotals()).isEqualTo(new Totals(golden.get("inventory").get("TARGET").asLong(),golden.get("inventory").get("NON_TARGET").asLong(),golden.get("inventory").get("NEEDS_REVIEW").asLong()));
        assertThat(after.shipmentTotals()).isEqualTo(new Totals(golden.get("shipments").get("TARGET").asLong(),golden.get("shipments").get("NON_TARGET").asLong(),golden.get("shipments").get("NEEDS_REVIEW").asLong()));
        Map<String,Decision> receiptResults=new HashMap<>(); after.receipts().forEach(r->receiptResults.put(r.receiptId(),r.decision()));
        try (var reader=Files.newBufferedReader(SAMPLE.resolve("expected_receipt_decisions.csv")); var parser=CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get().parse(reader)) {
            for (var row:parser) assertThat(receiptResults.get(row.get("receipt_id")).name()).isEqualTo(row.get("after"));
        }
        Map<String,ShipmentImpact> shipmentResults=new HashMap<>(); after.shipments().forEach(s->shipmentResults.put(s.shipmentId(),s));
        try (var reader=Files.newBufferedReader(SAMPLE.resolve("expected_shipment_impacts.csv")); var parser=CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get().parse(reader)) {
            for (var row:parser) {
                var s=shipmentResults.get(row.get("shipment_id"));
                assertThat(s.target()).isEqualTo(Long.parseLong(row.get("after_target")));
                assertThat(s.nonTarget()).isEqualTo(Long.parseLong(row.get("after_non_target")));
                assertThat(s.needsReview()).isEqualTo(Long.parseLong(row.get("after_needs_review")));
            }
        }
        assertThat(shipmentResults.get("S2").unlinked()).isEqualTo(10);
        assertThat(jdbc.queryForList("SELECT id,shipment_id,receipt_id,product_id,quantity FROM shipment_allocation WHERE dataset_id=? ORDER BY id",newDataset))
            .isEqualTo(jdbc.queryForList("SELECT id,shipment_id,receipt_id,product_id,quantity FROM shipment_allocation WHERE dataset_id=? ORDER BY id",datasetId));
        assertThat(jdbc.queryForList("SELECT id,product_id,lot_number,expiry_date,received_quantity,received_at FROM receipt WHERE dataset_id=? AND id<>'R4' ORDER BY id",newDataset))
            .isEqualTo(jdbc.queryForList("SELECT id,product_id,lot_number,expiry_date,received_quantity,received_at FROM receipt WHERE dataset_id=? AND id<>'R4' ORDER BY id",datasetId));
        originalUnchanged();
        var oldDefinition=(NewCondition)recalls.getCondition(caseId,before.conditionId()).get("definition");
        var newDefinition=(NewCondition)recalls.getCondition(caseId,after.conditionId()).get("definition");
        assertThat(newDefinition.rule()).isEqualTo(oldDefinition.rule());
        assertThat(newDefinition.productReviews()).isEqualTo(oldDefinition.productReviews());
        mvc.perform(get(prefix()+"/"+created.get("id").asText())).andExpect(status().isOk()).andExpect(jsonPath("$.reviewedBy").value("test-reviewer"));
    }
    @Test void blocksMismatchedIdentityAndPartialQuantity() throws Exception {
        for (var change:Map.<String,Object>of("observedProductId","P2","observedReceivedQuantity",20,"observedReceivedAt","2026-09-05").entrySet()) {
            var body=proposal(); body.put(change.getKey(),change.getValue());
            var created=create(body);
            assertThat(created.get("issues").isEmpty()).isFalse();
            assertThat(approve(created.get("id").asText(),409).get("code").asText()).isEqualTo("EVIDENCE_BLOCKED");
        }
        singleDataset(); originalUnchanged();
    }
    @Test void blocksConflictingExistingValue() throws Exception {
        var body=proposal(); body.put("expiryDate","2026-11-30");
        body.put("documentText",body.get("documentText").toString().replace("2026-10-31","2026-11-30"));
        body.put("sourceQuote",body.get("sourceQuote").toString().replace("2026-10-31","2026-11-30"));
        var created=create(body);
        assertThat(created.get("issues").toString()).contains("EXISTING_VALUE_CONFLICT");
        approve(created.get("id").asText(),409); singleDataset(); originalUnchanged();
    }
    @Test void blocksUnsupportedQuoteOrValue() throws Exception {
        var body=proposal(); body.put("sourceQuote","원문에 없음");
        var created=create(body); approve(created.get("id").asText(),409);
        body=proposal(); body.put("lotNumber","Z99");
        created=create(body); approve(created.get("id").asText(),409);
        singleDataset();
    }
    @Test void requiresHumanConfirmationAndReviewer() throws Exception {
        var created=create(proposal()); String url=prefix()+"/"+created.get("id").asText()+"/approval";
        postJson(url,Map.of("reviewer","test","note","검토","receiptAndSingleLotConfirmed",false),400);
        postJson(url,Map.of("reviewer"," ","note","검토","receiptAndSingleLotConfirmed",true),400);
        singleDataset();
    }
    @Test void rejectionPreservesReasonAndBlocksLaterApproval() throws Exception {
        var created=create(proposal()); String id=created.get("id").asText();
        var rejected=postJson(prefix()+"/"+id+"/rejection",Map.of("reviewer","test","note","제조분이 혼합된 자료로 확인"),200);
        assertThat(rejected.get("status").asText()).isEqualTo("REJECTED");
        assertThat(rejected.get("reviewNote").asText()).contains("혼합");
        approve(id,409); singleDataset(); originalUnchanged();
    }
    @Test void wrongCaseOrReceiptCannotBeLinked() throws Exception {
        postJson("/api/v1/recalls/"+UUID.randomUUID()+"/evidence",proposal(),404);
        var body=proposal(); body.put("receiptId","R404"); postJson(prefix(),body,404);
        singleDataset();
    }
    @Test void duplicateConcurrentApprovalCreatesOnlyOneDerivedDataset() throws Exception {
        UUID id=UUID.fromString(create(proposal()).get("id").asText());
        var confirmation=new EvidenceModels.ApproveEvidence("test","입고 검토 완료",true);
        try (var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            Callable<Boolean> task=()->{
                start.await();
                try { evidence.approve(caseId,id,confirmation); return true; }
                catch (RecallService.Failure e) { assertThat(e.status.value()).isEqualTo(409); return false; }
            };
            var first=pool.submit(task); var second=pool.submit(task); start.countDown();
            assertThat(List.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dataset",Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM assessment_run",Long.class)).isEqualTo(2);
    }
    @Test void databaseFailureRollsBackCopyApprovalAndAssessment() throws Exception {
        UUID id=UUID.fromString(create(proposal()).get("id").asText());
        jdbc.execute("ALTER TABLE receipt_evidence ADD CONSTRAINT evidence_test_failure CHECK (status <> 'APPROVED')");
        try {
            assertThatThrownBy(()->evidence.approve(caseId,id,new EvidenceModels.ApproveEvidence("test","검토",true))).isInstanceOf(org.springframework.dao.DataAccessException.class);
            singleDataset(); originalUnchanged();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM assessment_run",Long.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM recall_condition",Long.class)).isEqualTo(1);
            assertThat(evidence.get(caseId,id).get("status")).isEqualTo("PENDING");
        } finally { jdbc.execute("ALTER TABLE receipt_evidence DROP CONSTRAINT evidence_test_failure"); }
    }
}
