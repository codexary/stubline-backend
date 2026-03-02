package com.elsworne.stubline.health;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** A compact UDP heartbeat with an instance identity, sequence and sender timestamp. */
public record Heartbeat(String instanceId, long sequence, long timestampMillis) {
  public Heartbeat {
    if (instanceId == null || instanceId.isBlank() || instanceId.length() > 100 || sequence < 0) {
      throw new IllegalArgumentException("Invalid heartbeat");
    }
  }

  public byte[] encode() {
    try {
      var bytes = new ByteArrayOutputStream();
      try (var out = new DataOutputStream(bytes)) {
        out.writeByte(1);
        out.writeUTF(instanceId);
        out.writeLong(sequence);
        out.writeLong(timestampMillis);
      }
      return bytes.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  public static Heartbeat decode(byte[] bytes, int length) throws IOException {
    if (length < 1 || length > 256) throw new IOException("Invalid heartbeat length");
    try (var in = new DataInputStream(new ByteArrayInputStream(bytes, 0, length))) {
      if (in.readUnsignedByte() != 1) throw new IOException("Unknown heartbeat version");
      var heartbeat = new Heartbeat(in.readUTF(), in.readLong(), in.readLong());
      if (in.available() != 0) throw new IOException("Trailing heartbeat bytes");
      return heartbeat;
    } catch (IllegalArgumentException e) {
      throw new IOException("Invalid heartbeat", e);
    }
  }
}
