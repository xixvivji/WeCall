package com.wecall.recall;

import com.wecall.auth.CurrentActor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.http.HttpStatus;
import java.util.*;

@RestController
@RequestMapping("/api/v1/workspace")
public class WorkspaceController {
    private final JdbcTemplate jdbc;
    public WorkspaceController(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    private static final String REVIEWS="""
        SELECT 'CONDITION' AS kind,r.id,r.case_id,c.title AS case_title,
            '조건 v' || r.version AS title,r.created_at,NULL::uuid AS task_id
        FROM recall_condition r JOIN recall_case c ON c.id=r.case_id WHERE r.status='DRAFT' AND c.status='OPEN'
        UNION ALL
        SELECT 'EVIDENCE',e.id,e.case_id,c.title,'입고 ' || e.receipt_id || ' 증거',e.created_at,NULL::uuid
        FROM receipt_evidence e JOIN recall_case c ON c.id=e.case_id WHERE e.status='PENDING' AND c.status='OPEN'
        UNION ALL
        SELECT 'PROOF',p.id,t.case_id,c.title,t.title || ' · 증빙',p.submitted_at,t.id
        FROM response_task_proof p JOIN response_task t ON t.id=p.task_id JOIN recall_case c ON c.id=t.case_id
        WHERE p.status='PENDING' AND p.review_round=t.review_round AND t.status IN ('IN_PROGRESS','CANCELLED') AND c.status='OPEN'
        """;
    @GetMapping("/reviews")
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> reviews(@RequestParam(defaultValue="ALL") String kind,@RequestParam(defaultValue="") String q,
        @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        if(!CurrentActor.reviewer())throw new RecallService.Failure(HttpStatus.FORBIDDEN,"검토 대기함은 검토자만 사용할 수 있습니다");
        if(!Set.of("ALL","CONDITION","EVIDENCE","PROOF").contains(kind)||q.length()>200||page<0||size<1||size>100)
            throw new RecallService.Failure(HttpStatus.BAD_REQUEST,"검토 대기함 조회 조건을 확인하세요");
        String from=" FROM ("+REVIEWS+") r WHERE (strpos(lower(case_title),lower(?))>0 OR strpos(lower(title),lower(?))>0)";
        List<Object> args=new ArrayList<>(List.of(q.strip(),q.strip()));
        Map<String,Long> counts=new LinkedHashMap<>();for(String k:List.of("CONDITION","EVIDENCE","PROOF"))counts.put(k,0L);
        jdbc.query("SELECT kind,count(*) AS n"+from+" GROUP BY kind",rs->{counts.put(rs.getString("kind"),rs.getLong("n"));},args.toArray());
        if(!kind.equals("ALL")){from+=" AND kind=?";args.add(kind);}
        long total=kind.equals("ALL")?counts.values().stream().mapToLong(Long::longValue).sum():counts.get(kind);
        args.add(size);args.add((long)page*size);
        var rows=jdbc.queryForList("SELECT kind,id,case_id AS \"caseId\",case_title AS \"caseTitle\",title,created_at AS \"createdAt\",task_id AS \"taskId\""+from+" ORDER BY created_at,id,kind LIMIT ? OFFSET ?",args.toArray());
        return Map.of("items",rows,"counts",counts,"totalElements",total,"page",page,"size",size,"totalPages",(total+size-1)/size);
    }
    @GetMapping("/summary")
    public Map<String,Object> summary() {
        return jdbc.queryForMap("""
            SELECT (SELECT count(*) FROM recall_case WHERE status='OPEN') AS "openCases",
            (SELECT count(*) FROM response_task WHERE status IN ('OPEN','IN_PROGRESS')) AS "openTasks",
            (SELECT count(*) FROM response_task WHERE status IN ('OPEN','IN_PROGRESS') AND assignee=?) AS "myTasks",
            (SELECT count(*) FROM recall_case c JOIN LATERAL
                (SELECT result FROM assessment_run WHERE case_id=c.id ORDER BY created_at DESC,id DESC LIMIT 1) a ON true
                WHERE c.status='OPEN' AND ((a.result->'inventoryTotals'->>'needsReview')::bigint>0
                    OR (a.result->'shipmentTotals'->>'needsReview')::bigint>0)) AS "reviewCases",
            (SELECT count(*) FROM recall_case c WHERE c.status='OPEN' AND NOT EXISTS
                (SELECT 1 FROM assessment_run a WHERE a.case_id=c.id)) AS "unassessedCases"
            """,CurrentActor.username());
    }
    @GetMapping("/tasks")
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> tasks(@RequestParam(defaultValue="mine") String scope,
        @RequestParam(defaultValue="ACTIVE") String status,@RequestParam(defaultValue="") String q,
        @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        if(!Set.of("mine","all","reassign").contains(scope) || !Set.of("ACTIVE","ALL","OPEN","IN_PROGRESS","COMPLETED","CANCELLED").contains(status)
            || page<0 || page>100000 || size<1 || size>100 || q.length()>200)
            throw new RecallService.Failure(HttpStatus.BAD_REQUEST,"조회 조건을 확인하세요");
        if(!scope.equals("mine") && !CurrentActor.reviewer())
            throw new RecallService.Failure(HttpStatus.FORBIDDEN,"전체 작업 관리는 검토자만 사용할 수 있습니다");
        String from=" FROM response_task t JOIN recall_case c ON c.id=t.case_id LEFT JOIN app_user u ON u.username=t.assignee WHERE true";
        List<Object> args=new ArrayList<>();
        if(scope.equals("mine")){from+=" AND t.assignee=?";args.add(CurrentActor.username());}
        if(scope.equals("reassign"))from+=" AND t.status IN ('OPEN','IN_PROGRESS') AND (t.assignee IS NULL OR u.username IS NULL OR NOT u.enabled)";
        if(status.equals("ACTIVE"))from+=" AND t.status IN ('OPEN','IN_PROGRESS')";
        else if(!status.equals("ALL")){from+=" AND t.status=?";args.add(status);}
        if(!q.isBlank()){from+=" AND (strpos(lower(t.title),lower(?))>0 OR strpos(lower(c.title),lower(?))>0)";args.add(q.trim());args.add(q.trim());}
        long total=jdbc.queryForObject("SELECT count(*)"+from,Long.class,args.toArray());
        args.add(size);args.add((long)page*size);
        var rows=jdbc.queryForList("""
            SELECT t.id,t.case_id AS "caseId",c.title AS "caseTitle",c.status AS "caseStatus",t.title,t.status,t.assignee,
            coalesce(u.enabled,false) AS "assigneeActive",t.updated_at AS "updatedAt"
            """+from+" ORDER BY t.updated_at DESC,t.id DESC LIMIT ? OFFSET ?",args.toArray());
        return Map.of("items",rows,"totalElements",total,"page",page,"size",size,"totalPages",(total+size-1)/size);
    }
}
