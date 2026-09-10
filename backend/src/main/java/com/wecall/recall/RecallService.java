package com.wecall.recall;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import static com.wecall.recall.RecallModels.*;

@Service
public class RecallService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public RecallService(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc=jdbc; this.json=json; }
    public static class Failure extends RuntimeException {
        public final HttpStatus status;
        public Failure(HttpStatus status, String message) { super(message); this.status=status; }
    }
    private void ensure(boolean valid, HttpStatus status, String message) {
        if (!valid) throw new Failure(status,message);
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot serialize recall data",e); }
    }
    private <T> T decode(Object value, Class<T> type) {
        try { return json.readValue(value.toString(),type); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot read stored recall data",e); }
    }
    private Map<String,Object> one(String sql, Object... params) {
        var rows=jdbc.queryForList(sql,params);
        ensure(!rows.isEmpty(),HttpStatus.NOT_FOUND,"요청한 사건·조건·판정 결과가 없습니다");
        return rows.getFirst();
    }
    @Transactional
    public Map<String,Object> createCase(NewCase request) {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO recall_case(id,title,source_type,source_text) VALUES (?,?,?,?)",id,request.title(),request.sourceType().name(),request.sourceText());
        return getCase(id);
    }
    public Map<String,Object> getCase(UUID id) {
        var row=one("SELECT * FROM recall_case WHERE id=?",id);
        return Map.of("id",id,"title",row.get("title"),"sourceType",row.get("source_type"),"sourceText",row.get("source_text"),
            "createdAt",row.get("created_at").toString(),"conditions",jdbc.queryForList("SELECT id,version,status FROM recall_condition WHERE case_id=? ORDER BY version",id));
    }
    @Transactional
    public Map<String,Object> createCondition(UUID caseId, NewCondition request) {
        // Serialize version allocation within the case; definitions are insert-only.
        var recall=one("SELECT * FROM recall_case WHERE id=? FOR UPDATE",caseId);
        ensure(jdbc.queryForObject("SELECT count(*) FROM dataset WHERE id=?",Long.class,request.datasetId())==1,
            HttpStatus.NOT_FOUND,"데이터 버전이 없습니다");
        try { RuleEngine.validate(request.rule()); }
        catch (IllegalArgumentException e) { throw new Failure(HttpStatus.BAD_REQUEST,e.getMessage()); }
        ensure(recall.get("source_text").toString().contains(request.sourceQuote()),HttpStatus.BAD_REQUEST,"근거 인용문이 사건 원문에 존재하지 않습니다");
        Set<String> products=new HashSet<>(jdbc.queryForList("SELECT id FROM product WHERE dataset_id=?",String.class,request.datasetId()));
        ensure(products.containsAll(request.productReviews().keySet()),HttpStatus.BAD_REQUEST,"검토 상품이 지정 데이터 버전에 없습니다");
        ensure(request.productReviews().values().stream().anyMatch(r->r.status()==Match.MATCHED),HttpStatus.BAD_REQUEST,"최소 한 상품의 연결 확인이 필요합니다");
        UUID id=UUID.randomUUID();
        int version=jdbc.queryForObject("SELECT COALESCE(max(version),0)+1 FROM recall_condition WHERE case_id=?",Integer.class,caseId);
        jdbc.update("INSERT INTO recall_condition(id,case_id,dataset_id,version,definition) VALUES (?,?,?,?,?::jsonb)",id,caseId,request.datasetId(),version,encode(request));
        return getCondition(caseId,id);
    }
    public Map<String,Object> getCondition(UUID caseId, UUID conditionId) {
        return conditionResponse(one("SELECT * FROM recall_condition WHERE case_id=? AND id=?",caseId,conditionId));
    }
    private Map<String,Object> conditionResponse(Map<String,Object> row) {
        Map<String,Object> response=new LinkedHashMap<>();
        response.put("id",row.get("id")); response.put("caseId",row.get("case_id"));
        response.put("version",row.get("version")); response.put("status",row.get("status"));
        response.put("definition",decode(row.get("definition"),NewCondition.class));
        response.put("approvedBy",row.get("approved_by"));
        response.put("approvedAt",row.get("approved_at")==null?null:row.get("approved_at").toString());
        return response;
    }
    @Transactional
    public Map<String,Object> approve(UUID caseId, UUID conditionId, Approval request) {
        var row=one("SELECT * FROM recall_condition WHERE case_id=? AND id=? FOR UPDATE",caseId,conditionId);
        ensure(row.get("status").equals("DRAFT"),HttpStatus.CONFLICT,"이미 승인된 조건입니다. 변경은 새 조건 버전을 생성하세요");
        jdbc.update("UPDATE recall_condition SET status='APPROVED',approved_by=?,approved_at=now() WHERE id=?",request.reviewer(),conditionId);
        return getCondition(caseId,conditionId);
    }
    @Transactional
    public Assessment assess(UUID caseId, UUID conditionId) {
        var condition=one("SELECT * FROM recall_condition WHERE case_id=? AND id=?",caseId,conditionId);
        ensure(condition.get("status").equals("APPROVED"),HttpStatus.CONFLICT,"조건 승인 후 판정할 수 있습니다");
        NewCondition definition=decode(condition.get("definition"),NewCondition.class);
        UUID datasetId=definition.datasetId();
        Map<String,ReceiptDecision> decisions=new LinkedHashMap<>();
        jdbc.query("SELECT * FROM receipt WHERE dataset_id=? ORDER BY id",rs->{
            String receipt=rs.getString("id"), product=rs.getString("product_id");
            ProductReview review=definition.productReviews().get(product);
            Decision state;
            String reason;
            if (review==null) { state=Decision.NEEDS_REVIEW; reason="PRODUCT_NOT_REVIEWED"; }
            else if (review.status()==Match.EXCLUDED) { state=Decision.NON_TARGET; reason="PRODUCT_EXCLUDED"; }
            else {
                var result=RuleEngine.evaluate(definition.rule(),rs.getString("lot_number"),rs.getObject("expiry_date",LocalDate.class));
                state=switch(result) { case TRUE->Decision.TARGET; case FALSE->Decision.NON_TARGET; case UNKNOWN->Decision.NEEDS_REVIEW; };
                reason=switch(result) { case TRUE->"CONDITION_MATCHED"; case FALSE->"CONDITION_NOT_MATCHED"; case UNKNOWN->"REQUIRED_FACT_MISSING"; };
            }
            decisions.put(receipt,new ReceiptDecision(receipt,product,state,reason));
        },datasetId);
        List<InventoryImpact> inventory=jdbc.query("SELECT * FROM inventory WHERE dataset_id=? ORDER BY id",(rs,n)->
            new InventoryImpact(rs.getString("id"),rs.getString("receipt_id"),rs.getString("warehouse"),rs.getLong("quantity"),rs.getString("hold_status"),decisions.get(rs.getString("receipt_id")).decision()),datasetId);
        Map<String,long[]> allocationTotals=new HashMap<>();
        jdbc.query("SELECT * FROM shipment_allocation WHERE dataset_id=?",rs->{
            var totals=allocationTotals.computeIfAbsent(rs.getString("shipment_id"),id->new long[3]);
            totals[decisions.get(rs.getString("receipt_id")).decision().ordinal()]+=rs.getLong("quantity");
        },datasetId);
        List<ShipmentImpact> shipments=jdbc.query("SELECT * FROM shipment WHERE dataset_id=? ORDER BY id",(rs,n)->{
            long[] totals=allocationTotals.getOrDefault(rs.getString("id"),new long[3]);
            long quantity=rs.getLong("quantity");
            long unlinked=quantity-totals[0]-totals[1]-totals[2];
            ensure(unlinked>=0,HttpStatus.CONFLICT,"입력 데이터의 연결 수량이 출고량을 초과합니다");
            return new ShipmentImpact(rs.getString("id"),rs.getString("order_id"),quantity,totals[0],totals[1],totals[2]+unlinked,unlinked);
        },datasetId);
        long[] stockTotals=new long[3];
        inventory.forEach(i->stockTotals[i.decision().ordinal()]+=i.quantity());
        var result=new Assessment(UUID.randomUUID(),caseId,conditionId,datasetId,List.copyOf(decisions.values()),inventory,shipments,
            new Totals(stockTotals[0],stockTotals[1],stockTotals[2]),
            new Totals(shipments.stream().mapToLong(ShipmentImpact::target).sum(),shipments.stream().mapToLong(ShipmentImpact::nonTarget).sum(),shipments.stream().mapToLong(ShipmentImpact::needsReview).sum()));
        jdbc.update("INSERT INTO assessment_run(id,case_id,condition_id,dataset_id,result) VALUES (?,?,?,?,?::jsonb)",result.id(),caseId,conditionId,datasetId,encode(result));
        return result;
    }
    public Assessment getAssessment(UUID caseId,UUID runId) {
        return decode(one("SELECT result FROM assessment_run WHERE case_id=? AND id=?",caseId,runId).get("result"),Assessment.class);
    }
}
