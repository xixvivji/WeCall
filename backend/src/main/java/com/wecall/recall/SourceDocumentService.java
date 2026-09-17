package com.wecall.recall;

import com.wecall.attachment.*;
import com.wecall.auth.CurrentActor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;
import org.springframework.web.multipart.MultipartFile;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.IOException;
import java.util.*;

@Service
public class SourceDocumentService {
    private final JdbcTemplate jdbc;
    private final RecallService recalls;
    private final CaseGuard guard;
    private final SourcePdfController pdf;
    private final AttachmentStorage storage;
    private final MalwareScanner scanner;
    public SourceDocumentService(JdbcTemplate jdbc, RecallService recalls, CaseGuard guard, SourcePdfController pdf, AttachmentStorage storage, MalwareScanner scanner) {
        this.jdbc=jdbc; this.recalls=recalls; this.guard=guard; this.pdf=pdf; this.storage=storage; this.scanner=scanner;
    }
    public record Registration(@NotNull @Valid RecallModels.NewCase recall, @NotBlank @Pattern(regexp="[a-f0-9]{64}") String expectedSha256) {}
    public record Revision(@NotNull @Positive Long expectedVersion, @NotBlank @Size(max=100000) String sourceText, @NotBlank @Size(max=2000) String note) {}
    private RecallService.Failure fail(HttpStatus status,String message) { return new RecallService.Failure(status,message); }

    @Transactional
    public Map<String,Object> register(Registration request, MultipartFile file) {
        String filename=file.getOriginalFilename();
        if(filename==null || filename.isBlank() || filename.length()>200 || filename.contains("/") || filename.contains("\\") || filename.chars().anyMatch(Character::isISOControl))
            throw fail(HttpStatus.BAD_REQUEST,"파일명은 경로 없이 200자 이내로 지정하세요");
        var parsed=pdf.parse(file);
        String hash=AttachmentService.hash(parsed.bytes());
        if(!hash.equals(request.expectedSha256())) throw fail(HttpStatus.CONFLICT,"미리보기와 다른 PDF입니다. 파일을 다시 확인하세요");
        var recall=recalls.createCase(request.recall());
        UUID caseId=(UUID)recall.get("id"), documentId=UUID.randomUUID();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            public void afterCompletion(int status) {
                if(status!=STATUS_COMMITTED) try {storage.delete(documentId);} catch(IOException e) {
                    org.slf4j.LoggerFactory.getLogger(SourceDocumentService.class).error("Source document rollback cleanup failed for {}",documentId);
                }
            }
        });
        try { storage.write(documentId,parsed.bytes()); }
        catch(IOException e) { throw fail(HttpStatus.SERVICE_UNAVAILABLE,"PDF 원본 저장에 실패했습니다. 다시 시도하세요"); }
        jdbc.update("INSERT INTO source_document(id,case_id,filename,byte_size,sha256,extracted_text,pages,uploaded_by,scan_status,scan_engine,scanned_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            documentId,caseId,filename,parsed.bytes().length,hash,parsed.text(),parsed.pages(),CurrentActor.username(),parsed.scan().status(),parsed.scan().engine(),parsed.scan().scannedAt());
        event(documentId,"UPLOADED");
        return recall;
    }
    private void event(UUID id,String type) {
        jdbc.update("INSERT INTO source_document_event(id,document_id,event_type,actor) VALUES (?,?,?,?)",UUID.randomUUID(),id,type,CurrentActor.username());
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> history(UUID caseId,int page) {
        if(page<0 || page>100000) throw fail(HttpStatus.BAD_REQUEST,"페이지 범위를 확인하세요");
        var recall=recalls.getCase(caseId);
        var docs=jdbc.queryForList("SELECT id,filename,byte_size AS \"byteSize\",sha256,pages,uploaded_by AS \"uploadedBy\",scan_status AS \"scanStatus\",scan_engine AS \"scanEngine\",scanned_at AS \"scannedAt\",created_at AS \"createdAt\" FROM source_document WHERE case_id=?",caseId);
        var result=new LinkedHashMap<String,Object>();
        result.put("document",docs.isEmpty()?null:docs.getFirst());
        result.put("currentVersion",recall.get("sourceVersion"));
        result.put("items",jdbc.queryForList("SELECT version,actor,note,created_at AS \"createdAt\" FROM source_revision WHERE case_id=? ORDER BY version DESC LIMIT 20 OFFSET ?",caseId,(long)page*20));
        long total=jdbc.queryForObject("SELECT count(*) FROM source_revision WHERE case_id=?",Long.class,caseId);
        result.put("page",page); result.put("totalPages",(total+19)/20);
        return result;
    }
    public Map<String,Object> revision(UUID caseId,long version) {
        var rows=jdbc.queryForList("SELECT version,source_text AS \"sourceText\",actor,note,created_at AS \"createdAt\" FROM source_revision WHERE case_id=? AND version=?",caseId,version);
        if(rows.isEmpty()) throw fail(HttpStatus.NOT_FOUND,"원문 버전이 없습니다");
        var result=new LinkedHashMap<>(rows.getFirst());
        var texts=jdbc.queryForList("SELECT extracted_text FROM source_document WHERE case_id=?",caseId);
        result.put("extractedText",texts.isEmpty()?null:texts.getFirst().get("extracted_text"));
        return result;
    }
    @Transactional
    public Map<String,Object> revise(UUID caseId,Revision request) {
        guard.requireOpen(caseId);
        var recall=guard.lock(caseId);
        long current=((Number)recall.get("source_version")).longValue();
        if(current!=request.expectedVersion()) throw fail(HttpStatus.CONFLICT,"다른 사용자가 원문을 수정했습니다. 최신 버전을 조회하고 다시 검토하세요");
        if(request.sourceText().equals(recall.get("source_text"))) throw fail(HttpStatus.BAD_REQUEST,"원문 변경 내용이 없습니다");
        jdbc.update("INSERT INTO source_revision(case_id,version,source_text,actor,note) VALUES (?,?,?,?,?)",caseId,current+1,request.sourceText(),CurrentActor.username(),request.note());
        jdbc.update("UPDATE recall_case SET source_text=?,source_version=? WHERE id=?",request.sourceText(),current+1,caseId);
        return revision(caseId,current+1);
    }
    @Transactional
    public AttachmentService.Download download(UUID caseId,UUID id) {
        var rows=jdbc.queryForList("SELECT * FROM source_document WHERE case_id=? AND id=?",caseId,id);
        if(rows.isEmpty()) throw fail(HttpStatus.NOT_FOUND,"해당 사건의 PDF 원본이 없습니다");
        var row=rows.getFirst(); byte[] bytes;
        try {bytes=storage.read(id);} catch(IOException e) {throw fail(HttpStatus.SERVICE_UNAVAILABLE,"PDF 원본을 읽을 수 없습니다. 관리자에게 확인하세요");}
        if(bytes.length!=((Number)row.get("byte_size")).longValue() || !AttachmentService.hash(bytes).equals(row.get("sha256")))
            throw fail(HttpStatus.SERVICE_UNAVAILABLE,"PDF 원본 무결성 확인에 실패했습니다");
        scanner.scan(bytes); event(id,"DOWNLOAD_REQUESTED");
        return new AttachmentService.Download((String)row.get("filename"),"application/pdf",bytes);
    }
}
