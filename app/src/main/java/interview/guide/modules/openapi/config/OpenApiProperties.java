package interview.guide.modules.openapi.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.openapi")
public class OpenApiProperties {

  private List<String> apiKeys = new ArrayList<>();
  private int defaultQuestionCount = 10;
  private String defaultDifficulty = "mid";
  private long taskTtlSeconds = 3600;
  private long maxFileSizeBytes = 20L * 1024 * 1024;
}
