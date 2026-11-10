package tech.artcoded.triplestore.tdb;

import static org.apache.jena.query.ResultSetFormatter.output;
import static org.apache.jena.riot.Lang.TURTLE;
import static org.apache.jena.riot.RDFDataMgr.write;
import static org.apache.jena.riot.resultset.ResultSetLang.RS_CSV;
import static org.apache.jena.riot.resultset.ResultSetLang.RS_JSON;
import static org.apache.jena.riot.resultset.ResultSetLang.RS_Text;
import static org.apache.jena.riot.resultset.ResultSetLang.RS_XML;

import com.google.common.collect.Lists;
import com.google.common.io.FileBackedOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Triple;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionDatasetBuilder;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFFormat;
import org.apache.jena.riot.RDFLanguages;
import org.apache.jena.sparql.exec.UpdateExec;
import org.apache.jena.system.Txn;
import org.apache.jena.update.UpdateRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import tech.artcoded.triplestore.sparql.QueryParserUtil;
import tech.artcoded.triplestore.sparql.SparqlResult;

@Service
@Slf4j
public class TDBService {

  private static final int THRESHOLD = 4 * 1024 * 1024; // 4mb
  private static final List<Lang> RESULT_SET_LANGS = List.of(RS_JSON, RS_XML, RS_CSV, RS_Text);
  private static final ExecutorService QUERY_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

  private final Dataset ds;

  @Value("${triplestore.batchSize}")
  private int batchSize;
  @Value("${triplestore.maxRetry}")
  private int maxRetry;
  @Value("${triplestore.query.timeout}")
  private long timeout;

  public TDBService(Dataset ds) {
    this.ds = ds;
  }

  public SparqlResult executeQuery(Query q, String acceptHeader) {
    Supplier<SparqlResult> _executeQuery = () -> {
      try (QueryExecution queryExecution = QueryExecutionDatasetBuilder.create()
          .query(q)
          .dataset(ds)
          .timeout(timeout, TimeUnit.SECONDS)
          .build()) {
        return switch (q.queryType()) {
          case ASK -> tryFormat((lang, out) -> output(out, queryExecution.execAsk(), lang), acceptHeader, RS_JSON,
              RESULT_SET_LANGS);
          case SELECT ->
            tryFormat((lang, out) -> output(out, queryExecution.execSelect(), lang), acceptHeader, RS_JSON,
                RESULT_SET_LANGS);
          case DESCRIBE ->
            tryFormat((lang, out) -> write(out, queryExecution.execDescribe(), lang), acceptHeader, TURTLE,
                rdfLangs());
          case CONSTRUCT ->
            tryFormat((lang, out) -> write(out, queryExecution.execConstruct(), lang), acceptHeader, TURTLE,
                rdfLangs());
          default -> throw new UnsupportedOperationException(q.queryType() + " Not supported");
        };
      } catch (Exception exc) {
        log.error("exception occurred", exc);
        throw new RuntimeException(exc);
      }
    };
    return this.executeQueryTimeout(() -> Txn.calculateRead(ds, _executeQuery));
  }

  private SparqlResult executeQueryTimeout(Supplier<SparqlResult> supplier) {
    // dedicated (virtual) threads: blocking queries must not starve the shared ForkJoin common pool
    CompletableFuture<SparqlResult> future = CompletableFuture.supplyAsync(supplier, QUERY_EXECUTOR);
    try {
      return future.get(timeout, TimeUnit.SECONDS);
    } catch (TimeoutException | InterruptedException | ExecutionException e) {
      future.cancel(true);
      throw new RuntimeException(e);
    }
  }

  private SparqlResult tryFormat(BiConsumer<Lang, OutputStream> consumer, String contentType, Lang fallback,
      List<Lang> candidates) {
    Lang lang = guessLang(contentType, fallback, candidates);
    var body = writeToOutputStream(outputStream -> consumer.accept(lang, outputStream));

    return SparqlResult.builder()
        .contentType(lang.getContentType().getContentTypeStr())
        .body(body)
        .build();
  }

