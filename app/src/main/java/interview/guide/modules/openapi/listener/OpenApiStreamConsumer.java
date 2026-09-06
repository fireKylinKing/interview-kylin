package interview.guide.modules.openapi.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.async.AbstractStreamConsumer;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.interview.model.HistoricalQuestion;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.ResumeAnalysisResponse;
import interview.guide.modules.interview.service.InterviewQuestionService;
import interview.guide.modules.openapi.InterviewStyles;
import interview.guide.modules.openapi.config.OpenApiProperties;
import interview.guide.modules.resume.service.ResumeGradingService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.stream.StreamMessageId;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class OpenApiStreamConsumer extends AbstractStreamConsumer<OpenApiStreamConsumer.OpenApiPayload> {

  private static final String TASK_KEY_PREFIX = "openapi:task:";
  private static final String STATUS_FIELD = "status";
  private static final String ERROR_FIELD = "error";
  private static final String RESULT_FIELD = "result";

  private final ResumeGradingService gradingService;
  private final InterviewQuestionService questionService;
  private final OpenApiProperties properties;
  private final ObjectMapper objectMapper;
  private final PromptSanitizer promptSanitizer;

  public OpenApiStreamConsumer(
      RedisService redisService,
      ResumeGradingService gradingService,
      InterviewQuestionService questionService,
      OpenApiProperties properties,
      ObjectMapper objectMapper,
      PromptSanitizer promptSanitizer
  ) {
    super(redisService);
    this.gradingService = gradingService;
    this.questionService = questionService;
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.promptSanitizer = promptSanitizer;
  }

  record OpenApiPayload(
      String taskId, String resumeText, String jdText,
      String skillId, String difficulty, int questionCount, String llmProvider,
      String style, List<String> focusTags, String extraInstructions,
      List<String> previousQuestions, String mode
  ) {}

  @Override
  protected String taskDisplayName() {
    return "OpenAPI分析";
  }

  @Override
  protected String streamKey() {
    return AsyncTaskStreamConstants.OPENAPI_STREAM_KEY;
  }

  @Override
  protected String groupName() {
    return AsyncTaskStreamConstants.OPENAPI_GROUP_NAME;
  }

  @Override
  protected String consumerPrefix() {
    return AsyncTaskStreamConstants.OPENAPI_CONSUMER_PREFIX;
  }

  @Override
  protected String threadName() {
    return "openapi-consumer";
  }

  @Override
  protected OpenApiPayload parsePayload(StreamMessageId messageId, Map<String, String> data) {
    String taskId = data.get(AsyncTaskStreamConstants.FIELD_TASK_ID);
    String skillId = data.get(AsyncTaskStreamConstants.FIELD_SKILL_ID);
    if (taskId == null || skillId == null) {
      log.warn("OpenAPI 消息格式错误，跳过: messageId={}", messageId);
      return null;
    }
    String difficulty = data.getOrDefault(AsyncTaskStreamConstants.FIELD_DIFFICULTY,
        properties.getDefaultDifficulty());
    int questionCount = parseInt(data.get(AsyncTaskStreamConstants.FIELD_QUESTION_COUNT),
        properties.getDefaultQuestionCount());
    List<String> focusTags = splitAndTrim(data.get(AsyncTaskStreamConstants.FIELD_FOCUS_TAGS), ",");
    List<String> previousQuestions = splitAndTrim(
        data.get(AsyncTaskStreamConstants.FIELD_PREVIOUS_QUESTIONS), "\n");
    return new OpenApiPayload(
        taskId,
        data.get(AsyncTaskStreamConstants.FIELD_RESUME_TEXT),
        data.get(AsyncTaskStreamConstants.FIELD_JD_TEXT),
        skillId,
        difficulty,
        questionCount,
        data.get(AsyncTaskStreamConstants.FIELD_LLM_PROVIDER),
        data.get(AsyncTaskStreamConstants.FIELD_STYLE),
        focusTags,
        data.get(AsyncTaskStreamConstants.FIELD_EXTRA_INSTRUCTIONS),
        previousQuestions,
        data.get(AsyncTaskStreamConstants.FIELD_MODE)
    );
  }

  @Override
  protected String payloadIdentifier(OpenApiPayload payload) {
    return "taskId=" + payload.taskId();
  }

  @Override
  protected void markProcessing(OpenApiPayload payload) {
    updateTaskStatus(payload.taskId(), "PROCESSING", null);
  }

  private record TaskResult(ResumeAnalysisResponse resumeAnalysis, List<InterviewQuestionDTO> questions) {}

  @Override
  protected void processBusiness(OpenApiPayload payload) {
    try {
      String taskKey = TASK_KEY_PREFIX + payload.taskId();
      boolean questionsOnly = "questions".equals(payload.mode());
      boolean analysisOnly = "analysis".equals(payload.mode());

      // 1. 简历分析（有简历文本且非 questions-only 模式）
      ResumeAnalysisResponse resumeAnalysis = null;
      if (!questionsOnly && payload.resumeText() != null && !payload.resumeText().isBlank()) {
        log.info("OpenAPI 开始简历分析: taskId={}", payload.taskId());
        resumeAnalysis = gradingService.analyzeResume(payload.resumeText(), payload.jdText());
      }

      // 2. 生成面试题（analysis-only 模式跳过）
      List<InterviewQuestionDTO> questions = null;
      if (!analysisOnly) {
        String styleDirective = InterviewStyles.compose(
            payload.style(), payload.focusTags(), payload.extraInstructions(), promptSanitizer);
        List<HistoricalQuestion> historical = toHistoricalQuestions(payload.previousQuestions());
        log.info("OpenAPI 开始生成面试题: taskId={}, skillId={}, style={}, focusTags={}",
            payload.taskId(), payload.skillId(), payload.style(), payload.focusTags());
        questions = questionService.generateQuestionsBySkill(
            payload.llmProvider(),
            payload.skillId(),
            payload.difficulty(),
            payload.resumeText(),
            payload.questionCount(),
            historical,
            payload.jdText(),
            styleDirective
        );
      }

      // 3. 构建结果并存入 Redis
      TaskResult result = new TaskResult(resumeAnalysis, questions);
      String resultJson = objectMapper.writeValueAsString(result);

      redisService().hSet(taskKey, STATUS_FIELD, "COMPLETED");
      redisService().hSet(taskKey, RESULT_FIELD, resultJson);
      redisService().expire(taskKey, Duration.ofSeconds(properties.getTaskTtlSeconds()));
      log.info("OpenAPI 任务完成: taskId={}, questions={}",
          payload.taskId(), questions == null ? 0 : questions.size());
    } catch (Exception e) {
      throw new RuntimeException("OpenAPI 任务处理失败: " + e.getMessage(), e);
    }
  }

  private List<HistoricalQuestion> toHistoricalQuestions(List<String> previousQuestions) {
    if (previousQuestions == null || previousQuestions.isEmpty()) {
      return List.of();
    }
    return previousQuestions.stream()
        .filter(q -> q != null && !q.isBlank())
        .map(q -> new HistoricalQuestion(q, null, null))
        .toList();
  }

  private List<String> splitAndTrim(String joined, String separator) {
    if (joined == null || joined.isBlank()) {
      return List.of();
    }
    return Arrays.stream(joined.split(separator))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .toList();
  }

  @Override
  protected void markCompleted(OpenApiPayload payload) {
    // 状态已在 processBusiness 中更新
  }

  @Override
  protected void markFailed(OpenApiPayload payload, String error) {
    updateTaskStatus(payload.taskId(), "FAILED", error);
  }

  @Override
  protected void retryMessage(OpenApiPayload payload, int retryCount) {
    try {
      // 重入队必须携带与首次入队相同的完整字段，否则重试后会静默丢失简历分析
      Map<String, String> message = new HashMap<>();
      message.put(AsyncTaskStreamConstants.FIELD_TASK_ID, payload.taskId());
      message.put(AsyncTaskStreamConstants.FIELD_SKILL_ID, payload.skillId());
      message.put(AsyncTaskStreamConstants.FIELD_DIFFICULTY, payload.difficulty());
      message.put(AsyncTaskStreamConstants.FIELD_QUESTION_COUNT, String.valueOf(payload.questionCount()));
      message.put(AsyncTaskStreamConstants.FIELD_RETRY_COUNT, String.valueOf(retryCount));
      if (payload.resumeText() != null) {
        message.put(AsyncTaskStreamConstants.FIELD_RESUME_TEXT, payload.resumeText());
      }
      if (payload.jdText() != null) {
        message.put(AsyncTaskStreamConstants.FIELD_JD_TEXT, payload.jdText());
      }
      if (payload.llmProvider() != null) {
        message.put(AsyncTaskStreamConstants.FIELD_LLM_PROVIDER, payload.llmProvider());
      }
      if (payload.style() != null) {
        message.put(AsyncTaskStreamConstants.FIELD_STYLE, payload.style());
      }
      if (payload.focusTags() != null && !payload.focusTags().isEmpty()) {
        message.put(AsyncTaskStreamConstants.FIELD_FOCUS_TAGS, String.join(",", payload.focusTags()));
      }
      if (payload.extraInstructions() != null) {
        message.put(AsyncTaskStreamConstants.FIELD_EXTRA_INSTRUCTIONS, payload.extraInstructions());
      }
      if (payload.previousQuestions() != null && !payload.previousQuestions().isEmpty()) {
        List<String> flattened = payload.previousQuestions().stream()
            .map(q -> q == null ? "" : q.replace("\n", " "))
            .toList();
        message.put(AsyncTaskStreamConstants.FIELD_PREVIOUS_QUESTIONS, String.join("\n", flattened));
      }
      if (payload.mode() != null) {
        message.put(AsyncTaskStreamConstants.FIELD_MODE, payload.mode());
      }
      redisService().streamAdd(AsyncTaskStreamConstants.OPENAPI_STREAM_KEY, message,
          AsyncTaskStreamConstants.STREAM_MAX_LEN);
      log.info("OpenAPI 任务已重新入队: taskId={}, retryCount={}", payload.taskId(), retryCount);
    } catch (Exception e) {
      log.error("OpenAPI 重试入队失败: taskId={}", payload.taskId(), e);
      updateTaskStatus(payload.taskId(), "FAILED", "重试入队失败: " + e.getMessage());
    }
  }

  private void updateTaskStatus(String taskId, String status, String error) {
    try {
      String taskKey = TASK_KEY_PREFIX + taskId;
      redisService().hSet(taskKey, STATUS_FIELD, status);
      if (error != null) {
        redisService().hSet(taskKey, ERROR_FIELD, error.length() > 500 ? error.substring(0, 500) : error);
      }
      redisService().expire(taskKey, Duration.ofSeconds(properties.getTaskTtlSeconds()));
    } catch (Exception e) {
      log.error("更新 OpenAPI 任务状态失败: taskId={}, status={}", taskId, status, e);
    }
  }

  private int parseInt(String value, int defaultValue) {
    if (value == null || value.isBlank()) return defaultValue;
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }
}
