package com.wecall.recall;

import com.wecall.attachment.MalwareScanner;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Semaphore;

/** Transient local extraction. No document or extraction is persisted by this endpoint. */
@RestController
@RequestMapping("/api/v1/source-pdf")
public class SourcePdfController {
    private final MalwareScanner scanner;
    private final Semaphore slots = new Semaphore(2);
    public SourcePdfController(MalwareScanner scanner) { this.scanner = scanner; }
    public record Preview(String text, int pages, String scanStatus) {}
    private RecallService.Failure fail(HttpStatus status, String message) { return new RecallService.Failure(status, message); }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Preview> extract(@RequestPart("file") MultipartFile file) {
        if (!slots.tryAcquire()) throw fail(HttpStatus.SERVICE_UNAVAILABLE, "PDF 추출 중입니다. 잠시 후 다시 시도하세요");
        try {
            if (file.getSize() > 10485760) throw fail(HttpStatus.PAYLOAD_TOO_LARGE, "PDF는 10 MiB 이하여야 합니다");
            byte[] bytes;
            try (var input = file.getInputStream()) { bytes = input.readNBytes(10485761); }
            if (bytes.length > 10485760) throw fail(HttpStatus.PAYLOAD_TOO_LARGE, "PDF는 10 MiB 이하여야 합니다");
            if (bytes.length < 5 || !new String(bytes, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-"))
                throw fail(HttpStatus.BAD_REQUEST, "PDF 파일을 선택하세요");
            var scan = scanner.scan(bytes);
            try (var document = Loader.loadPDF(bytes)) {
                if (document.isEncrypted()) throw fail(HttpStatus.BAD_REQUEST, "암호화 PDF는 지원하지 않습니다");
                int pages = document.getNumberOfPages();
                if (pages < 1 || pages > 50) throw fail(HttpStatus.BAD_REQUEST, "PDF는 1~50쪽이어야 합니다");
                var writer = new Writer() {
                    final StringBuilder text = new StringBuilder();
                    public void write(char[] chars, int offset, int length) {
                        if (text.length() + length > 100000) throw fail(HttpStatus.PAYLOAD_TOO_LARGE, "추출 원문은 100,000자 이하여야 합니다. 필요한 쪽만 준비하세요");
                        text.append(chars, offset, length);
                    }
                    public void flush() {}
                    public void close() {}
                    public String toString() { return text.toString(); }
                };
                var stripper = new PDFTextStripper();
                stripper.setSortByPosition(true);
                stripper.writeText(document, writer);
                String text = writer.toString().strip();
                if (text.isBlank()) throw fail(HttpStatus.UNPROCESSABLE_ENTITY, "추출할 텍스트가 없습니다. 스캔 PDF의 OCR은 아직 지원하지 않으므로 원문을 직접 입력하세요");
                return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Preview(text, pages, scan.status()));
            }
        } catch (IOException | IllegalArgumentException e) {
            throw fail(HttpStatus.BAD_REQUEST, "PDF를 읽을 수 없습니다. 손상되거나 암호화된 파일인지 확인하세요");
        } finally { slots.release(); }
    }
}
