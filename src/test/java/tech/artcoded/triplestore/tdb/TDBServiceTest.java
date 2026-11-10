package tech.artcoded.triplestore.tdb;

import static org.apache.jena.riot.resultset.ResultSetLang.RS_CSV;
import static org.apache.jena.riot.resultset.ResultSetLang.RS_JSON;
import static org.apache.jena.riot.resultset.ResultSetLang.RS_XML;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.system.Txn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tech.artcoded.triplestore.sparql.SparqlResult;

class TDBServiceTest {

  private Dataset dataset;
  private TDBService service;

  @BeforeEach
  void setUp() {
    dataset = DatasetFactory.createTxnMem();
    service = new TDBService(dataset);
    ReflectionTestUtils.setField(service, "batchSize", 2);
    ReflectionTestUtils.setField(service, "maxRetry", 2);
    ReflectionTestUtils.setField(service, "timeout", 10L);
  }

  private static String body(SparqlResult result) throws Exception {
    try (var is = result.getBody()) {
      return new String(is.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  void updateReportsAddedTriples() {
    var summary = service.executeUpdateQuery("INSERT DATA { <urn:s> <urn:p> \"o\" }");
    assertThat(summary.getCountAddData()).isEqualTo(1);

    var deleted = service.executeUpdateQuery("DELETE DATA { <urn:s> <urn:p> \"o\" }");
    assertThat(deleted.getCountDeleteData()).isEqualTo(1);
  }

  @Test
  void selectDefaultsToSparqlJson() throws Exception {
    service.executeUpdateQuery("INSERT DATA { <urn:s> <urn:p> \"hello\" }");
    var result = service.executeQuery(QueryFactory.create("SELECT ?o WHERE { <urn:s> <urn:p> ?o }"), null);
    assertThat(result.getContentType()).isEqualTo(RS_JSON.getContentType().getContentTypeStr());
    assertThat(body(result)).contains("hello");
  }

  @Test
  void selectHonoursAcceptHeader() throws Exception {
    service.executeUpdateQuery("INSERT DATA { <urn:s> <urn:p> \"hello\" }");
    var result = service.executeQuery(QueryFactory.create("SELECT ?o WHERE { <urn:s> <urn:p> ?o }"), "text/csv");
    assertThat(result.getContentType()).isEqualTo("text/csv");
    assertThat(body(result)).contains("o").contains("hello");
  }

  @Test
  void askReturnsBoolean() throws Exception {
    service.executeUpdateQuery("INSERT DATA { <urn:s> <urn:p> \"hello\" }");
    var result = service.executeQuery(QueryFactory.create("ASK { <urn:s> <urn:p> \"hello\" }"),
        "application/sparql-results+json");
    assertThat(body(result)).contains("true");
  }

  @Test
  void constructDefaultsToTurtle() throws Exception {
    service.executeUpdateQuery("INSERT DATA { <urn:s> <urn:p> \"hello\" }");
    var result = service.executeQuery(QueryFactory.create("CONSTRUCT WHERE { ?s ?p ?o }"), null);
    assertThat(result.getContentType()).isEqualTo(Lang.TURTLE.getContentType().getContentTypeStr());
    assertThat(body(result)).contains("urn:s").contains("hello");
  }

  @Test
  void constructHonoursAcceptHeader() throws Exception {
    service.executeUpdateQuery("INSERT DATA { <urn:s> <urn:p> \"hello\" }");
    var result = service.executeQuery(QueryFactory.create("CONSTRUCT WHERE { ?s ?p ?o }"), "application/n-triples");
    assertThat(result.getContentType()).isEqualTo("application/n-triples");
    assertThat(body(result)).contains("<urn:s> <urn:p> \"hello\" .");
  }

  @Test
  void guessLangHandlesMissingAndWildcardAccept() {
    List<Lang> rs = List.of(RS_JSON, RS_XML, RS_CSV);
    assertThat(TDBService.guessLang(null, RS_JSON, rs)).isEqualTo(RS_JSON);
    assertThat(TDBService.guessLang("", RS_JSON, rs)).isEqualTo(RS_JSON);
    assertThat(TDBService.guessLang("*/*", RS_JSON, rs)).isEqualTo(RS_JSON);
    assertThat(TDBService.guessLang("not a media type;;;", RS_JSON, rs)).isEqualTo(RS_JSON);
  }

  @Test
  void guessLangPicksFirstSupportedTypeByQuality() {
    List<Lang> rs = List.of(RS_JSON, RS_XML, RS_CSV);
    // turtle is not valid for a result set, so it must be skipped
    assertThat(TDBService.guessLang("text/turtle, text/csv;q=0.5", RS_JSON, rs)).isEqualTo(RS_CSV);
    assertThat(TDBService.guessLang("text/csv;q=0.4, application/sparql-results+xml;q=0.9", RS_JSON, rs))
        .isEqualTo(RS_XML);
    assertThat(TDBService.guessLang("application/sparql-results+json; charset=utf-8", RS_CSV, rs))
        .isEqualTo(RS_JSON);
  }

  @Test
  void batchLoadDataLoadsEverythingInBatches() {
    var model = ModelFactory.createDefaultModel();
    for (int i = 0; i < 5; i++) {
      model.add(model.createResource("urn:s" + i), model.createProperty("urn:p"), "v" + i);
    }
    long count = service.batchLoadData("urn:graph", model);
    assertThat(count).isEqualTo(5);
    long size = Txn.calculateRead(dataset, () -> dataset.getNamedModel("urn:graph").size());
    assertThat(size).isEqualTo(5);
  }

  @Test
  void largeResultsAreStreamedFully() throws Exception {
    var sb = new StringBuilder("INSERT DATA {");
    String filler = "x".repeat(1000);
    for (int i = 0; i < 6000; i++) { // ~6MB of literals, above the 4MB in-memory threshold
      sb.append(" <urn:s").append(i).append("> <urn:p> \"").append(filler).append("\" .");
    }
    service.executeUpdateQuery(sb.append(" }").toString());
    var result = service.executeQuery(QueryFactory.create("SELECT (COUNT(*) AS ?c) WHERE { ?s ?p ?o }"), null);
    assertThat(body(result)).contains("6000");

    var big = service.executeQuery(QueryFactory.create("SELECT ?o WHERE { ?s ?p ?o }"), "text/csv");
    assertThat(body(big).lines().count()).isEqualTo(6001); // header + rows
  }
}
