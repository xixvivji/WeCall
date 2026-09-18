package com.wecall.recall;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;

@RestController
@RequestMapping("/api/v1/recalls/{caseId}/report")
public class CaseReportController {
    private final JdbcTemplate jdbc;
    private final RecallService recalls;
    private final ClosureService closure;
    private final TaskService tasks;
    public CaseReportController(JdbcTemplate jdbc,RecallService recalls,ClosureService closure,TaskService tasks) {
        this.jdbc=jdbc;this.recalls=recalls;this.closure=closure;this.tasks=tasks;
    }
    @GetMapping
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public ResponseEntity<Map<String,Object>> report(@PathVariable UUID caseId,@RequestParam(required=false) UUID assessmentId) {
        var result=new LinkedHashMap<String,Object>();
        result.put("case",recalls.getCase(caseId));
        result.put("generatedAt",jdbc.queryForObject("SELECT transaction_timestamp()",java.time.OffsetDateTime.class));
        var assessment=assessmentId==null?null:recalls.getAssessment(caseId,assessmentId);
        result.put("assessment",assessment);
        result.put("condition",assessment==null?null:recalls.getCondition(caseId,assessment.conditionId()));
        result.put("assessmentSourceVersion",assessment==null?null:jdbc.queryForObject("SELECT source_version FROM assessment_run WHERE id=?",Long.class,assessmentId));
        result.put("assessmentCreatedAt",assessment==null?null:jdbc.queryForObject("SELECT created_at FROM assessment_run WHERE id=?",java.time.OffsetDateTime.class,assessmentId));
        result.put("datasetAsOf",assessment==null?null:jdbc.queryForObject("SELECT as_of FROM dataset WHERE id=?",java.time.OffsetDateTime.class,assessment.datasetId()));
        result.put("tasks",tasks.list(caseId));
        result.put("proofs",jdbc.queryForList("""
            SELECT p.id,p.task_id AS "taskId",p.review_round AS "reviewRound",t.review_round AS "currentRound",
                p.status,p.reviewed_by AS "reviewedBy",p.review_note AS "reviewNote",to_char(p.reviewed_at, 'YYYY-MM-DD"T"HH24:MI:SS.MSTZH:TZM') AS "reviewedAt"
            FROM response_task_proof p JOIN response_task t ON t.id=p.task_id
            WHERE t.case_id=? ORDER BY t.created_at,t.id,p.review_round,p.submitted_at,p.id
            """,caseId));
        result.put("evidence",jdbc.queryForList("""
            SELECT id,receipt_id AS "receiptId",base_assessment_id AS "assessmentId",status,
                reviewed_by AS "reviewedBy",review_note AS "reviewNote",to_char(reviewed_at, 'YYYY-MM-DD"T"HH24:MI:SS.MSTZH:TZM') AS "reviewedAt"
            FROM receipt_evidence WHERE case_id=? ORDER BY created_at,id
            """,caseId));
        var check=closure.check(caseId,assessmentId);
        result.put("blockers",check.get("blockers"));result.put("warnings",check.get("warnings"));
        result.put("lifecycle",jdbc.queryForList("""
            SELECT id,version,event_type AS type,assessment_id AS "assessmentId",reviewer,note,to_char(created_at, 'YYYY-MM-DD"T"HH24:MI:SS.MSTZH:TZM') AS at
            FROM case_lifecycle_event WHERE case_id=? ORDER BY version
            """,caseId));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }
}
