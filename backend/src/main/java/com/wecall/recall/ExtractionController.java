package com.wecall.recall;

import com.wecall.auth.CurrentActor;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static com.wecall.recall.ExtractionModels.*;

@RestController
@RequestMapping("/api/v1/recalls/{caseId}/extractions")
public class ExtractionController {
    private final ExtractionService service;
    public ExtractionController(ExtractionService service){this.service=service;}
    @PostMapping @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String,Object> enqueue(@PathVariable UUID caseId){return service.enqueue(caseId,CurrentActor.username());}
    @GetMapping public List<Map<String,Object>> list(@PathVariable UUID caseId){return service.list(caseId);}
    @GetMapping("/{jobId}") public Map<String,Object> get(@PathVariable UUID caseId,@PathVariable UUID jobId){return service.get(caseId,jobId);}
    @PostMapping("/{jobId}/condition") @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> convert(@PathVariable UUID caseId,@PathVariable UUID jobId,@Valid @RequestBody Conversion body){return service.convert(caseId,jobId,body,CurrentActor.username());}
    @PostMapping("/{jobId}/dismissal") public Map<String,Object> dismiss(@PathVariable UUID caseId,@PathVariable UUID jobId,@Valid @RequestBody Dismissal body){return service.dismiss(caseId,jobId,body,CurrentActor.username());}
}
