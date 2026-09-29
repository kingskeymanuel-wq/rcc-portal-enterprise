package com.ecobank.rccportal.security;

import com.ecobank.rccportal.model.User;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

/** Session disponible 24h/24 : pas de déconnexion pour inactivité, jeton renouvelé et repris après une veille. */
class SlidingSessionTest {

    private static final String SECRET = "UnSecretDeTestSuffisammentLongPourHmac256!!";

    private JwtService jwt() {
        JwtProperties p = new JwtProperties();
        p.setJwtSecret(SECRET);
        p.setJwtAccessExpiresInMinutes(60);
        p.setJwtRefreshExpiresInDays(30);
        return new JwtService(p);
    }

    private String token(String subject, Instant issued, Instant expires) {
        return Jwts.builder().subject(subject).claim("role", "AGENT").claim("name", "Awa")
                .issuedAt(Date.from(issued)).expiration(Date.from(expires))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }

    private MockHttpServletResponse run(Cookie... cookies) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/auth/keepalive");
        req.setCookies(cookies);
        MockHttpServletResponse res = new MockHttpServletResponse();
        new JwtAuthenticationFilter(jwt()).doFilter(req, res, new MockFilterChain());
        return res;
    }

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void recentTokenIsKeptAsIs() throws Exception {
        Instant now = Instant.now();
        MockHttpServletResponse res = run(new Cookie(SessionCookies.ACCESS, token("awa", now, now.plusSeconds(3600))));
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        assertNull(res.getCookie(SessionCookies.ACCESS));
    }

    @Test
    void olderTokenIsRenewedWhilePageIsOpen() throws Exception {
        Instant now = Instant.now();
        MockHttpServletResponse res = run(new Cookie(SessionCookies.ACCESS, token("awa", now.minusSeconds(1800), now.plusSeconds(1800))));
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        Cookie renewed = res.getCookie(SessionCookies.ACCESS);
        assertNotNull(renewed);
        JwtService.Parsed p = jwt().parseLenient(renewed.getValue());
        assertFalse(p.expired());
        assertEquals("awa", p.claims().getSubject());
        assertEquals("Awa", p.claims().get("name", String.class));
        assertTrue(renewed.getMaxAge() >= 29 * 86400, "cookie gardé au-delà d'une nuit de veille");
    }

    @Test
    void expiredTokenIsResumedWithRefreshCookie() throws Exception {
        Instant now = Instant.now();
        User u = new User();
        u.setUsername("awa");
        String refresh = jwt().generateRefreshToken(u).token();
        MockHttpServletResponse res = run(
                new Cookie(SessionCookies.ACCESS, token("awa", now.minusSeconds(20 * 3600), now.minusSeconds(16 * 3600))),
                new Cookie(SessionCookies.REFRESH, refresh));
        assertNotNull(SecurityContextHolder.getContext().getAuthentication(), "session reprise après la veille");
        assertNotNull(res.getCookie(SessionCookies.ACCESS));
    }

    @Test
    void expiredTokenWithoutRefreshOrOfAnotherUserIsRefused() throws Exception {
        Instant now = Instant.now();
        String expired = token("awa", now.minusSeconds(7200), now.minusSeconds(3600));
        run(new Cookie(SessionCookies.ACCESS, expired));
        assertNull(SecurityContextHolder.getContext().getAuthentication());

        User other = new User();
        other.setUsername("mallory");
        run(new Cookie(SessionCookies.ACCESS, expired), new Cookie(SessionCookies.REFRESH, jwt().generateRefreshToken(other).token()));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void forgedTokenIsRefused() throws Exception {
        run(new Cookie(SessionCookies.ACCESS, "abc.def.ghi"));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }
}
