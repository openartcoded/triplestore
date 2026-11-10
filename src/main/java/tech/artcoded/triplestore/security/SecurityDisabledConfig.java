package tech.artcoded.triplestore.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Used when application.security.enabled=false: every request is allowed.
 * Declaring our own SecurityFilterChain makes Spring Boot's default (login-protected) chains
 * back off, without depending on auto-configuration class names that change between Boot versions.
 */
@Configuration
@EnableWebSecurity
@ConditionalOnProperty(prefix = "application.security", name = "enabled", havingValue = "false")
public class SecurityDisabledConfig {

  @Bean
  public SecurityFilterChain permitAllFilterChain(HttpSecurity http) throws Exception {
    http.csrf(c -> c.disable())
        .authorizeHttpRequests(a -> a.anyRequest().permitAll());
    return http.build();
  }
}
