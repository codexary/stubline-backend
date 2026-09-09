package com.elsworne.stubline.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/** Prints JWT parts and verifies an RS256 signature with JDK cryptography. */
public final class JwtAnatomy {
  private JwtAnatomy() {}

  public static void main(String[] args) throws Exception {
    if (args.length < 2 || args.length > 3)
      throw new IllegalArgumentException("JwtAnatomy <token> <jwks-url> [ca-pem]");
    String[] parts = args[0].split("\\.", -1);
    if (parts.length != 3) throw new IllegalArgumentException("JWT needs three parts");
    var decoder = Base64.getUrlDecoder();
    var json = new ObjectMapper();
    String header = new String(decoder.decode(parts[0]), StandardCharsets.UTF_8);
    String claims = new String(decoder.decode(parts[1]), StandardCharsets.UTF_8);
    System.out.println("base64url header: " + parts[0]);
    System.out.println("base64url claims: " + parts[1]);
    System.out.println("base64url signature: " + parts[2]);
    System.out.println("header: " + header);
    System.out.println("claims: " + claims);
    var parsedHeader = json.readTree(header);
    String algorithm = parsedHeader.path("alg").asText();
    String kid = parsedHeader.path("kid").asText();
    System.out.println("algorithm: " + algorithm);
    System.out.println("kid: " + kid);
    if (!"RS256".equals(algorithm) || kid.isBlank())
      throw new IllegalArgumentException("Only RS256 with a kid is supported");
    HttpClient http = httpClient(args.length == 3 ? Path.of(args[2]) : null);
    var response =
        http.send(
            HttpRequest.newBuilder(URI.create(args[1])).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200)
      throw new IllegalStateException("JWKS HTTP " + response.statusCode());
    var keys = json.readTree(response.body()).path("keys");
    for (var key : keys) {
      if (!kid.equals(key.path("kid").asText())) continue;
      if (!"RSA".equals(key.path("kty").asText()))
        throw new IllegalArgumentException("Expected RSA JWK");
      var modulus = new BigInteger(1, decoder.decode(key.path("n").asText()));
      var exponent = new BigInteger(1, decoder.decode(key.path("e").asText()));
      var publicKey =
          KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(modulus, exponent));
      String signingInput = parts[0] + "." + parts[1];
      System.out.println("signing input: " + signingInput);
      var verifier = Signature.getInstance("SHA256withRSA");
      verifier.initVerify(publicKey);
      verifier.update(signingInput.getBytes(StandardCharsets.US_ASCII));
      System.out.println("signature valid: " + verifier.verify(decoder.decode(parts[2])));
      return;
    }
    throw new IllegalArgumentException("No JWKS key for kid " + kid);
  }

  private static HttpClient httpClient(Path caFile) throws Exception {
    if (caFile == null) return HttpClient.newHttpClient();
    var trust = KeyStore.getInstance(KeyStore.getDefaultType());
    trust.load(null);
    try (var input = Files.newInputStream(caFile)) {
      trust.setCertificateEntry(
          "issuer-ca", CertificateFactory.getInstance("X.509").generateCertificate(input));
    }
    var managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    managers.init(trust);
    var context = SSLContext.getInstance("TLS");
    context.init(null, managers.getTrustManagers(), null);
    return HttpClient.newBuilder().sslContext(context).build();
  }
}
