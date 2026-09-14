package com.wecall.recall;

import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestControllerAdvice(assignableTypes = {CaseHistoryController.class,com.wecall.attachment.AttachmentController.class,AssessmentExportController.class, WorkspaceController.class, RecallController.class, EvidenceController.class, TaskController.class, ClosureController.class, ExtractionController.class, com.wecall.auth.AuthController.class})
class RecallErrors {
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<?> attachmentTooLarge(Exception e){return ResponseEntity.status(413).body(Map.of("code","UPLOAD_TOO_LARGE","message","첨부 파일당 10 MiB, 요청당 52 MiB 제한입니다"));}
    @ExceptionHandler(org.springframework.web.multipart.support.MissingServletRequestPartException.class)
    ResponseEntity<?> missingPart(Exception e){return ResponseEntity.badRequest().body(Map.of("code","INVALID_REQUEST","message","metadata JSON과 files 첨부가 필요합니다"));}
    @ExceptionHandler(ClosureService.Blocked.class)
    ResponseEntity<?> closureBlocked(ClosureService.Blocked e) {
        return ResponseEntity.status(409).body(Map.of("code","CLOSURE_BLOCKED","message",e.getMessage(),"check",e.check));
    }
    @ExceptionHandler(EvidenceService.Blocked.class)
    ResponseEntity<?> evidenceBlocked(EvidenceService.Blocked e) {
        return ResponseEntity.status(409).body(Map.of("code","EVIDENCE_BLOCKED","message",e.getMessage(),"issues",e.issues));
    }
    @ExceptionHandler(RecallService.Failure.class)
    ResponseEntity<?> failure(RecallService.Failure e) {
        return ResponseEntity.status(e.status).body(Map.of("code",e.status.name(),"message",e.getMessage()));
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<?> invalid(MethodArgumentNotValidException e) {
        return ResponseEntity.badRequest().body(Map.of("code","INVALID_REQUEST","errors",e.getBindingResult().getFieldErrors().stream()
            .map(f->Map.of("field",f.getField(),"message",String.valueOf(f.getDefaultMessage()))).toList()));
    }
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    ResponseEntity<?> invalidId(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException e) {
        if(!java.util.UUID.class.equals(e.getRequiredType())) return ResponseEntity.badRequest().body(Map.of("code","INVALID_REQUEST","message","조회 매개변수의 타입과 형식을 확인하세요"));
        return ResponseEntity.badRequest().body(Map.of("code","INVALID_ID","message","경로 ID는 UUID 형식이어야 합니다"));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<?> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(Map.of("code","INVALID_JSON","message","JSON 필드 타입과 형식을 확인하세요"));
    }
}
