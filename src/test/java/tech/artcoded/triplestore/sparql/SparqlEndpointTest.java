package tech.artcoded.triplestore.sparql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.apache.camel.ExchangePattern;
import org.apache.camel.ProducerTemplate;
import org.apache.jena.query.DatasetFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tech.artcoded.triplestore.tdb.TDBService;

class SparqlEndpointTest {

  private static final String UPDATE = "INSERT DATA { <urn:s> <urn:p> \"o\" }";
  private static final String SELECT = "SELECT ?o WHERE { <urn:s> <urn:p> ?o }";

  private ProducerTemplate producerTemplate;
  private SparqlSecurityConfig securityConfig;
  private TDBService tdbService;
  private SparqlEndpoint endpoint;

  @BeforeEach
  void setUp() {
    producerTemplate = mock(ProducerTemplate.class);
    securityConfig = new SparqlSecurityConfig();
    securityConfig.setAllowedRoles(Set.of("ADMIN"));
    tdbService = new TDBService(DatasetFactory.createTxnMem());
    ReflectionTestUtils.setField(tdbService, "batchSize", 100);
    ReflectionTestUtils.setField(tdbService, "maxRetry", 1);
    ReflectionTestUtils.setField(tdbService, "timeout", 10L);
    endpoint = new SparqlEndpoint(producerTemplate, securityConfig, tdbService);
    ReflectionTestUtils.setField(endpoint, "securityEnabled", false);
  }

  @AfterEach
  void clearSecurity() {
    SecurityContextHolder.clearContext();
  }

  private static String body(ResponseEntity<StreamingResponseBody> response) throws Exception {
    var out = new ByteArrayOutputStream();
    response.getBody().writeTo(out);
    return out.toString(StandardCharsets.UTF_8);
  }

  @Test
  void noQueryGivesNoContent() {
    var response = endpoint.executeQuery(null, null, new MockHttpServletRequest());
    assertThat(response.getStatusCode().value()).isEqualTo(204);
  }

  @Test
  void selectIsExecuted() throws Exception {
    tdbService.executeUpdateQuery(UPDATE);
    var response = endpoint.executePublicQuery(SELECT, null, new MockHttpServletRequest());
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(body(response)).contains("\"o\"");
    verify(producerTemplate, timeout(2000)).sendBodyAndHeader(eq("jms:queue:sparql-read"),
        eq(ExchangePattern.InOnly), anyString(), eq("accept"), any());
  }

  @Test
  void publicEndpointRejectsUpdates() throws Exception {
    var response = endpoint.executePublicQuery(null, UPDATE, new MockHttpServletRequest());
    assertThat(response.getStatusCode().value()).isEqualTo(400);
    assertThat(body(response)).startsWith("{\"error\": \"");
    verify(producerTemplate, never()).sendBody(anyString(), any(ExchangePattern.class), any());
  }

  @Test
  void updateIsQueuedWhenSecurityDisabled() throws Exception {
    var response = endpoint.executeQuery(null, UPDATE, new MockHttpServletRequest());
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(body(response)).isEqualTo("processing update");
    verify(producerTemplate, timeout(2000)).sendBody(eq("jms:queue:sparql-update"), eq(ExchangePattern.InOnly),
        anyString());
  }

  @Test
  void updateRequiresAllowedRoleWhenSecurityEnabled() {
    ReflectionTestUtils.setField(endpoint, "securityEnabled", true);

    SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("u", "p", "ROLE_USER"));
    assertThat(endpoint.executeQuery(null, UPDATE, new MockHttpServletRequest()).getStatusCode().value())
        .isEqualTo(403);

    SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("u", "p", "ROLE_ADMIN"));
    assertThat(endpoint.executeQuery(null, UPDATE, new MockHttpServletRequest()).getStatusCode().value())
        .isEqualTo(200);
  }

  @Test
  void updateForbiddenWithoutAuthenticationWhenSecurityEnabled() {
    ReflectionTestUtils.setField(endpoint, "securityEnabled", true);
    assertThat(endpoint.executeQuery(null, UPDATE, new MockHttpServletRequest()).getStatusCode().value())
        .isEqualTo(403);
  }

  @Test
  void invalidQueryGivesJsonError() throws Exception {
    var response = endpoint.executeQuery("SELEKT nonsense", null, new MockHttpServletRequest());
    assertThat(response.getStatusCode().value()).isEqualTo(400);
    assertThat(response.getHeaders().getContentType()).hasToString("application/json");
    assertThat(body(response)).startsWith("{\"error\": \"").endsWith("\"}");
  }

  @Test
  void jsonErrorIsEscaped() {
    assertThat(SparqlEndpoint.toJsonError("a \"quoted\"\nline \\ end"))
        .isEqualTo("{\"error\": \"a \\\"quoted\\\"\\nline \\\\ end\"}");
    assertThat(SparqlEndpoint.toJsonError(null)).isEqualTo("{\"error\": \"null\"}");
  }
}
