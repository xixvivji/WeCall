package com.wecall;
import com.fasterxml.jackson.databind.*;
import com.wecall.dataset.DatasetService;
import com.wecall.recall.*;
import com.wecall.attachment.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import java.nio.file.*;
import java.io.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @Import(PostgresTestConfiguration.class)
@WithMockUser(username="reviewer",roles="REVIEWER")
class AttachmentTests {
    static final Path ROOT;
    static {try{ROOT=Files.createTempDirectory("wecall-attachment-tests-").toRealPath();}catch(IOException e){throw new ExceptionInInitializerError(e);}}
    @DynamicPropertySource static void config(DynamicPropertyRegistry r){r.add("wecall.attachments.directory",()->ROOT.toString());}
    @Autowired MockMvc mvc;@Autowired JdbcTemplate jdbc;@Autowired ObjectMapper json;@Autowired DatasetService datasets;@Autowired RecallService recalls;@Autowired TaskService tasks;@Autowired AttachmentService attachments;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean LocalAttachmentStorage storage;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean ClamdScanner scanner;
    UUID caseId,taskId;byte[] png;
    @BeforeEach void setup() throws Exception {
        jdbc.execute("TRUNCATE dataset,recall_case CASCADE");
        try(var files=Files.list(ROOT)){for(Path p:files.toList())Files.delete(p);}
        var image=new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB);var out=new ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",out);png=out.toByteArray();
        caseId=(UUID)recalls.createCase(new RecallModels.NewCase("첨부 합성 사건",RecallModels.SourceType.INTERNAL,Files.readString(Path.of("../samples/recall-001/notice.md")))).get("id");
        taskId=(UUID)tasks.create(caseId,new TaskModels.NewTask(TaskModels.TaskType.QUARANTINE,TaskModels.TargetType.CASE,null,null,"첨부 작업","합성 작업","operator","reviewer")).get("id");
        tasks.transition(caseId,taskId,new TaskModels.Transition(0L,TaskModels.Action.START,"reviewer","시작"));
    }
    @Test void sourcePdfRequiresReviewerAndCsrf() throws Exception {
        byte[] pdf=SourcePdfTests.pdf("Recall A01",1);
        mvc.perform(multipart("/api/v1/source-pdf").file(new MockMultipartFile("file","source.pdf","application/pdf",pdf)).with(csrf()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.text").value("Recall A01"));
        mvc.perform(multipart("/api/v1/source-pdf").file(new MockMultipartFile("file","source.pdf","application/pdf",pdf)).with(csrf()).with(user("operator").roles("OPERATOR")))
            .andExpect(status().isForbidden());
        mvc.perform(multipart("/api/v1/source-pdf").file(new MockMultipartFile("file","source.pdf","application/pdf",pdf)))
            .andExpect(status().isForbidden());
        assertThat(diskCount()).isZero();
    }
    String proofUrl(){return "/api/v1/recalls/"+caseId+"/tasks/"+taskId+"/proofs";}
    MockMultipartHttpServletRequestBuilder request(String name,byte[] bytes) throws Exception {
        var req=multipart(proofUrl()).file(new MockMultipartFile("metadata","","application/json",json.writeValueAsBytes(Map.of("expectedVersion",1,"evidenceText","합성 이미지 근거"))));
        req.file(new MockMultipartFile("files",name,"application/octet-stream",bytes));req.with(csrf());return req;
    }
    UUID onlyId(){return jdbc.queryForObject("SELECT id FROM evidence_attachment",UUID.class);}
    long diskCount() throws IOException {try(var files=Files.list(ROOT)){return files.count();}}
    @Test void scanFailuresRollbackProofTaskAndAllAttachments() throws Exception {
        for(var code:List.of(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)) {
            org.mockito.Mockito.doReturn(new MalwareScanner.Receipt("CLEAN","clamd",OffsetDateTime.now()))
                .doThrow(new RecallService.Failure(code,"검사 차단"))
                .when(scanner).scan(org.mockito.ArgumentMatchers.any(byte[].class));
            var req=request("one.png",png).file(new MockMultipartFile("files","two.png","image/png",png));
            mvc.perform(req).andExpect(status().is(code.value()));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM response_task_proof",Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM evidence_attachment",Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT version FROM response_task WHERE id=?",Long.class,taskId)).isEqualTo(1L);
            assertThat(diskCount()).isZero();
            org.mockito.Mockito.reset(scanner);
        }
    }
    @Test void cleanReceiptPersistsAndDownloadsMustPassANewScan() throws Exception {
        org.mockito.Mockito.doReturn(new MalwareScanner.Receipt("CLEAN","clamd",OffsetDateTime.now())).when(scanner).scan(org.mockito.ArgumentMatchers.any(byte[].class));
        mvc.perform(request("clean.png",png)).andExpect(status().isCreated());UUID id=onlyId();
        assertThat(jdbc.queryForObject("SELECT scan_status FROM evidence_attachment",String.class)).isEqualTo("CLEAN");
        assertThat(jdbc.queryForObject("SELECT scanned_at FROM evidence_attachment",OffsetDateTime.class)).isNotNull();
        org.mockito.Mockito.doThrow(new RecallService.Failure(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,"검사 차단")).when(scanner).scan(org.mockito.ArgumentMatchers.any(byte[].class));
        mvc.perform(get("/api/v1/recalls/"+caseId+"/attachments/"+id+"/download")).andExpect(status().isUnprocessableEntity());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM attachment_event WHERE event_type='DOWNLOAD_REQUESTED'",Long.class)).isZero();
        assertThat(diskCount()).isEqualTo(1);
    }
    @Test void assignedProofUploadDownloadAndAuditPreserveOriginalBytes() throws Exception {
        mvc.perform(request("합성.png",png).with(user("operator").roles("OPERATOR"))).andExpect(status().isCreated());
        UUID id=onlyId();
        assertThat(Files.getPosixFilePermissions(ROOT.resolve(id+".blob"))).isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        UUID proof=jdbc.queryForObject("SELECT proof_id FROM evidence_attachment",UUID.class);
        mvc.perform(get("/api/v1/recalls/"+caseId+"/attachments?proofId="+proof)).andExpect(status().isOk()).andExpect(jsonPath("$[0].uploadedBy").value("operator"));
        var response=mvc.perform(get("/api/v1/recalls/"+caseId+"/attachments/"+id+"/download").with(user("other").roles("OPERATOR")))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(header().string("X-Content-Type-Options","nosniff")).andReturn().getResponse();
        assertThat(response.getContentAsByteArray()).isEqualTo(png);assertThat(response.getHeader("Content-Disposition")).startsWith("attachment;");
        assertThat(jdbc.queryForObject("SELECT sha256 FROM evidence_attachment",String.class)).isEqualTo(AttachmentService.hash(png));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM attachment_event",Long.class)).isEqualTo(2);
        mvc.perform(get("/api/v1/recalls/"+UUID.randomUUID()+"/attachments/"+id+"/download")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/recalls/"+caseId+"/attachments/"+id+"/download").with(anonymous())).andExpect(status().isUnauthorized());
        Files.write(ROOT.resolve(id+".blob"),new byte[]{1});
        mvc.perform(get("/api/v1/recalls/"+caseId+"/attachments/"+id+"/download")).andExpect(status().isServiceUnavailable());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM attachment_event",Long.class)).isEqualTo(2);
    }
    @Test void invalidFilesAndUnauthorizedUploadsLeaveNoProofOrFile() throws Exception {
        for(String name:List.of("../secret.png","C:\\fake.png","fake.pdf","image.exe"))mvc.perform(request(name,png)).andExpect(status().isBadRequest());
        mvc.perform(request("empty.png",new byte[0])).andExpect(status().isBadRequest());
        mvc.perform(request("large.png",new byte[10485761])).andExpect(status().isPayloadTooLarge());
        var six=request("one.png",png);for(int i=0;i<5;i++)six.file(new MockMultipartFile("files","more.png","image/png",png));
        mvc.perform(six).andExpect(status().isBadRequest());
        mvc.perform(request("ok.png",png).with(user("other").roles("OPERATOR"))).andExpect(status().isForbidden());
        var noCsrf=multipart(proofUrl()).file(new MockMultipartFile("files","ok.png","image/png",png));
        mvc.perform(noCsrf).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM response_task_proof",Long.class)).isZero();assertThat(diskCount()).isZero();
    }
    @Test void databaseFailureRemovesWrittenFileAndRollsBackTaskVersion() throws Exception {
        jdbc.execute("ALTER TABLE evidence_attachment ADD CONSTRAINT reject_attachment_test CHECK (byte_size=1)");
        try{Assertions.assertThrows(Exception.class,()->mvc.perform(request("ok.png",png)));}
        finally{jdbc.execute("ALTER TABLE evidence_attachment DROP CONSTRAINT reject_attachment_test");}
        assertThat(diskCount()).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM evidence_attachment",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM response_task_proof",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT version FROM response_task WHERE id=?",Long.class,taskId)).isEqualTo(1);
    }
    @Test void storageFailureRollsBackProofAndMetadata() throws Exception {
        org.mockito.Mockito.doThrow(new IOException("synthetic storage failure")).when(storage).write(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        mvc.perform(request("ok.png",png)).andExpect(status().isServiceUnavailable());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM response_task_proof",Long.class)).isZero();assertThat(diskCount()).isZero();
    }
    @Test void closedCaseAndStaleVersionCannotCreateAttachments() throws Exception {
        tasks.assign(caseId,taskId,new TaskModels.Assignment(1L,"operator","reviewer","확인"));
        mvc.perform(request("ok.png",png)).andExpect(status().isConflict());
        jdbc.update("UPDATE recall_case SET status='CLOSED',closed_at=now() WHERE id=?",caseId);
        mvc.perform(request("ok.png",png)).andExpect(status().isConflict());assertThat(diskCount()).isZero();
    }
    @Test void parsersAcceptPdfAndJpegAndRejectEncryptedPdf() throws Exception {
        byte[] pdf;try(var doc=new org.apache.pdfbox.pdmodel.PDDocument()){doc.addPage(new org.apache.pdfbox.pdmodel.PDPage());var out=new ByteArrayOutputStream();doc.save(out);pdf=out.toByteArray();}
        assertThat(attachments.validate(List.of(new MockMultipartFile("files","sample.pdf","image/png",pdf))).getFirst().mediaType()).isEqualTo("application/pdf");
        var image=new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB);var out=new ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"jpeg",out);
        assertThat(attachments.validate(List.of(new MockMultipartFile("files","sample.jpg","text/plain",out.toByteArray()))).getFirst().mediaType()).isEqualTo("image/jpeg");
        try(var doc=new org.apache.pdfbox.pdmodel.PDDocument()){doc.addPage(new org.apache.pdfbox.pdmodel.PDPage());doc.protect(new org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy("owner","user",new org.apache.pdfbox.pdmodel.encryption.AccessPermission()));out=new ByteArrayOutputStream();doc.save(out);}
        byte[] encrypted=out.toByteArray();assertThatThrownBy(()->attachments.validate(List.of(new MockMultipartFile("files","secret.pdf","application/pdf",encrypted)))).isInstanceOf(RecallService.Failure.class);
    }
    @Test void receiptEvidenceAndFilesAreCreatedAtomically() throws Exception {
        var uploads=new HashMap<String,org.springframework.web.multipart.MultipartFile>();
        for(String type:List.of("products","receipts","inventory","shipments","shipment_allocations"))uploads.put(type,new MockMultipartFile(type,Files.readAllBytes(Path.of("../samples/recall-001/"+type+".csv"))));
        UUID dataset=(UUID)datasets.importFiles(OffsetDateTime.parse("2026-09-09T18:00:00+09:00"),uploads).get("datasetId");
        var condition=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(Files.readString(Path.of("../samples/recall-001/condition-request.json")));condition.put("datasetId",dataset.toString());
        UUID conditionId=(UUID)recalls.createCondition(caseId,json.treeToValue(condition,RecallModels.NewCondition.class)).get("id");recalls.approve(caseId,conditionId,new RecallModels.Approval("reviewer"));
        var run=recalls.assess(caseId,conditionId);
        var body=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(Files.readString(Path.of("../samples/recall-001/evidence-request.json")));body.put("baseAssessmentId",run.id().toString());
        var req=multipart("/api/v1/recalls/"+caseId+"/evidence").file(new MockMultipartFile("metadata","","application/json",json.writeValueAsBytes(body))).file(new MockMultipartFile("files","receipt.png","image/png",png));req.with(csrf()).with(user("operator").roles("OPERATOR"));
        mvc.perform(req).andExpect(status().isCreated());assertThat(jdbc.queryForObject("SELECT evidence_id IS NOT NULL AND proof_id IS NULL FROM evidence_attachment",Boolean.class)).isTrue();
    }
}
