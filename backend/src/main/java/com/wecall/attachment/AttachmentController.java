package com.wecall.attachment;
import com.wecall.recall.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.web.multipart.MultipartFile;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/api/v1/recalls/{caseId}")
public class AttachmentController {
    private final AttachmentCreationService creation;private final AttachmentService files;
    public AttachmentController(AttachmentCreationService creation,AttachmentService files){this.creation=creation;this.files=files;}
    @PostMapping(value="/evidence",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> evidence(@PathVariable UUID caseId,@RequestPart("metadata") @Valid EvidenceModels.NewEvidence metadata,@RequestPart("files") List<MultipartFile> uploads){return creation.evidence(caseId,metadata,uploads);}
    @PostMapping(value="/tasks/{taskId}/proofs",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> proof(@PathVariable UUID caseId,@PathVariable UUID taskId,@RequestPart("metadata") @Valid TaskModels.NewProof metadata,@RequestPart("files") List<MultipartFile> uploads){return creation.proof(caseId,taskId,metadata,uploads);}
    @GetMapping("/attachments")
    public List<Map<String,Object>> list(@PathVariable UUID caseId,@RequestParam(required=false) UUID evidenceId,@RequestParam(required=false) UUID proofId){return files.list(caseId,evidenceId,proofId);}
    @GetMapping("/attachments/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable UUID caseId,@PathVariable UUID id){
        var file=files.download(caseId,id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.mediaType()))
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(file.filename(),StandardCharsets.UTF_8).build().toString())
            .header(HttpHeaders.CACHE_CONTROL,"no-store").header("X-Content-Type-Options","nosniff")
            .header("Content-Security-Policy","sandbox; default-src 'none'").body(file.bytes());
    }
}
