package com.wecall.recall;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/recalls/{caseId}/history")
public class CaseHistoryController {
    private final NamedParameterJdbcTemplate jdbc;
    public CaseHistoryController(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    // Read the original records; do not manufacture historical actors or reasons.
    private static final String EVENTS = """
        SELECT 'condition:' || c.id AS id, 'CONDITION' AS kind, 'CONDITION_APPROVED' AS type,
            c.approved_at AS occurred_at, c.approved_by AS actor, NULL::text AS note,
            '조건 v' || c.version AS title, c.version::bigint AS version,
            c.id AS condition_id, NULL::uuid AS evidence_id, NULL::uuid AS task_id,
            NULL::uuid AS proof_id, NULL::text AS assignee
        FROM recall_condition c WHERE c.case_id=:caseId AND c.status='APPROVED'
        UNION ALL
        SELECT 'source-review:' || v.condition_id || ':' || v.source_version,'CONDITION','CONDITION_SOURCE_REVIEWED',
            v.created_at,v.reviewer,v.note,'조건 v' || c.version || ' · 원문 v' || v.source_version || ' 영향 없음 확인',c.version::bigint,
            c.id,NULL::uuid,NULL::uuid,NULL::uuid,NULL::text
        FROM condition_source_review v JOIN recall_condition c ON c.id=v.condition_id WHERE v.case_id=:caseId
        UNION ALL
        SELECT 'withdrawal:' || c.id,'CONDITION','CONDITION_WITHDRAWN',c.withdrawn_at,c.withdrawn_by,c.withdrawal_note,
            '조건 v' || c.version || ' 초안 철회',c.version::bigint,c.id,NULL::uuid,NULL::uuid,NULL::uuid,NULL::text
        FROM recall_condition c WHERE c.case_id=:caseId AND c.status='WITHDRAWN'
        UNION ALL
        SELECT 'evidence:' || e.id, 'EVIDENCE', 'EVIDENCE_' || e.status,
            e.reviewed_at,e.reviewed_by,e.review_note,'입고 ' || e.receipt_id || ' 증거',NULL::bigint,
            NULL::uuid,e.id,NULL::uuid,NULL::uuid,NULL::text
        FROM receipt_evidence e WHERE e.case_id=:caseId AND e.status IN ('APPROVED','REJECTED')
        UNION ALL
        SELECT 'task:' || e.id,
            CASE WHEN e.event_type IN ('PROOF_ADDED','PROOF_REVIEWED') THEN 'PROOF' ELSE 'TASK' END,
            CASE WHEN e.event_type='PROOF_REVIEWED' THEN 'PROOF_' || coalesce(e.details->>'decision','REVIEWED')
                 WHEN e.event_type='PROOF_ADDED' THEN 'PROOF_ADDED' ELSE 'TASK_' || e.event_type END,
            e.created_at,e.actor,e.details->>'note',t.title,e.version,
            NULL::uuid,NULL::uuid,t.id,p.id,e.details->>'assignee'
        FROM response_task_event e JOIN response_task t ON t.id=e.task_id
        LEFT JOIN response_task_proof p ON p.task_id=t.id AND p.id::text=e.details->>'proofId'
        WHERE t.case_id=:caseId
        UNION ALL
        SELECT 'lifecycle:' || e.id,'LIFECYCLE','CASE_' || e.event_type,
            e.created_at,e.reviewer,e.note,'사건 종료·재개',e.version,
            NULL::uuid,NULL::uuid,NULL::uuid,NULL::uuid,NULL::text
        FROM case_lifecycle_event e WHERE e.case_id=:caseId
        """;

    @GetMapping
    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> history(@PathVariable UUID caseId,
            @RequestParam(defaultValue="ALL") String kind,
            @RequestParam(defaultValue="DESC") String order,
            @RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="20") int size) {
        if (!Set.of("ALL","CONDITION","EVIDENCE","TASK","PROOF","LIFECYCLE").contains(kind)
                || !Set.of("ASC","DESC").contains(order) || page<0 || page>100000 || size<1 || size>100)
            throw new RecallService.Failure(HttpStatus.BAD_REQUEST,"업무 이력 조회 조건을 확인하세요");
        var args = Map.of("caseId",caseId,"kind",kind,"size",size,"offset",(long)page*size);
        if (jdbc.queryForObject("SELECT count(*) FROM recall_case WHERE id=:caseId",args,Long.class)==0)
            throw new RecallService.Failure(HttpStatus.NOT_FOUND,"해당 사건이 없습니다");
        String from = " FROM (" + EVENTS + ") h WHERE (:kind='ALL' OR kind=:kind)";
        long total = jdbc.queryForObject("SELECT count(*)"+from,args,Long.class);
        var items = jdbc.queryForList("""
            SELECT id,kind,type,occurred_at AS "occurredAt",actor,note,title,version,
                condition_id AS "conditionId",evidence_id AS "evidenceId",task_id AS "taskId",
                proof_id AS "proofId",assignee
            """ + from + " ORDER BY occurred_at " + order + ",id " + order + " LIMIT :size OFFSET :offset",args);
        return Map.of("items",items,"totalElements",total,"page",page,"size",size,"totalPages",(total+size-1)/size);
    }
}
