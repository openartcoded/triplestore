package tech.artcoded.triplestore.sparql;


import org.apache.commons.lang3.StringUtils;
import org.apache.jena.query.QueryException;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.update.UpdateFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

import static java.util.Optional.*;

public interface QueryParserUtil {
  Logger LOGGER = LoggerFactory.getLogger(QueryParserUtil.class);

  enum QueryType {UPDATE, READ}

  record QueryTuple(Object query, QueryType type) {
  }

  static Optional<QueryTuple> parseOperation(String query, boolean forceRead) {

    return ofNullable(query).filter(StringUtils::isNotEmpty)
      .flatMap(q -> parseQuery(q, forceRead).or(() -> parseUpdate(q)));
  }

  static Optional<QueryTuple> parseQuery(String query, boolean forceRead) {
    try {
      return of(new QueryTuple(QueryFactory.create(query), QueryType.READ));
    } catch (QueryException exception) {
      // expected for every update request (we try to parse as a query first), so don't log at error level
      LOGGER.debug("not a query: {}", exception.getMessage());
      if (forceRead) {
        throw new RuntimeException(exception.getMessage());
      }
      return empty();
    }
  }

  static Optional<QueryTuple> parseUpdate(String query) {
    try {
      return of(new QueryTuple(UpdateFactory.create(query), QueryType.UPDATE));
    } catch (Exception exc) {
      LOGGER.warn("unsupported operation: {}", exc.getMessage());
      throw new RuntimeException(exc.getMessage());
    }
  }

}