  @SneakyThrows
  private InputStream writeToOutputStream(Consumer<OutputStream> consumer) {
    var outputStream = new FileBackedOutputStream(THRESHOLD);
    try (outputStream) {
      consumer.accept(outputStream);
    } catch (RuntimeException e) {
      outputStream.reset();
      throw e;
    }
    // reset() deletes the temp file (if the result exceeded the threshold) once the response has been streamed
    return new FilterInputStream(outputStream.asByteSource().openStream()) {
      @Override
      public void close() throws IOException {
        try {
          super.close();
        } finally {
          outputStream.reset();
        }
      }
    };
  }

  private static List<Lang> rdfLangs() {
    return RDFLanguages.getRegisteredLanguages().stream()
        .filter(l -> RDFLanguages.isTriples(l) || RDFLanguages.isQuads(l))
        .toList();
  }

  /**
   * Picks the first language (by Accept q-value, then order) matching the Accept header among the
   * languages that make sense for the query type. Falls back when the header is missing, a wildcard,
   * or unknown.
   */
  static Lang guessLang(String acceptHeader, Lang fallback, List<Lang> candidates) {
    if (acceptHeader == null || acceptHeader.isBlank()) {
      return fallback;
    }
    try {
      var mediaTypes = new ArrayList<>(MediaType.parseMediaTypes(acceptHeader));
      mediaTypes.sort(Comparator.comparingDouble(MediaType::getQualityValue).reversed());
      for (MediaType mediaType : mediaTypes) {
        if (mediaType.isWildcardType() || mediaType.isWildcardSubtype()) {
          continue;
        }
        String requested = mediaType.getType() + "/" + mediaType.getSubtype();
        for (Lang lang : candidates) {
          if (lang.getContentType().getContentTypeStr().equalsIgnoreCase(requested)) {
            return lang;
          }
        }
      }
    } catch (Exception exc) {
      log.debug("could not parse accept header '{}': {}", acceptHeader, exc.getMessage());
    }
    return fallback;
  }

  @SneakyThrows
  public org.apache.jena.rdfpatch.changes.PatchSummary executeUpdateQuery(String updateQuery) {
    var counter = new org.apache.jena.rdfpatch.changes.RDFChangesCounter();
    var c = new org.apache.jena.rdfpatch.system.RDFChangesSuppressEmpty(counter);
    var dsg0 = ds.asDatasetGraph();
    var dsgc = new org.apache.jena.rdfpatch.system.DatasetGraphChanges(dsg0, c);
    Txn.executeWrite(ds, () -> {
      QueryParserUtil.parseUpdate(updateQuery)
          .map(u -> u.query() instanceof UpdateRequest updates ? updates : null)
          .map(u -> UpdateExec.dataset(dsgc).update(u).build())
          .ifPresent(UpdateExec::execute);
    });
    return counter.summary();
  }

  public org.apache.jena.rdfpatch.changes.PatchSummary insertModel(
      String graphUri, Model model) {
    var writer = new StringWriter();
    RDFDataMgr.write(writer, model, RDFFormat.NTRIPLES);
    String updateQuery = String.format("INSERT DATA { GRAPH <%s> { %s } }",
        graphUri, writer);
    log.debug(updateQuery);
    return executeUpdateQuery(updateQuery);
  }

  public long batchLoadData(String graph, Model model) {
    log.info(
        "running import triples with batch size {}, model size: {}, graph: <{}>",
        batchSize, model.size(), graph);
    List<Triple> triples = model.getGraph().find().toList(); // duplicate so we can splice
    return Lists.partition(triples, batchSize)
        .stream()
        .parallel()
        .map(batch -> {
          Model batchModel = ModelFactory.createDefaultModel();
          Graph batchGraph = batchModel.getGraph();
          batch.forEach(batchGraph::add);
          return batchModel;
        })
        .peek(batchModel -> log.info("running import triples with model size {}",
            batchModel.size()))
        .map(batchModel -> this.insertModelOrRetry(graph, batchModel))
        .mapToLong(p -> p.getCountAddData() + p.getCountDeleteData())
        .sum();
  }

  private org.apache.jena.rdfpatch.changes.PatchSummary insertModelOrRetry(
      String graph, Model batchModel) {
    int retryCount = 0;
    do {
      try {
        return this.insertModel(graph, batchModel);
      } catch (Exception e) {
        log.error(
            "an error occurred, retry count {}, max retry {}, error: {}",
            retryCount, maxRetry, e);
        retryCount += 1;
      }
    } while (retryCount < maxRetry);
    throw new RuntimeException(
        "Reaching max retries. Check the logs for further details.");
  }
}
