package interview.guide.modules.openapi.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.modules.openapi.config.OpenApiProperties;
import jakarta.servlet.FilterChain;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@DisplayName("OpenAPI 鉴权过滤器测试")
class OpenApiAuthFilterTest {

  private OpenApiProperties properties;
  private OpenApiAuthFilter filter;
  private FilterChain chain;

  @BeforeEach
  void setUp() {
    properties = new OpenApiProperties();
    properties.setApiKeys(List.of("secret-key-123"));
    filter = new OpenApiAuthFilter(properties, new ObjectMapper());
    chain = mock(FilterChain.class);
  }

  private MockHttpServletResponse runFilter(MockHttpServletRequest request) throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, chain);
    return response;
  }

  @Test
  @DisplayName("携带正确 X-API-Key 时放行")
  void shouldPassWithValidKey() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/openapi/analyze");
    request.addHeader("X-API-Key", "secret-key-123");

    MockHttpServletResponse response = runFilter(request);

    assertEquals(200, response.getStatus());
    verify(chain).doFilter(request, response);
  }

  @Test
  @DisplayName("缺少或错误的 X-API-Key 时返回 401")
  void shouldRejectMissingOrWrongKey() throws Exception {
    MockHttpServletRequest noHeader = new MockHttpServletRequest("POST", "/api/openapi/analyze");
    assertEquals(401, runFilter(noHeader).getStatus());
    verify(chain, never()).doFilter(noHeader, null);

    MockHttpServletRequest wrongHeader = new MockHttpServletRequest("POST", "/api/openapi/analyze");
    wrongHeader.addHeader("X-API-Key", "wrong-key");
    assertEquals(401, runFilter(wrongHeader).getStatus());
  }

  @Test
  @DisplayName("未配置任何 API Key 时一律拒绝（fail-closed）")
  void shouldRejectAllWhenNoKeysConfigured() throws Exception {
    properties.setApiKeys(List.of());
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/openapi/analyze");
    request.addHeader("X-API-Key", "anything");

    assertEquals(401, runFilter(request).getStatus());
  }

  @Test
  @DisplayName("401 响应为 JSON 错误体")
  void shouldWriteJsonErrorBody() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/openapi/analyze");

    MockHttpServletResponse response = runFilter(request);

    assertEquals("application/json;charset=UTF-8", response.getContentType());
    assertTrue(response.getContentAsString().contains("12005"));
  }

  @Test
  @DisplayName("非 openapi 路径不做鉴权拦截")
  void shouldSkipNonOpenApiPaths() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/resumes");

    org.junit.jupiter.api.Assertions.assertTrue(filter.shouldNotFilter(request));
  }
}
