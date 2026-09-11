package com.wecall.recall;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;

public final class RecallModels {
    private RecallModels() {}
    public enum SourceType { SUPPLIER, OFFICIAL, INTERNAL }
    public enum Match { MATCHED, EXCLUDED }
    public enum Decision { TARGET, NON_TARGET, NEEDS_REVIEW }
    public record NewCase(@NotBlank @Size(max=200) String title, @NotNull SourceType sourceType,
                          @NotBlank @Size(max=100000) String sourceText) {}
    // Logical nodes use children; leaves use field and values. No free-form executable conditions.
    public record Rule(String op, String field, List<String> values, List<Rule> children) {}
    public record ProductReview(@NotNull Match status, @NotBlank @Size(max=2000) String reason) {}
    public record NewCondition(@NotNull UUID datasetId, @NotNull Rule rule,
                               @NotEmpty @Size(max=1000) Map<@NotBlank String, @NotNull @Valid ProductReview> productReviews,
                               @NotBlank @Size(max=10000) String sourceQuote) {}
    public record Approval(@Pattern(regexp="(?s).*\\S.*") @Size(max=200) String reviewer) {}
    public record NewAssessment(@NotNull UUID conditionId) {}
    public record ReceiptDecision(String receiptId, String productId, Decision decision, String reason) {}
    public record InventoryImpact(String inventoryId, String receiptId, String warehouse, long quantity,
                                  String holdStatus, Decision decision) {}
    public record ShipmentImpact(String shipmentId, String orderId, long quantity,
                                 long target, long nonTarget, long needsReview, long unlinked) {}
    public record Totals(long target, long nonTarget, long needsReview) {}
    public record Assessment(UUID id, UUID caseId, UUID conditionId, UUID datasetId,
                             List<ReceiptDecision> receipts, List<InventoryImpact> inventory,
                             List<ShipmentImpact> shipments, Totals inventoryTotals, Totals shipmentTotals) {}
}
