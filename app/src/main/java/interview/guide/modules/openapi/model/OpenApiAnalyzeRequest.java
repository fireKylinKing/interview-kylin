package interview.guide.modules.openapi.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record OpenApiAnalyzeRequest(
    String jdText,
    String resumeText,
    @NotBlank String skillId,
    String difficulty,
    @Min(3) @Max(20) int questionCount,
    String llmProvider,
    // 面试风格：standard / deep_dive / scenario / quick，未识别回退 standard
    String style,
    // 考察重点标签（白名单见 InterviewStyles），最多 4 个
    @Size(max = 4) List<String> focusTags,
    // 调用方补充出题要求（≤500 字符，超长截断）
    String extraInstructions,
    // 历史已出题目列表，用于避免重复出题，最多 30 条
    @Size(max = 30) List<String> previousQuestions,
    // 任务模式：both（评分+出题，默认）/ questions（仅出题）/ analysis（仅评分）
    String mode
) {
  public OpenApiAnalyzeRequest {
    if (questionCount == 0) {
      questionCount = 10;
    }
    if (focusTags != null && focusTags.size() > 4) {
      focusTags = focusTags.subList(0, 4);
    }
    if (previousQuestions != null && previousQuestions.size() > 30) {
      previousQuestions = previousQuestions.subList(0, 30);
    }
  }

  public boolean questionsOnly() {
    return "questions".equals(mode);
  }

  public boolean analysisOnly() {
    return "analysis".equals(mode);
  }
}
