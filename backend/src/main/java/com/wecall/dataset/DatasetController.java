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

    @GetMapping("/templates")
    public java.util.List<CsvSchema.Template> templates() {return CsvSchema.TEMPLATES;}

    @GetMapping("/templates/{type}.csv")
    public ResponseEntity<byte[]> template(@PathVariable String type) {
        var template=CsvSchema.find(type);
        return download(template.type()+".csv","text/csv; charset=UTF-8",("\ufeff"+template.header()+"\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    @GetMapping("/templates.zip")
    public ResponseEntity<byte[]> templatesZip() throws java.io.IOException {
        var bytes=new java.io.ByteArrayOutputStream();
        try(var zip=new java.util.zip.ZipOutputStream(bytes,java.nio.charset.StandardCharsets.UTF_8)) {
            for(var template:CsvSchema.TEMPLATES) {
                zip.putNextEntry(new java.util.zip.ZipEntry(template.type()+".csv"));
                zip.write(("\ufeff"+template.header()+"\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();
            }
            zip.putNextEntry(new java.util.zip.ZipEntry("작성안내.txt"));
            var guide=new StringBuilder("WeCall CSV 작성 안내\n빈 양식 5종입니다. 예시 데이터는 넣지 않았습니다.\nUTF-8 CSV로 저장하고 헤더와 순서를 유지하세요. 파일당 5 MiB, 데이터 10000행, 텍스트 500자 제한입니다.\n제조번호·소비기한만 빈칸을 허용합니다. 없는 출고 연결은 추정하지 마세요. 기록이 없는 파일도 헤더를 유지해서 함께 제출하세요.\nID는 텍스트로 입력해 앞자리 0을 보존하고 날짜는 YYYY-MM-DD, 수량은 정수 EA로 작성하세요.\n오류 행은 헤더를 1로 센 CSV 기록 번호입니다. 인용된 셀 안의 줄바꿈은 새 기록으로 세지 않습니다.\n");
            for(var template:CsvSchema.TEMPLATES) {
                guide.append("\n").append(template.label()).append(" · ").append(template.type()).append(".csv\n");
                for(var c:template.columns())guide.append(c.name()).append(" · ").append(c.label()).append(c.required()?" (필수) · ":" (선택) · ").append(c.guidance()).append(" 예: ").append(c.example()).append("\n");
            }
            zip.write(guide.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();
        }
        return download("wecall-csv-templates.zip","application/zip",bytes.toByteArray());
    }
    private ResponseEntity<byte[]> download(String filename,String media,byte[] bytes) {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(filename).build().toString())
            .header("X-Content-Type-Options","nosniff").contentType(MediaType.parseMediaType(media)).body(bytes);
    }

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
        return ResponseEntity.badRequest().body(Map.of("code","INVALID_DATASET","errors",e.errors,"errorCount",e.errorCount,"truncated",e.errorCount>e.errors.size()));
    }
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<?> type(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest().body(Map.of("code","INVALID_REQUEST","message",e.getName()+"의 형식을 확인하세요"));
    }
    @ExceptionHandler({MissingServletRequestPartException.class, MissingServletRequestParameterException.class})
    ResponseEntity<?> request(Exception e) {
        return ResponseEntity.badRequest().body(Map.of("code","INVALID_REQUEST","message","파일 5종과 타임존을 포함한 asOf가 필요합니다"));
    }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<?> tooLarge(Exception e) {
        return ResponseEntity.status(413).body(Map.of("code","UPLOAD_TOO_LARGE","message","파일당 5MB, 요청당 26MB 제한"));
    }
}
