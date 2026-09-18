package com.wecall.recall;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.LocalDate;
import java.util.*;
import static com.wecall.recall.EvidenceModels.*;
import static com.wecall.recall.RecallModels.*;

@Service
public class EvidenceService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final RecallService recalls;
    private final CaseGuard guard;
    public EvidenceService(JdbcTemplate jdbc,ObjectMapper json,RecallService recalls,CaseGuard guard) {
        this.jdbc=jdbc; this.json=json; this.recalls=recalls; this.guard=guard;
    }
    public static class Blocked extends RuntimeException {
        public final List<Issue> issues;
        public Blocked(List<Issue> issues) { super("증거 검토 항목을 해결해야 승인할 수 있습니다"); this.issues=issues; }
    }
    private void require(boolean valid,HttpStatus status,String message) {
        if (!valid) throw new RecallService.Failure(status,message);
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }
    private NewEvidence proposal(Map<String,Object> row) {
        try { return json.readValue(row.get("proposal").toString(),NewEvidence.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }
    private String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private Map<String,Object> evidence(UUID caseId,UUID id,boolean lock) {
        var rows=jdbc.queryForList("SELECT * FROM receipt_evidence WHERE case_id=? AND id=?"+(lock?" FOR UPDATE":""),caseId,id);
        require(!rows.isEmpty(),HttpStatus.NOT_FOUND,"해당 사건의 증거가 없습니다");
        return rows.getFirst();
    }
    private ReceiptFacts facts(UUID datasetId,String receiptId) {
        var rows=jdbc.query("SELECT product_id,received_quantity,received_at,lot_number,expiry_date FROM receipt WHERE dataset_id=? AND id=?",
            (rs,n)->new ReceiptFacts(rs.getString("product_id"),rs.getLong("received_quantity"),rs.getObject("received_at",LocalDate.class),rs.getString("lot_number"),rs.getObject("expiry_date",LocalDate.class)),datasetId,receiptId);
        require(!rows.isEmpty(),HttpStatus.NOT_FOUND,"기준 판정 데이터에 해당 입고 건이 없습니다");
        return rows.getFirst();
    }
    private List<Issue> issues(NewEvidence p,ReceiptFacts before) {
        List<Issue> result=new ArrayList<>();
        if (!before.productId().equals(p.observedProductId())) result.add(new Issue("observedProductId","PRODUCT_MISMATCH","입고 상품과 문서 상품이 다릅니다"));
        if (before.receivedQuantity()!=p.observedReceivedQuantity()) result.add(new Issue("observedReceivedQuantity","QUANTITY_MISMATCH","전체 입고 수량이 일치하지 않습니다. 부분/혼합 제조분은 자동 보완하지 않습니다"));
        if (!before.receivedAt().equals(p.observedReceivedAt())) result.add(new Issue("observedReceivedAt","DATE_MISMATCH","입고일이 일치하지 않습니다"));
        if (!p.documentText().contains(p.sourceQuote())) result.add(new Issue("sourceQuote","QUOTE_NOT_FOUND","원문에 인용문이 없습니다"));
        if (p.lotNumber()!=null) {
            if (!p.sourceQuote().contains(p.lotNumber())) result.add(new Issue("lotNumber","VALUE_NOT_IN_QUOTE","제조번호가 인용문에 없습니다"));
            if (before.lotNumber()!=null && !before.lotNumber().equals(p.lotNumber())) result.add(new Issue("lotNumber","EXISTING_VALUE_CONFLICT","기존 제조번호와 충돌합니다"));
        }
        if (p.expiryDate()!=null) {
            if (!p.sourceQuote().contains(p.expiryDate().toString())) result.add(new Issue("expiryDate","VALUE_NOT_IN_QUOTE","소비기한이 인용문에 없습니다"));
            if (before.expiryDate()!=null && !before.expiryDate().equals(p.expiryDate())) result.add(new Issue("expiryDate","EXISTING_VALUE_CONFLICT","기존 소비기한과 충돌합니다"));
        }
        if (!(before.lotNumber()==null && p.lotNumber()!=null) && !(before.expiryDate()==null && p.expiryDate()!=null))
            result.add(new Issue("proposal","NO_MISSING_VALUE_TO_FILL","보완할 누락값이 없습니다"));
        return List.copyOf(result);
    }
    @Transactional
    public Map<String,Object> create(UUID caseId,NewEvidence p) {
        guard.requireOpen(caseId);
        require(p.lotNumber()!=null || p.expiryDate()!=null,HttpStatus.BAD_REQUEST,"제조번호 또는 소비기한 보완값이 필요합니다");
        require(p.lotNumber()==null || (!p.lotNumber().isBlank() && p.lotNumber().equals(p.lotNumber().trim())),HttpStatus.BAD_REQUEST,"제조번호는 공백 없이 입력하세요");
        Assessment base=recalls.getAssessment(caseId,p.baseAssessmentId());
        facts(base.datasetId(),p.receiptId());
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO receipt_evidence(id,case_id,base_assessment_id,base_dataset_id,receipt_id,document_text,document_sha256,proposal) VALUES (?,?,?,?,?,?,?,?::jsonb)",
            id,caseId,p.baseAssessmentId(),base.datasetId(),p.receiptId(),p.documentText(),hash(p.documentText()),encode(p));
        return get(caseId,id);
    }
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String,Object> list(UUID caseId,String status,int page,int size) {
        require(page>=0 && size>=1 && size<=100,HttpStatus.BAD_REQUEST,"page는 0 이상, size는 1~100이어야 합니다");
        require(status.isEmpty() || Set.of("PENDING","APPROVED","REJECTED").contains(status),HttpStatus.BAD_REQUEST,"증거 상태 형식을 확인하세요");
        recalls.getCase(caseId);
        String where=" WHERE case_id=? AND (?='' OR status=?)";
        long total=jdbc.queryForObject("SELECT count(*) FROM receipt_evidence"+where,Long.class,caseId,status,status);
        var rows=jdbc.queryForList("SELECT id,receipt_id AS \"receiptId\",status,created_at AS \"createdAt\",reviewed_by AS \"reviewedBy\",result_assessment_id AS \"resultAssessmentId\" FROM receipt_evidence"+where+" ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",caseId,status,status,size,(long)page*size);
        return Map.of("items",rows,"page",page,"size",size,"totalElements",total,"totalPages",(total+size-1)/size);
    }
    public Map<String,Object> get(UUID caseId,UUID id) {
        var row=evidence(caseId,id,false);
        var p=proposal(row);
        var before=facts((UUID)row.get("base_dataset_id"),p.receiptId());
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("id",id); result.put("caseId",caseId); result.put("baseDatasetId",row.get("base_dataset_id"));
        result.put("proposal",p); result.put("documentSha256",row.get("document_sha256"));
        result.put("before",before); result.put("issues",issues(p,before)); result.put("status",row.get("status"));
        result.put("reviewedBy",row.get("reviewed_by")); result.put("reviewNote",row.get("review_note"));
        result.put("reviewedAt",row.get("reviewed_at")==null?null:row.get("reviewed_at").toString());
        result.put("resultDatasetId",row.get("result_dataset_id")); result.put("resultAssessmentId",row.get("result_assessment_id"));
        if (row.get("result_dataset_id")!=null) result.put("after",facts((UUID)row.get("result_dataset_id"),p.receiptId()));
        return result;
    }
    @Transactional
    public Map<String,Object> approve(UUID caseId,UUID id,ApproveEvidence approval) {
        guard.requireOpen(caseId);
        // Same lock order as condition version creation. The evidence lock prevents double application.
        recalls.getCase(caseId);
        jdbc.queryForList("SELECT id FROM recall_case WHERE id=? FOR UPDATE",caseId);
        var row=evidence(caseId,id,true);
        require(row.get("status").equals("PENDING"),HttpStatus.CONFLICT,"이미 검토한 증거입니다");
        require(approval.receiptAndSingleLotConfirmed(),HttpStatus.BAD_REQUEST,"입고 및 단일 제조분 확인이 필요합니다");
        NewEvidence p=proposal(row);
        UUID baseDatasetId=(UUID)row.get("base_dataset_id");
        var errors=issues(p,facts(baseDatasetId,p.receiptId()));
        if (!errors.isEmpty()) throw new Blocked(errors);
        Assessment base=recalls.getAssessment(caseId,p.baseAssessmentId());
        recalls.requireCurrentSource(caseId,base.conditionId());
        UUID derived=copyDataset(baseDatasetId,p);
        var original=(NewCondition)recalls.getCondition(caseId,base.conditionId()).get("definition");
        var next=new NewCondition(derived,original.rule(),original.productReviews(),original.sourceQuote());
        UUID conditionId=(UUID)recalls.createCondition(caseId,next).get("id");
        recalls.approve(caseId,conditionId,new Approval(approval.reviewer()));
        Assessment result=recalls.assess(caseId,conditionId);
        jdbc.update("UPDATE receipt_evidence SET status='APPROVED',reviewed_by=?,review_note=?,reviewed_at=now(),result_dataset_id=?,result_assessment_id=? WHERE id=?",
            approval.reviewer(),approval.note(),derived,result.id(),id);
        return get(caseId,id);
    }
    @Transactional
    public Map<String,Object> reject(UUID caseId,UUID id,RejectEvidence review) {
        guard.requireOpen(caseId);
        var row=evidence(caseId,id,true);
        require(row.get("status").equals("PENDING"),HttpStatus.CONFLICT,"이미 검토한 증거입니다");
        jdbc.update("UPDATE receipt_evidence SET status='REJECTED',reviewed_by=?,review_note=?,reviewed_at=now() WHERE id=?",review.reviewer(),review.note(),id);
        return get(caseId,id);
    }
    private UUID copyDataset(UUID base,NewEvidence p) {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO dataset(id,as_of) SELECT ?,as_of FROM dataset WHERE id=?",id,base);
        jdbc.update("INSERT INTO product(dataset_id,id,name,manufacturer,pack_size,unit) SELECT ?,id,name,manufacturer,pack_size,unit FROM product WHERE dataset_id=?",id,base);
        jdbc.update("INSERT INTO receipt(dataset_id,id,product_id,lot_number,expiry_date,received_quantity,received_at) SELECT ?,id,product_id,lot_number,expiry_date,received_quantity,received_at FROM receipt WHERE dataset_id=?",id,base);
        jdbc.update("UPDATE receipt SET lot_number=COALESCE(lot_number,?),expiry_date=COALESCE(expiry_date,?) WHERE dataset_id=? AND id=?",p.lotNumber(),p.expiryDate(),id,p.receiptId());
        jdbc.update("INSERT INTO inventory(dataset_id,id,receipt_id,warehouse,quantity,hold_status) SELECT ?,id,receipt_id,warehouse,quantity,hold_status FROM inventory WHERE dataset_id=?",id,base);
        jdbc.update("INSERT INTO shipment(dataset_id,id,order_id,product_id,quantity,shipped_at) SELECT ?,id,order_id,product_id,quantity,shipped_at FROM shipment WHERE dataset_id=?",id,base);
        jdbc.update("INSERT INTO shipment_allocation(dataset_id,id,shipment_id,receipt_id,product_id,quantity) SELECT ?,id,shipment_id,receipt_id,product_id,quantity FROM shipment_allocation WHERE dataset_id=?",id,base);
        return id;
    }
}
