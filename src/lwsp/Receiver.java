package lwsp;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.*;
import java.util.*;
import javax.imageio.ImageIO;
import javax.swing.*;

public class Receiver {

    static final int PORT = 5005;
    static final int BUF_SIZE = 65535;
    static final byte[] WELCOME_PAYLOAD = "WELCOME".getBytes();

    static final LWSPTopics.SubscriptionFilter subscriptions = new LWSPTopics.SubscriptionFilter();
    static final Map<Integer, FrameAssembly> assemblies = new HashMap<>();
    static final Map<Byte, byte[]> retained = new HashMap<>();

    public static void main(String[] args) throws Exception {
        String brokerIp = args.length > 0 ? args[0] : null;
        String secret = args.length > 1 ? args[1] : System.getenv("LWSP_SECRET");
        subscriptions.subscribe(LWSPTopics.SCREEN);

        if (brokerIp == null || brokerIp.isBlank()) {
            runDirectMode(secret);
        } else {
            runBrokerMode(brokerIp, secret);
        }
    }

    static void runDirectMode(String secret) throws Exception {
        DatagramSocket socket = new DatagramSocket(PORT);
        byte[] buf = new byte[BUF_SIZE];
        JFrame window = createWindow("LWSP Receiver - direct/topic/" + LWSPTopics.SCREEN);
        JLabel label = (JLabel) window.getContentPane().getComponent(0);

        showRetained(window, label);

        System.out.println("LWSP Receiver on port " + PORT);
        System.out.println("Subscribed: " + subscriptions.subscribed());
        System.out.println("Secret mode: " + LWSPCrypto.enabled(secret));

        while (true) {
            DatagramPacket dp = receive(socket, buf);
            LWSPPacket packet = decode(dp, secret);
            if (packet == null) {
                continue;
            }

            switch (packet.type) {
                case LWSPPacket.TYPE_HELLO -> sendWelcome(socket, dp.getAddress(), dp.getPort(), packet.msgId, secret);
                case LWSPPacket.TYPE_SUBSCRIBE -> handleSubscribe(packet);
                case LWSPPacket.TYPE_PUBLISH -> handlePublish(packet, window, label);
                case LWSPPacket.TYPE_PING -> sendPong(socket, dp.getAddress(), dp.getPort(), secret);
                default -> System.out.println("Ignore type " + packet.type);
            }
        }
    }

    static void runBrokerMode(String brokerIp, String secret) throws Exception {
        DatagramSocket socket = new DatagramSocket();
        InetAddress broker = InetAddress.getByName(brokerIp);
        byte[] buf = new byte[BUF_SIZE];
        JFrame window = createWindow("LWSP Receiver - broker/" + brokerIp + "/topic/" + LWSPTopics.SCREEN);
        JLabel label = (JLabel) window.getContentPane().getComponent(0);

        performHandshake(socket, broker, secret);
        sendSubscribe(socket, broker, LWSPTopics.SCREEN, secret);
        showRetained(window, label);

        System.out.println("LWSP Receiver connected to broker " + brokerIp + ":" + PORT);
        System.out.println("Subscribed: " + subscriptions.subscribed());
        System.out.println("Secret mode: " + LWSPCrypto.enabled(secret));

        while (true) {
            DatagramPacket dp = receive(socket, buf);
            LWSPPacket packet = decode(dp, secret);
            if (packet == null) {
                continue;
            }

            switch (packet.type) {
                case LWSPPacket.TYPE_PUBLISH -> handlePublish(packet, window, label);
                case LWSPPacket.TYPE_PING -> sendPong(socket, dp.getAddress(), dp.getPort(), secret);
                default -> System.out.println("Ignore type " + packet.type);
            }
        }
    }

    static JFrame createWindow(String title) {
        JFrame window = new JFrame(title);
        JLabel label = new JLabel();
        window.add(label);
        window.setSize(600, 600);
        window.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        window.setVisible(true);
        return window;
    }

    static void showRetained(JFrame window, JLabel label) {
        byte[] cached = retained.get(LWSPTopics.SCREEN);
        if (cached != null) {
            displayImage(window, label, cached);
        }
    }

    static DatagramPacket receive(DatagramSocket socket, byte[] buf) throws Exception {
        DatagramPacket dp = new DatagramPacket(buf, buf.length);
        socket.receive(dp);
        return dp;
    }

    static LWSPPacket decode(DatagramPacket dp, String secret) {
        byte[] data = Arrays.copyOf(dp.getData(), dp.getLength());
        try {
            return LWSPWire.decode(data, secret);
        } catch (Exception e) {
            System.err.println("Drop malformed packet: " + e.getMessage());
            return null;
        }
    }

    static void performHandshake(DatagramSocket socket, InetAddress broker, String secret) throws Exception {
        int nonce = new Random().nextInt(0x10000);
        LWSPPacket hello = LWSPPacket.control(
                LWSPPacket.TYPE_HELLO,
                LWSPTopics.CONTROL,
                nonce,
                "LWSP-RECEIVER".getBytes());
        byte[] raw = LWSPWire.encode(hello, secret);

        socket.setSoTimeout(400);
        for (int attempt = 1; attempt <= 8; attempt++) {
            socket.send(new DatagramPacket(raw, raw.length, broker, PORT));
            try {
                byte[] buf = new byte[256];
                DatagramPacket response = new DatagramPacket(buf, buf.length);
                socket.receive(response);
                LWSPPacket packet = LWSPWire.decode(Arrays.copyOf(response.getData(), response.getLength()), secret);
                if (packet.type == LWSPPacket.TYPE_WELCOME && packet.msgId == nonce) {
                    System.out.println("Handshake complete: " + new String(packet.payload));
                    socket.setSoTimeout(0);
                    return;
                }
            } catch (SocketTimeoutException ignored) {
                // Retry.
            }
        }
        socket.setSoTimeout(0);
        throw new SocketTimeoutException("Handshake timed out");
    }

