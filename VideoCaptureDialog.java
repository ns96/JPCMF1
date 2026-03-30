import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A standalone, portable JDialog for hardware video capture using FFmpeg.
 * Provides real-time preview and a data pipe for the main application to
 * consume.
 */
public class VideoCaptureDialog extends JFrame {
    private final JComboBox<String> cmbDevice = new JComboBox<>();
    private final JComboBox<String> cmbFormat = new JComboBox<>(new String[] { "NTSC (30 fps)", "PAL (25 fps)" });
    private final JComboBox<String> cmbProtocol = new JComboBox<>(new String[] { "PCM-F1", "PCM-48K" });
    private final JButton btnProps = new JButton("PROPS");
    private final JButton btnStart = new JButton("START CAPTURE");
    private final JButton btnStop = new JButton("ABORT");
    private final JButton btnFrameGrab = new JButton("FRAME GRAB");
    private final JButton btnClose = new JButton("EXIT / CLOSE");
    private final JLabel lblStatus = new JLabel("Status: Capture Thread IDLE...");
    private final PreviewPanel previewPanel = new PreviewPanel();

    private Process ffmpegProcess;
    private volatile boolean capturing = false;
    private Thread captureThread;
    private final LinkedBlockingQueue<BufferedImage> frameQueue = new LinkedBlockingQueue<>(2);

    private final int width;
    private final int height;

