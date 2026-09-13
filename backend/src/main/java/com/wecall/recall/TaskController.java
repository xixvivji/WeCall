package com.wecall.recall;

import jakarta.validation.Valid;
import com.wecall.auth.CurrentActor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static com.wecall.recall.TaskModels.*;

@RestController
@RequestMapping("/api/v1/recalls/{caseId}/tasks")
public class TaskController {
    private final TaskService service;
    private final com.wecall.auth.UserDirectory users;
    public TaskController(TaskService service,com.wecall.auth.UserDirectory users) { this.service=service; this.users=users; }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> create(@PathVariable UUID caseId,@Valid @RequestBody NewTask body) { users.requireActive(body.assignee()); return service.create(caseId,new NewTask(body.taskType(),body.targetType(),body.assessmentId(),body.targetId(),body.title(),body.instructions(),body.assignee(),CurrentActor.username())); }
    @GetMapping public List<Map<String,Object>> list(@PathVariable UUID caseId) { return service.list(caseId); }
    @GetMapping("/{taskId}") public Map<String,Object> get(@PathVariable UUID caseId,@PathVariable UUID taskId) { return service.get(caseId,taskId); }
    @PostMapping("/{taskId}/assignment") public Map<String,Object> assign(@PathVariable UUID caseId,@PathVariable UUID taskId,@Valid @RequestBody Assignment body) { users.requireActive(body.assignee()); return service.assign(caseId,taskId,new Assignment(body.expectedVersion(),body.assignee(),CurrentActor.username(),body.note())); }
    @PostMapping("/{taskId}/transitions") public Map<String,Object> transition(@PathVariable UUID caseId,@PathVariable UUID taskId,@Valid @RequestBody Transition body) { var command=new Transition(body.expectedVersion(),body.action(),CurrentActor.username(),body.note()); return CurrentActor.reviewer()?service.transition(caseId,taskId,command):service.transitionAssigned(caseId,taskId,command); }
    @PostMapping(value="/{taskId}/proofs",consumes="application/json") @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> proof(@PathVariable UUID caseId,@PathVariable UUID taskId,@Valid @RequestBody NewProof body) { var command=new NewProof(body.expectedVersion(),body.evidenceText(),CurrentActor.username()); return CurrentActor.reviewer()?service.addProof(caseId,taskId,command):service.addAssignedProof(caseId,taskId,command); }
    @PostMapping("/{taskId}/proofs/{proofId}/review")
    public Map<String,Object> review(@PathVariable UUID caseId,@PathVariable UUID taskId,@PathVariable UUID proofId,@Valid @RequestBody ProofReview body) { return service.reviewProof(caseId,taskId,proofId,new ProofReview(body.expectedVersion(),body.decision(),CurrentActor.username(),body.note())); }
}
