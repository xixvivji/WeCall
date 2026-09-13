package com.wecall.attachment;
import com.wecall.recall.*;
import com.wecall.auth.CurrentActor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.multipart.MultipartFile;
import java.util.*;

@Service
public class AttachmentCreationService {
    private final AttachmentService attachments;private final EvidenceService evidence;private final TaskService tasks;private final JdbcTemplate jdbc;
    public AttachmentCreationService(AttachmentService attachments,EvidenceService evidence,TaskService tasks,JdbcTemplate jdbc){this.attachments=attachments;this.evidence=evidence;this.tasks=tasks;this.jdbc=jdbc;}
    @Transactional
    public Map<String,Object> evidence(UUID caseId,EvidenceModels.NewEvidence body,List<MultipartFile> files) {
        var checked=attachments.validate(files);
        var result=evidence.create(caseId,body);
        attachments.save(caseId,(UUID)result.get("id"),null,null,checked);
        return result;
    }
    @Transactional
    public Map<String,Object> proof(UUID caseId,UUID taskId,TaskModels.NewProof body,List<MultipartFile> files) {
        var checked=attachments.validate(files);
        var command=new TaskModels.NewProof(body.expectedVersion(),body.evidenceText(),CurrentActor.username());
        var result=CurrentActor.reviewer()?tasks.addProof(caseId,taskId,command):tasks.addAssignedProof(caseId,taskId,command);
        UUID proofId=jdbc.queryForObject("SELECT (details->>'proofId')::uuid FROM response_task_event WHERE task_id=? AND version=? AND event_type='PROOF_ADDED'",UUID.class,taskId,result.get("version"));
        attachments.save(caseId,null,taskId,proofId,checked);
        return result;
    }
}
