package tech.artcoded.triplestore;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Boots the whole application (embedded Artemis, TDB2 on disk in a temp dir, Camel routes, security disabled)
 * and checks the main flows over HTTP. Mostly guards the Spring Boot 4 migration (server, security, actuator).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TriplestoreApplicationTests {

  private static final Path DATA_DIR;

  static {
    try {
      DATA_DIR = Files.createTempDirectory("triplestore-test");
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("application.basePath", DATA_DIR::toString);
    registry.add("BANDWIDTH_CAPACITY", () -> "10000");
  }

  @Value("${local.server.port}")
  private int port;

  private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  private URI uri(String path) {
    return URI.create("http://localhost:" + port + path);
  }

  private static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }

  private HttpResponse<String> get(String path, String accept) throws Exception {
    var builder = HttpRequest.newBuilder(uri(path)).GET();
    if (accept != null) {
      builder.header("Accept", accept);
    }
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> postForm(String path, String form) throws Exception {
    var request = HttpRequest.newBuilder(uri(path))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(form))
        .build();
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void healthIsUp() throws Exception {
    var response = get("/actuator/health", null);
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("UP");
  }

  @Test
  void prometheusIsExposed() throws Exception {
    var response = get("/actuator/prometheus", null);
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("jvm_");
  }

  @Test
  void noLoginRequiredWhenSecurityDisabled() throws Exception {
    var response = get("/sparql?query=" + enc("ASK { ?s ?p ?o }"), "application/sparql-results+json");
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("boolean");
  }

  @Test
  void publicEndpointRejectsUpdates() throws Exception {
    var response = postForm("/public/sparql", "update=" + enc("INSERT DATA { <urn:x> <urn:y> \"z\" }"));
    assertThat(response.statusCode()).isEqualTo(400);
  }

  @Test
  void updateIsAppliedAsynchronouslyThenVisible() throws Exception {
    var update = "INSERT DATA { GRAPH <urn:it:graph> { <urn:it:s> <urn:it:p> \"value\" } }";
    var response = postForm("/sparql", "update=" + enc(update));
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).isEqualTo("processing update");

    var ask = "/public/sparql?query=" + enc("ASK { GRAPH ?g { <urn:it:s> <urn:it:p> \"value\" } }");
    long deadline = System.currentTimeMillis() + 20_000;
    String body = "";
    while (System.currentTimeMillis() < deadline) {
      var result = get(ask, "application/sparql-results+json");
      assertThat(result.statusCode()).isEqualTo(200);
      body = result.body();
      if (body.contains("true")) {
        break;
      }
      Thread.sleep(250);
    }
    assertThat(body).contains("true");
  }

  @Test
  void largeFormUpdateIsAccepted() throws Exception {
    // > 2MB form body: Tomcat's default limit would drop the parameter (Undertow had no limit)
    var sb = new StringBuilder("INSERT DATA { GRAPH <urn:it:big> {");
    String filler = "y".repeat(1000);
    for (int i = 0; i < 2500; i++) {
      sb.append(" <urn:it:big").append(i).append("> <urn:it:p> \"").append(filler).append("\" .");
    }
    var response = postForm("/sparql", "update=" + enc(sb.append(" } }").toString()));
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).isEqualTo("processing update");
  }
}