    /**
     * @param width  The target width (e.g., 720)
     * @param height The target height (e.g., 480 or 576)
     * @param isPal  Whether the current format is PAL or NTSC (legacy param
     *               removed)
     */
    public VideoCaptureDialog(int width, int height, boolean isPal) {
        super("Sony PCM-F1 Video Capture Engine");
        this.width = width;
        this.height = height;

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        setLayout(new BorderLayout(10, 10));
        setSize(800, 650);
        setLocationRelativeTo(null);

        JPanel topPanel = new JPanel();
        topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));

        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controlPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        controlPanel.add(new JLabel("Input:"));
        controlPanel.add(cmbDevice);
        cmbDevice.setPreferredSize(new Dimension(300, 25));
        controlPanel.add(new JLabel("Standard:"));
        controlPanel.add(cmbFormat);
        controlPanel.add(new JLabel(" Protocol:"));
        controlPanel.add(cmbProtocol);
        controlPanel.add(btnProps);
        btnProps.setToolTipText("Open Native Driver Configuration Window (Windows Only)");

        JLabel lblAdvice = new JLabel("  OPTIMAL PCM-F1: Saturation=0, Contrast=Max. DISABLE DEINTERLACER IN PROPS!");
        lblAdvice.setFont(new Font("SansSerif", Font.ITALIC, 11));
        lblAdvice.setForeground(Color.RED);
        lblAdvice.setAlignmentX(Component.LEFT_ALIGNMENT);

        topPanel.add(controlPanel);
        topPanel.add(lblAdvice);

        JPanel centerPanel = new JPanel(new BorderLayout());
        centerPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        centerPanel.add(previewPanel, BorderLayout.CENTER);

        JPanel bottomPanel = new JPanel(new GridLayout(2, 1));
        JPanel btnPanel = new JPanel();
        btnPanel.add(btnStart);
        btnPanel.add(btnFrameGrab);
        btnPanel.add(btnStop);
        btnPanel.add(btnClose);
        bottomPanel.add(btnPanel);
        bottomPanel.add(lblStatus);

        add(topPanel, BorderLayout.NORTH);
        add(centerPanel, BorderLayout.CENTER);
        add(bottomPanel, BorderLayout.SOUTH);

        btnStop.setEnabled(false);
        detectDevices();

        btnStart.addActionListener(e -> startCapture());
        btnStop.addActionListener(e -> stopCapture());
        btnFrameGrab.addActionListener(e -> saveFrameGrab());
        btnProps.addActionListener(e -> runPropsCheck());
        btnClose.addActionListener(e -> {
            stopCapture();
            dispose();
        });

        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                stopCapture();
            }
        });

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    }

    private void detectDevices() {
        lblStatus.setText("Status: Scanning for hardware devices...");
        new Thread(() -> {
            List<String> devices = new ArrayList<>();
            String os = System.getProperty("os.name").toLowerCase();
            try {
                ProcessBuilder pb;
                if (os.contains("win")) {
                    pb = new ProcessBuilder("ffmpeg", "-list_devices", "true", "-f", "dshow", "-i", "dummy");
                } else if (os.contains("mac")) {
                    pb = new ProcessBuilder("ffmpeg", "-list_devices", "true", "-f", "avfoundation", "-i", "dummy");
                } else {
                    pb = new ProcessBuilder("sh", "-c", "ls /dev/video*");
                }

                pb.redirectErrorStream(true);
                Process p = pb.start();
                java.util.Scanner s = new java.util.Scanner(p.getInputStream());

                if (os.contains("win")) {
                    Pattern pattern = Pattern.compile("\"([^\"]+)\" \\(video\\)");
                    while (s.hasNextLine()) {
                        String line = s.nextLine();
                        Matcher m = pattern.matcher(line);
                        if (m.find())
                            devices.add(m.group(1));
                    }
                } else if (os.contains("mac")) {
                    Pattern pattern = Pattern.compile("\\[(\\d+)\\]\\s+(.+)");
                    while (s.hasNextLine()) {
                        String line = s.nextLine();
                        if (line.contains("audio devices") || line.contains("AVFoundation video devices"))
                            continue;
                        Matcher m = pattern.matcher(line);
                        if (m.find())
                            devices.add(m.group(1) + ": " + m.group(2));
                    }
                } else {
                    while (s.hasNextLine())
                        devices.add(s.nextLine());
                }
                p.waitFor();
                s.close();
            } catch (Exception ex) {
                ex.printStackTrace();
            }

            SwingUtilities.invokeLater(() -> {
                cmbDevice.removeAllItems();
                for (String d : devices)
                    cmbDevice.addItem(d);
                if (devices.isEmpty()) {
                    cmbDevice.addItem("No devices found (Is FFmpeg in PATH?)");
                    btnStart.setEnabled(false);
                }
                lblStatus.setText("Status: Capture Thread IDLE...");
            });
        }).start();
    }

    private long totalCrcErrors = 0;
    private long totalLinesScanned = 0;
    private long totalFramesProcessed = 0;

    // STC-007 (PCM-F1) Constants
    private static final int F1_BITS_PER_LINE = 137;
    private static final int F1_BIT_WIDTH = 5;
    private static final int F1_PIXEL_OFFSET = ((720 - (F1_BITS_PER_LINE * F1_BIT_WIDTH)) / 2) + 1;
    private static final int F1_VBLANK_LINES = 0; // Starts at top of active picture
    private static final int F1_DATA_LINES_PER_FIELD = 245;

    // PCM-48K Constants
    private static final int PCM48_BITS_PER_LINE = 152;
    private static final int PCM48_BIT_WIDTH = 4;
    private static final int PCM48_PIXEL_OFFSET = ((720 - (PCM48_BITS_PER_LINE * PCM48_BIT_WIDTH)) / 2) + 1;
    private static final int PCM48_AUDIO_LINES = 400;

    private int crc16CCITT(boolean[] bits, int offset, int len) {
        int crc = 0x0000;
        for (int i = 0; i < len; i++) {
            boolean crcBit = (crc & 0x8000) != 0;
            crc <<= 1;
            if (bits[offset + i] ^ crcBit)
                crc ^= 0x1021;
        }
        return crc & 0xFFFF;
    }

    private boolean[] extractBitsPCM48(BufferedImage bi, int y) {
        // 1. Adaptive Contrast Baseline (CONSTRAINED TO CENTER)
        int minL = 255, maxL = 0;
        for (int x = 50; x < bi.getWidth() - 50; x += 10) {
            int l = bi.getRGB(x, y) & 0xFF;
            if (l < minL)
                minL = l;
            if (l > maxL)
                maxL = l;
        }
        int threshold = (minL + maxL) / 2;
        if (maxL - minL < 30)
            return null; // Very low contrast line

        // 2. Constrained Horizontal Tracking
        int leftEdge = -1;
        int safeLeftBound = Math.max(20, PCM48_PIXEL_OFFSET - 10);
        for (int px = safeLeftBound; px < PCM48_PIXEL_OFFSET + 50; px++) {
            if (px < bi.getWidth() && (bi.getRGB(px, y) & 0xFF) > threshold) {
                leftEdge = px;
                break;
            }
        }

        int rightEdge = -1;
        int expRight = PCM48_PIXEL_OFFSET + 151 * PCM48_BIT_WIDTH - 1;
        int safeRightBound = Math.min(bi.getWidth() - 30, expRight + 20);
        for (int px = safeRightBound; px > expRight - 50; px--) {
            if (px >= 0 && (bi.getRGB(px, y) & 0xFF) > threshold) {
                rightEdge = px;
                break;
            }
        }

        double bitW = PCM48_BIT_WIDTH;
        double offset = PCM48_PIXEL_OFFSET;
        if (leftEdge != -1 && rightEdge != -1) {
            bitW = (rightEdge - leftEdge) / 150.0;
            offset = leftEdge - bitW;
        }

        // 3. Probabilistic Polarity Detection (Normal vs Inverted contrast scoring)
        int[] syncP = new int[4];
        for (int b = 0; b < 4; b++) {
            int midX = (int) (offset + b * bitW + bitW / 2.0);
            if (midX >= 0 && midX < bi.getWidth())
                syncP[b] = bi.getRGB(midX, y) & 0xFF;
        }
        // Score Normal (0101): (p1+p3) - (p0+p2)
        int scoreNormal = (syncP[1] + syncP[3]) - (syncP[0] + syncP[2]);
        // Score Inverted (1010): (p0+p2) - (p1+p3)
        int scoreInvert = (syncP[0] + syncP[2]) - (syncP[1] + syncP[3]);

        boolean inverted = scoreInvert > scoreNormal && scoreInvert > 40;
        if (!inverted && scoreNormal < 40)
            return null; // No clear sync pattern found

        // 4. Bloom-Resistant Sampling (3-point weighted average)
        boolean[] bits = new boolean[PCM48_BITS_PER_LINE];
        for (int b = 0; b < PCM48_BITS_PER_LINE; b++) {
            double bitCenter = offset + (b * bitW) + (bitW / 2.0);
            int v0 = 0, v1 = 0, v2 = 0;
            if (bitCenter - 1 >= 0 && bitCenter - 1 < bi.getWidth())
                v0 = bi.getRGB((int) (bitCenter - 1), y) & 0xFF;
            if (bitCenter >= 0 && bitCenter < bi.getWidth())
                v1 = bi.getRGB((int) bitCenter, y) & 0xFF;
            if (bitCenter + 1 >= 0 && bitCenter + 1 < bi.getWidth())
                v2 = bi.getRGB((int) (bitCenter + 1), y) & 0xFF;

            int weightedVal = (v0 + 2 * v1 + v2) / 4;
            bits[b] = weightedVal > threshold;
            if (inverted)
                bits[b] = !bits[b];
        }

        return bits;
    }

    private boolean[] extractBitsF1(BufferedImage bi, int y) {
        // 1. Adaptive Contrast Baseline (CONSTRAINED TO CENTER)
        int minL = 255, maxL = 0;
        for (int x = 50; x < bi.getWidth() - 50; x += 10) {
            int l = bi.getRGB(x, y) & 0xFF;
            if (l < minL)
                minL = l;
            if (l > maxL)
                maxL = l;
        }
        int threshold = (minL + maxL) / 2;
        if (maxL - minL < 30)
            return null;

        // 2. Constrained Horizontal Tracking
        int leftEdge = -1;
        int safeLeftBound = Math.max(20, F1_PIXEL_OFFSET - 10);
        for (int px = safeLeftBound; px < F1_PIXEL_OFFSET + 50; px++) {
            if (px < bi.getWidth() && (bi.getRGB(px, y) & 0xFF) > threshold) {
                leftEdge = px;
                break;
            }
        }

        int rightEdge = -1;
        int expectedRight = F1_PIXEL_OFFSET + 136 * F1_BIT_WIDTH - 1;
        int safeRightBound = Math.min(bi.getWidth() - 30, expectedRight + 20);
        for (int px = safeRightBound; px > expectedRight - 50; px--) {
            if (px >= 0 && (bi.getRGB(px, y) & 0xFF) > threshold) {
                rightEdge = px;
                break;
            }
        }

        double bitW = F1_BIT_WIDTH;
        double startOffset = F1_PIXEL_OFFSET;
        if (leftEdge != -1 && rightEdge != -1) {
            bitW = (rightEdge - leftEdge) / 135.0;
            startOffset = leftEdge - bitW;
        }

        // 3. Probabilistic Polarity Detection
        int[] syncP = new int[4];
        for (int b = 0; b < 4; b++) {
            int midX = (int) (startOffset + b * bitW + bitW / 2.0);
            if (midX >= 0 && midX < bi.getWidth())
                syncP[b] = bi.getRGB(midX, y) & 0xFF;
        }
        int scoreNormal = (syncP[1] + syncP[3]) - (syncP[0] + syncP[2]);
        int scoreInvert = (syncP[0] + syncP[2]) - (syncP[1] + syncP[3]);

        boolean inverted = scoreInvert > scoreNormal && scoreInvert > 40;
        if (!inverted && scoreNormal < 40)
            return null;

        // 4. Bloom-Resistant Sampling
        boolean[] bits = new boolean[137];
        for (int b = 0; b < 137; b++) {
            double bitCenter = startOffset + (b * bitW) + (bitW / 2.0);
            int v0 = 0, v1 = 0, v2 = 0;
            if (bitCenter - 1 >= 0 && bitCenter - 1 < bi.getWidth())
                v0 = bi.getRGB((int) (bitCenter - 1), y) & 0xFF;
            if (bitCenter >= 0 && bitCenter < bi.getWidth())
                v1 = bi.getRGB((int) bitCenter, y) & 0xFF;
            if (bitCenter + 1 >= 0 && bitCenter + 1 < bi.getWidth())
                v2 = bi.getRGB((int) (bitCenter + 1), y) & 0xFF;

            int weightedVal = (v0 + 2 * v1 + v2) / 4;
            bits[b] = weightedVal > threshold;
            if (inverted)
                bits[b] = !bits[b];
        }

        return bits;
    }

    private void processCrcForFrame(BufferedImage img) {
        boolean isPcm48 = cmbProtocol.getSelectedIndex() == 1;

        if (isPcm48) {
            int startY = 0;
            // Dynamic Vertical Sync Search for PCM-48K
            for (int y = 0; y < 50 && y < img.getHeight(); y++) {
                if (extractBitsPCM48(img, y) != null) {
                    startY = y;
                    break;
                }
            }

            for (int i = 0; i < PCM48_AUDIO_LINES; i++) {
                int y = startY + i;
                if (y >= img.getHeight())
                    break;
                boolean[] bits = extractBitsPCM48(img, y);
                if (bits == null)
                    continue;

                totalLinesScanned++;
                int expectedCrc = 0;
                for (int b = 0; b < 16; b++)
                    if (bits[136 + b])
                        expectedCrc |= (1 << (15 - b));
                // if (crc16CCITT(bits, 4, 132) != expectedCrc)
                //     totalCrcErrors++;
            }
        } else {
            // PCM-F1 (STC-007) - Replicate PCMF1SimulatorApp's logic
            for (int field = 0; field < 2; field++) {
                int controlLineY = F1_VBLANK_LINES;
                // Dynamic Vertical Sync Search
                for (int fieldY = 0; fieldY < 30; fieldY++) {
                    int y = fieldY * 2 + field;
                    if (y >= img.getHeight())
                        continue;
                    boolean[] bits = extractBitsF1(img, y);
                    if (bits == null)
                        continue;

                    boolean isControl = true;
                    for (int w = 0; w < 8; w++) {
                        int val = 0;
                        for (int b = 0; b < 14; b++)
                            if (bits[4 + (w * 14) + b])
                                val |= (1 << (13 - b));
                        if (val != 0x3333)
                            isControl = false;
                    }
                    if (isControl) {
                        controlLineY = fieldY;
                        break;
                    }
                }

                for (int i = 0; i < F1_DATA_LINES_PER_FIELD; i++) {
                    int fieldY = controlLineY + 1 + i;
                    int y = fieldY * 2 + field;
                    if (y >= img.getHeight())
                        continue;

                    boolean[] bits = extractBitsF1(img, y);
                    if (bits == null)
                        continue;

                    totalLinesScanned++;
                    int expectedCrc = 0;
                    for (int b = 0; b < 16; b++)
                        if (bits[102 + b])
                            expectedCrc |= (1 << (15 - b));
                    // if (crc16CCITT(bits, 4, 98) != expectedCrc)
                    //     totalCrcErrors++;
                }
            }
        }
    }

    private void runPropsCheck() {
        String device = (String) cmbDevice.getSelectedItem();
        if (device == null || device.contains("No devices found"))
            return;

        btnProps.setEnabled(false);
        lblStatus.setText("Status: Opening Hardware Properties Dialog...");

        new Thread(() -> {
            try {
                String os = System.getProperty("os.name").toLowerCase();
                ProcessBuilder pb;
                if (os.contains("win")) {
                    pb = new ProcessBuilder("ffmpeg", "-f", "dshow", "-show_video_device_dialog", "true", "-i",
                            "video=" + device, "-f", "null", "-");
                } else if (os.contains("mac")) {
                    String index = device.split(":")[0];
                    pb = new ProcessBuilder("ffmpeg", "-f", "avfoundation", "-i", index);
                } else {
                    pb = new ProcessBuilder("ffmpeg", "-f", "v4l2", "-i", device);
                }

                pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                Process p = pb.start();
                p.waitFor();
            } catch (Exception ex) {
                ex.printStackTrace();
            } finally {
                SwingUtilities.invokeLater(() -> {
                    btnProps.setEnabled(true);
                    lblStatus.setText("Status: Capture Thread IDLE...");
                });
            }
        }).start();
    }

    private void startCapture() {
        String device = (String) cmbDevice.getSelectedItem();
        if (device == null || device.contains("No devices found"))
            return;

        capturing = true;
        btnStart.setEnabled(false);
        btnStop.setEnabled(true);
        cmbDevice.setEnabled(false);

        boolean palSelected = cmbFormat.getSelectedIndex() == 1;
        String framerate = palSelected ? "25" : "30";
        int captureHeight = palSelected ? 576 : 480;

        lblStatus.setText("Status: Capture ACTIVE (" + (palSelected ? "PAL" : "NTSC") + ")...");

        captureThread = new Thread(() -> {
            try {
                String os = System.getProperty("os.name").toLowerCase();
                ProcessBuilder pb;

                if (os.contains("win")) {
                    pb = new ProcessBuilder(
                            "ffmpeg", "-y", "-loglevel", "error",
                            "-f", "dshow", "-video_size", "720x" + captureHeight,
                            "-framerate", framerate, "-i", "video=" + device,
                            "-f", "rawvideo", "-pix_fmt", "gray", "-");
                } else if (os.contains("mac")) {
                    String index = device.split(":")[0];
                    pb = new ProcessBuilder(
                            "ffmpeg", "-f", "avfoundation", "-video_size", "720x" + captureHeight,
                            "-framerate", framerate, "-i", index,
                            "-f", "rawvideo", "-pix_fmt", "gray", "-");
                } else {
                    pb = new ProcessBuilder(
                            "ffmpeg", "-f", "v4l2", "-video_size", "720x" + captureHeight,
                            "-framerate", framerate, "-i", device,
                            "-f", "rawvideo", "-pix_fmt", "gray", "-");
                }

                pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                ffmpegProcess = pb.start();
                InputStream ffmpegIn = ffmpegProcess.getInputStream();

                totalCrcErrors = 0;
                totalLinesScanned = 0;
                totalFramesProcessed = 0;
                byte[] frameBuffer = new byte[720 * captureHeight];

                while (capturing) {
                    int read = 0;
                    while (read < frameBuffer.length && capturing) {
                        int r = ffmpegIn.read(frameBuffer, read, frameBuffer.length - read);
                        if (r == -1)
                            break;
                        read += r;
                    }

                    if (read == frameBuffer.length) {
                        totalFramesProcessed++;
                        BufferedImage img = new BufferedImage(720, captureHeight, BufferedImage.TYPE_BYTE_GRAY);
                        byte[] target = ((DataBufferByte) img.getRaster().getDataBuffer()).getData();
                        System.arraycopy(frameBuffer, 0, target, 0, frameBuffer.length);

// processCrcForFrame(img);

                        final BufferedImage fImg = img;
                        // final long snapErrors = totalCrcErrors;
                        // final long snapLines = totalLinesScanned;
                        final long snapFrames = totalFramesProcessed;
                        final boolean palSelectedFinal = palSelected;

                        SwingUtilities.invokeLater(() -> {
                            // double errorPercent = snapLines > 0 ? (double) snapErrors / snapLines * 100.0 : 0.0;
                            previewPanel.setImage(fImg);
                            lblStatus.setText(String.format(
                                    "Status: Capture ACTIVE (%s) | Frames: %d | Protocol: %s [CRC DISABLED]",
                                    (palSelectedFinal ? "PAL" : "NTSC"), snapFrames, cmbProtocol.getSelectedItem()));
                        });

                        // Broadcast to main application
                        if (frameQueue.size() >= 2)
                            frameQueue.poll();
                        frameQueue.offer(img);
                    }
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            } finally {
                stopCapture();
            }
        });
        captureThread.start();
    }

    public void stopCapture() {
        if (!capturing)
            return;
        capturing = false;
        if (ffmpegProcess != null) {
            ffmpegProcess.destroyForcibly();
            try {
                ffmpegProcess.waitFor();
            } catch (InterruptedException ignored) {
            }
            ffmpegProcess = null;
        }
        SwingUtilities.invokeLater(() -> {
            btnStart.setEnabled(true);
            btnStop.setEnabled(false);
            cmbDevice.setEnabled(true);
            lblStatus.setText("Status: Capture Thread IDLE...");
        });
    }

    public boolean isCapturing() {
        return capturing;
    }

    /**
     * Blocks and waits for the next frame from the capture stream.
     * Returns null if interrupted or no frame arrived within 250ms.
     */
    public BufferedImage getNextFrame() {
        try {
            return frameQueue.poll(250, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            return null;
        }
    }

    /**
     * Captures the current active frame in the preview panel and saves it
     * as a lossless PNG file in the current working directory.
     */
    private void saveFrameGrab() {
        BufferedImage currentImg = previewPanel.getImage();
        if (currentImg == null) {
            lblStatus.setText("Status: Error - No frame available to grab!");
            return;
        }
        try {
            File outFile = new File("framegrab.png");
            ImageIO.write(currentImg, "png", outFile);
            lblStatus.setText("Status: Frame Grab SAVED to " + outFile.getName());
        } catch (IOException ex) {
            ex.printStackTrace();
            lblStatus.setText("Status: Error saving frame grab: " + ex.getMessage());
        }
    }

    class PreviewPanel extends JPanel {
        private BufferedImage img;

        public PreviewPanel() {
            setPreferredSize(new Dimension(width, height));
            setBackground(Color.BLACK);
        }

        public void setImage(BufferedImage img) {
            this.img = img;
            repaint();
        }

        public BufferedImage getImage() {
            return img;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (img != null) {
                // Maintain aspect ratio or fill? Fill for bitstream analysis usually.
                g.drawImage(img, 0, 0, getWidth(), getHeight(), null);
            }
        }
    }

    /**
     * Standalone main method for independent testing.
     * Default capture dimensions: 720x526.
     */
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Capture Engine Host");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setSize(100, 100);
            frame.setVisible(false);

            VideoCaptureDialog dialog = new VideoCaptureDialog(720, 480, false);
            dialog.setVisible(true);
        });
    }
}