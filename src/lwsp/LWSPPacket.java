package lwsp;

import java.nio.ByteBuffer;

public class LWSPPacket {

    public static final byte TYPE_VIDEO_KEY   = 0x1;
    public static final byte TYPE_VIDEO_DELTA = 0x2;
    public static final byte TYPE_AUDIO       = 0x3;
    public static final byte TYPE_ACK         = 0x4;
    public static final byte TYPE_SYNC        = 0x5;

    // Header fields
    public byte  version      = 1;
    public byte  type;
    public int   sequenceNum;
    public long  timestamp;           // System.nanoTime() at send time
    public int   frameId;             // which video frame this belongs to
    public short fragIndex;           // fragment index (for large frames)
    public short totalFrags;          // total fragments in this frame
    public byte  flags;               // reserved for future use
    public byte[] payload;

    public static final int HEADER_SIZE = 24; // bytes

    // Pack this packet into bytes to send over UDP
    public byte[] serialize() {
        ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE + payload.length);
        buf.put(version);
        buf.put(type);
        buf.putInt(sequenceNum);
        buf.putLong(timestamp);
        buf.putInt(frameId);
        buf.putShort(fragIndex);
        buf.putShort(totalFrags);
        buf.put(flags);
        buf.put((byte) 0); // padding
        buf.put(payload);
        return buf.array();
    }

    // Unpack bytes received from UDP into a packet
    public static LWSPPacket deserialize(byte[] data) {
        ByteBuffer buf = ByteBuffer.wrap(data);
        LWSPPacket p   = new LWSPPacket();
        p.version      = buf.get();
        p.type         = buf.get();
        p.sequenceNum  = buf.getInt();
        p.timestamp    = buf.getLong();
        p.frameId      = buf.getInt();
        p.fragIndex    = buf.getShort();
        p.totalFrags   = buf.getShort();
        p.flags        = buf.get();
        buf.get(); // padding
        p.payload      = new byte[buf.remaining()];
        buf.get(p.payload);
        return p;
    }

    public String toString() {
        return String.format("[LWSP] type=%d seq=%d frameId=%d frag=%d/%d payloadSize=%d",
            type, sequenceNum, frameId, fragIndex+1, totalFrags, payload.length);
    }
}