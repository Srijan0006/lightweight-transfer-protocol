package lwsp;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.*;
import java.util.*;
import javax.imageio.ImageIO;
import javax.swing.*;

public class Receiver {

    static final int PORT     = 5005;
    static final int BUF_SIZE = 65535;

    // Reassembly buffer: frameId → list of fragments
    static Map<Integer, byte[][]> frameBuffer = new HashMap<>();

    public static void main(String[] args) throws Exception {
        DatagramSocket socket = new DatagramSocket(PORT);
        byte[] buf            = new byte[BUF_SIZE];

        // Simple window to display the stream
        JFrame   frame = new JFrame("LWSP Receiver");
        JLabel   label = new JLabel();
        frame.add(label);
        frame.setSize(1280, 720);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setVisible(true);

        System.out.println("LWSP Receiver listening on port " + PORT + "...");

        while (true) {
            DatagramPacket dp = new DatagramPacket(buf, buf.length);
            socket.receive(dp);

            byte[]     data   = Arrays.copyOf(dp.getData(), dp.getLength());
            LWSPPacket packet = LWSPPacket.deserialize(data);

            // Store fragment
            frameBuffer.computeIfAbsent(packet.frameId,
                k -> new byte[packet.totalFrags][]);
            frameBuffer.get(packet.frameId)[packet.fragIndex] = packet.payload;

            // Check if all fragments for this frame have arrived
            byte[][] frags = frameBuffer.get(packet.frameId);
            if (isComplete(frags)) {
                byte[] frameBytes = reassemble(frags);
                frameBuffer.remove(packet.frameId); // free memory

                // Decode and display
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(frameBytes));
                if (img != null) {
                    Image scaled = img.getScaledInstance(
                        frame.getWidth(), frame.getHeight(), Image.SCALE_FAST);
                    label.setIcon(new ImageIcon(scaled));
                    frame.repaint();
                }

                System.out.printf("Frame %d displayed — %d bytes%n",
                    packet.frameId, frameBytes.length);
            }
        }
    }

    static boolean isComplete(byte[][] frags) {
        for (byte[] f : frags) if (f == null) return false;
        return true;
    }

    static byte[] reassemble(byte[][] frags) {
        int total = 0;
        for (byte[] f : frags) total += f.length;
        byte[] out = new byte[total];
        int pos = 0;
        for (byte[] f : frags) {
            System.arraycopy(f, 0, out, pos, f.length);
            pos += f.length;
        }
        return out;
    }
}