package interview.guide.modules.openapi.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.result.Result;
import interview.guide.modules.openapi.config.OpenApiProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Slf4j
@Component
@RequiredArgsConstructor
public class OpenApiAuthFilter extends OncePerRequestFilter {

  private final OpenApiProperties properties;
  private final ObjectMapper objectMapper;

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                  FilterChain filterChain) throws ServletException, IOException {
    String apiKey = request.getHeader("X-API-Key");
    if (!isAuthorized(apiKey)) {
      log.warn("OpenAPI 认证失败: ip={}, path={}", request.getRemoteAddr(), request.getRequestURI());
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      response.setContentType("application/json;charset=UTF-8");
      response.getWriter().write(objectMapper.writeValueAsString(
          Result.error(ErrorCode.OPENAPI_UNAUTHORIZED)));
      return;
    }
    filterChain.doFilter(request, response);
  }

  /** 常量时间比较，避免时序侧信道；未配置任何 key 时一律拒绝（fail-closed）。 */
  private boolean isAuthorized(String apiKey) {
    if (apiKey == null || apiKey.isBlank()) {
      return false;
    }
    for (String candidate : properties.getApiKeys()) {
      if (candidate == null || candidate.isBlank()) {
        continue;
      }
      if (MessageDigest.isEqual(
          candidate.getBytes(StandardCharsets.UTF_8), apiKey.getBytes(StandardCharsets.UTF_8))) {
        return true;
      }
    }
    return false;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith("/api/openapi/");
  }
}
