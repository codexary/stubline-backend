package com.elsworne.stubline.tickets;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/** AWS SDK v2 S3 storage; endpoint overrides are optional for AWS S3. */
public final class S3TicketStorage implements TicketStorage {
  private final String bucket;
  private final S3Client client;
  private final S3Presigner presigner;

  public S3TicketStorage(
      String bucket,
      String region,
      URI internalEndpoint,
      URI publicEndpoint,
      boolean pathStyle,
      String accessKey,
      String secretKey) {
    this.bucket = Objects.requireNonNull(bucket);
    AwsCredentialsProvider credentials =
        accessKey == null || secretKey == null
            ? DefaultCredentialsProvider.create()
            : StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
    var s3config = S3Configuration.builder().pathStyleAccessEnabled(pathStyle).build();
    var clientBuilder =
        S3Client.builder()
            .region(Region.of(region))
            .credentialsProvider(credentials)
            .serviceConfiguration(s3config);
    var presignerBuilder =
        S3Presigner.builder()
            .region(Region.of(region))
            .credentialsProvider(credentials)
            .serviceConfiguration(s3config);
    if (internalEndpoint != null) clientBuilder.endpointOverride(internalEndpoint);
    if (publicEndpoint != null) presignerBuilder.endpointOverride(publicEndpoint);
    client = clientBuilder.build();
    presigner = presignerBuilder.build();
  }

  @Override
  public void put(String key, byte[] pdf) {
    client.putObject(
        PutObjectRequest.builder().bucket(bucket).key(key).contentType("application/pdf").build(),
        RequestBody.fromBytes(pdf));
  }

  @Override
  public URI presignGet(String key, Duration lifetime) {
    checkLifetime(lifetime);
    return URI.create(
        presigner
            .presignGetObject(
                GetObjectPresignRequest.builder()
                    .signatureDuration(lifetime)
                    .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).build())
                    .build())
            .url()
            .toString());
  }

  public URI presignPut(String key, Duration lifetime) {
    checkLifetime(lifetime);
    return URI.create(
        presigner
            .presignPutObject(
                PutObjectPresignRequest.builder()
                    .signatureDuration(lifetime)
                    .putObjectRequest(PutObjectRequest.builder().bucket(bucket).key(key).build())
                    .build())
            .url()
            .toString());
  }

  private static void checkLifetime(Duration lifetime) {
    if (lifetime.isNegative()
        || lifetime.isZero()
        || lifetime.compareTo(Duration.ofMinutes(15)) > 0)
      throw new IllegalArgumentException("Ticket URL lifetime must be 1 second to 15 minutes");
  }

  @Override
  public void close() {
    presigner.close();
    client.close();
  }
}
