package lwsp;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class Broker {

    static final int PORT = 5005;
    static final int BUF_SIZE = 65535;

    private static final Set<SocketAddress> subscribers = new HashSet<>();
    private static final Map<SocketAddress, Set<Byte>> topicFilters = new HashMap<>();

    public static void main(String[] args) throws Exception {
        String secret = args.length > 0 ? args[0] : System.getenv("LWSP_SECRET");

        DatagramSocket socket = new DatagramSocket(PORT);
        byte[] buf = new byte[BUF_SIZE];

        System.out.println("LWSP Broker on port " + PORT);
        System.out.println("Secret mode: " + LWSPCrypto.enabled(secret));

        while (true) {
            DatagramPacket dp = new DatagramPacket(buf, buf.length);
            socket.receive(dp);

            byte[] data = Arrays.copyOf(dp.getData(), dp.getLength());
            LWSPPacket packet;
            try {
                packet = LWSPWire.decode(data, secret);
            } catch (Exception e) {
                System.err.println("Drop packet from " + dp.getSocketAddress() + ": " + e.getMessage());
                continue;
            }

            SocketAddress client = dp.getSocketAddress();
            switch (packet.type) {
                case LWSPPacket.TYPE_HELLO -> handleHello(socket, client, packet, secret);
                case LWSPPacket.TYPE_SUBSCRIBE -> handleSubscribe(client, packet);
                case LWSPPacket.TYPE_PUBLISH -> relayPublish(socket, client, packet, data);
                case LWSPPacket.TYPE_PING -> sendControl(socket, client, LWSPPacket.TYPE_PONG, packet.msgId, "PONG", secret);
                default -> System.out.println("Ignore type " + packet.type + " from " + client);
            }
        }
    }

    static void handleHello(DatagramSocket socket, SocketAddress client, LWSPPacket packet, String secret)
            throws Exception {
        subscribers.add(client);
        topicFilters.computeIfAbsent(client, ignored -> new HashSet<>());
        String role = new String(packet.payload, StandardCharsets.UTF_8);
        sendControl(socket, client, LWSPPacket.TYPE_WELCOME, packet.msgId, "WELCOME " + role, secret);
        System.out.println("HELLO from " + client + " role=" + role);
    }

    static void handleSubscribe(SocketAddress client, LWSPPacket packet) {
        subscribers.add(client);
        topicFilters.computeIfAbsent(client, ignored -> new HashSet<>()).add(packet.topicId);
        System.out.println("SUBSCRIBE " + LWSPTopics.name(packet.topicId) + " from " + client);
    }

    static void relayPublish(DatagramSocket socket, SocketAddress sender, LWSPPacket packet, byte[] raw)
            throws Exception {
        int count = 0;
        for (SocketAddress subscriber : subscribers) {
            if (subscriber.equals(sender)) {
                continue;
            }
            Set<Byte> filters = topicFilters.get(subscriber);
            if (filters != null && !filters.isEmpty() && !filters.contains(packet.topicId)) {
                continue;
            }
            socket.send(new DatagramPacket(raw, raw.length, subscriber));
            count++;
        }
        System.out.println("RELAY " + LWSPTopics.name(packet.topicId) + " to " + count + " client(s)");
    }

    static void sendControl(DatagramSocket socket, SocketAddress target, byte type, int msgId, String payload,
            String secret) throws Exception {
        LWSPPacket control = LWSPPacket.control(type, LWSPTopics.CONTROL, msgId,
                payload.getBytes(StandardCharsets.UTF_8));
        byte[] raw = LWSPWire.encode(control, secret);
        socket.send(new DatagramPacket(raw, raw.length, target));
    }
}
