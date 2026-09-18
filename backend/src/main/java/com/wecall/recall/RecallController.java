package com.wecall.recall;

import jakarta.validation.Valid;
import com.wecall.auth.CurrentActor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static com.wecall.recall.RecallModels.*;

@RestController
@RequestMapping("/api/v1/recalls")
public class RecallController {
    private final RecallService service; private final ConditionSourceService source;
    public RecallController(RecallService service,ConditionSourceService source) { this.service=service; this.source=source; }
    @GetMapping
    public Map<String,Object> list(@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String status,
        @RequestParam(defaultValue="") String sourceType,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return service.listCases(q,status,sourceType,page,size);
    }
    @GetMapping("/{caseId}/assessments")
    public List<Map<String,Object>> assessments(@PathVariable UUID caseId) {return service.listAssessments(caseId);}
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> create(@Valid @RequestBody NewCase body) { return service.createCase(body); }
    @GetMapping("/{caseId}")
    public Map<String,Object> get(@PathVariable UUID caseId) { return service.getCase(caseId); }
    @PostMapping("/{caseId}/conditions") @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> condition(@PathVariable UUID caseId,@Valid @RequestBody NewCondition body) { return service.createCondition(caseId,body); }
    @GetMapping("/{caseId}/conditions/{conditionId}")
    public Map<String,Object> getCondition(@PathVariable UUID caseId,@PathVariable UUID conditionId) { return service.getCondition(caseId,conditionId); }
    @PostMapping("/{caseId}/conditions/{conditionId}/approval")
    public Map<String,Object> approve(@PathVariable UUID caseId,@PathVariable UUID conditionId,@Valid @RequestBody Approval body) { return service.approve(caseId,conditionId,new Approval(CurrentActor.username())); }
    @PostMapping("/{caseId}/conditions/{conditionId}/source-review")
    public Map<String,Object> reviewSource(@PathVariable UUID caseId,@PathVariable UUID conditionId,@Valid @RequestBody ConditionSourceService.Review body) {return source.review(caseId,conditionId,body);}
    @PostMapping("/{caseId}/conditions/{conditionId}/withdrawal")
    public Map<String,Object> withdraw(@PathVariable UUID caseId,@PathVariable UUID conditionId,@Valid @RequestBody ConditionSourceService.Withdrawal body) {source.withdraw(caseId,conditionId,body);return service.getCondition(caseId,conditionId);}
    @PostMapping("/{caseId}/assessments") @ResponseStatus(HttpStatus.CREATED)
    public Assessment assess(@PathVariable UUID caseId,@Valid @RequestBody NewAssessment body) { return service.assess(caseId,body.conditionId()); }
    @GetMapping("/{caseId}/assessments/{runId}")
    public Assessment result(@PathVariable UUID caseId,@PathVariable UUID runId) { return service.getAssessment(caseId,runId); }
}
