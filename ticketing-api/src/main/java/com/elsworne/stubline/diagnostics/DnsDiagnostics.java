package com.elsworne.stubline.diagnostics;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.Security;
import java.util.Arrays;
import java.util.List;

/** Reports JVM DNS answers and its configured positive cache lifetime. */
public final class DnsDiagnostics {
  /** DNS lookup seam for deterministic tests. */
  public interface Resolver {
    List<String> resolve(String host) throws UnknownHostException;
  }

  /** One lookup report. */
  public record Result(String name, List<String> addresses, String effectiveTtl) {}

  private final Resolver resolver;

  public DnsDiagnostics() {
    this(
        host ->
            Arrays.stream(InetAddress.getAllByName(host))
                .map(InetAddress::getHostAddress)
                .toList());
  }

  public DnsDiagnostics(Resolver resolver) {
    this.resolver = resolver;
  }

  public Result report(String name) throws UnknownHostException {
    if (name == null || name.isBlank() || name.length() > 253)
      throw new IllegalArgumentException("DNS name required");
    String securityTtl = Security.getProperty("networkaddress.cache.ttl");
    String systemTtl = System.getProperty("sun.net.inetaddr.ttl");
    String effective =
        securityTtl != null ? securityTtl : systemTtl != null ? systemTtl : "JVM default";
    return new Result(name, resolver.resolve(name), effective);
  }
}
