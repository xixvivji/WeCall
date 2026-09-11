package com.wecall.recall;

import jakarta.validation.constraints.*;
import java.util.*;

public final class ClosureModels {
    private ClosureModels() {}
    public record CloseRequest(@NotNull UUID assessmentId, @NotNull @Min(0) Long expectedVersion,
        @Pattern(regexp="(?s).*\\S.*") @Size(max=200) String reviewer, @NotBlank @Size(max=2000) String note,
        @AssertTrue(message="대상 범위와 취소·이전 판정 작업까지 검토해야 합니다") boolean responseCoverageConfirmed) {}
    public record ReopenRequest(@NotNull @Min(0) Long expectedVersion,
        @Pattern(regexp="(?s).*\\S.*") @Size(max=200) String reviewer,@NotBlank @Size(max=2000) String note) {}
    public record Issue(String code,String message,long count) {}
}
