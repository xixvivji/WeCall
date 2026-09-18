package com.wecall.recall;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.*;
import static com.wecall.recall.ClosureModels.*;

@Service
public class ClosureService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final RecallService recalls;
    private final CaseGuard guard;
    private final ConditionSourceService source;
    public ClosureService(JdbcTemplate jdbc,ObjectMapper json,RecallService recalls,CaseGuard guard,ConditionSourceService source) {
        this.jdbc=jdbc;this.json=json;this.recalls=recalls;this.guard=guard;this.source=source;
    }
    public static class Blocked extends RuntimeException {
        public final Map<String,Object> check;
        public Blocked(Map<String,Object> check) { super("종료 전 확인할 항목이 남아 있습니다");this.check=check; }
    }
    private void require(boolean valid,String message) {
        if (!valid) throw new RecallService.Failure(HttpStatus.CONFLICT,message);
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); } catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }
    private long count(String sql,Object... args) { return jdbc.queryForObject(sql,Long.class,args); }
    private void issue(List<Issue> issues,String code,String message,long count) {
        if (count>0) issues.add(new Issue(code,message,count));
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> check(UUID caseId,UUID assessmentId) {
        var caseRows=jdbc.queryForList("SELECT * FROM recall_case WHERE id=?",caseId);
        if (caseRows.isEmpty()) throw new RecallService.Failure(HttpStatus.NOT_FOUND,"사건이 없습니다");
        var state=caseRows.getFirst();
        List<Issue> blockers=new ArrayList<>(),warnings=new ArrayList<>();
        issue(blockers,"CASE_CLOSED","이미 종료된 사건입니다",state.get("status").equals("CLOSED")?1:0);
        var latest=jdbc.queryForList("SELECT id,status,version FROM recall_condition WHERE case_id=? AND status<>'WITHDRAWN' ORDER BY version DESC LIMIT 1",caseId);
        issue(blockers,"NO_CONDITION","승인할 회수 조건이 없습니다",latest.isEmpty()?1:0);
        issue(blockers,"PENDING_CONDITIONS","미승인 조건 버전이 남아 있습니다",count("SELECT count(*) FROM recall_condition WHERE case_id=? AND status='DRAFT'",caseId));
        issue(blockers,"NO_ASSESSMENT","종료 기준 판정 ID를 지정하세요",assessmentId==null?1:0);
        Set<UUID> reviewNeeded=new HashSet<>();
        Map<String,Object> latestSource=latest.isEmpty()?null:source.state(caseId,(UUID)latest.getFirst().get("id"));
        if(latestSource!=null && (boolean)latestSource.get("required"))reviewNeeded.add((UUID)latest.getFirst().get("id"));
        Map<String,Object> assessmentSource=null;
        Object assessedSourceVersion=null;
        long targetQuantity=0;
        Object totals=null;
        if (assessmentId!=null) {
            var run=recalls.getAssessment(caseId,assessmentId);
            assessmentSource=source.state(caseId,run.conditionId());
            assessedSourceVersion=jdbc.queryForObject("SELECT source_version FROM assessment_run WHERE id=?",Long.class,assessmentId);
            if((boolean)assessmentSource.get("required"))reviewNeeded.add(run.conditionId());
            issue(blockers,"OUTDATED_ASSESSMENT","가장 최근 조건 버전의 판정을 사용해야 합니다",!latest.isEmpty() && !latest.getFirst().get("id").equals(run.conditionId())?1:0);
            issue(blockers,"UNRESOLVED_RECEIPTS","확인 필요 입고 판정이 남아 있습니다",run.receipts().stream().filter(r->r.decision()==RecallModels.Decision.NEEDS_REVIEW).count());
            issue(blockers,"UNRESOLVED_SHIPMENTS","확인 필요 출고 수량이 남아 있습니다",run.shipmentTotals().needsReview());
            totals=Map.of("inventory",run.inventoryTotals(),"shipments",run.shipmentTotals());
            targetQuantity=run.inventoryTotals().target()+run.shipmentTotals().target();
        }
        issue(blockers,"SOURCE_REVIEW_REQUIRED","현재 원문 기준으로 조건·판정 근거를 재검토하세요",reviewNeeded.size());
        issue(blockers,"UNREVIEWED_EXTRACTIONS","진행 중이거나 검토하지 않은 조건 분석이 남아 있습니다",count("SELECT count(*) FROM extraction_job WHERE case_id=? AND review_status='PENDING'",caseId));
        var tasks=jdbc.queryForList("SELECT id,status,version,review_round,assessment_id FROM response_task WHERE case_id=? ORDER BY id",caseId);
        long incomplete=tasks.stream().filter(t->Set.of("OPEN","IN_PROGRESS").contains(t.get("status"))).count();
        long completed=tasks.stream().filter(t->t.get("status").equals("COMPLETED")).count();
        issue(blockers,"INCOMPLETE_TASKS","미처리 작업이 남아 있습니다",incomplete);
        issue(blockers,"NO_COMPLETED_RESPONSE","대상 재고·출고가 있지만 완료한 대응 작업이 없습니다",targetQuantity>0 && completed==0?1:0);
        issue(blockers,"PENDING_RECEIPT_EVIDENCE","미검토 입고 증거가 남아 있습니다",count("SELECT count(*) FROM receipt_evidence WHERE case_id=? AND status='PENDING'",caseId));
        issue(blockers,"PENDING_TASK_PROOFS","취소 작업을 포함하여 미검토 처리 증빙이 남아 있습니다",count("SELECT count(*) FROM response_task_proof p JOIN response_task t ON t.id=p.task_id WHERE t.case_id=? AND p.status='PENDING'",caseId));
        issue(warnings,"CANCELLED_TASKS","취소 사유와 남은 대응 범위를 최종 검토하세요",tasks.stream().filter(t->t.get("status").equals("CANCELLED")).count());
        issue(warnings,"OTHER_ASSESSMENT_TASKS","다른 판정에 연결된 작업이 현재 범위에도 충분한지 확인하세요",tasks.stream().filter(t->t.get("assessment_id")!=null && !Objects.equals(t.get("assessment_id"),assessmentId)).count());
        issue(warnings,"CASE_SCOPE_TASKS","사건 단위 작업이 실제 대상 범위를 모두 포함하는지 확인하세요",tasks.stream().filter(t->t.get("assessment_id")==null).count());
        // Preserve precise reviewed IDs/versions, not only aggregate counts.
        var receiptEvidence=jdbc.queryForList("SELECT id,status,reviewed_by,review_note,result_assessment_id FROM receipt_evidence WHERE case_id=? ORDER BY id",caseId);
        var proofs=jdbc.queryForList("SELECT p.id,p.task_id,p.review_round,p.status,p.reviewed_by,p.review_note FROM response_task_proof p JOIN response_task t ON t.id=p.task_id WHERE t.case_id=? ORDER BY p.id",caseId);
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("caseId",caseId);result.put("status",state.get("status"));result.put("version",state.get("lifecycle_version"));
        result.put("assessmentId",assessmentId);result.put("latestCondition",latest.isEmpty()?null:latest.getFirst());result.put("totals",totals);
        result.put("ready",blockers.isEmpty());result.put("blockers",blockers);result.put("warnings",warnings);
        result.put("tasks",tasks);result.put("receiptEvidence",receiptEvidence);result.put("taskProofs",proofs);
        result.put("extractions",jdbc.queryForList("SELECT id,status,review_status,condition_id,reviewed_by,review_note FROM extraction_job WHERE case_id=? ORDER BY id",caseId));
        result.put("sourceVersion",state.get("source_version"));result.put("latestConditionSource",latestSource);
        result.put("assessmentConditionSource",assessmentSource);result.put("assessmentSourceVersion",assessedSourceVersion);
        result.put("coverageConfirmationRequired",true);
        return result;
    }
    @Transactional
    public Map<String,Object> close(UUID caseId,CloseRequest body) {
        var row=guard.lock(caseId);
        require(row.get("status").equals("OPEN"),"이미 종료된 사건입니다");
        require(((Number)row.get("lifecycle_version")).longValue()==body.expectedVersion(),"사건 상태가 변경되었습니다. 다시 조회하세요");
        require(body.responseCoverageConfirmed(),"대상 범위와 대응 이력의 최종 검토가 필요합니다");
        var report=check(caseId,body.assessmentId());
        if (!(boolean)report.get("ready")) throw new Blocked(report);
        long version=body.expectedVersion()+1;
        jdbc.update("UPDATE recall_case SET status='CLOSED',lifecycle_version=?,closed_at=now() WHERE id=?",version,caseId);
        event(caseId,version,"CLOSED",body.assessmentId(),body.reviewer(),body.note(),Map.of("check",report,"responseCoverageConfirmed",true));
        return lifecycle(caseId);
    }
    @Transactional
    public Map<String,Object> reopen(UUID caseId,ReopenRequest body) {
        var row=guard.lock(caseId);
        require(row.get("status").equals("CLOSED"),"종료된 사건만 재개할 수 있습니다");
        require(((Number)row.get("lifecycle_version")).longValue()==body.expectedVersion(),"사건 상태가 변경되었습니다. 다시 조회하세요");
        long version=body.expectedVersion()+1;
        jdbc.update("UPDATE recall_case SET status='OPEN',lifecycle_version=?,closed_at=NULL WHERE id=?",version,caseId);
        event(caseId,version,"REOPENED",null,body.reviewer(),body.note(),Map.of("previousVersion",body.expectedVersion(),"previousClosedAt",row.get("closed_at").toString()));
        return lifecycle(caseId);
    }
    private void event(UUID caseId,long version,String type,UUID assessmentId,String reviewer,String note,Object snapshot) {
        jdbc.update("INSERT INTO case_lifecycle_event(id,case_id,version,event_type,assessment_id,reviewer,note,snapshot) VALUES (?,?,?,?,?,?,?,?::jsonb)",UUID.randomUUID(),caseId,version,type,assessmentId,reviewer,note,encode(snapshot));
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> lifecycle(UUID caseId) {
        var result=new LinkedHashMap<>(recalls.getCase(caseId));
        result.put("history",jdbc.query("SELECT * FROM case_lifecycle_event WHERE case_id=? ORDER BY version",(rs,n)->{
            Map<String,Object> event=new LinkedHashMap<>();
            event.put("id",rs.getObject("id"));event.put("version",rs.getLong("version"));event.put("type",rs.getString("event_type"));
            event.put("assessmentId",rs.getObject("assessment_id"));event.put("reviewer",rs.getString("reviewer"));event.put("note",rs.getString("note"));event.put("at",rs.getObject("created_at").toString());
            try { event.put("snapshot",json.readTree(rs.getString("snapshot"))); } catch (JsonProcessingException e) { throw new IllegalStateException(e); }
            return event;
        },caseId));
        return result;
    }
}
