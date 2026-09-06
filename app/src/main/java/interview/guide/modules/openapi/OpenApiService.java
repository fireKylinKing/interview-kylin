package interview.guide.modules.openapi;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.file.DocumentParseService;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.ResumeAnalysisResponse;
import interview.guide.modules.openapi.config.OpenApiProperties;
import interview.guide.modules.openapi.listener.OpenApiStreamProducer;
import interview.guide.modules.openapi.model.OpenApiAnalyzeRequest;
import interview.guide.modules.openapi.model.OpenApiTaskResponse;
import interview.guide.modules.openapi.model.OpenApiTaskResultResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OpenApiService {

  private static final String TASK_KEY_PREFIX = "openapi:task:";
  private static final String STATUS_FIELD = "status";
  private static final String ERROR_FIELD = "error";
  private static final String RESULT_FIELD = "result";

  private static final List<String> ALLOWED_EXTENSIONS = List.of(".pdf", ".doc", ".docx", ".txt");

  private final OpenApiStreamProducer streamProducer;
  private final RedisService redisService;
  private final DocumentParseService documentParseService;
  private final OpenApiProperties properties;
  private final ObjectMapper objectMapper;

  public OpenApiTaskResponse submitTask(OpenApiAnalyzeRequest request, MultipartFile resumeFile) {
    // skillId 由 @NotBlank 校验保证必填；简历文本/文件可选（无简历时按技能题库直接出题）
    String resumeText = resolveResumeText(request.resumeText(), resumeFile);

    String taskId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    redisService.hSet(taskKey(taskId), STATUS_FIELD, "PENDING");
    redisService.expire(taskKey(taskId), java.time.Duration.ofSeconds(properties.getTaskTtlSeconds()));

    String difficulty = request.difficulty() != null ? request.difficulty() : properties.getDefaultDifficulty();
    int questionCount = request.questionCount() > 0 ? request.questionCount() : properties.getDefaultQuestionCount();

    streamProducer.submitTask(new OpenApiStreamProducer.OpenApiTaskPayload(
        taskId, resumeText, request.jdText(), request.skillId(),
        difficulty, questionCount, request.llmProvider()
    ));

    log.info("OpenAPI 任务已提交: taskId={}, skillId={}", taskId, request.skillId());
    return new OpenApiTaskResponse(taskId);
  }

  public OpenApiTaskResultResponse getTaskResult(String taskId) {
    Map<Object, Object> data = redisService.hGetAll(taskKey(taskId));
    if (data.isEmpty()) {
      throw new BusinessException(ErrorCode.OPENAPI_TASK_NOT_FOUND);
    }

    String status = (String) data.get(STATUS_FIELD);
    String error = (String) data.get(ERROR_FIELD);
    String resultJson = (String) data.get(RESULT_FIELD);

    ResumeAnalysisResponse resumeAnalysis = null;
    List<InterviewQuestionDTO> questions = null;

    if ("COMPLETED".equals(status) && resultJson != null) {
      try {
        Map<String, Object> result = objectMapper.readValue(resultJson, new TypeReference<>() {});
        String analysisJson = objectMapper.writeValueAsString(result.get("resumeAnalysis"));
        String questionsJson = objectMapper.writeValueAsString(result.get("questions"));
        if (result.get("resumeAnalysis") != null) {
          resumeAnalysis = objectMapper.readValue(analysisJson, ResumeAnalysisResponse.class);
        }
        if (result.get("questions") != null) {
          questions = objectMapper.readValue(questionsJson, new TypeReference<>() {});
        }
      } catch (Exception e) {
        log.error("解析 OpenAPI 结果失败: taskId={}", taskId, e);
        // 结果损坏时不能返回"完成但为空"的语义，置为失败让调用方明确感知
        redisService.hSet(taskKey(taskId), STATUS_FIELD, "FAILED");
        redisService.hSet(taskKey(taskId), ERROR_FIELD, "结果解析失败");
        return new OpenApiTaskResultResponse(taskId, "FAILED", "结果解析失败", null, null);
      }
    }

    return new OpenApiTaskResultResponse(taskId, status, error, resumeAnalysis, questions);
  }

  private String resolveResumeText(String resumeText, MultipartFile resumeFile) {
    if (resumeText != null && !resumeText.isBlank()) {
      return resumeText;
    }
    if (resumeFile != null && !resumeFile.isEmpty()) {
      validateResumeFile(resumeFile);
      try {
        return documentParseService.parseContent(resumeFile);
      } catch (BusinessException e) {
        throw e;
      } catch (Exception e) {
        throw new BusinessException(ErrorCode.OPENAPI_FILE_PARSE_FAILED, e.getMessage());
      }
    }
    return null;
  }

  private void validateResumeFile(MultipartFile file) {
    if (file.getSize() > properties.getMaxFileSizeBytes()) {
      throw new BusinessException(ErrorCode.OPENAPI_INVALID_REQUEST,
          "简历文件不能超过 " + (properties.getMaxFileSizeBytes() / 1024 / 1024) + "MB");
    }
    String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
    boolean allowed = ALLOWED_EXTENSIONS.stream().anyMatch(filename::endsWith);
    if (!allowed) {
      throw new BusinessException(ErrorCode.OPENAPI_INVALID_REQUEST, "仅支持 PDF / DOC / DOCX / TXT 简历文件");
    }
  }

  private String taskKey(String taskId) {
    return TASK_KEY_PREFIX + taskId;
  }
}
