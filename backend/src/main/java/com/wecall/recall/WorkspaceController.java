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
