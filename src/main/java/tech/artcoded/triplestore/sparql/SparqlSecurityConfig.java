package tech.artcoded.triplestore.sparql;

import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@NoArgsConstructor
@ConfigurationProperties("application.security.sparql.update")
public class SparqlSecurityConfig {
  @Getter
  @Setter
  private Set<String> allowedRoles;
}
