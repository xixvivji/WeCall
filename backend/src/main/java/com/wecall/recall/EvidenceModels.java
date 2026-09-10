package com.wecall.recall;

import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.UUID;

public final class EvidenceModels {
    private EvidenceModels() {}
    public record NewEvidence(
        @NotNull UUID baseAssessmentId,
        @NotBlank @Size(max=500) String receiptId,
        @NotBlank @Size(max=100000) String documentText,
        @NotBlank @Size(max=10000) String sourceQuote,
        @NotBlank @Size(max=500) String observedProductId,
        @NotNull @Min(0) @Max(1000000000) Long observedReceivedQuantity,
        @NotNull LocalDate observedReceivedAt,
        @Size(max=500) String lotNumber,
        LocalDate expiryDate
    ) {}
    public record ApproveEvidence(
        @NotBlank @Size(max=200) String reviewer,
        @NotBlank @Size(max=2000) String note,
        @AssertTrue(message="입고 건·상품·수량·날짜와 단일 제조분 증거를 직접 확인해야 합니다")
        boolean receiptAndSingleLotConfirmed
    ) {}
    public record RejectEvidence(@NotBlank @Size(max=200) String reviewer,
                                 @NotBlank @Size(max=2000) String note) {}
    public record Issue(String field, String code, String message) {}
    public record ReceiptFacts(String productId, long receivedQuantity, LocalDate receivedAt,
                               String lotNumber, LocalDate expiryDate) {}
}
