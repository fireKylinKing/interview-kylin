package interview.guide.modules.openapi.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.common.async.AbstractStreamConsumer;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.ResumeAnalysisResponse;
import interview.guide.modules.interview.service.InterviewQuestionService;
import interview.guide.modules.openapi.config.OpenApiProperties;
import interview.guide.modules.resume.service.ResumeGradingService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.stream.StreamMessageId;
import org.springframework.stereotype.Component;

import java.time.Duration;
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

  public OpenApiStreamConsumer(
      RedisService redisService,
      ResumeGradingService gradingService,
      InterviewQuestionService questionService,
      OpenApiProperties properties,
      ObjectMapper objectMapper
  ) {
    super(redisService);
    this.gradingService = gradingService;
    this.questionService = questionService;
    this.properties = properties;
    this.objectMapper = objectMapper;
  }

  record OpenApiPayload(
      String taskId, String resumeText, String jdText,
      String skillId, String difficulty, int questionCount, String llmProvider
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
    return new OpenApiPayload(
        taskId,
        data.get(AsyncTaskStreamConstants.FIELD_RESUME_TEXT),
        data.get(AsyncTaskStreamConstants.FIELD_JD_TEXT),
        skillId,
        difficulty,
        questionCount,
        data.get(AsyncTaskStreamConstants.FIELD_LLM_PROVIDER)
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

      // 1. 简历分析（如果有简历文本）
      ResumeAnalysisResponse resumeAnalysis = null;
      if (payload.resumeText() != null && !payload.resumeText().isBlank()) {
        log.info("OpenAPI 开始简历分析: taskId={}", payload.taskId());
        resumeAnalysis = gradingService.analyzeResume(payload.resumeText(), payload.jdText());
      }

      // 2. 生成面试题
      log.info("OpenAPI 开始生成面试题: taskId={}, skillId={}", payload.taskId(), payload.skillId());
      List<InterviewQuestionDTO> questions = questionService.generateQuestionsBySkill(
          payload.llmProvider(),
          payload.skillId(),
          payload.difficulty(),
          payload.resumeText(),
          payload.questionCount(),
          List.of(),
          payload.jdText()
      );

      // 3. 构建结果并存入 Redis
      TaskResult result = new TaskResult(resumeAnalysis, questions);
      String resultJson = objectMapper.writeValueAsString(result);

      redisService().hSet(taskKey, STATUS_FIELD, "COMPLETED");
      redisService().hSet(taskKey, RESULT_FIELD, resultJson);
      redisService().expire(taskKey, Duration.ofSeconds(properties.getTaskTtlSeconds()));
      log.info("OpenAPI 任务完成: taskId={}, questions={}", payload.taskId(), questions.size());
    } catch (Exception e) {
      throw new RuntimeException("OpenAPI 任务处理失败: " + e.getMessage(), e);
    }
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
