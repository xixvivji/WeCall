package com.wecall.recall;

import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/api/v1/recalls")
public class SourceDocumentController {
    private final SourceDocumentService service;
    public SourceDocumentController(SourceDocumentService service) {this.service=service;}
    @PostMapping(value="/from-pdf",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> register(@RequestPart("metadata") @Valid SourceDocumentService.Registration metadata,@RequestPart("file") MultipartFile file) {return service.register(metadata,file);}
    @GetMapping("/{caseId}/source")
    public ResponseEntity<?> history(@PathVariable UUID caseId,@RequestParam(defaultValue="0") int page) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.history(caseId,page));
    }
    @GetMapping("/{caseId}/source/revisions/{version}")
    public ResponseEntity<?> revision(@PathVariable UUID caseId,@PathVariable long version) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.revision(caseId,version));
    }
    @PostMapping("/{caseId}/source/revisions") @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> revise(@PathVariable UUID caseId,@Valid @RequestBody SourceDocumentService.Revision body) {return service.revise(caseId,body);}
    @GetMapping("/{caseId}/source/documents/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable UUID caseId,@PathVariable UUID id) {
        var file=service.download(caseId,id);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(file.filename(),StandardCharsets.UTF_8).build().toString())
            .header(HttpHeaders.CACHE_CONTROL,"no-store").header("X-Content-Type-Options","nosniff")
            .header("Content-Security-Policy","sandbox; default-src 'none'").body(file.bytes());
    }
}
