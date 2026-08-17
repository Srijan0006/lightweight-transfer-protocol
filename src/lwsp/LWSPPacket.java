package lwsp;

import java.nio.ByteBuffer;

/**
 * Lightweight UDP framing layer. Fixed 4-byte header; +2 bytes when fragmented.
 *
 * Byte 0: [ver:2][type:4][more_frags:1][frag_msg:1]
 * Byte 1: [ttl:4][topic_id:3][retain:1]
 * Byte 2-3: msg_id (uint16)
 * Byte 4-5 (if frag_msg): frag_index, total_frags
 */
public class LWSPPacket {

    public static final byte VERSION = 0;

    public static final byte TYPE_PUBLISH = 1;
    public static final byte TYPE_SUBSCRIBE = 2;
    public static final byte TYPE_PING = 3;
    public static final byte TYPE_PONG = 4;
    public static final byte TYPE_HELLO = 5;
    public static final byte TYPE_WELCOME = 6;

    public static final byte FLAG_MORE_FRAGS = 0x01;
    public static final byte FLAG_FRAG_MSG = 0x02;
    public static final byte FLAG_RETAIN = 0x04;

    /** TTL unit on receiver: max reassembly age = ttl * TTL_UNIT_MS (0 = no limit). */
    public static final long TTL_UNIT_MS = 200L;

    public static final int MIN_HEADER_SIZE = 4;
    public static final int FRAG_HEADER_SIZE = 6;

    public byte type = TYPE_PUBLISH;
    public byte ttl;
    public byte topicId;
    public boolean moreFrags;
    public boolean fragMsg;
    public boolean retain;
    public int msgId;
    public int fragIndex;
    public int totalFrags;
    public byte[] payload = new byte[0];

    public int headerSize() {
        return fragMsg ? FRAG_HEADER_SIZE : MIN_HEADER_SIZE;
    }

    public byte[] headerBytes() {
        ByteBuffer buf = ByteBuffer.allocate(headerSize());

        byte flags = 0;
        if (moreFrags) {
            flags |= FLAG_MORE_FRAGS;
        }
        if (fragMsg) {
            flags |= FLAG_FRAG_MSG;
        }
        if (retain) {
            flags |= FLAG_RETAIN;
        }

        buf.put((byte) ((VERSION << 6) | ((type & 0x0F) << 2) | (flags & 0x03)));
        int b1 = ((ttl & 0x0F) << 4) | ((topicId & 0x07) << 1);
        if (retain) {
            b1 |= 0x01;
        }
        buf.put((byte) b1);
        buf.putShort((short) (msgId & 0xFFFF));

        if (fragMsg) {
            buf.put((byte) fragIndex);
            buf.put((byte) totalFrags);
        }

        return buf.array();
    }

    public LWSPPacket copy() {
        LWSPPacket p = new LWSPPacket();
        p.type = type;
        p.ttl = ttl;
        p.topicId = topicId;
        p.moreFrags = moreFrags;
        p.fragMsg = fragMsg;
        p.retain = retain;
        p.msgId = msgId;
        p.fragIndex = fragIndex;
        p.totalFrags = totalFrags;
        p.payload = payload.clone();
        return p;
    }

    public byte[] serialize() {
        byte[] header = headerBytes();
        ByteBuffer buf = ByteBuffer.allocate(header.length + payload.length);
        buf.put(header);
        buf.put(payload);
        return buf.array();
    }

    public static LWSPPacket deserialize(byte[] data) {
        if (data.length < MIN_HEADER_SIZE) {
            throw new IllegalArgumentException("Packet too short: " + data.length);
        }

        ByteBuffer buf = ByteBuffer.wrap(data);
        LWSPPacket p = new LWSPPacket();

        byte b0 = buf.get();
        p.type = (byte) ((b0 >> 2) & 0x0F);
        p.moreFrags = (b0 & FLAG_MORE_FRAGS) != 0;
        p.fragMsg = (b0 & FLAG_FRAG_MSG) != 0;

        byte b1 = buf.get();
        p.ttl = (byte) ((b1 >> 4) & 0x0F);
        p.topicId = (byte) ((b1 >> 1) & 0x07);
        p.retain = (b1 & 0x01) != 0;

        p.msgId = buf.getShort() & 0xFFFF;

        if (p.fragMsg) {
            if (data.length < FRAG_HEADER_SIZE) {
                throw new IllegalArgumentException("Fragment packet too short: " + data.length);
            }
            p.fragIndex = buf.get() & 0xFF;
            p.totalFrags = buf.get() & 0xFF;
        }

        p.payload = new byte[buf.remaining()];
        buf.get(p.payload);
        return p;
    }

    public static LWSPPacket publish(byte topicId, int msgId, byte[] payload) {
        LWSPPacket p = new LWSPPacket();
        p.type = TYPE_PUBLISH;
        p.topicId = topicId;
        p.msgId = msgId;
        p.payload = payload;
        return p;
    }

    public static LWSPPacket control(byte type, byte topicId, int msgId, byte[] payload) {
        LWSPPacket p = new LWSPPacket();
        p.type = type;
        p.topicId = topicId;
        p.msgId = msgId;
        p.payload = payload;
        return p;
    }

    public static LWSPPacket publishFragment(byte topicId, int msgId, byte ttl, boolean retain,
            int fragIndex, int totalFrags, byte[] chunk) {
        LWSPPacket p = publish(topicId, msgId, chunk);
        p.ttl = ttl;
        p.retain = retain;
        p.fragMsg = true;
        p.moreFrags = fragIndex < totalFrags - 1;
        p.fragIndex = fragIndex;
        p.totalFrags = totalFrags;
        return p;
    }

    @Override
    public String toString() {
        if (fragMsg) {
            return String.format("[LWSP] type=%d topic=%d msg=%d frag=%d/%d ttl=%d payload=%d",
                    type, topicId, msgId, fragIndex + 1, totalFrags, ttl, payload.length);
        }
        return String.format("[LWSP] type=%d topic=%d msg=%d ttl=%d payload=%d",
                type, topicId, msgId, ttl, payload.length);
    }
}
