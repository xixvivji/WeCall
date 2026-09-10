package com.wecall.recall;

import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestControllerAdvice(assignableTypes = {RecallController.class, EvidenceController.class})
class RecallErrors {
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
    ResponseEntity<?> invalidId(Exception e) {
        return ResponseEntity.badRequest().body(Map.of("code","INVALID_ID","message","경로 ID는 UUID 형식이어야 합니다"));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<?> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(Map.of("code","INVALID_JSON","message","JSON 필드 타입과 형식을 확인하세요"));
    }
}
