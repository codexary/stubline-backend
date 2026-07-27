package com.elsworne.stubline.tickets;

import com.elsworne.stubline.orders.Order;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;

/** Writes a one-page PDF ticket with a deterministic QR-like visual identifier. */
public final class TicketRenderer {
  public byte[] render(Order order) {
    var content = new StringBuilder();
    content.append("BT /F1 20 Tf 40 250 Td (STUBLINE E-TICKET) Tj ET\n");
    line(content, 205, "Order: " + order.id());
    line(content, 175, "Event: " + order.eventId());
    line(content, 145, "Seat: " + order.seatId());
    byte[] digest = digest(order.id() + ":" + order.seatId());
    for (int y = 0; y < 16; y++) {
      for (int x = 0; x < 16; x++) {
        int index = y * 16 + x;
        if ((digest[index / 8] & (1 << (index % 8))) != 0)
          content.append(400 + x * 9).append(' ').append(95 + y * 9).append(" 9 9 re f\n");
      }
    }
    byte[] stream = content.toString().getBytes(StandardCharsets.US_ASCII);
    try (var output = new ByteArrayOutputStream()) {
      var offsets = new ArrayList<Integer>();
      write(output, "%PDF-1.4\n");
      object(output, offsets, 1, "<< /Type /Catalog /Pages 2 0 R >>");
      object(output, offsets, 2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>");
      object(
          output,
          offsets,
          3,
          "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 300] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>");
      object(output, offsets, 4, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>");
      offsets.add(output.size());
      write(output, "5 0 obj\n<< /Length " + stream.length + " >>\nstream\n");
      output.write(stream);
      write(output, "endstream\nendobj\n");
      int xref = output.size();
      write(output, "xref\n0 6\n0000000000 65535 f \n");
      for (int offset : offsets) write(output, "%010d 00000 n \n".formatted(offset));
      write(output, "trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n");
      return output.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException("Cannot render ticket", e);
    }
  }

  private static void line(StringBuilder content, int y, String value) {
    String safe = value.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
    content.append("BT /F1 14 Tf 40 ").append(y).append(" Td (").append(safe).append(") Tj ET\n");
  }

  private static byte[] digest(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void object(
      ByteArrayOutputStream output, ArrayList<Integer> offsets, int id, String body)
      throws IOException {
    offsets.add(output.size());
    write(output, id + " 0 obj\n" + body + "\nendobj\n");
  }

  private static void write(ByteArrayOutputStream output, String value) throws IOException {
    output.write(value.getBytes(StandardCharsets.US_ASCII));
  }
}
