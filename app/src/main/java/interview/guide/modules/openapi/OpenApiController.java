package interview.guide.modules.openapi;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.result.Result;
import interview.guide.modules.openapi.model.OpenApiAnalyzeRequest;
import interview.guide.modules.openapi.model.OpenApiTaskResponse;
import interview.guide.modules.openapi.model.OpenApiTaskResultResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/openapi")
@RequiredArgsConstructor
@Tag(name = "Open API", description = "开放 API：简历分析 + 面试题生成")
public class OpenApiController {

  private final OpenApiService openApiService;

  @PostMapping("/analyze")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 2, interval = 1, timeUnit = RateLimit.TimeUnit.SECONDS)
  @RateLimit(dimension = RateLimit.Dimension.IP, count = 5, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
  @RateLimit(dimension = RateLimit.Dimension.API_KEY, count = 30, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
  @Operation(summary = "提交分析任务", description = "接收 JD + 简历文本/文件 + 岗位 + 级别，返回任务 ID")
  public Result<OpenApiTaskResponse> analyze(
      @RequestPart(value = "resumeFile", required = false) MultipartFile resumeFile,
      @Valid @ModelAttribute OpenApiAnalyzeRequest request) {
    return Result.success(openApiService.submitTask(request, resumeFile));
  }

  @GetMapping("/tasks/{taskId}")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 20, interval = 1, timeUnit = RateLimit.TimeUnit.SECONDS)
  @Operation(summary = "查询任务结果", description = "轮询任务状态，完成后返回简历分析和面试题")
  public Result<OpenApiTaskResultResponse> getTask(@PathVariable String taskId) {
    return Result.success(openApiService.getTaskResult(taskId));
  }
}
