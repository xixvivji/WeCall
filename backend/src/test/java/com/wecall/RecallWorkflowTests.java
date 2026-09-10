package com.wecall;

import com.fasterxml.jackson.databind.*;
import com.wecall.dataset.DatasetService;
import org.apache.commons.csv.CSVFormat;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.file.*;
import java.time.OffsetDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @Import(PostgresTestConfiguration.class)
class RecallWorkflowTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired DatasetService datasets;
    @Autowired JdbcTemplate jdbc;
    UUID datasetId;
    String caseId;
    static final Path SAMPLE=Path.of("../samples/recall-001");
    static final String QUOTE="제조번호 A01 또는 A02이면서 소비기한이 2026-10-31인 제품";
    @BeforeEach void setup() throws Exception {
        jdbc.execute("TRUNCATE recall_case, dataset CASCADE");
        Map<String,org.springframework.web.multipart.MultipartFile> files=new HashMap<>();
        for (String name:List.of("products","receipts","inventory","shipments","shipment_allocations"))
            files.put(name,new MockMultipartFile(name,Files.readAllBytes(SAMPLE.resolve(name+".csv"))));
        datasetId=(UUID)datasets.importFiles(OffsetDateTime.parse("2026-09-09T18:00:00+09:00"),files).get("datasetId");
        caseId=postJson("/api/v1/recalls",Map.of("title","샘플 회수","sourceType","SUPPLIER","sourceText",Files.readString(SAMPLE.resolve("notice.md"))),201).get("id").asText();
    }
    JsonNode postJson(String path,Object value,int status) throws Exception {
        var response=mvc.perform(post(path).contentType("application/json").content(json.writeValueAsBytes(value)))
            .andExpect(status().is(status)).andReturn().getResponse();
        return json.readTree(response.getContentAsByteArray());
    }
    Map<String,Object> condition() {
        return new LinkedHashMap<>(Map.of("datasetId",datasetId,"sourceQuote",QUOTE,
            "productReviews",Map.of("P1",Map.of("status","MATCHED","reason","제조사·규격 일치"),"P2",Map.of("status","EXCLUDED","reason","규격 다름"),"P3",Map.of("status","EXCLUDED","reason","제조사 다름")),
            "rule",Map.of("op","AND","children",List.of(Map.of("op","IN","field","LOT_NUMBER","values",List.of("A01","A02")),Map.of("op","EQ","field","EXPIRY_DATE","values",List.of("2026-10-31"))))));
    }
    String createCondition(Map<String,Object> request) throws Exception {
        return postJson("/api/v1/recalls/"+caseId+"/conditions",request,201).get("id").asText();
    }
    void approve(String conditionId) throws Exception {
        postJson("/api/v1/recalls/"+caseId+"/conditions/"+conditionId+"/approval",Map.of("reviewer","local-test-reviewer"),200);
    }
    JsonNode run(String conditionId,int status) throws Exception {
        return postJson("/api/v1/recalls/"+caseId+"/assessments",Map.of("conditionId",conditionId),status);
    }
    @Test void sampleMatchesManuallyAuthoredGoldenFilesAndPersistsResult() throws Exception {
        String conditionId=createCondition(condition()); approve(conditionId);
        JsonNode result=run(conditionId,201);
        Map<String,String> actual=new HashMap<>();
        result.get("receipts").forEach(r->actual.put(r.get("receiptId").asText(),r.get("decision").asText()));
        try (var reader=Files.newBufferedReader(SAMPLE.resolve("expected_receipt_decisions.csv"));
             var parser=CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get().parse(reader)) {
            for (var row:parser) assertThat(actual.get(row.get("receipt_id"))).isEqualTo(row.get("before"));
        }
        Map<String,JsonNode> shipments=new HashMap<>();
        result.get("shipments").forEach(s->shipments.put(s.get("shipmentId").asText(),s));
        try (var reader=Files.newBufferedReader(SAMPLE.resolve("expected_shipment_impacts.csv"));
             var parser=CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get().parse(reader)) {
            for (var row:parser) {
                var s=shipments.get(row.get("shipment_id"));
                assertThat(s.get("target").asLong()).isEqualTo(Long.parseLong(row.get("before_target")));
                assertThat(s.get("nonTarget").asLong()).isEqualTo(Long.parseLong(row.get("before_non_target")));
                assertThat(s.get("needsReview").asLong()).isEqualTo(Long.parseLong(row.get("before_needs_review")));
            }
        }
        var golden=json.readTree(Files.readString(SAMPLE.resolve("expected_summary.json"))).get("before");
        for (String part:List.of("inventory","shipments")) {
            var totals=result.get(part.equals("inventory")?"inventoryTotals":"shipmentTotals");
            assertThat(totals.get("target").asLong()).isEqualTo(golden.get(part).get("TARGET").asLong());
            assertThat(totals.get("nonTarget").asLong()).isEqualTo(golden.get(part).get("NON_TARGET").asLong());
            assertThat(totals.get("needsReview").asLong()).isEqualTo(golden.get(part).get("NEEDS_REVIEW").asLong());
        }
        assertThat(shipments.get("S2").get("unlinked").asLong()).isEqualTo(10);
        var stored=mvc.perform(get("/api/v1/recalls/"+caseId+"/assessments/"+result.get("id").asText()))
            .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(json.readTree(stored.getContentAsByteArray())).isEqualTo(result);
    }
    @Test void requiresApprovalAndRejectsRepeatApproval() throws Exception {
        String id=createCondition(condition()); run(id,409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM assessment_run",Long.class)).isZero();
        approve(id);
        postJson("/api/v1/recalls/"+caseId+"/conditions/"+id+"/approval",Map.of("reviewer","other"),409);
    }
    @Test void unreviewedProductsRemainUnknown() throws Exception {
        var body=condition(); body.put("productReviews",Map.of("P1",Map.of("status","MATCHED","reason","일치")));
        String id=createCondition(body); approve(id);
        JsonNode result=run(id,201);
        for (JsonNode receipt:result.get("receipts")) if (Set.of("R6","R7").contains(receipt.get("receiptId").asText())) {
            assertThat(receipt.get("decision").asText()).isEqualTo("NEEDS_REVIEW");
            assertThat(receipt.get("reason").asText()).isEqualTo("PRODUCT_NOT_REVIEWED");
        }
    }
    @Test void newVersionDoesNotChangeOldAssessment() throws Exception {
        String first=createCondition(condition()); approve(first); JsonNode old=run(first,201);
        var body=condition(); body.put("rule",Map.of("op","EQ","field","LOT_NUMBER","values",List.of("A03")));
        String second=createCondition(body); approve(second); run(second,201);
        var response=mvc.perform(get("/api/v1/recalls/"+caseId+"/assessments/"+old.get("id").asText())).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(json.readTree(response.getContentAsByteArray())).isEqualTo(old);
        assertThat(jdbc.queryForObject("SELECT max(version) FROM recall_condition",Integer.class)).isEqualTo(2);
    }
    @Test void rejectsWrongCaseAndUnsupportedCondition() throws Exception {
        String id=createCondition(condition()); approve(id);
        postJson("/api/v1/recalls/"+UUID.randomUUID()+"/assessments",Map.of("conditionId",id),404);
        var body=condition(); body.put("rule",Map.of("op","SQL","field","LOT_NUMBER","values",List.of("A01")));
        postJson("/api/v1/recalls/"+caseId+"/conditions",body,400);
    }
    @Test void rejectsUnknownProductsAndInventedQuote() throws Exception {
        var body=condition(); body.put("sourceQuote","원문에 없는 문장");
        postJson("/api/v1/recalls/"+caseId+"/conditions",body,400);
        body=condition(); body.put("productReviews",Map.of("MISSING",Map.of("status","MATCHED","reason","일치")));
        postJson("/api/v1/recalls/"+caseId+"/conditions",body,400);
    }
    @Test void rejectsInvalidPathId() throws Exception {
        mvc.perform(get("/api/v1/recalls/not-a-uuid")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_ID"));
    }
    @Test void rejectsBlankApprovalAndMissingDefinition() throws Exception {
        String id=createCondition(condition());
        postJson("/api/v1/recalls/"+caseId+"/conditions/"+id+"/approval",Map.of("reviewer"," "),400);
        var body=condition(); body.remove("rule");
        postJson("/api/v1/recalls/"+caseId+"/conditions",body,400);
    }
}
