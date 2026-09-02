package com.elsworne.stubline.inventory.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.grpc.StatusRuntimeException;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.protobuf.services.HealthStatusManager;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;

/** In-memory CA and leaf certificates exercise real Netty TLS handshakes. */
final class MutualTlsTest {
  @Test
  void validClientCertificateSucceeds() throws Exception {
    try (Fixture f = new Fixture("inventory-service")) {
      assertEquals(
          HealthCheckResponse.ServingStatus.SERVING, f.call(f.client, "inventory-service"));
    }
  }

  @Test
  void missingClientCertificateRejected() throws Exception {
    try (Fixture f = new Fixture("inventory-service")) {
      assertThrows(StatusRuntimeException.class, () -> f.call(null, "inventory-service"));
    }
  }

  @Test
  void foreignClientCertificateRejected() throws Exception {
    try (Fixture f = new Fixture("inventory-service")) {
      var otherCa = certificate("foreign-ca", null, null, true);
      var foreign = certificate("foreign-client", otherCa, null, false);
      assertThrows(StatusRuntimeException.class, () -> f.call(foreign, "inventory-service"));
    }
  }

  @Test
  void wrongServerNameRejected() throws Exception {
    try (Fixture f = new Fixture("other-service")) {
      assertThrows(StatusRuntimeException.class, () -> f.call(f.client, "inventory-service"));
    }
  }

  private record Identity(KeyPair pair, X509Certificate certificate) {}

  private static Identity certificate(String name, Identity issuer, String san, boolean ca)
      throws Exception {
    var generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    var pair = generator.generateKeyPair();
    var subject = new X500Name("CN=" + name);
    var signer = issuer == null ? pair.getPrivate() : issuer.pair().getPrivate();
    var issuerName =
        issuer == null
            ? subject
            : new X500Name(issuer.certificate().getSubjectX500Principal().getName());
    var now = Instant.now();
    var builder =
        new JcaX509v3CertificateBuilder(
            issuerName,
            BigInteger.valueOf(System.nanoTime()).abs(),
            Date.from(now.minusSeconds(60)),
            Date.from(now.plusSeconds(3600)),
            subject,
            pair.getPublic());
    builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(ca));
    if (san != null)
      builder.addExtension(
          Extension.subjectAlternativeName,
          false,
          new GeneralNames(new GeneralName(GeneralName.dNSName, san)));
    var signed = builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(signer));
    return new Identity(pair, new JcaX509CertificateConverter().getCertificate(signed));
  }

  private static ByteArrayInputStream pem(String type, byte[] der) {
    String value =
        "-----BEGIN "
            + type
            + "-----\n"
            + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der)
            + "\n-----END "
            + type
            + "-----\n";
    return new ByteArrayInputStream(value.getBytes(StandardCharsets.US_ASCII));
  }

  private static final class Fixture implements AutoCloseable {
    final Identity ca;
    final Identity client;
    final io.grpc.Server server;

    Fixture(String serverName) throws Exception {
      ca = certificate("internal-ca", null, null, true);
      client = certificate("ticketing-api", ca, "ticketing-api", false);
      var serverIdentity = certificate(serverName, ca, serverName, false);
      var health = new HealthStatusManager();
      server =
          NettyServerBuilder.forPort(0)
              .sslContext(
                  GrpcSslContexts.forServer(
                          pem("CERTIFICATE", serverIdentity.certificate().getEncoded()),
                          pem("PRIVATE KEY", serverIdentity.pair().getPrivate().getEncoded()))
                      .trustManager(pem("CERTIFICATE", ca.certificate().getEncoded()))
                      .clientAuth(ClientAuth.REQUIRE)
                      .build())
              .addService(health.getHealthService())
              .build()
              .start();
      health.setStatus("", HealthCheckResponse.ServingStatus.SERVING);
    }

    HealthCheckResponse.ServingStatus call(Identity identity, String authority) throws Exception {
      var ssl =
          GrpcSslContexts.forClient()
              .trustManager(pem("CERTIFICATE", ca.certificate().getEncoded()));
      if (identity != null)
        ssl.keyManager(
            pem("CERTIFICATE", identity.certificate().getEncoded()),
            pem("PRIVATE KEY", identity.pair().getPrivate().getEncoded()));
      var channel =
          NettyChannelBuilder.forAddress("localhost", server.getPort())
              .overrideAuthority(authority)
              .sslContext(ssl.build())
              .build();
      try {
        return HealthGrpc.newBlockingStub(channel)
            .withDeadlineAfter(2, TimeUnit.SECONDS)
            .check(HealthCheckRequest.getDefaultInstance())
            .getStatus();
      } finally {
        channel.shutdownNow();
      }
    }

    @Override
    public void close() throws Exception {
      server.shutdownNow();
      server.awaitTermination(2, TimeUnit.SECONDS);
    }
  }
}
