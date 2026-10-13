package com.elsworne.stubline.issuer;

import static org.junit.jupiter.api.Assertions.*;

import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuthorizationCodeFlowTest {
  private static final String REDIRECT = "http://localhost:5173/callback";
  private static final String VERIFIER =
      "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~";
  private HttpServer server;
  private HttpClient client;
  private MutableClock clock;
  private String base;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    clock = new MutableClock();
    new AuthorizationCodeFlow(
            new DevTokenIssuer(),
            "buyer-web",
            Set.of(REDIRECT),
            Set.of("http://localhost:5173"),
            clock)
        .register(server);
    server.start();
    base = "http://127.0.0.1:" + server.getAddress().getPort();
    client = HttpClient.newHttpClient();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void authorizationCodeIsBoundToPkceClientRedirectAndSingleUse() throws Exception {
    String query = requestQuery();
    HttpResponse<String> form = get("/authorize?" + query);
    assertEquals(200, form.statusCode());
    assertTrue(form.body().contains("Buyer subject"));
    HttpResponse<String> redirect = post("/authorize", query + "&sub=buyer-42", null);
    assertEquals(302, redirect.statusCode());
    String location = redirect.headers().firstValue("Location").orElseThrow();
    assertTrue(location.startsWith(REDIRECT + "?"));
    assertTrue(location.contains("state=state-123"));
    String code = location.substring(location.indexOf("code=") + 5, location.indexOf("&state="));
    HttpResponse<String> preflight = options("/token", "http://localhost:5173");
    assertEquals(204, preflight.statusCode());
    assertEquals(
        "http://localhost:5173",
        preflight.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
    HttpResponse<String> token = post("/token", tokenBody(code, VERIFIER), "http://localhost:5173");
    assertEquals(200, token.statusCode());
    String jwt = token.body().split("\\\"access_token\\\":\\\"")[1].split("\\\"")[0];
    assertEquals("buyer-42", SignedJWT.parse(jwt).getJWTClaimsSet().getSubject());
    assertEquals("Bearer", token.body().contains("\"token_type\":\"Bearer\"") ? "Bearer" : "");
    assertEquals(400, post("/token", tokenBody(code, VERIFIER), null).statusCode());
    assertEquals(200, get("/token?sub=script-buyer").statusCode());
  }

  @Test
  void wrongVerifierConsumesCode() throws Exception {
    String code = authorize();
    assertEquals(400, post("/token", tokenBody(code, "Z".repeat(43)), null).statusCode());
    assertEquals(400, post("/token", tokenBody(code, VERIFIER), null).statusCode());
  }

  @Test
  void expiredCodeIsRejected() throws Exception {
    String code = authorize();
    clock.advanceSeconds(60);
    assertEquals(400, post("/token", tokenBody(code, VERIFIER), null).statusCode());
  }

  @Test
  void unknownRedirectAndMissingStateAreRejectedWithoutRedirect() throws Exception {
    assertEquals(
        400,
        get("/authorize?"
                + requestQuery().replace(encode(REDIRECT), encode("http://evil.example/callback")))
            .statusCode());
    assertEquals(
        400, get("/authorize?" + requestQuery().replace("&state=state-123", "")).statusCode());
  }

  @Test
  void missingCodeAndUnsupportedPkceMethodAreRejected() throws Exception {
    assertEquals(
        400,
        get("/authorize?"
                + requestQuery()
                    .replace("code_challenge_method=S256", "code_challenge_method=plain"))
            .statusCode());
    assertEquals(400, post("/token", tokenBody("", VERIFIER), null).statusCode());
  }

  @Test
  void disallowedOriginAndRedirectMismatchAreRejected() throws Exception {
    String code = authorize();
    assertEquals(
        403, post("/token", tokenBody(code, VERIFIER), "https://evil.example").statusCode());
    assertEquals(
        400,
        post(
                "/token",
                tokenBody(code, VERIFIER)
                    .replace(encode(REDIRECT), encode("http://evil.example/callback")),
                null)
            .statusCode());
    assertEquals(200, post("/token", tokenBody(code, VERIFIER), null).statusCode());
  }

  private String authorize() throws Exception {
    String location =
        post("/authorize", requestQuery() + "&sub=buyer-42", null)
            .headers()
            .firstValue("Location")
            .orElseThrow();
    return location.substring(location.indexOf("code=") + 5, location.indexOf("&state="));
  }

  private String requestQuery() throws Exception {
    String challenge =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                MessageDigest.getInstance("SHA-256")
                    .digest(VERIFIER.getBytes(StandardCharsets.US_ASCII)));
    return "response_type=code&client_id=buyer-web&redirect_uri="
        + encode(REDIRECT)
        + "&state=state-123&code_challenge="
        + challenge
        + "&code_challenge_method=S256";
  }

  private static String tokenBody(String code, String verifier) {
    return "grant_type=authorization_code&client_id=buyer-web&redirect_uri="
        + encode(REDIRECT)
        + "&code="
        + code
        + "&code_verifier="
        + encode(verifier);
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private HttpResponse<String> get(String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(String path, String body, String origin) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create(base + path))
            .header("Content-Type", "application/x-www-form-urlencoded");
    if (origin != null) request.header("Origin", origin);
    return client.send(
        request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> options(String path, String origin) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(base + path))
            .header("Origin", origin)
            .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static final class MutableClock extends Clock {
    private Instant now = Instant.parse("2026-10-30T00:00:00Z");

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }

    void advanceSeconds(long seconds) {
      now = now.plusSeconds(seconds);
    }
  }
}
