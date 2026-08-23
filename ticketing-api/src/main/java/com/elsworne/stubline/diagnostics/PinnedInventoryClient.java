package com.elsworne.stubline.diagnostics;

import com.elsworne.stubline.inventory.GrpcInventoryClient;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import java.net.InetAddress;
import java.time.Duration;

/** Demonstrates a client that resolves once and pins one inventory replica. */
public final class PinnedInventoryClient {
  private PinnedInventoryClient() {}

  public static void main(String[] args) throws Exception {
    String name = args.length > 0 ? args[0] : "inventory-service";
    int port = args.length > 1 ? Integer.parseInt(args[1]) : 9090;
    InetAddress selected = InetAddress.getAllByName(name)[0];
    System.out.println("Pinned " + name + " to " + selected.getHostAddress());
    try (var inventory =
        new GrpcInventoryClient(
            NettyChannelBuilder.forAddress(selected.getHostAddress(), port).usePlaintext().build(),
            Duration.ofSeconds(2))) {
      for (int i = 0; i < 12; i++) {
        inventory.seatsFor("concert-1");
        System.out.println("request " + (i + 1) + " completed");
      }
    }
  }
}
