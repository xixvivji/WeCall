package com.wecall.recall;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static com.wecall.recall.TaskModels.*;

@RestController
@RequestMapping("/api/v1/recalls/{caseId}/tasks")
public class TaskController {
    private final TaskService service;
    public TaskController(TaskService service) { this.service=service; }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> create(@PathVariable UUID caseId,@Valid @RequestBody NewTask body) { return service.create(caseId,body); }
    @GetMapping public List<Map<String,Object>> list(@PathVariable UUID caseId) { return service.list(caseId); }
    @GetMapping("/{taskId}") public Map<String,Object> get(@PathVariable UUID caseId,@PathVariable UUID taskId) { return service.get(caseId,taskId); }
    @PostMapping("/{taskId}/assignment") public Map<String,Object> assign(@PathVariable UUID caseId,@PathVariable UUID taskId,@Valid @RequestBody Assignment body) { return service.assign(caseId,taskId,body); }
    @PostMapping("/{taskId}/transitions") public Map<String,Object> transition(@PathVariable UUID caseId,@PathVariable UUID taskId,@Valid @RequestBody Transition body) { return service.transition(caseId,taskId,body); }
    @PostMapping("/{taskId}/proofs") @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> proof(@PathVariable UUID caseId,@PathVariable UUID taskId,@Valid @RequestBody NewProof body) { return service.addProof(caseId,taskId,body); }
    @PostMapping("/{taskId}/proofs/{proofId}/review")
    public Map<String,Object> review(@PathVariable UUID caseId,@PathVariable UUID taskId,@PathVariable UUID proofId,@Valid @RequestBody ProofReview body) { return service.reviewProof(caseId,taskId,proofId,body); }
}
