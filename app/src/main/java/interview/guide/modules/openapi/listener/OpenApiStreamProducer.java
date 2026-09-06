package interview.guide.modules.openapi.listener;

import interview.guide.common.async.AbstractStreamProducer;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.infrastructure.redis.RedisService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class OpenApiStreamProducer extends AbstractStreamProducer<OpenApiStreamProducer.OpenApiTaskPayload> {

  public record OpenApiTaskPayload(
      String taskId, String resumeText, String jdText,
      String skillId, String difficulty, int questionCount, String llmProvider
  ) {}

  public OpenApiStreamProducer(RedisService redisService) {
    super(redisService);
  }

  public void submitTask(OpenApiTaskPayload payload) {
    sendTask(payload);
  }

  @Override
  protected String taskDisplayName() {
    return "OpenAPI分析";
  }

  @Override
  protected String streamKey() {
    return AsyncTaskStreamConstants.OPENAPI_STREAM_KEY;
  }

  @Override
  protected Map<String, String> buildMessage(OpenApiTaskPayload payload) {
    Map<String, String> msg = new HashMap<>();
    msg.put(AsyncTaskStreamConstants.FIELD_TASK_ID, payload.taskId());
    msg.put(AsyncTaskStreamConstants.FIELD_SKILL_ID, payload.skillId());
    msg.put(AsyncTaskStreamConstants.FIELD_DIFFICULTY, payload.difficulty());
    msg.put(AsyncTaskStreamConstants.FIELD_QUESTION_COUNT, String.valueOf(payload.questionCount()));
    msg.put(AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0");
    if (payload.resumeText() != null) {
      msg.put(AsyncTaskStreamConstants.FIELD_RESUME_TEXT, payload.resumeText());
    }
    if (payload.jdText() != null) {
      msg.put(AsyncTaskStreamConstants.FIELD_JD_TEXT, payload.jdText());
    }
    if (payload.llmProvider() != null) {
      msg.put(AsyncTaskStreamConstants.FIELD_LLM_PROVIDER, payload.llmProvider());
    }
    return msg;
  }

  @Override
  protected String payloadIdentifier(OpenApiTaskPayload payload) {
    return "taskId=" + payload.taskId();
  }

  @Override
  protected void onSendFailed(OpenApiTaskPayload payload, String error) {
    log.error("OpenAPI 任务入队失败: taskId={}, error={}", payload.taskId(), error);
  }
}
