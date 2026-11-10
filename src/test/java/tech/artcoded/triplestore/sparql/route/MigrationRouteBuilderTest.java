package tech.artcoded.triplestore.sparql.route;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.system.Txn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tech.artcoded.triplestore.tdb.TDBService;

class MigrationRouteBuilderTest {

  private Dataset dataset;
  private MigrationRouteBuilder routeBuilder;

  @BeforeEach
  void setUp() {
    dataset = DatasetFactory.createTxnMem();
    var tdbService = new TDBService(dataset);
    ReflectionTestUtils.setField(tdbService, "batchSize", 100);
    ReflectionTestUtils.setField(tdbService, "maxRetry", 1);
    ReflectionTestUtils.setField(tdbService, "timeout", 10L);
    routeBuilder = new MigrationRouteBuilder(tdbService);
    ReflectionTestUtils.setField(routeBuilder, "defaultGraph", "urn:default-graph");
  }

  private long graphSize(String graph) {
    return Txn.calculateRead(dataset, () -> dataset.getNamedModel(graph).size());
  }

  @Test
  void turtleFileGoesToDefaultGraph() {
    var ttl = "<urn:a> <urn:p> \"1\" . <urn:b> <urn:p> \"2\" .";
    var migration = routeBuilder.performMigration(ttl.getBytes(StandardCharsets.UTF_8), "default-test.ttl");
    assertThat(migration.count()).isEqualTo(2);
    assertThat(migration.uuid()).isNotBlank();
    assertThat(graphSize("urn:default-graph")).isEqualTo(2);
  }

  @Test
  void graphFileWithTrailingNewlineIsUsed() {
    routeBuilder.addGraphToCache("urn:custom-graph\n".getBytes(StandardCharsets.UTF_8), "custom-test.graph");
    var migration = routeBuilder.performMigration("<urn:a> <urn:p> \"1\" .".getBytes(StandardCharsets.UTF_8),
        "custom-test.ttl");
    assertThat(migration.count()).isEqualTo(1);
    assertThat(graphSize("urn:custom-graph")).isEqualTo(1);
  }

  @Test
  void sparqlFileIsExecutedAsUpdate() {
    var update = "INSERT DATA { GRAPH <urn:sparql-graph> { <urn:a> <urn:p> \"1\" } }";
    var migration = routeBuilder.performMigration(update.getBytes(StandardCharsets.UTF_8), "001-update.sparql");
    assertThat(migration.count()).isEqualTo(1);
    assertThat(graphSize("urn:sparql-graph")).isEqualTo(1);
  }
}
