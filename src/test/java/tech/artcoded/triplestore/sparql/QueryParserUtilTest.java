package tech.artcoded.triplestore.sparql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.apache.jena.query.Query;
import org.apache.jena.update.UpdateRequest;
import org.junit.jupiter.api.Test;
import tech.artcoded.triplestore.sparql.QueryParserUtil.QueryType;

class QueryParserUtilTest {

  @Test
  void parsesSelectAsRead() {
    var result = QueryParserUtil.parseOperation("SELECT * WHERE { ?s ?p ?o } LIMIT 1", false);
    assertThat(result).isPresent();
    assertThat(result.get().type()).isEqualTo(QueryType.READ);
    assertThat(result.get().query()).isInstanceOf(Query.class);
  }

  @Test
  void parsesUpdateAsUpdate() {
    var result = QueryParserUtil.parseOperation("INSERT DATA { <urn:s> <urn:p> <urn:o> }", false);
    assertThat(result).isPresent();
    assertThat(result.get().type()).isEqualTo(QueryType.UPDATE);
    assertThat(result.get().query()).isInstanceOf(UpdateRequest.class);
  }

  @Test
  void nullOrEmptyIsEmpty() {
    assertThat(QueryParserUtil.parseOperation(null, false)).isEmpty();
    assertThat(QueryParserUtil.parseOperation("", false)).isEmpty();
  }

  @Test
  void forceReadRejectsUpdates() {
    assertThatThrownBy(() -> QueryParserUtil.parseOperation("INSERT DATA { <urn:s> <urn:p> <urn:o> }", true))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void garbageIsRejected() {
    assertThatThrownBy(() -> QueryParserUtil.parseOperation("this is not sparql", false))
        .isInstanceOf(RuntimeException.class);
  }
}
