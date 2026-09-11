package com.wecall.recall;

import jakarta.validation.constraints.*;
import java.util.UUID;

public final class TaskModels {
    private TaskModels() {}
    public enum TaskType { QUARANTINE, SHIPMENT_HOLD, SALES_HOLD, SUPPLIER_CHECK, RETURN_CONFIRMATION, NOTICE_PREPARATION }
    public enum TargetType { CASE, INVENTORY, SHIPMENT }
    public enum Action { START, COMPLETE, REOPEN, CANCEL }
    public enum ProofDecision { ACCEPTED, REJECTED }
    public record NewTask(@NotNull TaskType taskType, @NotNull TargetType targetType,
        UUID assessmentId, @Size(max=500) String targetId,
        @NotBlank @Size(max=200) String title, @NotBlank @Size(max=10000) String instructions,
        @Size(max=200) String assignee, @NotBlank @Size(max=200) String actor) {}
    public record Assignment(@NotNull @Min(0) Long expectedVersion,
        @NotBlank @Size(max=200) String assignee, @NotBlank @Size(max=200) String actor,
        @NotBlank @Size(max=2000) String note) {}
    public record Transition(@NotNull @Min(0) Long expectedVersion, @NotNull Action action,
        @NotBlank @Size(max=200) String actor, @NotBlank @Size(max=2000) String note) {}
    public record NewProof(@NotNull @Min(0) Long expectedVersion,
        @NotBlank @Size(max=100000) String evidenceText, @NotBlank @Size(max=200) String actor) {}
    public record ProofReview(@NotNull @Min(0) Long expectedVersion, @NotNull ProofDecision decision,
        @NotBlank @Size(max=200) String actor, @NotBlank @Size(max=2000) String note) {}
}
