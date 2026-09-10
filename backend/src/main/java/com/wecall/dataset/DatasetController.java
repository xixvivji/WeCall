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
    public DatasetController(DatasetService service) { this.service = service; }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> upload(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime asOf,
        @RequestPart MultipartFile products, @RequestPart MultipartFile receipts,
        @RequestPart MultipartFile inventory, @RequestPart MultipartFile shipments,
        @RequestPart MultipartFile shipmentAllocations) {
        return service.importFiles(asOf, Map.of("products",products,"receipts",receipts,"inventory",inventory,
            "shipments",shipments,"shipment_allocations",shipmentAllocations));
    }
}

@RestControllerAdvice(assignableTypes = DatasetController.class)
class DatasetErrors {
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
