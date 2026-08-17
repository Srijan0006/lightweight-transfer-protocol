package lwsp;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import javax.imageio.ImageIO;

public class Sender {

    static final String RECEIVER_IP = "127.0.0.1";
    static final int PORT = 5005;
    static final int FPS = 20;
    static final int MTU = 1400;
    static final int HANDSHAKE_TIMEOUT_MS = 400;
    static final int HANDSHAKE_ATTEMPTS = 8;
    static final byte FRAME_TTL = 6;
    static final Random RANDOM = new Random();

    public static void main(String[] args) throws Exception {
        String receiverIp = args.length > 0 ? args[0] : RECEIVER_IP;
        String secret = args.length > 1 ? args[1] : System.getenv("LWSP_SECRET");

        DatagramSocket socket = new DatagramSocket();
        InetAddress receiver = InetAddress.getByName(receiverIp);
        Robot robot = new Robot();
        Rectangle capture = new Rectangle(0, 0, 600, 600);

        int msgId = 0;
        long frameDuration = 1000 / FPS;

        System.out.println("LWSP Sender -> " + receiverIp + ":" + PORT);
        System.out.printf("Header: 4 bytes (single) / 6 bytes (fragment) vs old 24 bytes%n");
        System.out.println("Secret mode: " + LWSPCrypto.enabled(secret));

        performHandshake(socket, receiver, secret);
        System.out.println("Handshake complete, starting stream.");

        while (true) {
            long frameStart = System.currentTimeMillis();

            BufferedImage frame = robot.createScreenCapture(capture);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(frame, "jpg", baos);
            byte[] frameBytes = baos.toByteArray();

            int totalFrags = (int) Math.ceil((double) frameBytes.length / MTU);
            int frameMsgId = msgId++ & 0xFFFF;

            for (int i = 0; i < totalFrags; i++) {
                int start = i * MTU;
                int end = Math.min(start + MTU, frameBytes.length);
                byte[] chunk = new byte[end - start];
                System.arraycopy(frameBytes, start, chunk, 0, chunk.length);

                LWSPPacket packet = LWSPPacket.publishFragment(
                        LWSPTopics.SCREEN,
                        frameMsgId,
                        FRAME_TTL,
                        true,
                        i,
                        totalFrags,
                        chunk);

                byte[] raw = packet.serialize();
                socket.send(new DatagramPacket(raw, raw.length, receiver, PORT));
            }

            System.out.printf("msg=%d sent - %d payload bytes, %d frags, %d bytes wire overhead%n",
                    frameMsgId, frameBytes.length, totalFrags, totalFrags * LWSPPacket.FRAG_HEADER_SIZE);
            System.out.printf("  saved %d bytes vs old header on this frame%n",
                    totalFrags * (24 - LWSPPacket.FRAG_HEADER_SIZE));

            long elapsed = System.currentTimeMillis() - frameStart;
            long sleep = frameDuration - elapsed;
            if (sleep > 0) {
                Thread.sleep(sleep);
            }
        }
    }

    static void performHandshake(DatagramSocket socket, InetAddress receiver, String secret) throws Exception {
        int nonce = RANDOM.nextInt(0x10000);
        LWSPPacket hello = LWSPPacket.control(
                LWSPPacket.TYPE_HELLO,
                LWSPTopics.CONTROL,
                nonce,
                "LWSP-SENDER".getBytes(StandardCharsets.UTF_8));
        byte[] raw = LWSPWire.encode(hello, secret);

        socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);

        for (int attempt = 1; attempt <= HANDSHAKE_ATTEMPTS; attempt++) {
            socket.send(new DatagramPacket(raw, raw.length, receiver, PORT));
            System.out.printf("Handshake attempt %d/%d sent (nonce=%d)%n", attempt, HANDSHAKE_ATTEMPTS, nonce);

            try {
                byte[] buf = new byte[256];
                DatagramPacket response = new DatagramPacket(buf, buf.length);
                socket.receive(response);

                LWSPPacket packet = LWSPWire.decode(
                        java.util.Arrays.copyOf(response.getData(), response.getLength()),
                        secret);
                if (packet.type == LWSPPacket.TYPE_WELCOME
                        && packet.msgId == nonce
                        && packet.topicId == LWSPTopics.CONTROL) {
                    System.out.println("Received WELCOME: " + new String(packet.payload, StandardCharsets.UTF_8));
                    socket.setSoTimeout(0);
                    return;
                }
            } catch (SocketTimeoutException ignored) {
                // Retry until the receiver answers.
            }
        }

        socket.setSoTimeout(0);
        throw new SocketTimeoutException("Handshake timed out after " + HANDSHAKE_ATTEMPTS + " attempts");
    }
}
