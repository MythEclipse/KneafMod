package com.kneaf.core.performance;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Compact binary envelope: [0..8) u64 little-endian taskId [8] u8 taskType [9..13) u32
 * little-endian payload length [13..] payload bytes
 */
public class TaskEnvelope {
  public long taskId;
  public byte taskType;
  public byte[] payload;

  // Task type constants
  public static final byte TYPE_ECHO = 0x01;
  public static final byte TYPE_HEAVY = 0x02;
  public static final byte TYPE_ITEM_OPTIMIZE = 0x03;
  public static final byte TYPE_PANIC_TEST = (byte) 0xFF;

  public TaskEnvelope() {}

  public TaskEnvelope(long taskId, byte taskType, byte[] payload) {
    this.taskId = taskId;
    this.taskType = taskType;
    this.payload = payload;
  }

  public byte[] toBytes() {
    int payloadLen = payload == null ? 0 : payload.length;
    ByteBuffer bb = ByteBuffer.allocate(13 + payloadLen).order(ByteOrder.LITTLE_ENDIAN);
    bb.putLong(taskId);
    bb.put(taskType);
    bb.putInt(payloadLen);
    if (payloadLen > 0) bb.put(payload);
    return bb.array();
  }

  public static TaskEnvelope fromBytes(byte[] b) {
    if (b == null || b.length < 13) throw new IllegalArgumentException("invalid envelope");
    ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
    long id = bb.getLong();
    byte t = bb.get();
    int len = bb.getInt();
    if (len < 0 || bb.remaining() < len)
      throw new IllegalArgumentException("invalid payload length");
    byte[] p = new byte[len];
    if (len > 0) bb.get(p);
    return new TaskEnvelope(id, t, p);
  }
}
