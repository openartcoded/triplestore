package tech.artcoded.triplestore.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

@Configuration
@EnableWebSecurity
@ConditionalOnProperty(prefix = "application.security", name = "enabled", havingValue = "true")
public class ResourceServerConfig {

  public ResourceServerConfig() {
  }

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {

    var mvc = PathPatternRequestMatcher.withDefaults();
    http.csrf(c -> c.disable())
        .authorizeHttpRequests(a -> a.requestMatchers(mvc.matcher("/public/**")).permitAll()
            .requestMatchers(mvc.matcher("/api/actuator/prometheus/**"))
            .hasAnyRole("PROMETHEUS")
            .anyRequest().authenticated())

        .httpBasic(auth -> auth.realmName("ArtcodedTriplestore"))
        .oauth2ResourceServer(a -> a.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
    return http.build();

  }

  private Converter<Jwt, ? extends AbstractAuthenticationToken> jwtAuthenticationConverter() {
    JwtAuthenticationConverter jwtConverter = new JwtAuthenticationConverter();
    jwtConverter.setJwtGrantedAuthoritiesConverter(new RealmRoleConverter());
    return jwtConverter;
  }
}
