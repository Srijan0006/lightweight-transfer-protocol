package lwsp;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.*;
import javax.imageio.ImageIO;

public class Sender {

    static final String RECEIVER_IP = "127.0.0.1";
    static final int PORT = 5005;
    static final int FPS = 20;
    static final int MTU = 1400;

    public static void main(String[] args) throws Exception {
        DatagramSocket socket = new DatagramSocket();
        InetAddress receiver = InetAddress.getByName(RECEIVER_IP);
        Robot robot = new Robot();
        Rectangle capture = new Rectangle(0, 0, 600, 600);

        int seqNum = 0;
        int frameId = 0;
        long frameDuration = 1000 / FPS;

        System.out.println("LWSP Sender started → " + RECEIVER_IP + ":" + PORT);

        while (true) {
            long frameStart = System.currentTimeMillis();

            BufferedImage frame = robot.createScreenCapture(capture);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(frame, "jpg", baos);
            byte[] frameBytes = baos.toByteArray();

            int totalFrags = (int) Math.ceil((double) frameBytes.length / MTU);

            for (int i = 0; i < totalFrags; i++) {
                int start = i * MTU;
                int end = Math.min(start + MTU, frameBytes.length);
                byte[] chunk = new byte[end - start];
                System.arraycopy(frameBytes, start, chunk, 0, chunk.length);

                LWSPPacket packet = new LWSPPacket();
                packet.type = (i == 0) ? LWSPPacket.TYPE_VIDEO_KEY : LWSPPacket.TYPE_VIDEO_DELTA;
                packet.sequenceNum = seqNum++;
                packet.timestamp = System.nanoTime();
                packet.frameId = frameId;
                packet.fragIndex = (short) i;
                packet.totalFrags = (short) totalFrags;
                packet.payload = chunk;

                byte[] raw = packet.serialize();
                socket.send(new DatagramPacket(raw, raw.length, receiver, PORT));
            }

            System.out.printf("Frame %d sent — %d bytes, %d fragments%n",
                    frameId, frameBytes.length, totalFrags);
            frameId++;

            long elapsed = System.currentTimeMillis() - frameStart;
            long sleep = frameDuration - elapsed;
            if (sleep > 0) {
                Thread.sleep(sleep);
            }
        }
    }
}
