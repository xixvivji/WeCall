package com.wecall.recall;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static com.wecall.recall.EvidenceModels.*;

@RestController
@RequestMapping("/api/v1/recalls/{caseId}/evidence")
public class EvidenceController {
    private final EvidenceService service;
    public EvidenceController(EvidenceService service) { this.service=service; }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> create(@PathVariable UUID caseId,@Valid @RequestBody NewEvidence body) { return service.create(caseId,body); }
    @GetMapping("/{evidenceId}")
    public Map<String,Object> get(@PathVariable UUID caseId,@PathVariable UUID evidenceId) { return service.get(caseId,evidenceId); }
    @PostMapping("/{evidenceId}/approval")
    public Map<String,Object> approve(@PathVariable UUID caseId,@PathVariable UUID evidenceId,@Valid @RequestBody ApproveEvidence body) { return service.approve(caseId,evidenceId,body); }
    @PostMapping("/{evidenceId}/rejection")
    public Map<String,Object> reject(@PathVariable UUID caseId,@PathVariable UUID evidenceId,@Valid @RequestBody RejectEvidence body) { return service.reject(caseId,evidenceId,body); }
}
