package tech.artcoded.triplestore.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class RealmRoleConverterTest {

  private final RealmRoleConverter converter = new RealmRoleConverter();

  private static Jwt.Builder jwt() {
    return Jwt.withTokenValue("token").header("alg", "none").subject("user");
  }

  @Test
  void mapsRealmRolesToUppercaseSpringRoles() {
    var token = jwt().claim("realm_access", Map.of("roles", List.of("admin", "User"))).build();
    assertThat(converter.convert(token))
        .extracting(GrantedAuthority::getAuthority)
        .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_USER");
  }

  @Test
  void noRealmAccessClaimGivesNoAuthorities() {
    assertThat(converter.convert(jwt().build())).isEmpty();
  }

  @Test
  void realmAccessWithoutRolesGivesNoAuthorities() {
    var token = jwt().claim("realm_access", Map.of("other", List.of("x"))).build();
    assertThat(converter.convert(token)).isEmpty();
  }
}
