package com.wecall.recall;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
import java.util.*;

/** All case mutations acquire this lock before condition/evidence/task locks. */
@Component
public class CaseGuard {
    private final JdbcTemplate jdbc;
    public CaseGuard(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Transactional(propagation=Propagation.MANDATORY)
    public Map<String,Object> lock(UUID caseId) {
        var rows=jdbc.queryForList("SELECT * FROM recall_case WHERE id=? FOR UPDATE",caseId);
        if (rows.isEmpty()) throw new RecallService.Failure(HttpStatus.NOT_FOUND,"사건이 없습니다");
        return rows.getFirst();
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void requireOpen(UUID caseId) {
        if (!lock(caseId).get("status").equals("OPEN"))
            throw new RecallService.Failure(HttpStatus.CONFLICT,"종료된 사건입니다. 사유를 기록해 재개한 후 변경하세요");
    }
}
