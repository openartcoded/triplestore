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
import org.springframework.security.web.servlet.util.matcher.MvcRequestMatcher;
import org.springframework.web.servlet.handler.HandlerMappingIntrospector;

@Configuration
@EnableWebSecurity
@ConditionalOnProperty(prefix = "application.security", name = "enabled", havingValue = "true")
public class ResourceServerConfig {

  private final HandlerMappingIntrospector introspector;

  public ResourceServerConfig(HandlerMappingIntrospector introspector) {
    this.introspector = introspector;
  }

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    var prometheusMatcher = new MvcRequestMatcher(introspector, "/api/actuator/prometheus/**");
    var publicMatcher = new MvcRequestMatcher(introspector, "/public/**");
    http
        .csrf().disable()
        .authorizeHttpRequests()
        .requestMatchers(publicMatcher).permitAll()
        .requestMatchers(prometheusMatcher)
        .hasAnyRole("PROMETHEUS")
        .anyRequest().authenticated()
        .and()
        .oauth2ResourceServer()
        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()));
    return http.build();
  }

  private Converter<Jwt, ? extends AbstractAuthenticationToken> jwtAuthenticationConverter() {
    JwtAuthenticationConverter jwtConverter = new JwtAuthenticationConverter();
    jwtConverter.setJwtGrantedAuthoritiesConverter(new RealmRoleConverter());
    return jwtConverter;
  }
}
