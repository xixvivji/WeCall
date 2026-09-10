package com.wecall.recall;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static com.wecall.recall.RecallModels.*;

@RestController
@RequestMapping("/api/v1/recalls")
public class RecallController {
    private final RecallService service;
    public RecallController(RecallService service) { this.service=service; }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> create(@Valid @RequestBody NewCase body) { return service.createCase(body); }
    @GetMapping("/{caseId}")
    public Map<String,Object> get(@PathVariable UUID caseId) { return service.getCase(caseId); }
    @PostMapping("/{caseId}/conditions") @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> condition(@PathVariable UUID caseId,@Valid @RequestBody NewCondition body) { return service.createCondition(caseId,body); }
    @GetMapping("/{caseId}/conditions/{conditionId}")
    public Map<String,Object> getCondition(@PathVariable UUID caseId,@PathVariable UUID conditionId) { return service.getCondition(caseId,conditionId); }
    @PostMapping("/{caseId}/conditions/{conditionId}/approval")
    public Map<String,Object> approve(@PathVariable UUID caseId,@PathVariable UUID conditionId,@Valid @RequestBody Approval body) { return service.approve(caseId,conditionId,body); }
    @PostMapping("/{caseId}/assessments") @ResponseStatus(HttpStatus.CREATED)
    public Assessment assess(@PathVariable UUID caseId,@Valid @RequestBody NewAssessment body) { return service.assess(caseId,body.conditionId()); }
    @GetMapping("/{caseId}/assessments/{runId}")
    public Assessment result(@PathVariable UUID caseId,@PathVariable UUID runId) { return service.getAssessment(caseId,runId); }
}
