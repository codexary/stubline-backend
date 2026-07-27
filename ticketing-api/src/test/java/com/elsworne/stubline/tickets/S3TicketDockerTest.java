package com.elsworne.stubline.tickets;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/** Exercises real SeaweedFS S3 uploads and signed GET requests. */
@Tag("docker")
@EnabledIfSystemProperty(named = "run.docker", matches = "true")
class S3TicketDockerTest {
  @Test
  void signedGetDownloadsThenExpires() throws Exception {
    try (var seaweed =
        new GenericContainer<>(DockerImageName.parse("chrislusf/seaweedfs:4.46"))
            .withEnv("AWS_ACCESS_KEY_ID", "admin")
            .withEnv("AWS_SECRET_ACCESS_KEY", "secret")
            .withEnv("S3_BUCKET", "stubline-tickets")
            .withCopyFileToContainer(
                MountableFile.forClasspathResource("seaweedfs-s3.json"), "/tmp/s3.json")
            .withCommand("mini", "-dir=/data", "-s3.config=/tmp/s3.json")
            .withExposedPorts(8333, 9333)
            .waitingFor(Wait.forHttp("/").forPort(8333).forStatusCode(403))) {
      seaweed.start();
      long clockDeadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
      while (true) {
        long containerEpoch =
            Long.parseLong(seaweed.execInContainer("date", "-u", "+%s").getStdout().trim());
        long skew = Math.abs(Instant.now().getEpochSecond() - containerEpoch);
        if (skew <= 1) break;
        if (System.nanoTime() >= clockDeadline)
          fail("SeaweedFS container clock is " + skew + "s from host clock");
        Thread.sleep(500);
      }
      URI endpoint = URI.create("http://" + seaweed.getHost() + ":" + seaweed.getMappedPort(8333));
      try (var storage =
              new S3TicketStorage(
                  "stubline-tickets", "us-east-1", endpoint, endpoint, true, "admin", "secret");
          var http = HttpClient.newHttpClient()) {
        byte[] pdf = new byte[] {0x25, 0x50, 0x44, 0x46};
        URI putUrl = storage.presignPut("tickets/order-1.pdf", Duration.ofMinutes(1));
        var upload =
            http.send(
                HttpRequest.newBuilder(putUrl)
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(pdf))
                    .build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(200, upload.statusCode());
        URI url = storage.presignGet("tickets/order-1.pdf", Duration.ofSeconds(2));
        var first =
            http.send(
                HttpRequest.newBuilder(url).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, first.statusCode());
        assertArrayEquals(pdf, first.body());
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        int status;
        do {
          Thread.sleep(250);
          status =
              http.send(
                      HttpRequest.newBuilder(url).GET().build(),
                      HttpResponse.BodyHandlers.discarding())
                  .statusCode();
        } while (status == 200 && System.nanoTime() < deadline);
        assertEquals(403, status);
      }
    }
  }
}
