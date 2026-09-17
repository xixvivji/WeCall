package com.wecall;

import com.wecall.attachment.MalwareScanner;
import com.wecall.recall.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import java.io.*;
import static org.assertj.core.api.Assertions.*;

class SourcePdfTests {
    SourcePdfController controller = new SourcePdfController(bytes -> new MalwareScanner.Receipt("NOT_SCANNED", null, null));
    static byte[] pdf(String text, int pages) throws Exception {
        try (var doc = new PDDocument(); var out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) {
                var page = new PDPage(); doc.addPage(page);
                if (!text.isEmpty()) try (var stream = new PDPageContentStream(doc, page)) {
                    stream.beginText(); stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    stream.newLineAtOffset(30, 700); stream.showText(text); stream.endText();
                }
            }
            doc.save(out); return out.toByteArray();
        }
    }
    MockMultipartFile file(byte[] bytes) { return new MockMultipartFile("file", "source.pdf", "application/pdf", bytes); }
    @Test void extractsTextLocallyWithoutClaimingScan() throws Exception {
        var response = controller.extract(file(pdf("Recall lot A01", 2)));
        assertThat(response.getBody().text()).contains("Recall lot A01");
        assertThat(response.getBody().pages()).isEqualTo(2);
        assertThat(response.getBody().scanStatus()).isEqualTo("NOT_SCANNED");
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    }
    @Test void rejectsScansMalformedAndTooManyPages() throws Exception {
        for (byte[] bytes : new byte[][]{pdf("",1), pdf("text",51), "%PDF-broken".getBytes(), new byte[0], new byte[10485761]})
            assertThatThrownBy(() -> controller.extract(file(bytes))).isInstanceOf(RecallService.Failure.class);
    }
    @Test void rejectsEncryption() throws Exception {
        byte[] bytes;
        try (var doc = new PDDocument(); var out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage());
            doc.protect(new org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy("owner", "secret", new org.apache.pdfbox.pdmodel.encryption.AccessPermission()));
            doc.save(out); bytes = out.toByteArray();
        }
        assertThatThrownBy(() -> controller.extract(file(bytes))).isInstanceOf(RecallService.Failure.class);
    }
    @Test void scanFailureStopsBeforePdfParsing() {
        var blocked = new SourcePdfController(bytes -> {throw new RecallService.Failure(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"검사 실패");});
        assertThatThrownBy(() -> blocked.extract(file("%PDF-broken".getBytes()))).hasMessage("검사 실패");
    }
}
