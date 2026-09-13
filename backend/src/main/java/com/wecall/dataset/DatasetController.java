package com.wecall.dataset;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.format.annotation.DateTimeFormat;
import java.time.OffsetDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/datasets")
public class DatasetController {
    private final DatasetService service;
    private final ReadinessService readiness;
    public DatasetController(DatasetService service,ReadinessService readiness) { this.service = service; this.readiness=readiness; }

    @GetMapping("/{id}/provenance")
    public Map<String,Object> provenance(@PathVariable java.util.UUID id) {return service.provenance(id);}
    @GetMapping("/{id}/readiness")
    public Map<String,Object> readiness(@PathVariable java.util.UUID id) {return readiness.summary(id);}
    @GetMapping("/{id}/readiness/issues")
    public Map<String,Object> issues(@PathVariable java.util.UUID id,@RequestParam(defaultValue="receipts") String type,
        @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {return readiness.issues(id,type,page,size);}

    @GetMapping
    public Map<String,Object> list(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {return service.list(page,size);}
    @GetMapping("/{id}/products")
    public Map<String,Object> products(@PathVariable java.util.UUID id,@RequestParam(defaultValue="") String q,
        @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {return service.products(id,q,page,size);}

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> upload(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime asOf,
        @RequestPart MultipartFile products, @RequestPart MultipartFile receipts,
        @RequestPart MultipartFile inventory, @RequestPart MultipartFile shipments,
        @RequestPart MultipartFile shipmentAllocations) {
        return service.importFiles(asOf, Map.of("products",products,"receipts",receipts,"inventory",inventory,
            "shipments",shipments,"shipment_allocations",shipmentAllocations),com.wecall.auth.CurrentActor.username());
    }
}

@RestControllerAdvice(assignableTypes = DatasetController.class)
class DatasetErrors {
    @ExceptionHandler(com.wecall.recall.RecallService.Failure.class)
    ResponseEntity<?> failure(com.wecall.recall.RecallService.Failure e) {
        return ResponseEntity.status(e.status).body(Map.of("code",e.status.name(),"message",e.getMessage()));
    }
    @ExceptionHandler(DatasetService.InvalidDataset.class)
    ResponseEntity<?> invalid(DatasetService.InvalidDataset e) {
        return ResponseEntity.badRequest().body(Map.of("code","INVALID_DATASET","errors",e.errors));
    }
    @ExceptionHandler({MissingServletRequestPartException.class, MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> request(Exception e) {
        return ResponseEntity.badRequest().body(Map.of("code","INVALID_REQUEST","message","파일 5종과 타임존을 포함한 asOf가 필요합니다"));
    }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<?> tooLarge(Exception e) {
        return ResponseEntity.status(413).body(Map.of("code","UPLOAD_TOO_LARGE","message","파일당 5MB, 요청당 26MB 제한"));
    }
}
