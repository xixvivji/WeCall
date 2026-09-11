package com.wecall.recall;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static com.wecall.recall.ClosureModels.*;

@RestController
@RequestMapping("/api/v1/recalls/{caseId}")
public class ClosureController {
    private final ClosureService service;
    public ClosureController(ClosureService service) { this.service=service; }
    @GetMapping("/closure-check")
    public Map<String,Object> check(@PathVariable UUID caseId,@RequestParam(required=false) UUID assessmentId) { return service.check(caseId,assessmentId); }
    @PostMapping("/closure")
    public Map<String,Object> close(@PathVariable UUID caseId,@Valid @RequestBody CloseRequest body) { return service.close(caseId,body); }
    @PostMapping("/reopen")
    public Map<String,Object> reopen(@PathVariable UUID caseId,@Valid @RequestBody ReopenRequest body) { return service.reopen(caseId,body); }
    @GetMapping("/lifecycle")
    public Map<String,Object> lifecycle(@PathVariable UUID caseId) { return service.lifecycle(caseId); }
}
