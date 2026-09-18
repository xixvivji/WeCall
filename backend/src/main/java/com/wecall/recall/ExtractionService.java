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
import static com.wecall.recall.ExtractionModels.*;

@Service
public class ExtractionService {
    private final JdbcTemplate jdbc;private final ObjectMapper json;private final CaseGuard guard;private final RecallService recalls;
    public ExtractionService(JdbcTemplate jdbc,ObjectMapper json,CaseGuard guard,RecallService recalls) {this.jdbc=jdbc;this.json=json;this.guard=guard;this.recalls=recalls;}
    public record Work(UUID id,UUID caseId,String source,String sha) {}
    private String encode(Object data) {try{return json.writeValueAsString(data);}catch(JsonProcessingException e){throw new IllegalStateException(e);}}
    private String sha(String source) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private void require(boolean valid,String message) {if(!valid)throw new RecallService.Failure(HttpStatus.CONFLICT,message);}
    private Map<String,Object> job(UUID caseId,UUID id,boolean lock) {
        var rows=jdbc.queryForList("SELECT * FROM extraction_job WHERE case_id=? AND id=?"+(lock?" FOR UPDATE":""),caseId,id);
        if(rows.isEmpty())throw new RecallService.Failure(HttpStatus.NOT_FOUND,"해당 사건의 분석 작업이 없습니다");
        return rows.getFirst();
    }
    @Transactional public Map<String,Object> enqueue(UUID caseId,String actor) {
        guard.requireOpen(caseId);
        require(jdbc.queryForObject("SELECT count(*) FROM extraction_job WHERE case_id=? AND status IN ('QUEUED','RUNNING')",Long.class,caseId)==0,"이 사건에 진행 중인 분석이 있습니다");
        var recall=recalls.getCase(caseId); String source=recall.get("sourceText").toString(); UUID id=UUID.randomUUID();
        if(source.isBlank())throw new RecallService.Failure(HttpStatus.UNPROCESSABLE_ENTITY,"분석할 원문이 비어 있습니다. 원문을 확인하세요.");
        if(source.getBytes(StandardCharsets.UTF_8).length>6000 || sourceLines(source)>80)
            throw new RecallService.Failure(HttpStatus.UNPROCESSABLE_ENTITY,"AI 입력 한도(UTF-8 6,000바이트·80줄)를 초과했습니다. 원문 전체를 보존하고 수동으로 조건을 작성하세요.");
        jdbc.update("INSERT INTO extraction_job(id,case_id,requested_by,source_text,source_sha256,source_version) VALUES (?,?,?,?,?,?)",id,caseId,actor,source,sha(source),recall.get("sourceVersion"));
        return get(caseId,id);
    }
    // Match Python str.splitlines(), including CRLF as one break and no extra final line.
    static int sourceLines(String source) {
        if(source.isEmpty())return 0;
        String[] lines=source.split("\\r\\n|[\\n\\r\\u000B\\f\\u001C-\\u001E\\u0085\\u2028\\u2029]",-1);
        return lines.length-(lines[lines.length-1].isEmpty()?1:0);
    }
    public Map<String,Object> get(UUID caseId,UUID id) {return response(job(caseId,id,false));}
    public List<Map<String,Object>> list(UUID caseId) {
        recalls.getCase(caseId);
        return jdbc.queryForList("SELECT * FROM extraction_job WHERE case_id=? ORDER BY created_at,id",caseId).stream().map(this::response).toList();
    }
    private Map<String,Object> response(Map<String,Object> row) {
        var result=new LinkedHashMap<String,Object>();
        for(String key:List.of("id","status"))result.put(key,row.get(key));
        result.put("caseId",row.get("case_id"));result.put("requestedBy",row.get("requested_by"));result.put("sourceText",row.get("source_text"));result.put("sourceSha256",row.get("source_sha256"));
        result.put("sourceVersion",row.get("source_version"));
        result.put("reviewStatus",row.get("review_status"));result.put("errorCode",row.get("error_code"));result.put("durationMs",row.get("duration_ms"));
        result.put("rawResponse",row.get("raw_response"));result.put("conditionId",row.get("condition_id"));
        for(var entry:Map.of("createdAt","created_at","startedAt","started_at","finishedAt","finished_at","reviewedAt","reviewed_at").entrySet())
            result.put(entry.getKey(),row.get(entry.getValue())==null?null:row.get(entry.getValue()).toString());
        result.put("reviewedBy",row.get("reviewed_by"));result.put("reviewNote",row.get("review_note"));
        try {result.put("output",row.get("response")==null?null:json.readTree(row.get("response").toString()));}catch(JsonProcessingException e){throw new IllegalStateException(e);}
        return result;
    }
    @Transactional public Optional<Work> claim() {
        // Bounded remote calls happen only after this short transaction commits.
        var rows=jdbc.queryForList("UPDATE extraction_job SET status='RUNNING',started_at=now() WHERE id=(SELECT id FROM extraction_job WHERE status='QUEUED' ORDER BY created_at,id FOR UPDATE SKIP LOCKED LIMIT 1) RETURNING id,case_id,source_text,source_sha256");
        if(rows.isEmpty())return Optional.empty();var r=rows.getFirst();
        return Optional.of(new Work((UUID)r.get("id"),(UUID)r.get("case_id"),r.get("source_text").toString(),r.get("source_sha256").toString()));
    }
    @Transactional public void finish(Work work,ExtractionClient.Response result,String error,String raw,long duration) {
        guard.lock(work.caseId());var row=job(work.caseId(),work.id(),true);
        if(!row.get("status").equals("RUNNING"))return; // A stale worker cannot overwrite interruption recovery.
        jdbc.update("UPDATE extraction_job SET status=?,response=?::jsonb,raw_response=?,error_code=?,duration_ms=?,finished_at=now() WHERE id=?",
            error==null?"SUCCEEDED":"FAILED",result==null?null:encode(result.result()),result==null?raw:result.raw(),error,duration,work.id());
    }
    @Transactional public void recoverInterrupted() {
        jdbc.update("UPDATE extraction_job SET status='FAILED',error_code='WORKER_INTERRUPTED',finished_at=now() WHERE status='RUNNING' AND started_at < now()-interval '2 minutes'");
    }
    @Transactional public Map<String,Object> convert(UUID caseId,UUID id,Conversion body,String reviewer) {
        guard.requireOpen(caseId);var row=job(caseId,id,true);
        require(row.get("status").equals("SUCCEEDED") && row.get("review_status").equals("PENDING"),"성공한 미검토 분석만 조건 초안으로 전환할 수 있습니다");
        require(((Number)row.get("source_version")).longValue()==((Number)recalls.getCase(caseId).get("sourceVersion")).longValue(),"원문이 수정된 분석입니다. 기각 후 현재 원문으로 다시 분석하세요");
        var condition=recalls.createCondition(caseId,body.definition());
        jdbc.update("UPDATE extraction_job SET review_status='ACCEPTED',condition_id=?,reviewed_by=?,review_note=?,reviewed_at=now() WHERE id=?",condition.get("id"),reviewer,body.note(),id);
        return get(caseId,id);
    }
    @Transactional public Map<String,Object> dismiss(UUID caseId,UUID id,Dismissal body,String reviewer) {
        guard.requireOpen(caseId);var row=job(caseId,id,true);
        require(Set.of("SUCCEEDED","FAILED").contains(row.get("status")) && row.get("review_status").equals("PENDING"),"완료된 미검토 분석만 기각할 수 있습니다");
        jdbc.update("UPDATE extraction_job SET review_status='DISMISSED',reviewed_by=?,review_note=?,reviewed_at=now() WHERE id=?",reviewer,body.note(),id);
        return get(caseId,id);
    }
}
