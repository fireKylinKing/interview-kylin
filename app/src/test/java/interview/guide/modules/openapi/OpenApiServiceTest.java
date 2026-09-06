package interview.guide.modules.openapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.file.DocumentParseService;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.openapi.config.OpenApiProperties;
import interview.guide.modules.openapi.listener.OpenApiStreamProducer;
import interview.guide.modules.openapi.model.OpenApiAnalyzeRequest;
import interview.guide.modules.openapi.model.OpenApiTaskResponse;
import interview.guide.modules.openapi.model.OpenApiTaskResultResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.mock.web.MockMultipartFile;

@DisplayName("OpenAPI 服务测试")
class OpenApiServiceTest {

  @Mock
  private OpenApiStreamProducer streamProducer;

  @Mock
  private RedisService redisService;

  @Mock
  private DocumentParseService documentParseService;

  private OpenApiProperties properties;
  private OpenApiService service;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    properties = new OpenApiProperties();
    service = new OpenApiService(streamProducer, redisService, documentParseService, properties, new ObjectMapper());
    lenient().when(redisService.expire(anyString(), any(Duration.class))).thenReturn(true);
  }

  private OpenApiAnalyzeRequest request(String resumeText) {
    return new OpenApiAnalyzeRequest("Java 后端工程师 JD", resumeText, "java-backend", "mid", 0, null);
  }

  @Test
  @DisplayName("提交任务：写入 PENDING 状态并投递完整字段到 Stream")
  void shouldSubmitTaskWithPendingStatus() {
    OpenApiTaskResponse resp = service.submitTask(request("简历文本"), null);

    verify(redisService).hSet(eq("openapi:task:" + resp.taskId()), eq("status"), eq("PENDING"));
    verify(streamProducer).submitTask(any(OpenApiStreamProducer.OpenApiTaskPayload.class));
  }

  @Test
  @DisplayName("提交任务：上传文件解析为简历文本")
  void shouldParseResumeFile() {
    MockMultipartFile file = new MockMultipartFile(
        "resumeFile", "简历.pdf", "application/pdf", "%PDF-1.4 fake".getBytes());
    when(documentParseService.parseContent(any())).thenReturn("解析后的简历文本");

    service.submitTask(request(null), file);

    verify(documentParseService).parseContent(file);
    verify(streamProducer).submitTask(any(OpenApiStreamProducer.OpenApiTaskPayload.class));
  }

  @Test
  @DisplayName("提交任务：文件超过大小上限时拒绝")
  void shouldRejectOversizeFile() {
    properties.setMaxFileSizeBytes(1024);
    MockMultipartFile file = new MockMultipartFile(
        "resumeFile", "简历.pdf", "application/pdf", new byte[2048]);

    BusinessException ex = assertThrows(BusinessException.class, () -> service.submitTask(request(null), file));
    assertEquals(ErrorCode.OPENAPI_INVALID_REQUEST.getCode(), ex.getCode());
    verify(documentParseService, never()).parseContent(any());
  }

  @Test
  @DisplayName("提交任务：不支持的扩展名时拒绝")
  void shouldRejectUnsupportedExtension() {
    MockMultipartFile file = new MockMultipartFile(
        "resumeFile", "photo.png", "image/png", new byte[10]);

    BusinessException ex = assertThrows(BusinessException.class, () -> service.submitTask(request(null), file));
    assertEquals(ErrorCode.OPENAPI_INVALID_REQUEST.getCode(), ex.getCode());
  }

  @Test
  @DisplayName("查询结果：任务不存在时抛出业务异常")
  void shouldThrowWhenTaskMissing() {
    when(redisService.hGetAll(anyString())).thenReturn(Map.of());

    assertThrows(BusinessException.class, () -> service.getTaskResult("ghost"));
  }

  @Test
  @DisplayName("查询结果：COMPLETED 但结果损坏时返回 FAILED 而非空成功")
  void shouldReturnFailedWhenResultCorrupted() {
    Map<Object, Object> data = new HashMap<>();
    data.put("status", "COMPLETED");
    data.put("result", "{not-valid-json");
    when(redisService.hGetAll(anyString())).thenReturn(data);

    OpenApiTaskResultResponse resp = service.getTaskResult("task-1");

    assertEquals("FAILED", resp.status());
    verify(redisService).hSet(eq("openapi:task:task-1"), eq("status"), eq("FAILED"));
  }

  @Test
  @DisplayName("查询结果：正常结果反序列化为分析+题目")
  void shouldDeserializeCompletedResult() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    String resultJson = mapper.writeValueAsString(Map.of(
        "resumeAnalysis", Map.of("overallScore", 82),
        "questions", List.of()
    ));
    Map<Object, Object> data = new HashMap<>();
    data.put("status", "COMPLETED");
    data.put("result", resultJson);
    when(redisService.hGetAll(anyString())).thenReturn(data);

    OpenApiTaskResultResponse resp = service.getTaskResult("task-1");

    assertEquals("COMPLETED", resp.status());
    assertTrue(resp.resumeAnalysis() != null && resp.resumeAnalysis().overallScore() == 82);
    List<InterviewQuestionDTO> questions = resp.questions();
    assertEquals(0, questions == null ? 0 : questions.size());
  }
}
