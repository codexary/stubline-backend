package com.elsworne.stubline.health;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Runs a UDP heartbeat sender or an operator-facing failure detector. */
public final class HeartbeatMain {
  private static final Logger LOG = LoggerFactory.getLogger(HeartbeatMain.class);

  private HeartbeatMain() {}

  public static void main(String[] args) throws Exception {
    if (args.length == 0)
      throw new IllegalArgumentException(
          "sender <id> <host> <port> <interval-ms> [drop-every] | detector <port> <timeout-ms>");
    if ("sender".equals(args[0]) && (args.length == 5 || args.length == 6)) {
      send(
          args[1],
          args[2],
          Integer.parseInt(args[3]),
          Long.parseLong(args[4]),
          args.length == 6 ? Long.parseLong(args[5]) : 0);
    } else if ("detector".equals(args[0]) && args.length == 3) {
      detect(Integer.parseInt(args[1]), Long.parseLong(args[2]));
    } else throw new IllegalArgumentException("Invalid arguments");
  }

  private static void send(String id, String host, int port, long intervalMillis, long dropEvery)
      throws Exception {
    if (intervalMillis <= 0 || dropEvery < 0)
      throw new IllegalArgumentException("Invalid interval or drop rate");
    var destination = new InetSocketAddress(host, port);
    try (var socket = new DatagramSocket();
        var scheduler = Executors.newSingleThreadScheduledExecutor()) {
      final long[] sequence = {0};
      scheduler.scheduleAtFixedRate(
          () -> {
            long current = ++sequence[0];
            if (dropEvery > 0 && current % dropEvery == 0) {
              LOG.info("dropped {} sequence={}", id, current);
              return;
            }
            byte[] bytes = new Heartbeat(id, current, System.currentTimeMillis()).encode();
            try {
              socket.send(new DatagramPacket(bytes, bytes.length, destination));
              LOG.info("sent {} sequence={}", id, current);
            } catch (Exception e) {
              LOG.error("heartbeat send failed", e);
            }
          },
          0,
          intervalMillis,
          TimeUnit.MILLISECONDS);
      new java.util.concurrent.CountDownLatch(1).await();
    }
  }

  private static void detect(int port, long timeoutMillis) throws Exception {
    var detector = new FailureDetector(Duration.ofMillis(timeoutMillis), System::nanoTime);
    try (var socket = new DatagramSocket(port)) {
      socket.setSoTimeout(100);
      MapPrinter printer = new MapPrinter();
      while (!Thread.currentThread().isInterrupted()) {
        byte[] bytes = new byte[256];
        var packet = new DatagramPacket(bytes, bytes.length);
        try {
          socket.receive(packet);
          detector.observe(Heartbeat.decode(packet.getData(), packet.getLength()));
        } catch (java.net.SocketTimeoutException ignored) {
        } catch (java.io.IOException e) {
          LOG.warn("discarded invalid heartbeat: {}", e.getMessage());
        }
        printer.printChanges(detector);
      }
    }
  }

  private static final class MapPrinter {
    private java.util.Map<String, FailureDetector.Status> previous = java.util.Map.of();

    void printChanges(FailureDetector detector) {
      var members = detector.membership();
      var current = new java.util.LinkedHashMap<String, FailureDetector.Status>();
      members.forEach((id, member) -> current.put(id, member.status()));
      if (!current.equals(previous)) {
        current.forEach((id, status) -> LOG.info("{} {}", id, status));
        previous = current;
      }
    }
  }
}
