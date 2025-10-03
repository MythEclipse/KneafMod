package com.kneaf.core.performance;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class ResultEnvelope {
    public long taskId;
    public int status; // 0 ok, 1 error
    public byte[] payload;

    public static final int STATUS_OK = 0;
    public static final int STATUS_ERROR = 1;

    public ResultEnvelope() {}

    public ResultEnvelope(long taskId, int status, byte[] payload) {
        this.taskId = taskId;
        this.status = status;
        this.payload = payload;
    }

    public static ResultEnvelope fromBytes(byte[] b) {
        if (b == null || b.length < 13) throw new IllegalArgumentException("invalid result envelope");
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        long id = bb.getLong();
        int status = bb.get() & 0xff;
        int len = bb.getInt();
        if (len < 0 || bb.remaining() < len) throw new IllegalArgumentException("invalid payload length");
        byte[] p = new byte[len];
        if (len > 0) bb.get(p);
        return new ResultEnvelope(id, status, p);
    }

    public boolean isError() { return this.status == STATUS_ERROR; }
    public String payloadAsString() { return payload == null ? "" : new String(payload, java.nio.charset.StandardCharsets.UTF_8); }
}
