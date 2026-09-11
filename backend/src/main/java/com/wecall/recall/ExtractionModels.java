package com.wecall.recall;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;

public final class ExtractionModels {
    private ExtractionModels() {}
    public record Result(UUID requestId,String sourceSha256,String schemaVersion,String mode,String provider,
        String model,String promptVersion,RecallModels.Rule rule,String sourceQuote,List<String> warnings) {}
    public record Conversion(@NotNull @Valid RecallModels.NewCondition definition,@NotBlank @Size(max=2000) String note) {}
    public record Dismissal(@NotBlank @Size(max=2000) String note) {}
}
