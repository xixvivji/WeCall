package com.wecall.recall;

import com.wecall.auth.CurrentActor;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class ConditionSourceService {
    private final JdbcTemplate jdbc; private final CaseGuard guard;
    public ConditionSourceService(JdbcTemplate jdbc,CaseGuard guard) {this.jdbc=jdbc;this.guard=guard;}
    public record Review(@NotNull @Positive Long expectedSourceVersion,@NotBlank @Size(max=2000) String note,
        @AssertTrue(message="조건·상품 연결·원문 근거가 현재 원문에도 유효한지 확인하세요") boolean unchangedConfirmed) {}
    public record Withdrawal(@NotNull @Positive Long expectedSourceVersion,@NotBlank @Size(max=2000) String note) {}
    private RecallService.Failure conflict(String message) {return new RecallService.Failure(HttpStatus.CONFLICT,message);}
    public Map<String,Object> state(UUID caseId,UUID conditionId) {
        var rows=jdbc.queryForList("SELECT source_version AS \"basisVersion\",current_source_version AS \"currentVersion\",review_required AS required FROM condition_source_state WHERE case_id=? AND id=?",caseId,conditionId);
        if(rows.isEmpty())throw new RecallService.Failure(HttpStatus.NOT_FOUND,"해당 사건의 조건이 없습니다");
        var result=new LinkedHashMap<>(rows.getFirst());
        result.put("reviews",jdbc.queryForList("SELECT source_version AS \"sourceVersion\",reviewer,note,created_at AS \"createdAt\" FROM condition_source_review WHERE condition_id=? ORDER BY source_version",conditionId));
        return result;
    }
    public void requireCurrent(UUID caseId,UUID conditionId) {
        if((boolean)state(caseId,conditionId).get("required")) throw conflict("현재 원문 기준 재검토가 필요합니다. 영향 없음 확인 또는 새 조건 작성 후 진행하세요");
    }
    private Map<String,Object> lock(UUID caseId,UUID conditionId,long expected) {
        guard.requireOpen(caseId);
        var recall=guard.lock(caseId);
        if(((Number)recall.get("source_version")).longValue()!=expected)throw conflict("원문 버전이 변경되었습니다. 다시 조회하고 검토하세요");
        var rows=jdbc.queryForList("SELECT * FROM recall_condition WHERE case_id=? AND id=? FOR UPDATE",caseId,conditionId);
        if(rows.isEmpty())throw new RecallService.Failure(HttpStatus.NOT_FOUND,"해당 사건의 조건이 없습니다");
        return rows.getFirst();
    }
    @Transactional
    public Map<String,Object> review(UUID caseId,UUID conditionId,Review body) {
        var condition=lock(caseId,conditionId,body.expectedSourceVersion());
        if(condition.get("status").equals("WITHDRAWN"))throw conflict("철회한 조건은 재검토할 수 없습니다");
        if(!body.unchangedConfirmed())throw conflict("현재 원문에 대한 영향 없음 확인이 필요합니다");
        if(!(boolean)state(caseId,conditionId).get("required"))throw conflict("이미 현재 원문 기준으로 확인한 조건입니다");
        String quote=jdbc.queryForObject("SELECT definition->>'sourceQuote' FROM recall_condition WHERE id=?",String.class,conditionId);
        String text=jdbc.queryForObject("SELECT source_text FROM recall_case WHERE id=?",String.class,caseId);
        if(quote==null || quote.isBlank() || !text.contains(quote))throw conflict("기존 근거 인용이 현재 원문에 없습니다. 새 조건을 작성하세요");
        jdbc.update("INSERT INTO condition_source_review(condition_id,case_id,source_version,reviewer,note) VALUES (?,?,?,?,?)",conditionId,caseId,body.expectedSourceVersion(),CurrentActor.username(),body.note());
        return state(caseId,conditionId);
    }
    @Transactional
    public void withdraw(UUID caseId,UUID conditionId,Withdrawal body) {
        var condition=lock(caseId,conditionId,body.expectedSourceVersion());
        if(!condition.get("status").equals("DRAFT"))throw conflict("미승인 초안만 철회할 수 있습니다. 승인·판정 이력은 보존합니다");
        jdbc.update("UPDATE recall_condition SET status='WITHDRAWN',withdrawn_by=?,withdrawn_at=now(),withdrawal_note=? WHERE id=?",CurrentActor.username(),body.note(),conditionId);
    }
}
