package com.elsworne.stubline.diagnostics;

import com.elsworne.stubline.inventory.GrpcInventoryClient;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Duration;

/** Demonstrates a client that resolves once and pins one inventory replica. */
public final class PinnedInventoryClient {
  private PinnedInventoryClient() {}

  public static void main(String[] args) throws Exception {
    String name = args.length > 0 ? args[0] : "inventory-service";
    int port = args.length > 1 ? Integer.parseInt(args[1]) : 9090;
    InetAddress selected = InetAddress.getAllByName(name)[0];
    System.out.println("Pinned " + name + " to " + selected.getHostAddress());
    var builder =
        NettyChannelBuilder.forAddress(selected.getHostAddress(), port).overrideAuthority(name);
    if (System.getenv("STUBLINE_GRPC_CA") == null) builder.usePlaintext();
    else
      builder.sslContext(
          GrpcSslContexts.forClient()
              .trustManager(Path.of(System.getenv("STUBLINE_GRPC_CA")).toFile())
              .keyManager(
                  Path.of(System.getenv("STUBLINE_GRPC_CERT")).toFile(),
                  Path.of(System.getenv("STUBLINE_GRPC_KEY")).toFile())
              .build());
    try (var inventory = new GrpcInventoryClient(builder.build(), Duration.ofSeconds(2))) {
      for (int i = 0; i < 12; i++) {
        inventory.seatsFor("concert-1");
        System.out.println("request " + (i + 1) + " completed");
      }
    }
  }
}
