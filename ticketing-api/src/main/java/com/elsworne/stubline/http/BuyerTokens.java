package com.elsworne.stubline.http;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Clock;
import java.time.Duration;
import java.util.Date;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/** Validates buyer bearer tokens against a rotating RS256 JWKS. */
public final class BuyerTokens {
  private final URI jwksUri;
  private final String issuer;
  private final String audience;
  private final Clock clock;
  private final HttpClient http;
  private volatile JWKSet keys;

  public BuyerTokens(URI jwksUri, String issuer, String audience, Clock clock) {
    this.jwksUri = jwksUri;
    this.issuer = issuer;
    this.audience = audience;
    this.clock = clock;
    this.http = HttpClient.newHttpClient();
  }

  public BuyerTokens(URI jwksUri, String issuer, String audience, Clock clock, Path caFile)
      throws Exception {
    this.jwksUri = jwksUri;
    this.issuer = issuer;
    this.audience = audience;
    this.clock = clock;
    var certs = CertificateFactory.getInstance("X.509");
    var trust = KeyStore.getInstance(KeyStore.getDefaultType());
    trust.load(null);
    try (var input = Files.newInputStream(caFile)) {
      trust.setCertificateEntry("issuer-ca", certs.generateCertificate(input));
    }
    var managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    managers.init(trust);
    var context = SSLContext.getInstance("TLS");
    context.init(null, managers.getTrustManagers(), null);
    this.http = HttpClient.newBuilder().sslContext(context).build();
  }

  public String subject(String authorization) {
    if (authorization == null || !authorization.startsWith("Bearer ")) return null;
    try {
      var jwt = SignedJWT.parse(authorization.substring(7));
      if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())) return null;
      String kid = jwt.getHeader().getKeyID();
      if (kid == null || kid.isBlank()) return null;
      RSAKey key = key(kid);
      if (key == null) return null;
      JWSVerifier verifier = new RSASSAVerifier(key.toRSAPublicKey());
      if (!jwt.verify(verifier)) return null;
      var claims = jwt.getJWTClaimsSet();
      Date exp = claims.getExpirationTime();
      Date nbf = claims.getNotBeforeTime();
      long now = clock.millis();
      if (exp == null || exp.getTime() <= now - 30_000) return null;
      if (nbf != null && nbf.getTime() > now + 30_000) return null;
      if (!issuer.equals(claims.getIssuer()) || !claims.getAudience().contains(audience))
        return null;
      String sub = claims.getSubject();
      return sub == null || sub.isBlank() ? null : sub;
    } catch (Exception e) {
      return null;
    }
  }

  private synchronized RSAKey key(String kid) throws Exception {
    if (keys != null && keys.getKeyByKeyId(kid) instanceof RSAKey cached) return cached;
    var response =
        http.send(
            HttpRequest.newBuilder(jwksUri).timeout(Duration.ofSeconds(3)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) return null;
    keys = JWKSet.parse(response.body());
    return keys.getKeyByKeyId(kid) instanceof RSAKey fresh ? fresh : null;
  }
}
