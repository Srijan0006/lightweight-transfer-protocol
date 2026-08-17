package lwsp;

final class LWSPWire {

    private LWSPWire() {
    }

    static byte[] encode(LWSPPacket packet, String secret) throws Exception {
        if (LWSPCrypto.enabled(secret) && packet.type != LWSPPacket.TYPE_PUBLISH) {
            LWSPPacket encrypted = packet.copy();
            encrypted.payload = LWSPCrypto.encrypt(secret, encrypted.headerBytes(), encrypted.payload);
            return encrypted.serialize();
        }
        return packet.serialize();
    }

    static LWSPPacket decode(byte[] data, String secret) throws Exception {
        LWSPPacket packet = LWSPPacket.deserialize(data);
        if (LWSPCrypto.enabled(secret) && packet.type != LWSPPacket.TYPE_PUBLISH) {
            packet.payload = LWSPCrypto.decrypt(secret, packet.headerBytes(), packet.payload);
        }
        return packet;
    }
}
