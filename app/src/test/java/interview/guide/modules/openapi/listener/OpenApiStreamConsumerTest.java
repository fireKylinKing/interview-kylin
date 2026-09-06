package interview.guide.modules.openapi.listener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.interview.service.InterviewQuestionService;
import interview.guide.modules.openapi.config.OpenApiProperties;
import interview.guide.modules.resume.service.ResumeGradingService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@DisplayName("OpenAPI Stream 消费者测试")
class OpenApiStreamConsumerTest {

  @Mock
  private ResumeGradingService gradingService;

  @Mock
  private InterviewQuestionService questionService;

  private RedisService redisService;
  private OpenApiStreamConsumer consumer;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    redisService = mock(RedisService.class);
    lenient().when(redisService.expire(anyString(), any())).thenReturn(true);
    consumer = new OpenApiStreamConsumer(
        redisService, gradingService, questionService, new OpenApiProperties(), new ObjectMapper(),
        new PromptSanitizer(new LlmProviderProperties()));
  }

  private OpenApiStreamConsumer.OpenApiPayload payload() {
    return new OpenApiStreamConsumer.OpenApiPayload(
        "task-1", "简历全文", "JD 全文", "java-backend", "mid", 10, "deepseek",
        "deep_dive", java.util.List.of("稳定性"), "重点考察银行场景",
        java.util.List.of("旧题一"), "both");
  }

  @Test
  @DisplayName("重试入队必须保留 resumeText/jdText/llmProvider，避免重试后简历分析丢失")
  void retryMessageShouldCarryAllOriginalFields() {
    consumer.retryMessage(payload(), 1);

    var captor = org.mockito.ArgumentCaptor.forClass(Map.class);
    verify(redisService).streamAdd(
        eq(AsyncTaskStreamConstants.OPENAPI_STREAM_KEY), captor.capture(), anyInt());

    @SuppressWarnings("unchecked")
    Map<String, String> message = (Map<String, String>) captor.getValue();
    assertEquals("简历全文", message.get(AsyncTaskStreamConstants.FIELD_RESUME_TEXT));
    assertEquals("JD 全文", message.get(AsyncTaskStreamConstants.FIELD_JD_TEXT));
    assertEquals("deepseek", message.get(AsyncTaskStreamConstants.FIELD_LLM_PROVIDER));
    assertEquals("1", message.get(AsyncTaskStreamConstants.FIELD_RETRY_COUNT));
    assertEquals("deep_dive", message.get(AsyncTaskStreamConstants.FIELD_STYLE));
    assertEquals("稳定性", message.get(AsyncTaskStreamConstants.FIELD_FOCUS_TAGS));
    assertEquals("旧题一", message.get(AsyncTaskStreamConstants.FIELD_PREVIOUS_QUESTIONS));
  }

  @Test
  @DisplayName("任务失败时状态置为 FAILED 并记录截断后的错误")
  void markFailedShouldWriteTruncatedError() {
    String longError = "x".repeat(600);

    consumer.markFailed(payload(), longError);

    verify(redisService).hSet(eq("openapi:task:task-1"), eq("status"), eq("FAILED"));
    var captor = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(redisService).hSet(eq("openapi:task:task-1"), eq("error"), captor.capture());
    assertEquals(500, captor.getValue().length());
  }

  @Test
  @DisplayName("parsePayload 缺少 taskId 或 skillId 时返回 null 交由框架丢弃")
  void parsePayloadShouldRejectMalformedMessage() {
    org.redisson.api.stream.StreamMessageId id = new org.redisson.api.stream.StreamMessageId(1L);

    // 缺少 taskId
    assertEquals(null, consumer.parsePayload(id, Map.of(
        AsyncTaskStreamConstants.FIELD_SKILL_ID, "java-backend")));

    // 缺少 skillId
    assertEquals(null, consumer.parsePayload(id, Map.of(
        AsyncTaskStreamConstants.FIELD_TASK_ID, "task-1")));

    // 字段齐全时正常解析，questionCount 非法值回退默认
    OpenApiStreamConsumer.OpenApiPayload payload = consumer.parsePayload(id, Map.of(
        AsyncTaskStreamConstants.FIELD_TASK_ID, "task-1",
        AsyncTaskStreamConstants.FIELD_SKILL_ID, "java-backend",
        AsyncTaskStreamConstants.FIELD_QUESTION_COUNT, "abc"));
    assertEquals("task-1", payload.taskId());
    assertEquals(10, payload.questionCount());
  }
}
