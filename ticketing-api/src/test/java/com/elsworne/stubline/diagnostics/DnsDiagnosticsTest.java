package com.elsworne.stubline.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Resolver abstraction preserves all answers without requiring DNS infrastructure. */
class DnsDiagnosticsTest {
  @Test
  void reportsAllAnswersAndConfiguredTtl() throws Exception {
    String previous = System.getProperty("sun.net.inetaddr.ttl");
    System.setProperty("sun.net.inetaddr.ttl", "5");
    try {
      var diagnostics = new DnsDiagnostics(name -> List.of("answer-a", "answer-b"));
      var report = diagnostics.report("inventory-service");
      assertEquals(List.of("answer-a", "answer-b"), report.addresses());
      assertEquals("5", report.effectiveTtl());
      assertThrows(IllegalArgumentException.class, () -> diagnostics.report(""));
    } finally {
      if (previous == null) System.clearProperty("sun.net.inetaddr.ttl");
      else System.setProperty("sun.net.inetaddr.ttl", previous);
    }
  }
}
