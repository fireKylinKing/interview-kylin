package interview.guide.modules.openapi;

import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.config.LlmProviderProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("面试风格指令组装测试")
class InterviewStylesTest {

  private final PromptSanitizer sanitizer = new PromptSanitizer(new LlmProviderProperties());

  @Test
  @DisplayName("标准风格输出题型配比与合规红线")
  void shouldComposeStandardDirective() {
    String directive = InterviewStyles.compose("standard", null, null, sanitizer);
    assertTrue(directive.contains("标准初面"));
    assertTrue(directive.contains("合规红线"));
  }

  @Test
  @DisplayName("未识别风格回退 standard")
  void shouldFallbackToStandardForUnknownStyle() {
    String directive = InterviewStyles.compose("hacker-style", null, null, sanitizer);
    assertTrue(directive.contains("标准初面"));
    assertFalse(directive.contains("深挖验证"));
  }

  @Test
  @DisplayName("考察标签白名单过滤，未知标签忽略，超过 4 个截断")
  void shouldFilterUnknownTagsAndCapAtFour() {
    String directive = InterviewStyles.compose(
        "standard",
        List.of("稳定性", "不存在的标签", "求职动机", "团队协作", "技术深度", "沟通表达"),
        null,
        sanitizer);
    assertTrue(directive.contains("离职原因"));
    assertTrue(directive.contains("求职动机"));
    assertTrue(directive.contains("跨部门协作"));
    assertFalse(directive.contains("不存在的标签"));
    // 只保留白名单内前 4 个：稳定性、求职动机、团队协作、技术深度
    assertTrue(directive.contains("核心技术栈"));
  }

  @Test
  @DisplayName("补充说明被分隔符包裹且超长截断")
  void shouldWrapAndTruncateExtraInstructions() {
    String longText = "要求".repeat(400);
    String directive = InterviewStyles.compose("standard", null, longText, sanitizer);
    assertTrue(directive.contains("data-boundary-"));
    assertTrue(directive.contains("extra_instructions"));
    // 截断到 500 字符
    assertFalse(directive.contains("要求".repeat(300)));
    assertTrue(directive.contains("要求".repeat(200)));
  }

  @Test
  @DisplayName("快筛风格明确要求不生成追问")
  void shouldInstructNoFollowUpsForQuickStyle() {
    String directive = InterviewStyles.compose("quick", null, null, sanitizer);
    assertTrue(directive.contains("不生成追问"));
  }
}
