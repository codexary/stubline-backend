package com.elsworne.stubline.issuer;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.FileInputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/** Local identity-provider stand-in; never use its unauthenticated issue endpoint in production. */
public final class DevTokenIssuer {
  private static final long MAX_TOKEN_LIFETIME_SECONDS = 300;
  private volatile RSAKey active;
  private final List<RetiredKey> retired = new ArrayList<>();

  private record RetiredKey(RSAKey key, Instant removeAfter) {}

  public DevTokenIssuer() throws Exception {
    rotate();
  }

  public synchronized void rotate() throws Exception {
    if (active != null)
      retired.add(new RetiredKey(active, Instant.now().plusSeconds(MAX_TOKEN_LIFETIME_SECONDS)));
    var generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    var pair = generator.generateKeyPair();
    active =
        new RSAKey.Builder((java.security.interfaces.RSAPublicKey) pair.getPublic())
            .privateKey((java.security.interfaces.RSAPrivateKey) pair.getPrivate())
            .keyID(UUID.randomUUID().toString())
            .build();
  }

  public byte[] publicKeyDer() {
    try {
      return active.toRSAPublicKey().getEncoded();
    } catch (com.nimbusds.jose.JOSEException e) {
      throw new IllegalStateException(e);
    }
  }

  public String jwks() {
    return jwks(Instant.now());
  }

  public synchronized String jwks(Instant now) {
    retired.removeIf(key -> !key.removeAfter().isAfter(now));
    var publicKeys = new ArrayList<com.nimbusds.jose.jwk.JWK>();
    publicKeys.add(active.toPublicJWK());
    retired.forEach(key -> publicKeys.add(key.key().toPublicJWK()));
    return new JWKSet(publicKeys).toString();
  }

  public String issue(String subject) throws Exception {
    var now = Instant.now();
    return issue(
        subject, "https://dev-token-issuer:8443", "stubline-api", now, now.plusSeconds(300));
  }

  public String issue(
      String subject, String issuer, String audience, Instant notBefore, Instant expires)
      throws Exception {
    var now = Instant.now();
    var claims =
        new JWTClaimsSet.Builder()
            .issuer(issuer)
            .audience(audience)
            .subject(subject)
            .issueTime(Date.from(now))
            .notBeforeTime(Date.from(notBefore))
            .expirationTime(Date.from(expires))
            .build();
    var jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(active.getKeyID()).build(), claims);
    jwt.sign(new RSASSASigner(active));
    return jwt.serialize();
  }

  public static void main(String[] args) throws Exception {
    var issuer = new DevTokenIssuer();
    HttpServer server;
    if (System.getenv("STUBLINE_TLS_KEYSTORE") == null) {
      server = HttpServer.create(new InetSocketAddress(8082), 0);
    } else {
      var store = KeyStore.getInstance("PKCS12");
      char[] password = required("STUBLINE_TLS_PASSWORD").toCharArray();
      try (var input = new FileInputStream(System.getenv("STUBLINE_TLS_KEYSTORE"))) {
        store.load(input, password);
      }
      var managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      managers.init(store, password);
      var context = SSLContext.getInstance("TLS");
      context.init(managers.getKeyManagers(), null, null);
      var https = HttpsServer.create(new InetSocketAddress(8443), 0);
      https.setHttpsConfigurator(new HttpsConfigurator(context));
      server = https;
    }
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.createContext(
        "/health",
        exchange -> {
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    for (String path : new String[] {"/health/live", "/health/ready"}) {
      server.createContext(
          path,
          exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
          });
    }
    server.createContext(
        "/.well-known/jwks.json",
        exchange -> {
          byte[] bytes = issuer.jwks().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, bytes.length);
          try (var out = exchange.getResponseBody()) {
            out.write(bytes);
          }
        });
    server.createContext(
        "/token",
        exchange -> {
          try {
            String query = exchange.getRequestURI().getRawQuery();
            String sub =
                query == null
                    ? "buyer-a"
                    : URLDecoder.decode(query.replaceFirst("^sub=", ""), StandardCharsets.UTF_8);
            byte[] bytes = issuer.issue(sub).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) {
              out.write(bytes);
            }
          } catch (Exception e) {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
          }
        });
    server.createContext(
        "/rotate",
        exchange -> {
          try {
            issuer.rotate();
            exchange.sendResponseHeaders(204, -1);
          } catch (Exception e) {
            exchange.sendResponseHeaders(500, -1);
          }
          exchange.close();
        });
    server.start();
    Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(5)));
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank())
      throw new IllegalStateException("Missing required setting: " + name);
    return value;
  }
}
