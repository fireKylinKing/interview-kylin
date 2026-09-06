package interview.guide.modules.openapi.model;

import interview.guide.modules.interview.model.ResumeAnalysisResponse;
import interview.guide.modules.interview.model.InterviewQuestionDTO;

import java.util.List;

public record OpenApiTaskResultResponse(
    String taskId,
    String status,
    String error,
    ResumeAnalysisResponse resumeAnalysis,
    List<InterviewQuestionDTO> questions
) {
}
