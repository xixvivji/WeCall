package com.wecall.recall;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import static com.wecall.recall.TaskModels.*;

@Service
public class TaskService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final RecallService recalls;
    private final CaseGuard guard;
    public TaskService(JdbcTemplate jdbc,ObjectMapper json,RecallService recalls,CaseGuard guard) { this.jdbc=jdbc; this.json=json; this.recalls=recalls; this.guard=guard; }
    private void require(boolean valid,HttpStatus status,String message) {
        if (!valid) throw new RecallService.Failure(status,message);
    }
    private String encode(Object body) {
        try { return json.writeValueAsString(body); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }
    private String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private Map<String,Object> task(UUID caseId,UUID id,boolean lock) {
        var rows=jdbc.queryForList("SELECT * FROM response_task WHERE case_id=? AND id=?"+(lock?" FOR UPDATE":""),caseId,id);
        require(!rows.isEmpty(),HttpStatus.NOT_FOUND,"해당 사건의 대응 작업이 없습니다");
        return rows.getFirst();
    }
    private Map<String,Object> locked(UUID caseId,UUID id,long version) {
        guard.requireOpen(caseId);
        var row=task(caseId,id,true);
        require(((Number)row.get("version")).longValue()==version,HttpStatus.CONFLICT,"작업이 변경되었습니다. 최신 version으로 다시 요청하세요");
        return row;
    }
    private void active(Map<String,Object> row) {
        require(Set.of("OPEN","IN_PROGRESS").contains(row.get("status")),HttpStatus.CONFLICT,"진행 중인 작업만 변경할 수 있습니다");
    }
    private void event(UUID id,long version,String type,String actor,Object details) {
        jdbc.update("INSERT INTO response_task_event(id,task_id,version,event_type,actor,details) VALUES (?,?,?,?,?,?::jsonb)",UUID.randomUUID(),id,version,type,actor,encode(details));
    }
    private void changed(UUID id,long previous,String type,String actor,Object details) {
        jdbc.update("UPDATE response_task SET version=version+1,updated_at=now() WHERE id=?",id);
        event(id,previous+1,type,actor,details);
    }
    @Transactional
    public Map<String,Object> create(UUID caseId,NewTask body) {
        guard.requireOpen(caseId);
        recalls.getCase(caseId);
        require(body.assignee()==null || !body.assignee().isBlank(),HttpStatus.BAD_REQUEST,"담당자를 생략하거나 유효한 이름을 입력하세요");
        if (body.targetType()==TargetType.CASE) {
            require(body.targetId()==null,HttpStatus.BAD_REQUEST,"사건 전체 작업은 targetId를 생략하세요");
            if (body.assessmentId()!=null) recalls.getAssessment(caseId,body.assessmentId());
        } else {
            require(body.assessmentId()!=null && body.targetId()!=null && !body.targetId().isBlank(),HttpStatus.BAD_REQUEST,"재고·출고 작업에는 판정 ID와 대상 ID가 필요합니다");
            var run=recalls.getAssessment(caseId,body.assessmentId());
            boolean exists=body.targetType()==TargetType.INVENTORY
                ? run.inventory().stream().anyMatch(i->i.inventoryId().equals(body.targetId()))
                : run.shipments().stream().anyMatch(s->s.shipmentId().equals(body.targetId()));
            require(exists,HttpStatus.BAD_REQUEST,"해당 판정에 없는 작업 대상입니다");
        }
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO response_task(id,case_id,assessment_id,task_type,target_type,target_id,title,instructions,assignee) VALUES (?,?,?,?,?,?,?,?,?)",
            id,caseId,body.assessmentId(),body.taskType().name(),body.targetType().name(),body.targetId(),body.title(),body.instructions(),body.assignee());
        event(id,0,"CREATED",body.actor(),body);
        return get(caseId,id);
    }
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String,Object> get(UUID caseId,UUID id) {
        var row=task(caseId,id,false);
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("id",id); result.put("caseId",caseId); result.put("assessmentId",row.get("assessment_id"));
        result.put("taskType",row.get("task_type")); result.put("targetType",row.get("target_type")); result.put("targetId",row.get("target_id"));
        result.put("title",row.get("title")); result.put("instructions",row.get("instructions")); result.put("assignee",row.get("assignee"));
        result.put("status",row.get("status")); result.put("version",row.get("version")); result.put("reviewRound",row.get("review_round"));
        result.put("completedAt",row.get("completed_at")==null?null:row.get("completed_at").toString());
        result.put("proofs",jdbc.query("SELECT * FROM response_task_proof WHERE task_id=? ORDER BY submitted_at,id",(rs,n)->{
            Map<String,Object> p=new LinkedHashMap<>();
            p.put("id",rs.getObject("id")); p.put("reviewRound",rs.getInt("review_round")); p.put("evidenceText",rs.getString("evidence_text"));
            p.put("sha256",rs.getString("sha256")); p.put("status",rs.getString("status")); p.put("submittedBy",rs.getString("submitted_by"));
            p.put("submittedAt",rs.getObject("submitted_at").toString()); p.put("reviewedBy",rs.getString("reviewed_by")); p.put("reviewNote",rs.getString("review_note"));
            p.put("reviewedAt",rs.getObject("reviewed_at")==null?null:rs.getObject("reviewed_at").toString()); return p;
        },id));
        result.put("events",jdbc.query("SELECT * FROM response_task_event WHERE task_id=? ORDER BY version",(rs,n)->{
            try { return Map.of("version",rs.getLong("version"),"type",rs.getString("event_type"),"actor",rs.getString("actor"),"at",rs.getObject("created_at").toString(),"details",json.readTree(rs.getString("details"))); }
            catch (JsonProcessingException e) { throw new IllegalStateException(e); }
        },id));
        return result;
    }
    public List<Map<String,Object>> list(UUID caseId) {
        recalls.getCase(caseId);
        return jdbc.query("SELECT id,title,status,assignee,version,task_type,target_type,target_id,assessment_id FROM response_task WHERE case_id=? ORDER BY created_at,id",(rs,n)->{
            Map<String,Object> row=new LinkedHashMap<>();
            row.put("id",rs.getObject("id"));row.put("title",rs.getString("title"));row.put("status",rs.getString("status"));row.put("assignee",rs.getString("assignee"));row.put("version",rs.getLong("version"));
            row.put("taskType",rs.getString("task_type"));row.put("targetType",rs.getString("target_type"));row.put("targetId",rs.getString("target_id"));row.put("assessmentId",rs.getObject("assessment_id"));return row;
        },caseId);
    }
    @Transactional
    public Map<String,Object> assign(UUID caseId,UUID id,Assignment body) {
        var row=locked(caseId,id,body.expectedVersion()); active(row);
        jdbc.update("UPDATE response_task SET assignee=? WHERE id=?",body.assignee(),id);
        changed(id,body.expectedVersion(),"ASSIGNED",body.actor(),body);
        return get(caseId,id);
    }
    @Transactional
    public Map<String,Object> transition(UUID caseId,UUID id,Transition body) {
        var row=locked(caseId,id,body.expectedVersion());
        String status=row.get("status").toString();
        switch (body.action()) {
            case START -> {
                require(status.equals("OPEN") && row.get("assignee")!=null,HttpStatus.CONFLICT,"담당자를 지정한 OPEN 작업만 시작할 수 있습니다");
                jdbc.update("UPDATE response_task SET status='IN_PROGRESS' WHERE id=?",id);
            }
            case COMPLETE -> {
                require(status.equals("IN_PROGRESS"),HttpStatus.CONFLICT,"진행 중인 작업만 완료할 수 있습니다");
                long accepted=jdbc.queryForObject("SELECT count(*) FROM response_task_proof WHERE task_id=? AND review_round=? AND status='ACCEPTED'",Long.class,id,row.get("review_round"));
                long pending=jdbc.queryForObject("SELECT count(*) FROM response_task_proof WHERE task_id=? AND review_round=? AND status='PENDING'",Long.class,id,row.get("review_round"));
                require(accepted>0 && pending==0,HttpStatus.CONFLICT,"현재 처리 회차의 승인 증빙이 필요하고 미검토 증빙이 없어야 합니다");
                jdbc.update("UPDATE response_task SET status='COMPLETED',completed_at=now() WHERE id=?",id);
            }
            case REOPEN -> {
                require(status.equals("COMPLETED"),HttpStatus.CONFLICT,"완료된 작업만 재개할 수 있습니다");
                jdbc.update("UPDATE response_task SET status='IN_PROGRESS',completed_at=NULL,review_round=review_round+1 WHERE id=?",id);
            }
            case CANCEL -> {
                active(row);
                jdbc.update("UPDATE response_task SET status='CANCELLED' WHERE id=?",id);
            }
        }
        changed(id,body.expectedVersion(),body.action().name(),body.actor(),body);
        return get(caseId,id);
    }
    @Transactional
    public Map<String,Object> addProof(UUID caseId,UUID id,NewProof body) {
        var row=locked(caseId,id,body.expectedVersion());
        require(row.get("status").equals("IN_PROGRESS"),HttpStatus.CONFLICT,"진행 중인 작업에만 증빙을 등록할 수 있습니다");
        UUID proofId=UUID.randomUUID();
        jdbc.update("INSERT INTO response_task_proof(id,task_id,review_round,evidence_text,sha256,submitted_by) VALUES (?,?,?,?,?,?)",proofId,id,row.get("review_round"),body.evidenceText(),hash(body.evidenceText()),body.actor());
        changed(id,body.expectedVersion(),"PROOF_ADDED",body.actor(),Map.of("proofId",proofId,"reviewRound",row.get("review_round")));
        return get(caseId,id);
    }
    @Transactional
    public Map<String,Object> reviewProof(UUID caseId,UUID id,UUID proofId,ProofReview body) {
        var row=locked(caseId,id,body.expectedVersion());
        require(Set.of("IN_PROGRESS","CANCELLED").contains(row.get("status")),HttpStatus.CONFLICT,"진행 중 또는 취소된 작업의 미검토 증빙만 검토할 수 있습니다");
        var proofs=jdbc.queryForList("SELECT * FROM response_task_proof WHERE id=? AND task_id=? AND review_round=?",proofId,id,row.get("review_round"));
        require(!proofs.isEmpty(),HttpStatus.NOT_FOUND,"현재 처리 회차의 증빙이 없습니다");
        require(proofs.getFirst().get("status").equals("PENDING"),HttpStatus.CONFLICT,"이미 검토된 증빙입니다");
        jdbc.update("UPDATE response_task_proof SET status=?,reviewed_by=?,review_note=?,reviewed_at=now() WHERE id=?",body.decision().name(),body.actor(),body.note(),proofId);
        changed(id,body.expectedVersion(),"PROOF_REVIEWED",body.actor(),Map.of("proofId",proofId,"decision",body.decision(),"note",body.note()));
        return get(caseId,id);
    }
}