    static void sendSubscribe(DatagramSocket socket, InetAddress target, byte topicId, String secret) throws Exception {
        LWSPPacket subscribe = LWSPPacket.control(
                LWSPPacket.TYPE_SUBSCRIBE,
                topicId,
                0,
                new byte[0]);
        byte[] raw = LWSPWire.encode(subscribe, secret);
        socket.send(new DatagramPacket(raw, raw.length, target, PORT));
    }

    static void handleSubscribe(LWSPPacket packet) {
        subscriptions.subscribe(packet.topicId);
        System.out.println("SUBSCRIBE " + LWSPTopics.name(packet.topicId));
    }

    static void handlePublish(LWSPPacket packet, JFrame window, JLabel label) {
        if (!subscriptions.accepts(packet.topicId)) {
            return;
        }

        if (!packet.fragMsg) {
            deliver(packet.topicId, packet.payload, packet.retain, window, label);
            return;
        }

        long now = System.currentTimeMillis();
        FrameAssembly assembly = assemblies.computeIfAbsent(packet.msgId, FrameAssembly::new);
        assembly.touch(now);

        if (packet.ttl > 0 && now - assembly.firstSeenMs > packet.ttl * LWSPPacket.TTL_UNIT_MS) {
            assemblies.remove(packet.msgId);
            System.out.printf("Drop expired msg=%d (ttl=%d)%n", packet.msgId, packet.ttl);
            return;
        }

        assembly.put(packet.fragIndex, packet.totalFrags, packet.payload);

        if (assembly.isComplete()) {
            assemblies.remove(packet.msgId);
            deliver(packet.topicId, assembly.reassemble(), packet.retain, window, label);
        }

        assemblies.entrySet().removeIf(e -> e.getValue().isExpired(now));
    }

    static void deliver(byte topicId, byte[] payload, boolean retain, JFrame window, JLabel label) {
        if (retain) {
            retained.put(topicId, payload);
        }

        if (topicId == LWSPTopics.SCREEN) {
            displayImage(window, label, payload);
        }

        System.out.printf("PUBLISH %s - %d bytes%n", LWSPTopics.name(topicId), payload.length);
    }

    static void displayImage(JFrame window, JLabel label, byte[] frameBytes) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(frameBytes));
            if (img == null) {
                return;
            }
            Image scaled = img.getScaledInstance(window.getWidth(), window.getHeight(), Image.SCALE_FAST);
            SwingUtilities.invokeLater(() -> {
                label.setIcon(new ImageIcon(scaled));
                window.repaint();
            });
        } catch (Exception e) {
            System.err.println("Failed to decode frame: " + e.getMessage());
        }
    }

    static void sendPong(DatagramSocket socket, InetAddress sender, int port, String secret) throws Exception {
        LWSPPacket pong = LWSPPacket.control(LWSPPacket.TYPE_PONG, LWSPTopics.CONTROL, 0, new byte[0]);
        byte[] raw = LWSPWire.encode(pong, secret);
        socket.send(new DatagramPacket(raw, raw.length, sender, port));
    }

    static void sendWelcome(DatagramSocket socket, InetAddress sender, int port, int nonce, String secret)
            throws Exception {
        LWSPPacket welcome = LWSPPacket.control(
                LWSPPacket.TYPE_WELCOME,
                LWSPTopics.CONTROL,
                nonce,
                WELCOME_PAYLOAD);
        byte[] raw = LWSPWire.encode(welcome, secret);
        socket.send(new DatagramPacket(raw, raw.length, sender, port));
        System.out.printf("HELLO -> WELCOME nonce=%d%n", nonce);
    }

    static final class FrameAssembly {
        final int msgId;
        long firstSeenMs;
        long lastSeenMs;
        byte[][] frags;
        int totalFrags;

        FrameAssembly(int msgId) {
            this.msgId = msgId;
            long now = System.currentTimeMillis();
            this.firstSeenMs = now;
            this.lastSeenMs = now;
        }

        void touch(long now) {
            lastSeenMs = now;
        }

        void put(int index, int total, byte[] chunk) {
            if (frags == null || frags.length != total) {
                frags = new byte[total][];
                totalFrags = total;
            }
            frags[index] = chunk;
        }

        boolean isComplete() {
            if (frags == null) {
                return false;
            }
            for (byte[] f : frags) {
                if (f == null) {
                    return false;
                }
            }
            return true;
        }

        byte[] reassemble() {
            int total = 0;
            for (byte[] f : frags) {
                total += f.length;
            }
            byte[] out = new byte[total];
            int pos = 0;
            for (byte[] f : frags) {
                System.arraycopy(f, 0, out, pos, f.length);
                pos += f.length;
            }
            return out;
        }

        boolean isExpired(long now) {
            return now - lastSeenMs > 3000L;
        }
    }
}
