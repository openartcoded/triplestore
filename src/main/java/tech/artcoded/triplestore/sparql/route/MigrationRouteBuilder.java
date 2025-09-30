package tech.artcoded.triplestore.sparql.route;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.apache.camel.Body;
import org.apache.camel.Exchange;
import org.apache.camel.ExchangePattern;
import org.apache.camel.Header;
import org.apache.camel.builder.RouteBuilder;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFLanguages;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tech.artcoded.triplestore.tdb.TDBService;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static java.util.Optional.ofNullable;
import static tech.artcoded.triplestore.sparql.route.Constants.*;

@Component
public class MigrationRouteBuilder extends RouteBuilder {
  private final TDBService tdbService;

  @Value("${triplestore.migration.defaultGraph}")
  private String defaultGraph;

  private static final Cache<String, String> GRAPH_CACHE = Caffeine.newBuilder()
      .expireAfterAccess(Duration.ofMinutes(5))
      .maximumSize(1000)
      .build();

  public MigrationRouteBuilder(TDBService tdbService) {
    this.tdbService = tdbService;
  }

  record Migration(String uuid, long count) {
  }

  @Override
  public void configure() throws Exception {
    onException(Exception.class)
        .handled(true)
        .log("Exception occurred due: ${exception.message}");

    from("file:{{triplestore.migration.dir}}?sortBy=file:name;file:modified")
        .routeId("MigrationRoute::Entrypoint")
        .log("receiving file '${headers.%s}', will execute migration to the triplestore".formatted(Exchange.FILE_NAME))
        .convertBodyTo(byte[].class)
        .choice()
        .when(header(Exchange.FILE_NAME).endsWith("graph"))
        .bean(() -> this, "addGraphToCache")
        .otherwise()
        .setProperty(HEADER_TITLE,
            simple("'${headers.%s}', has been executed to the triplestore".formatted(Exchange.FILE_NAME)))
        .setProperty(HEADER_TYPE, constant(SYNC_FILE_TRIPLESTORE))
        .to(ExchangePattern.InOnly, "direct:perform-migration")
        .endChoice();

    from("direct:perform-migration")
        .routeId("MigrationRoute::PerformMigrationInternal")
        .bean(() -> this, "performMigration")
        .choice()
        .when(simple("${body.count} > 0"))
        .transform(simple("${body.uuid}"))
        .setHeader(CORRELATION_ID, body())
        .setHeader(HEADER_TITLE, exchangeProperty(HEADER_TITLE))
        .setHeader(HEADER_TYPE, exchangeProperty(HEADER_TYPE))
        .to(ExchangePattern.InOnly, NOTIFICATION_ENDPOINT)
        .otherwise()
        .log("No triples changed")
        .endChoice();
  }

  void addGraphToCache(@Body byte[] file,
      @Header(Exchange.FILE_NAME) String fileName) {
    GRAPH_CACHE.put(FilenameUtils.getBaseName(fileName), IOUtils.toString(file, StandardCharsets.UTF_8.name()));
  }

  Migration performMigration(@Body byte[] file,
      @Header(Exchange.FILE_NAME) String fileName) {
    String extension = FilenameUtils.getExtension(fileName);

    if ("sparql".equalsIgnoreCase(extension)) {
      var summary = tdbService.executeUpdateQuery(IOUtils.toString(file, StandardCharsets.UTF_8.name()));
      return new Migration(UUID.randomUUID().toString(), summary.getCountAddData() + summary.getCountDeleteData());
    } else {
      Lang lang = RDFLanguages.filenameToLang(fileName);
      var model = ModelFactory.createDefaultModel();
      RDFDataMgr.read(model, new ByteArrayInputStream(file), lang);
      String graph = ofNullable(GRAPH_CACHE.getIfPresent(FilenameUtils.getBaseName(fileName)))
          .orElseGet(() -> defaultGraph);
      var summary = tdbService.batchLoadData(graph, model);
      return new Migration(UUID.randomUUID().toString(), summary);
    }

  }
}
