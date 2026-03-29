import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A standalone, portable JDialog for hardware video capture using FFmpeg.
 * Provides real-time preview and a data pipe for the main application to consume.
 */
public class VideoCaptureDialog extends JDialog {
    private final JComboBox<String> cmbDevice = new JComboBox<>();
    private final JButton btnStart = new JButton("START CAPTURE");
    private final JButton btnStop = new JButton("ABORT / STOP");
    private final JButton btnClose = new JButton("EXIT / CLOSE");
    private final JLabel lblStatus = new JLabel("Status: Capture Thread IDLE...");
    private final PreviewPanel previewPanel = new PreviewPanel();

    private Process ffmpegProcess;
    private volatile boolean capturing = false;
    private Thread captureThread;
    private final LinkedBlockingQueue<BufferedImage> frameQueue = new LinkedBlockingQueue<>(2);

    private final int width;
    private final int height;
    private final boolean isPal;

    /**
     * @param owner The parent JFrame
     * @param width The target width (e.g., 720)
     * @param height The target height (e.g., 480 or 576)
     * @param isPal Whether the current format is PAL or NTSC
     */
    public VideoCaptureDialog(Frame owner, int width, int height, boolean isPal) {
        super(owner, "Video Capture Engine", true);
        this.width = width;
        this.height = height;
        this.isPal = isPal;

        setLayout(new BorderLayout(10, 10));
        setSize(800, 650);
        setLocationRelativeTo(owner);

        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        topPanel.add(new JLabel("Input Device:"));
        topPanel.add(cmbDevice);
        cmbDevice.setPreferredSize(new Dimension(400, 25));

        JPanel centerPanel = new JPanel(new BorderLayout());
        centerPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        centerPanel.add(previewPanel, BorderLayout.CENTER);

        JPanel bottomPanel = new JPanel(new GridLayout(2, 1));
        JPanel btnPanel = new JPanel();
        btnPanel.add(btnStart);
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
        btnClose.addActionListener(e -> {
            stopCapture();
            dispose();
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
                        if (m.find()) devices.add(m.group(1));
                    }
                } else if (os.contains("mac")) {
                    // macOS FFmpeg avfoundation output parsing
                    // Typically looks like "[avfoundation @ ...] [0] FaceTime HD Camera"
                    Pattern pattern = Pattern.compile("\\[(\\d+)\\]\\s+(.+)");
                    while (s.hasNextLine()) {
                        String line = s.nextLine();
                        if (line.contains("audio devices") || line.contains("AVFoundation video devices")) continue;
                        Matcher m = pattern.matcher(line);
                        if (m.find()) devices.add(m.group(1) + ": " + m.group(2));
                    }
                } else {
                    while (s.hasNextLine()) devices.add(s.nextLine());
                }
                p.waitFor();
                s.close();
            } catch (Exception ex) {
                ex.printStackTrace();
            }

            SwingUtilities.invokeLater(() -> {
                cmbDevice.removeAllItems();
                for (String d : devices) cmbDevice.addItem(d);
                if (devices.isEmpty()) {
                    cmbDevice.addItem("No devices found (Is FFmpeg in PATH?)");
                    btnStart.setEnabled(false);
                }
                lblStatus.setText("Status: Capture Thread IDLE...");
            });
        }).start();
    }

    private void startCapture() {
        String device = (String) cmbDevice.getSelectedItem();
        if (device == null || device.contains("No devices found")) return;

        capturing = true;
        btnStart.setEnabled(false);
        btnStop.setEnabled(true);
        cmbDevice.setEnabled(false);
        lblStatus.setText("Status: Capture ACTIVE (" + (isPal ? "PAL" : "NTSC") + ")...");
 
        captureThread = new Thread(() -> {
            try {
                String os = System.getProperty("os.name").toLowerCase();
                ProcessBuilder pb;
                String res = width + ":" + height;

                if (os.contains("win")) {
                    pb = new ProcessBuilder("ffmpeg", "-f", "dshow", "-rtbufsize", "150M", "-i", "video=" + device, "-vf", "scale=" + res + ",format=gray", "-f", "rawvideo", "-pix_fmt", "gray", "-");
                } else if (os.contains("mac")) {
                    String index = device.split(":")[0];
                    pb = new ProcessBuilder("ffmpeg", "-f", "avfoundation", "-i", index, "-vf", "scale=" + res + ",format=gray", "-f", "rawvideo", "-pix_fmt", "gray", "-");
                } else {
                    pb = new ProcessBuilder("ffmpeg", "-f", "v4l2", "-i", device, "-vf", "scale=" + res + ",format=gray", "-f", "rawvideo", "-pix_fmt", "gray", "-");
                }

                pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                ffmpegProcess = pb.start();
                InputStream ffmpegIn = ffmpegProcess.getInputStream();
                
                byte[] frameBuffer = new byte[width * height];
                while (capturing) {
                    int read = 0;
                    while (read < frameBuffer.length && capturing) {
                        int r = ffmpegIn.read(frameBuffer, read, frameBuffer.length - read);
                        if (r == -1) break;
                        read += r;
                    }

                    if (read == frameBuffer.length) {
                        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
                        byte[] target = ((DataBufferByte) img.getRaster().getDataBuffer()).getData();
                        System.arraycopy(frameBuffer, 0, target, 0, frameBuffer.length);
                        
                        // Update preview
                        previewPanel.setImage(img);
                        
                        // Broadcast to main application
                        if (frameQueue.size() >= 2) frameQueue.poll(); // Keep only latest 2 frames
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

    public synchronized void stopCapture() {
        capturing = false;
        if (ffmpegProcess != null) {
            ffmpegProcess.destroy();
            ffmpegProcess = null;
        }
        btnStart.setEnabled(true);
        btnStop.setEnabled(false);
        cmbDevice.setEnabled(true);
        lblStatus.setText("Status: Capture Thread IDLE...");
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

            VideoCaptureDialog dialog = new VideoCaptureDialog(frame, 720, 526, false);
            dialog.setTitle("Capture Engine Standalone Test Mode");
            dialog.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowClosing(java.awt.event.WindowEvent e) { System.exit(0); }
            });
            dialog.setVisible(true);
        });
    }
}
