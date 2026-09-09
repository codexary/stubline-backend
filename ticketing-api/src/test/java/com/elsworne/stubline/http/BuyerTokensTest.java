package com.elsworne.stubline.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.elsworne.stubline.issuer.DevTokenIssuer;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.Test;

/** JWT signature, claim and rotation checks without Docker or certificate files. */
final class BuyerTokensTest {
  @Test
  void validTokenAccepted() throws Exception {
    try (Fixture fixture = new Fixture()) {
      assertEquals("buyer-a", fixture.tokens.subject("Bearer " + fixture.issuer.issue("buyer-a")));
    }
  }

  @Test
  void missingTokenRejected() throws Exception {
    try (Fixture fixture = new Fixture()) {
      assertNull(fixture.tokens.subject(null));
    }
  }

  @Test
  void badSignatureRejected() throws Exception {
    try (Fixture fixture = new Fixture()) {
      String token = fixture.issuer.issue("buyer-a");
      int signature = token.lastIndexOf('.') + 1;
      String replacement =
          token.substring(0, signature)
              + (token.charAt(signature) == 'A' ? "B" : "A")
              + token.substring(signature + 1);
      assertNull(fixture.tokens.subject("Bearer " + replacement));
    }
  }

  @Test
  void rotatedKidFetched() throws Exception {
    try (Fixture fixture = new Fixture()) {
      assertEquals("one", fixture.tokens.subject("Bearer " + fixture.issuer.issue("one")));
      fixture.issuer.rotate();
      assertEquals("two", fixture.tokens.subject("Bearer " + fixture.issuer.issue("two")));
    }
  }

  @Test
  void oldTokenAcceptedAfterRotation() throws Exception {
    try (Fixture fixture = new Fixture()) {
      String old = fixture.issuer.issue("buyer-a");
      fixture.issuer.rotate();
      assertEquals("buyer-a", fixture.tokens.subject("Bearer " + old));
    }
  }

  @Test
  void retiredKeyRemovedAfterLongestTokenLifetime() throws Exception {
    try (Fixture fixture = new Fixture()) {
      String old = fixture.issuer.issue("buyer-a");
      String kid = SignedJWT.parse(old).getHeader().getKeyID();
      fixture.issuer.rotate();
      var later = JWKSet.parse(fixture.issuer.jwks(Instant.now().plusSeconds(301)));
      assertNull(later.getKeyByKeyId(kid));
      var fresh =
          new BuyerTokens(
              URI.create("http://localhost:" + fixture.server.getAddress().getPort() + "/jwks"),
              "https://dev-token-issuer:8443",
              "stubline-api",
              Clock.systemUTC());
      assertNull(fresh.subject("Bearer " + old));
    }
  }

  @Test
  void newTokenUsesNewKid() throws Exception {
    try (Fixture fixture = new Fixture()) {
      String before = SignedJWT.parse(fixture.issuer.issue("buyer-a")).getHeader().getKeyID();
      fixture.issuer.rotate();
      String after = SignedJWT.parse(fixture.issuer.issue("buyer-a")).getHeader().getKeyID();
      org.junit.jupiter.api.Assertions.assertNotEquals(before, after);
    }
  }

  @Test
  void expiredTokenRejected() throws Exception {
    try (Fixture fixture = new Fixture()) {
      var now = Instant.now();
      String token =
          fixture.issuer.issue(
              "buyer-a",
              "https://dev-token-issuer:8443",
              "stubline-api",
              now.minusSeconds(600),
              now.minusSeconds(60));
      assertNull(fixture.tokens.subject("Bearer " + token));
    }
  }

  @Test
  void wrongAudienceRejected() throws Exception {
    try (Fixture fixture = new Fixture()) {
      var now = Instant.now();
      String token =
          fixture.issuer.issue(
              "buyer-a", "https://dev-token-issuer:8443", "another-api", now, now.plusSeconds(300));
      assertNull(fixture.tokens.subject("Bearer " + token));
    }
  }

  @Test
  void wrongIssuerRejected() throws Exception {
    try (Fixture fixture = new Fixture()) {
      var now = Instant.now();
      String token =
          fixture.issuer.issue(
              "buyer-a", "https://foreign.example", "stubline-api", now, now.plusSeconds(300));
      assertNull(fixture.tokens.subject("Bearer " + token));
    }
  }

  @Test
  void futureNotBeforeRejected() throws Exception {
    try (Fixture fixture = new Fixture()) {
      var now = Instant.now();
      String token =
          fixture.issuer.issue(
              "buyer-a",
              "https://dev-token-issuer:8443",
              "stubline-api",
              now.plusSeconds(60),
              now.plusSeconds(300));
      assertNull(fixture.tokens.subject("Bearer " + token));
    }
  }

  @Test
  void noneAlgorithmRejected() throws Exception {
    try (Fixture fixture = new Fixture()) {
      String payload =
          java.util.Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString("{\"sub\":\"buyer-a\"}".getBytes(StandardCharsets.UTF_8));
      assertNull(fixture.tokens.subject("Bearer eyJhbGciOiJub25lIn0." + payload + "."));
    }
  }

  @Test
  void hs256PublicKeyConfusionRejected() throws Exception {
    try (Fixture fixture = new Fixture()) {
      byte[] secret = fixture.issuer.jwks().getBytes(StandardCharsets.UTF_8);
      var jwt =
          new SignedJWT(
              new JWSHeader.Builder(JWSAlgorithm.HS256).keyID("key").build(),
              new JWTClaimsSet.Builder()
                  .subject("buyer-a")
                  .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                  .build());
      jwt.sign(new MACSigner(secret));
      assertNull(fixture.tokens.subject("Bearer " + jwt.serialize()));
    }
  }

  private static final class Fixture implements AutoCloseable {
    final DevTokenIssuer issuer = new DevTokenIssuer();
    final HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    final BuyerTokens tokens;

    Fixture() throws Exception {
      server.createContext(
          "/jwks",
          exchange -> {
            byte[] bytes = issuer.jwks().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) {
              output.write(bytes);
            }
          });
      server.start();
      tokens =
          new BuyerTokens(
              URI.create("http://localhost:" + server.getAddress().getPort() + "/jwks"),
              "https://dev-token-issuer:8443",
              "stubline-api",
              Clock.systemUTC());
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }
}
