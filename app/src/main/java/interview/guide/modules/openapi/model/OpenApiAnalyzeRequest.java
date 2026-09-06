package interview.guide.modules.openapi.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record OpenApiAnalyzeRequest(
    String jdText,
    String resumeText,
    @NotBlank String skillId,
    String difficulty,
    @Min(3) @Max(20) int questionCount,
    String llmProvider
) {
  public OpenApiAnalyzeRequest {
    if (questionCount == 0) {
      questionCount = 10;
    }
  }
}
