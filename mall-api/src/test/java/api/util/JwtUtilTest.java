package api.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JwtUtilTest {

    @Test
    void parsesGeneratedBearerToken() {
        String token = JwtUtil.generateToken(101L, "admin@example.com", "Administrator", "ADMIN");

        assertThat(JwtUtil.parseUserId("Bearer " + token)).isEqualTo(101L);
        assertThat(JwtUtil.parseRole("Bearer " + token)).isEqualTo("ADMIN");
        assertThat(JwtUtil.parsePayload("Bearer " + token).path("email").asText())
                .isEqualTo("admin@example.com");
    }

    @Test
    void rejectsMalformedToken() {
        assertThat(JwtUtil.parseUserId("Bearer malformed-token")).isNull();
        assertThat(JwtUtil.parseRole("Bearer malformed-token")).isNull();
        assertThat(JwtUtil.parsePayload("Bearer malformed-token")).isNull();
    }
}
