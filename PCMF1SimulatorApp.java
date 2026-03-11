import org.jcodec.api.FrameGrab;
import org.jcodec.api.SequenceEncoder;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.model.Rational;
import org.jcodec.scale.AWTUtil;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.sound.sampled.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.prefs.Preferences;

/**
 * Main application class for the Sony PCM-F1 STC-007 Hardware Simulator.
 * This system provides a graphical UI and processing backend for encoding
 * 16-bit PCM digital audio
 * into a black-and-white analog-style video signal compliant with the 1981 EIAJ
 * STC-007 standard,
 * as well as decoding that video stream back into real-time audio playback
 * while handling VHS-era
 * data dropout corrections natively.
 */
public class PCMF1SimulatorApp extends JFrame {
    private VideoPanel videoPanel = new VideoPanel();
    private VUMeterPanel vuLeft = new VUMeterPanel("L");
    private VUMeterPanel vuRight = new VUMeterPanel("R");
    private JLabel lblStatus = new JLabel("Status: Ready");
    private JButton btnPause = new JButton("Pause");
    private JButton btnRestart = new JButton("Restart");
    private volatile boolean isRunning = false;
    private volatile boolean isPaused = false;
    private String activeStatusText = "";
    private File currentDecodeFile = null;
    private JComboBox<String> cmbFormat = new JComboBox<>(new String[] { "NTSC", "PAL" });
    private JComboBox<String> cmbEncoder = new JComboBox<>(new String[] { "JCodec", "FFmpeg" });
    private int crcErrors = 0;
    private int correctionsP = 0;
    private int lostBlocks = 0;
    private int qLosses = 0;
    private byte[] audioBuffer = new byte[32768]; // Shared audio buffer to reduce GC pressure

    private Thread activeCaptureThread = null;
    private Thread activeDecodeThread = null;

    private void stopCurrentEngineConnections() {
        isRunning = false;
        isPaused = false;

        if (activeTargetLine != null) {
            activeTargetLine.stop();
            activeTargetLine.close();
        }
        if (activeDataLine != null) {
            activeDataLine.stop();
            activeDataLine.close();
        }

        if (activeCaptureThread != null && activeCaptureThread.isAlive()) {
            try {
                activeCaptureThread.join(500);
            } catch (Exception ignored) {
            }
        }
        if (activeDecodeThread != null && activeDecodeThread.isAlive()) {
            try {
                activeDecodeThread.join(500);
            } catch (Exception ignored) {
            }
        }
    }

    // New playback controls
    private JComboBox<String> cmbOutputDevice = new JComboBox<>();
    private javax.sound.sampled.Mixer.Info[] outputMixers;
    private SourceDataLine activeDataLine = null;
    private TargetDataLine activeTargetLine = null;
    private float currentVolume = 0.0f; // dB

    // True STC-007 Constants
    private static final int BITS_PER_LINE = 137;
    private static final int WIDTH = 720; // 720 pixels NTSC broadcast standard
    private static final int HEIGHT = 526; // Emulating full 525-line NTSC system
    private static final int BIT_WIDTH = 5; // Revert to fixed block width
    private static final int PIXEL_OFFSET = ((WIDTH - (BITS_PER_LINE * BIT_WIDTH)) / 2) + 1; // Center payload
    private static final int VBLANK_LINES = 17; // Lines skipped for VHS head sync
    private static final int DATA_LINES_PER_FIELD = 245; // Exactly 245 data lines per field as per STC-007 Spec
    private static final int DATA_LINES_PER_FRAME = DATA_LINES_PER_FIELD * 2; // 490 total data lines per frame

    public PCMF1SimulatorApp() {
        setTitle("Sony PCM-F1 Simulator v1.2.15 -- 3/11/2026");
        setSize(1000, 700);
        setDefaultCloseOperation(EXIT_ON_CLOSE);

        JPanel centerPanel = new JPanel(new BorderLayout());
        JPanel vuPanel = new JPanel(new GridLayout(1, 2, 5, 0));
        vuPanel.add(vuLeft);
        vuPanel.add(vuRight);
        centerPanel.add(vuPanel, BorderLayout.WEST);
        centerPanel.add(videoPanel, BorderLayout.CENTER);
        add(centerPanel, BorderLayout.CENTER);

        JPanel controlPanel = new JPanel(new GridLayout(2, 1, 0, 5));

        JPanel actionPanel = new JPanel();
        JButton btnSave = new JButton("Encode Audio");
        JButton btnOpen = new JButton("Decode Audio");
        JButton btnSimulate = new JButton("Encode Test (440Hz)");
        JButton btnRealtime = new JButton("Realtime");

        actionPanel.add(new JLabel("Encoder:"));
        actionPanel.add(cmbEncoder);
        actionPanel.add(new JLabel("Format:"));
        actionPanel.add(cmbFormat);
        actionPanel.add(btnSave);
        actionPanel.add(btnOpen);
        actionPanel.add(btnSimulate);
        actionPanel.add(btnRealtime);

        JPanel playbackPanel = new JPanel();
        JButton btnVolDown = new JButton("Vol -");
        JButton btnVolUp = new JButton("Vol +");
        JButton btnStop = new JButton("Stop");
        JButton btnExit = new JButton("Exit");

        populateMixers();
        playbackPanel.add(new JLabel("Audio Device:"));
        playbackPanel.add(cmbOutputDevice);
        playbackPanel.add(btnVolDown);
        playbackPanel.add(btnVolUp);
        playbackPanel.add(btnPause);
        playbackPanel.add(btnRestart);
        playbackPanel.add(btnStop);
        playbackPanel.add(btnExit);

        controlPanel.add(actionPanel);
        controlPanel.add(playbackPanel);
        add(controlPanel, BorderLayout.SOUTH);

        btnVolDown.addActionListener(e -> adjustVolume(-3.0f));
        btnVolUp.addActionListener(e -> adjustVolume(3.0f));

        JPanel topPanel = new JPanel();
        topPanel.add(lblStatus);
        add(topPanel, BorderLayout.NORTH);

        btnSave.addActionListener(e -> saveVideo());
        btnOpen.addActionListener(e -> openVideo());
        btnSimulate.addActionListener(e -> quickTest());
        btnRealtime.addActionListener(e -> startRealtimeCapture());
        btnPause.addActionListener(e -> {
            if (isRunning) {
                isPaused = !isPaused;
                btnPause.setText(isPaused ? "Resume" : "Pause");
                if (isPaused) {
                    activeStatusText = lblStatus.getText();
                    lblStatus.setText("Status: Paused...");
                } else {
                    lblStatus.setText(activeStatusText);
                }
            }
        });
        btnRestart.addActionListener(e -> {
            if (currentDecodeFile != null) {
                btnRestart.setEnabled(false);
                isRunning = false; // Kill old thread
                isPaused = false;
                btnPause.setText("Pause");
                lblStatus.setText("Status: Restarting...");
                new Thread(() -> {
                    try {
                        Thread.sleep(200);
                    } catch (Exception ignored) {
                    } // Wait for cleanup
                    SwingUtilities.invokeLater(() -> {
                        startDecoding(currentDecodeFile);
                        btnRestart.setEnabled(true);
                    });
                }).start();
            }
        });
        btnStop.addActionListener(e -> {
            stopCurrentEngineConnections();
            btnPause.setText("Pause");
            lblStatus.setText("Status: Stopped");
            vuLeft.reset();
            vuRight.reset();
        });
        btnExit.addActionListener(e -> System.exit(0));

        // Auto-restart Realtime capture if the device is changed while running
        cmbOutputDevice.addActionListener(e -> {
            boolean isRealtimeActive = lblStatus.getText().contains("Realtime Capture") ||
                    activeStatusText.contains("Realtime Capture");
            if (isRunning && isRealtimeActive) {
                startRealtimeCapture();
            }
        });

        setVisible(true);
    }

    /**
     * Discovers all available system audio devices and populates the Device
     * dropdown menu with mixers that support hardware audio playback or recording.
     * Prefixes the name with [IN] for recording devices and [OUT] for playback
     * devices. Devices that ambiguously report both are excluded.
     * NOTE: macOS CoreAudio is notoriously bad at reporting capabilities to Java.
     * If the OS is Mac, all devices are unconditionally listed.
     */
    private void populateMixers() {
        ArrayList<Mixer.Info> outMixers = new ArrayList<>();
        cmbOutputDevice.addItem("System Default");
        outMixers.add(null);

        boolean isMac = System.getProperty("os.name").toLowerCase().contains("mac");

        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            if (info.getName().startsWith("Port")) {
                continue;
            }

            if (isMac) {
                outMixers.add(info);
                cmbOutputDevice.addItem(info.getName());
                continue;
            }

            Mixer mixer = AudioSystem.getMixer(info);
            // Check if it supports SourceDataLine (playback) or TargetDataLine (recording)
            Line.Info[] playInfos = mixer.getSourceLineInfo();
            Line.Info[] recInfos = mixer.getTargetLineInfo();

            // Exclude devices that report supporting both (often problematic aggregators)
            if (playInfos.length > 0 && recInfos.length > 0) {
                continue;
            }

            if (playInfos.length > 0 || recInfos.length > 0) {
                outMixers.add(info);

                String label = "";
                if (playInfos.length > 0) {
                    label = "[OUT] ";
                } else if (recInfos.length > 0) {
                    label = "[IN] ";
                }

                cmbOutputDevice.addItem(label + info.getName());
            }
        }
        outputMixers = outMixers.toArray(new Mixer.Info[0]);
    }

    /**
     * Adjusts the current playback volume by a specific decibel increment.
     *
     * @param delta the amount of decibels to add or subtract from the current level
     */
    private void adjustVolume(float delta) {
        currentVolume += delta;
        applyVolume();
    }

    /**
     * Applies the tracked volume level securely to the active hardware audio line,
     * ensuring it clamps properly to the minimum and maximum capabilities of the
     * device.
     */
    private void applyVolume() {
        if (activeDataLine != null && activeDataLine.isOpen()) {
            if (activeDataLine.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                FloatControl gainControl = (FloatControl) activeDataLine.getControl(FloatControl.Type.MASTER_GAIN);
                currentVolume = Math.max(gainControl.getMinimum(), Math.min(gainControl.getMaximum(), currentVolume));
                gainControl.setValue(currentVolume);
            }
        }
    }

    /**
     * Generates a 1-minute 440Hz test sine wave and instantly routes it to the
     * PCM-F1 encoder,
     * bypassing the need for a user-provided input WAV file.
     */
    private void quickTest() {
        File file = new File("test_output_pcmf1.mp4");
        encodeSimulatedAudio(file);
    }

    /**
     * Spawns a file selection dialog requesting an input audio file (WAV, FLAC,
     * MP3, etc.),
     * and automatically directs it to the encoder pipeline to generate a Sony
     * PCM-F1 encoded MP4.
     */
    private void saveVideo() {
        Preferences prefs = Preferences.userNodeForPackage(PCMF1SimulatorApp.class);
        String lastDir = prefs.get("lastOpenedDir", null);

        JFileChooser openChooser = new JFileChooser(lastDir);
        openChooser.setFileFilter(
                new FileNameExtensionFilter("Audio Files (*.wav, *.flac, *.mp3, *.m4a)", "wav", "flac", "mp3", "m4a"));
        openChooser.setDialogTitle("Select Input Audio File For Encoding");
        if (openChooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION)
            return;
        File audioFile = openChooser.getSelectedFile();
        prefs.put("lastOpenedDir", audioFile.getParent());

        String path = audioFile.getAbsolutePath();
        int dotIndex = path.lastIndexOf('.');
        if (dotIndex > 0) {
            path = path.substring(0, dotIndex) + ".mp4";
        } else {
            path += ".mp4";
        }
        File outFile = new File(path);

        encodeAudioToVideo(audioFile, outFile);
    }

    /**
     * Spawns a background thread to safely parse the headers of a raw 16-bit stereo
     * WAV file,
     * extracting its samples into memory, handling fallback RIFF parsing if
     * necessary, or dynamically shelling out to FFmpeg to natively decode
     * FLAC, MP3, or M4A compressed audio files directly into memory, and
     * ultimately passing the clean binary array into the video conversion loop.
     *
     * @param audioFile the source audio file to read
     * @param outFile   the destination .mp4 file to write the STC-007 video to
     */
    private void encodeAudioToVideo(File audioFile, File outFile) {
        new Thread(() -> {
            try {
                isRunning = true;
                isPaused = false;
                SwingUtilities.invokeLater(() -> btnPause.setText("Pause"));
                lblStatus.setText("Status: Reading Audio file...");

                byte[] bytes = null;
                boolean isBigEndian = false;

                boolean isPal = cmbFormat.getSelectedIndex() == 1;
                float sampleRate = isPal ? 44100f : 44056f;

                String fileName = audioFile.getName().toLowerCase();

                if (fileName.endsWith(".wav")) {
                    try {
                        AudioInputStream ais = AudioSystem.getAudioInputStream(audioFile);
                        AudioFormat format = ais.getFormat();
                        if (format.getSampleSizeInBits() != 16 || format.getChannels() != 2) {
                            throw new Exception("AudioSystem: Only 16-bit Stereo PCM WAV files are supported! (Got "
                                    + format.getSampleSizeInBits() + " bit, " + format.getChannels() + " channels)");
                        }
                        bytes = ais.readAllBytes();
                        isBigEndian = format.isBigEndian();
                        sampleRate = format.getSampleRate();
                        ais.close();
                    } catch (Exception e) {
                        // Fallback to manual RIFF parsing if AudioSystem dislikes some header metadata
                        // (Extensible, etc)
                        lblStatus.setText("Status: AudioSystem rejected file, attempting manual WAV parse...");
                        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(audioFile, "r")) {
                            byte[] header = new byte[12];
                            raf.readFully(header);
                            if (header[0] != 'R' || header[1] != 'I' || header[2] != 'F' || header[3] != 'F' ||
                                    header[8] != 'W' || header[9] != 'A' || header[10] != 'V' || header[11] != 'E') {
                                throw new Exception("Fallback Parser: File is not a valid RIFF WAVE format.");
                            }

                            while (raf.getFilePointer() < raf.length() - 8) {
                                byte[] chunkIdBytes = new byte[4];
                                raf.readFully(chunkIdBytes);
                                String chunkId = new String(chunkIdBytes);

                                int chunkSize = Integer.reverseBytes(raf.readInt());
                                if (chunkSize < 0)
                                    chunkSize = 0;

                                if (chunkId.equals("fmt ")) {
                                    short audioFormat = Short.reverseBytes(raf.readShort());
                                    short channels = Short.reverseBytes(raf.readShort());
                                    sampleRate = (float) Integer.reverseBytes(raf.readInt());
                                    raf.skipBytes(6); // Skip byteRate (4), blockAlign (2)
                                    short bitsPerSample = Short.reverseBytes(raf.readShort());

                                    // 1 = Standard PCM, 65534 (0xFFFE) = WAVE_FORMAT_EXTENSIBLE
                                    if ((audioFormat != 1 && (audioFormat & 0xFFFF) != 65534) || channels != 2
                                            || bitsPerSample != 16) {
                                        throw new Exception(
                                                "Fallback Parser: Must be uncompressed 16-bit stereo PCM. Got format="
                                                        + (audioFormat & 0xFFFF) + " channels=" + channels + " bits="
                                                        + bitsPerSample);
                                    }
                                    if (chunkSize > 16)
                                        raf.skipBytes(chunkSize - 16);
                                } else if (chunkId.equals("data")) {
                                    bytes = new byte[chunkSize];
                                    raf.readFully(bytes);
                                    isBigEndian = false; // Standard WAV is little endian PCM
                                    break;
                                } else {
                                    raf.skipBytes(chunkSize);
                                }
                            }
                            if (bytes == null)
                                throw new Exception("Fallback Parser: No 'data' audio chunk found in WAV.");
                        }
                    }
                } else {
                    // It's a FLAC, MP3, or M4A (or other FFmpeg-supported format)
                    lblStatus.setText("Status: Native Shelling to FFmpeg for highly-compressed audio decode...");

                    ProcessBuilder pb = new ProcessBuilder(
                            "ffmpeg", "-y", "-i", audioFile.getAbsolutePath(),
                            "-f", "s16le", "-ac", "2", "-ar", String.valueOf((int) sampleRate), "-");
                    pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                    Process p = pb.start();

                    java.io.InputStream is = p.getInputStream();
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();

                    byte[] buf = new byte[8192];
                    int read;
                    while ((read = is.read(buf)) != -1) {
                        baos.write(buf, 0, read);
                    }

                    p.waitFor();
                    if (p.exitValue() != 0) {
                        throw new Exception("FFmpeg encountered an error decoding the " + fileName + " file.");
                    }

                    bytes = baos.toByteArray();
                    isBigEndian = false; // s16le guarantees Little Endian
                }

                int totalSamples = bytes.length / 2; // shorts
                short[] audio = new short[totalSamples];
                for (int i = 0; i < totalSamples; i++) {
                    if (isBigEndian) {
                        audio[i] = (short) (((bytes[i * 2] & 0xFF) << 8) | (bytes[i * 2 + 1] & 0xFF));
                    } else {
                        audio[i] = (short) (((bytes[i * 2 + 1] & 0xFF) << 8) | (bytes[i * 2] & 0xFF));
                    }
                }

                double durationSeconds = 0;
                if (sampleRate > 0 && bytes != null) {
                    durationSeconds = (bytes.length / 4.0) / sampleRate;
                }
                int hours = (int) (durationSeconds / 3600);
                int minutes = (int) ((durationSeconds % 3600) / 60);
                int seconds = (int) (durationSeconds % 60);
                String playtime = String.format("%02d:%02d:%02d", hours, minutes, seconds);

                String audioInfo = String.format("%s - %.2f MB [%s]", audioFile.getName(),
                        audioFile.length() / (1024.0 * 1024.0), playtime);
                lblStatus.setText("Status: Encoding... " + audioInfo);
                runEncoderLoop(audio, outFile, audioInfo);

            } catch (Exception ex) {
                ex.printStackTrace();
                lblStatus.setText("Error: " + ex.getMessage());
            } finally {
                SwingUtilities.invokeLater(() -> {
                    vuLeft.setValue(0);
                    vuRight.setValue(0);
                });
            }
        }).start();
    }

    /**
     * Spawns a background thread that calculates and generates 1 minute of a 440Hz
     * sine wave,
     * routing it directly into the STC-007 video generation pipeline.
     *
     * @param file the destination .mp4 file to write the simulated audio to
     */
    private void encodeSimulatedAudio(File file) {
        new Thread(() -> {
            try {
                isRunning = true;
                isPaused = false;
                SwingUtilities.invokeLater(() -> btnPause.setText("Pause"));

                boolean isPal = cmbFormat.getSelectedIndex() == 1;
                int sampleRate = isPal ? 44100 : 44056;
                String labelInfo = "Simulated 1m 440Hz Sine (" + (isPal ? "PAL" : "NTSC") + ")";
                lblStatus.setText("Status: Encoding... " + labelInfo);

                // Sony PCM-F1 uses 44.056 kHz for NTSC, 44.1 kHz for PAL (x2 for Stereo
                // Left/Right interleaved)
                int totalSamples = 60 * sampleRate * 2;
                short[] audio = new short[totalSamples];
                for (int i = 0; i < audio.length; i++) {
                    audio[i] = (short) (Math.sin(2 * Math.PI * i * 440 / sampleRate) * 16000);
                }

                runEncoderLoop(audio, file, labelInfo);

            } catch (Exception ex) {
                ex.printStackTrace();
                lblStatus.setText("Error: " + ex.getMessage());
            } finally {
                SwingUtilities.invokeLater(() -> {
                    vuLeft.setValue(0);
                    vuRight.setValue(0);
                });
            }
        }).start();
    }

    /**
     * Captures audio directly from the selected microphone or line-in device using
     * a TargetDataLine,
     * converting raw byte streams immediately into short data to continuously feed
     * the
     * realtime graphical STC-007 display. Audio is not saved to disk.
     */
    private void startRealtimeCapture() {
        stopCurrentEngineConnections();

        activeCaptureThread = new Thread(() -> {
            try {
                isRunning = true;
                isPaused = false;
                SwingUtilities.invokeLater(() -> btnPause.setText("Pause"));

                boolean isPal = cmbFormat.getSelectedIndex() == 1;
                int sampleRate = isPal ? 44100 : 44056;
                lblStatus.setText("Status: Realtime Capture (" + (isPal ? "PAL" : "NTSC") + ")...");

                AudioFormat format = new AudioFormat(sampleRate, 16, 2, true, false); // Little-endian
                DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);

                boolean isMonoCapture = false;

                try {
                    int selectedMixerIndex = cmbOutputDevice.getSelectedIndex();
                    Mixer mixer = null;
                    if (selectedMixerIndex > 0 && selectedMixerIndex < outputMixers.length) {
                        mixer = AudioSystem.getMixer(outputMixers[selectedMixerIndex]);
                    }

                    boolean success = false;
                    for (int channels : new int[] { 2, 1 }) {
                        for (int sr : new int[] { sampleRate, 44100, 48000 }) {
                            try {
                                format = new AudioFormat(sr, 16, channels, true, false);
                                info = new DataLine.Info(TargetDataLine.class, format);

                                if (mixer != null) {
                                    activeTargetLine = (TargetDataLine) mixer.getLine(info);
                                } else {
                                    activeTargetLine = (TargetDataLine) AudioSystem.getLine(info);
                                }

                                activeTargetLine.open(format); // Unrestricted OS buffer size
                                success = true;
                                isMonoCapture = (channels == 1);
                                if (sr != sampleRate || channels != 2) {
                                    System.out.println(
                                            "Hardware accepted fallback: " + sr + "Hz, " + channels + " channel(s)");
                                }
                                break;
                            } catch (Exception eFallback) {
                                // Line unavailable or format unsupported, try next parameter set
                            }
                        }
                        if (success)
                            break;
                    }

                    if (!success) {
                        throw new IllegalArgumentException("No supported format found for capture device.");
                    }

                    activeTargetLine.start();
                } catch (IllegalArgumentException e) {
                    e.printStackTrace(); // Log diagnostic information to the host terminal
                    SwingUtilities.invokeLater(() -> {
                        JOptionPane.showMessageDialog(this,
                                "The selected Audio Device does not support capturing audio.\nPlease select a device marked with [IN].",
                                "Unsupported Input Device", JOptionPane.ERROR_MESSAGE);
                        lblStatus.setText("Status: Ready");
                        btnPause.setText("Pause");
                    });
                    return; // Terminate thread gracefully
                }

                // 6 audio short samples (12 bytes) make up exactly 1 STC-007 Horizontal
                // Scanline.
                // STC-007 has exactly 490 data lines per frame (DATA_LINES_PER_FRAME).
                int samplesPerFrame = (DATA_LINES_PER_FRAME * 6) / 2; // 490 * 3 = 1470 stereo samples
                int bytesPerFrame = samplesPerFrame * 4; // 1470 * 4 = 5880 bytes
                byte[] captureBuffer = new byte[isMonoCapture ? bytesPerFrame / 2 : bytesPerFrame];

                // Track 112 previous lines dynamically to satisfy the 16-line Interleave Matrix
                short[] interleaveMatrix = new short[(DATA_LINES_PER_FRAME + 112) * 8];
                for (int i = 0; i < interleaveMatrix.length; i++)
                    interleaveMatrix[i] = -1;

                long startTime = System.currentTimeMillis();
                long totalBytesRecorded = 0;
                int frameCount = 0;

                while (isRunning) {
                    while (isPaused && isRunning) {
                        Thread.sleep(50);
                    }
                    if (!isRunning)
                        break;

                    // Strictly block and accumulate exactly one frame's worth of data
                    int bytesRead = 0;
                    while (bytesRead < captureBuffer.length && isRunning) {
                        int read = activeTargetLine.read(captureBuffer, bytesRead, captureBuffer.length - bytesRead);
                        if (read > 0) {
                            bytesRead += read;
                        }
                    }

                    if (bytesRead == captureBuffer.length) {
                        totalBytesRecorded += (isMonoCapture ? bytesRead * 2 : bytesRead);

                        int shortCount = bytesRead / 2;
                        short[] audioChunk = new short[isMonoCapture ? shortCount * 2 : shortCount];
                        for (int i = 0; i < shortCount; i++) {
                            // Little Endian extract
                            short sample = (short) (((captureBuffer[i * 2 + 1] & 0xFF) << 8)
                                    | (captureBuffer[i * 2] & 0xFF));

                            if (isMonoCapture) {
                                audioChunk[i * 2] = sample;
                                audioChunk[i * 2 + 1] = sample;
                            } else {
                                audioChunk[i] = sample;
                            }
                        }

                        runEncoderFrame(audioChunk, interleaveMatrix, frameCount++, isPal, totalBytesRecorded,
                                startTime);
                    }
                }
            } catch (Exception ex) {
                ex.printStackTrace();
                lblStatus.setText("Error: Capture device failed - " + ex.getMessage());
            } finally {
                if (activeTargetLine != null) {
                    activeTargetLine.stop();
                    activeTargetLine.close();
                }
                SwingUtilities.invokeLater(() -> {
                    vuLeft.setValue(0);
                    vuRight.setValue(0);
                    lblStatus.setText("Status: Capture Stopped");
                });
            }
        });
        activeCaptureThread.start();
    }

    /**
     * Receives a single frame's worth of streaming PCM audio dynamically over time,
     * mathematically interleaves it through the sliding 16-line delay matrices,
     * draws the exact STC-007 graphical bit representations, and blasts it straight
     * to the UI VideoPanel at ~30FPS. Entirely memory resident.
     */
    private void runEncoderFrame(short[] audioChunk, short[] interleaveMatrix, int f, boolean isPal,
            long totalBytesRecorded, long startTime) {
        int blocksInChunk = audioChunk.length / 6;
        int maxAmpL = 0;
        int maxAmpR = 0;

        // Shift the interleave matrix down to flush the oldest frame
        // We rendered the first 'blocksInChunk' lines of the matrix last frame.
        // So bring everything down.
        System.arraycopy(interleaveMatrix, blocksInChunk * 8, interleaveMatrix, 0,
                interleaveMatrix.length - (blocksInChunk * 8));

        // Blank out the tail
        for (int i = interleaveMatrix.length - (blocksInChunk * 8); i < interleaveMatrix.length; i++) {
            interleaveMatrix[i] = -1;
        }

        // Fill STC-007 Delay Matrix arrays for THIS frame's new audio payload
        for (int n = 0; n < blocksInChunk; n++) {
            short l1 = audioChunk[n * 6 + 0], r1 = audioChunk[n * 6 + 1];
            short l2 = audioChunk[n * 6 + 2], r2 = audioChunk[n * 6 + 3];
            short l3 = audioChunk[n * 6 + 4], r3 = audioChunk[n * 6 + 5];

            // Update VU Levels
            maxAmpL = Math.max(maxAmpL, Math.max(Math.abs(l1), Math.max(Math.abs(l2), Math.abs(l3))));
            maxAmpR = Math.max(maxAmpR, Math.max(Math.abs(r1), Math.max(Math.abs(r2), Math.abs(r3))));

            // MSB 14 bits for Words 1-6
            short w1 = (short) ((l1 >> 2) & 0x3FFF);
            short w2 = (short) ((r1 >> 2) & 0x3FFF);
            short w3 = (short) ((l2 >> 2) & 0x3FFF);
            short w4 = (short) ((r2 >> 2) & 0x3FFF);
            short w5 = (short) ((l3 >> 2) & 0x3FFF);
            short w6 = (short) ((r3 >> 2) & 0x3FFF);

            // Word 7 (P Parity)
            short p = (short) (w1 ^ w2 ^ w3 ^ w4 ^ w5 ^ w6);

            // Word 8 (Q Parity repurposed for 16-bit expansion)
            short q = (short) (((l1 & 3) << 12) | ((r1 & 3) << 10) | ((l2 & 3) << 8) | ((r2 & 3) << 6)
                    | ((l3 & 3) << 4) | ((r3 & 3) << 2));

            // Distribute to lines with 16-line delay (112 offset max)
            int pushIndex = interleaveMatrix.length - (blocksInChunk * 8) - (112 * 8) + (n * 8);
            if (pushIndex < 0)
                pushIndex = 0; // Guard

            interleaveMatrix[pushIndex + 0] = w1;
            interleaveMatrix[pushIndex + (16 * 8) + 1] = w2;
            interleaveMatrix[pushIndex + (32 * 8) + 2] = w3;
            interleaveMatrix[pushIndex + (48 * 8) + 3] = w4;
            interleaveMatrix[pushIndex + (64 * 8) + 4] = w5;
            interleaveMatrix[pushIndex + (80 * 8) + 5] = w6;
            interleaveMatrix[pushIndex + (96 * 8) + 6] = p;
            interleaveMatrix[pushIndex + (112 * 8) + 7] = q;
        }

        // --- Render UI Image ---
        // Write the STC-007 black and white bytes directly into the Image's raster
        // memory.
        BufferedImage bi = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_BYTE_GRAY);
        byte[] pixels = ((java.awt.image.DataBufferByte) bi.getRaster().getDataBuffer()).getData();

        int lineIdxFirstField = 0;
        int lineIdxSecondField = lineIdxFirstField + DATA_LINES_PER_FIELD;

        boolean[] bits = new boolean[137];

        for (int field = 0; field < 2; field++) {
            int startDataIdx = (field == 0) ? lineIdxFirstField : lineIdxSecondField;
            int dataCounter = 0;

            for (int fieldY = 0; fieldY < HEIGHT / 2; fieldY++) {
                if (fieldY < VBLANK_LINES)
                    continue;

                int y = fieldY * 2 + field;
                // Clear hoisted row for safety
                for (int z = 0; z < bits.length; z++)
                    bits[z] = false;

                // 1. Data Sync Signal (4 bits: 0101) - 2 white strips
                for (int i = 0; i < 4; i++) {
                    bits[i] = (i % 2 != 0);
                }

                // 2. White Reference Signal (5 bits: 11110) - big white strip on right
                for (int i = 132; i < 136; i++)
                    bits[i] = true;
                bits[136] = false;

                if (fieldY == VBLANK_LINES) {
                    // Sony Control Word (Line 1 of active video)
                    for (int w = 0; w < 7; w++) {
                        for (int b = 0; b < 14; b++) {
                            bits[4 + (w * 14) + b] = ((0x3333 >> (13 - b)) & 1) == 1;
                        }
                    }
                    int crc = crc16CCITT(bits, 4, 98);
                    for (int b = 0; b < 16; b++) {
                        bits[102 + b] = ((crc >> (15 - b)) & 1) == 1;
                    }
                    // Render Control Q-Word (Word 8) after the CRC
                    for (int b = 0; b < 14; b++) {
                        bits[118 + b] = ((0x3333 >> (13 - b)) & 1) == 1;
                    }
                } else {
                    // 3. Audio Data Words
                    int lineIdx = startDataIdx + dataCounter;
                    dataCounter++;

                    boolean hasData = false;

                    // Render Words 1-6 and Word 7 (Parity)
                    for (int w = 0; w < 7; w++) {
                        short word = (lineIdx * 8 + w) < interleaveMatrix.length ? interleaveMatrix[lineIdx * 8 + w]
                                : -1;
                        if (word != -1) {
                            hasData = true;
                            for (int b = 0; b < 14; b++) {
                                bits[4 + (w * 14) + b] = ((word >> (13 - b)) & 1) == 1;
                            }
                        }
                    }

                    if (hasData) {
                        // 4. CRC-16 over the 98 data bits (Words 1-7)
                        int crc = crc16CCITT(bits, 4, 98);
                        for (int b = 0; b < 16; b++) {
                            bits[102 + b] = ((crc >> (15 - b)) & 1) == 1;
                        }
                    }

                    // Render Word 8 (Q-Word)
                    short qWord = (lineIdx * 8 + 7) < interleaveMatrix.length ? interleaveMatrix[lineIdx * 8 + 7] : -1;
                    if (qWord != -1) {
                        for (int b = 0; b < 14; b++) {
                            bits[118 + b] = ((qWord >> (13 - b)) & 1) == 1;
                        }
                    }
                }

                // Draw Line natively into the backend byte[] array
                int startOffset = y * WIDTH + PIXEL_OFFSET;
                for (int b = 0; b < 137; b++) {
                    byte color = (byte) (bits[b] ? 255 : 0);
                    int bitOffset = startOffset + (b * BIT_WIDTH);
                    for (int pw = 0; pw < BIT_WIDTH; pw++) {
                        pixels[bitOffset + pw] = color;
                    }
                }
            }
        }

        final int pFrame = f;
        final int fmMaxL = maxAmpL;
        final int fmMaxR = maxAmpR;
        final long loopElapsed = System.currentTimeMillis() - startTime;
        double currentKbps = (totalBytesRecorded * 8.0 / 1000.0) / (loopElapsed / 1000.0);
        if (Double.isNaN(currentKbps) || Double.isInfinite(currentKbps))
            currentKbps = 0;
        final double safeKbps = currentKbps;

        SwingUtilities.invokeLater(() -> {
            videoPanel.setImage(bi);
            double fps = (double) pFrame / (loopElapsed / 1000.0);
            if (Double.isNaN(fps) || Double.isInfinite(fps))
                fps = 0.0;
            lblStatus.setText(String.format("Status: Realtime Capture [%.1f fps / %.1f kbps]", fps, safeKbps));
            vuLeft.setValue(fmMaxL);
            vuRight.setValue(fmMaxR);
        });
    }

    /**
     * Core encoding loop that transforms a raw 16-bit stereo audio sample array
     * into a sequence of
     * STC-007 compliant video frames, natively writing them to an MP4 sequence.
     * <p>
     * The EIAJ STC-007 format mathematically maps 6 audio samples into a 137-bit
     * video horizontal scanline,
     * complete with alternating synchronization pulses, CRC-16 hardware data
     * checksums, and interleaved
     * P-Parity and Q-Expansion matrices specifically delayed 16 lines apart to
     * survive horizontal VHS dropouts.
     *
     * @param audio     the array of raw audio samples (left and right channels
     *                  consecutively interleaved)
     * @param file      the output MP4 video file
     * @param labelInfo UI string describing the active task to map onto the GUI
     *                  status tracker
     * @throws Exception if I/O or JCodec encoding failures occur
     */
    private void runEncoderLoop(short[] audio, File file, String labelInfo) throws Exception {
        long startTime = System.currentTimeMillis();
        int totalBlocks = audio.length / 6;
        int totalLines = totalBlocks + 112;
        short[] matrixLines = new short[totalLines * 8];
        for (int i = 0; i < totalLines * 8; i++) {
            matrixLines[i] = -1;
        }

        // Create the STC-007 Interleave Matrix
        for (int n = 0; n < totalBlocks; n++) {
            short l1 = audio[n * 6 + 0], r1 = audio[n * 6 + 1];
            short l2 = audio[n * 6 + 2], r2 = audio[n * 6 + 3];
            short l3 = audio[n * 6 + 4], r3 = audio[n * 6 + 5];

            // MSB 14 bits for Words 1-6
            short w1 = (short) ((l1 >> 2) & 0x3FFF);
            short w2 = (short) ((r1 >> 2) & 0x3FFF);
            short w3 = (short) ((l2 >> 2) & 0x3FFF);
            short w4 = (short) ((r2 >> 2) & 0x3FFF);
            short w5 = (short) ((l3 >> 2) & 0x3FFF);
            short w6 = (short) ((r3 >> 2) & 0x3FFF);

            // Word 7 (P Parity)
            short p = (short) (w1 ^ w2 ^ w3 ^ w4 ^ w5 ^ w6);

            // Word 8 (Q Parity repurposed for 16-bit expansion)
            short q = (short) (((l1 & 3) << 12) | ((r1 & 3) << 10) | ((l2 & 3) << 8) | ((r2 & 3) << 6)
                    | ((l3 & 3) << 4) | ((r3 & 3) << 2));

            // Distribute to lines with 16-line delay
            matrixLines[n * 8 + 0] = w1;
            if (n + 16 < totalLines)
                matrixLines[(n + 16) * 8 + 1] = w2;
            if (n + 32 < totalLines)
                matrixLines[(n + 32) * 8 + 2] = w3;
            if (n + 48 < totalLines)
                matrixLines[(n + 48) * 8 + 3] = w4;
            if (n + 64 < totalLines)
                matrixLines[(n + 64) * 8 + 4] = w5;
            if (n + 80 < totalLines)
                matrixLines[(n + 80) * 8 + 5] = w6;
            if (n + 96 < totalLines)
                matrixLines[(n + 96) * 8 + 6] = p;
            if (n + 112 < totalLines)
                matrixLines[(n + 112) * 8 + 7] = q;
        }

        // Render into video frames
        int totalFrames = (totalLines + DATA_LINES_PER_FRAME - 1) / DATA_LINES_PER_FRAME;

        boolean useFfmpeg = cmbEncoder.getSelectedIndex() == 1;
        boolean isPal = cmbFormat.getSelectedIndex() == 1;
        int sampleRate = isPal ? 44100 : 44056;
        String fpsStr = isPal ? "30" : "30000/1001";
        Rational jcodecFps = isPal ? new Rational(30, 1) : new Rational(30000, 1001);

        SequenceEncoder encoder = null;
        Process ffmpegProc = null;
        java.io.OutputStream ffmpegIn = null;
        File tempAudioFile = null;

        if (useFfmpeg) {
            tempAudioFile = new File("temp_audio.pcm");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tempAudioFile)) {
                byte[] audioBytes = new byte[audio.length * 2];
                for (int i = 0; i < audio.length; i++) {
                    audioBytes[i * 2] = (byte) (audio[i] & 0xFF);
                    audioBytes[i * 2 + 1] = (byte) ((audio[i] >> 8) & 0xFF);
                }
                fos.write(audioBytes);
            }

            ProcessBuilder pb = new ProcessBuilder(
                    "ffmpeg", "-y",
                    "-f", "rawvideo", "-pix_fmt", "gray", "-s", WIDTH + "x" + HEIGHT, "-r", fpsStr, "-i", "-",
                    "-f", "s16le", "-ar", String.valueOf(sampleRate), "-ac", "2", "-i", tempAudioFile.getAbsolutePath(),
                    "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "1", "-preset", "ultrafast", "-c:a", "aac",
                    "-b:a", "192k",
                    file.getAbsolutePath());
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            ffmpegProc = pb.start();
            ffmpegIn = new java.io.BufferedOutputStream(ffmpegProc.getOutputStream(), 1024 * 1024 * 5); // 5MB fast pipe
                                                                                                        // buffer
        } else {
            encoder = SequenceEncoder.createWithFps(NIOUtils.writableChannel(file), jcodecFps);
        }

        for (int f = 0; f < totalFrames && isRunning; f++) {
            while (isPaused && isRunning) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (!isRunning)
                break;

            BufferedImage bi = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_BYTE_GRAY);
            Graphics2D g = bi.createGraphics();

            // Calculate amplitude for this frame's audio chunks
            int maxAmpL = 0, maxAmpR = 0;
            int startBlock = f * DATA_LINES_PER_FRAME;
            int endBlock = Math.min((f + 1) * DATA_LINES_PER_FRAME, totalBlocks);
            for (int n = startBlock; n < endBlock; n++) {
                maxAmpL = Math.max(maxAmpL, Math.max(Math.abs(audio[n * 6 + 0]),
                        Math.max(Math.abs(audio[n * 6 + 2]), Math.abs(audio[n * 6 + 4]))));
                maxAmpR = Math.max(maxAmpR, Math.max(Math.abs(audio[n * 6 + 1]),
                        Math.max(Math.abs(audio[n * 6 + 3]), Math.abs(audio[n * 6 + 5]))));
            }

            // Fill whole NTSC frame black initially (including VBLANK and pillarboxing)
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, WIDTH, HEIGHT);

            int lineIdxFirstField = f * DATA_LINES_PER_FRAME;
            int lineIdxSecondField = lineIdxFirstField + DATA_LINES_PER_FIELD;

            for (int field = 0; field < 2; field++) {
                int startDataIdx = (field == 0) ? lineIdxFirstField : lineIdxSecondField;
                int dataCounter = 0;

                for (int fieldY = 0; fieldY < HEIGHT / 2; fieldY++) {
                    if (fieldY < VBLANK_LINES)
                        continue;

                    int y = fieldY * 2 + field;
                    boolean[] bits = new boolean[137];

                    // 1. Data Sync Signal (4 bits: 0101) - 2 white strips
                    for (int i = 0; i < 4; i++) {
                        bits[i] = (i % 2 != 0);
                    }
                    // 2. White Reference Signal (5 bits: 11110) - big white strip on right
                    for (int i = 132; i < 136; i++)
                        bits[i] = true;
                    bits[136] = false;

                    if (fieldY == VBLANK_LINES) {
                        // Sony Control Word (Line 1 of active video)
                        for (int w = 0; w < 7; w++) {
                            for (int b = 0; b < 14; b++) {
                                bits[4 + (w * 14) + b] = ((0x3333 >> (13 - b)) & 1) == 1;
                            }
                        }
                        int crc = crc16CCITT(bits, 4, 98);
                        for (int b = 0; b < 16; b++) {
                            bits[102 + b] = ((crc >> (15 - b)) & 1) == 1;
                        }
                        // Render Control Q-Word (Word 8) after the CRC
                        for (int b = 0; b < 14; b++) {
                            bits[118 + b] = ((0x3333 >> (13 - b)) & 1) == 1;
                        }
                    } else {
                        // 3. Audio Data Words
                        int lineIdx = startDataIdx + dataCounter;
                        dataCounter++;

                        if (lineIdx >= totalLines)
                            continue; // Leave as just sync and white reference

                        boolean hasData = false;

                        // First render Words 1-6 and Word 7 (Parity)
                        for (int w = 0; w < 7; w++) {
                            short word = matrixLines[lineIdx * 8 + w];
                            if (word != -1) {
                                hasData = true;
                                for (int b = 0; b < 14; b++) {
                                    bits[4 + (w * 14) + b] = ((word >> (13 - b)) & 1) == 1;
                                }
                            }
                        }

                        if (hasData) {
                            // 4. CRC-16 over the 98 data bits (Words 1-7)
                            int crc = crc16CCITT(bits, 4, 98);
                            for (int b = 0; b < 16; b++) {
                                bits[102 + b] = ((crc >> (15 - b)) & 1) == 1;
                            }
                        }

                        // Finally render Word 8 (Q-Word)
                        short qWord = matrixLines[lineIdx * 8 + 7];
                        if (qWord != -1) {
                            for (int b = 0; b < 14; b++) {
                                bits[118 + b] = ((qWord >> (13 - b)) & 1) == 1;
                            }
                        }
                    }

                    // Draw Line
                    for (int b = 0; b < 137; b++) {
                        int startX = PIXEL_OFFSET + b * BIT_WIDTH;
                        g.setColor(bits[b] ? Color.WHITE : Color.BLACK);
                        g.fillRect(startX, y, BIT_WIDTH, 1);
                    }
                }
            }

            if (useFfmpeg) {
                byte[] pixels = ((java.awt.image.DataBufferByte) bi.getRaster().getDataBuffer()).getData();
                ffmpegIn.write(pixels);
            } else {
                encoder.encodeNativeFrame(AWTUtil.fromBufferedImage(bi, org.jcodec.common.model.ColorSpace.RGB));
            }

            final int pFrame = f;
            final int fmMaxL = maxAmpL;
            final int fmMaxR = maxAmpR;
            final long loopElapsed = System.currentTimeMillis() - startTime;

            SwingUtilities.invokeLater(() -> {
                videoPanel.setImage(bi); // bi is now isolated and immutable from thread changes
                lblStatus.setText(String.format("Encoding Video Frame: %d/%d [%s] - Elapsed: %s", pFrame, totalFrames,
                        labelInfo, formatElapsedTime(loopElapsed)));
                vuLeft.setValue(fmMaxL);
                vuRight.setValue(fmMaxR);
            });
            g.dispose();
        }

        if (useFfmpeg) {
            if (ffmpegIn != null) {
                ffmpegIn.flush();
                ffmpegIn.close();
            }
            if (ffmpegProc != null) {
                ffmpegProc.waitFor();
            }
            if (tempAudioFile != null && tempAudioFile.exists()) {
                tempAudioFile.delete();
            }
        } else {
            if (encoder != null) {
                encoder.finish();
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        String timeStr = formatElapsedTime(elapsed);
        double mbSize = 0;
        if (file.exists()) {
            mbSize = file.length() / (1024.0 * 1024.0);
        }
        final double finalMbSize = mbSize;
        final double avgFps = elapsed > 0 ? (totalFrames * 1000.0) / elapsed : 0;

        SwingUtilities.invokeLater(
                () -> {
                    if (isRunning) {
                        lblStatus.setText("Status: MP4 Saved Successfully! (Time: " + timeStr + ")");
                        JOptionPane.showMessageDialog(this,
                                String.format(
                                        "Encoding Complete!\nTime Elapsed: %s\nAverage FPS: %.2f\nMP4 Size: %.2f MB",
                                        timeStr, avgFps, finalMbSize),
                                "Task Complete", JOptionPane.INFORMATION_MESSAGE);
                    } else {
                        lblStatus.setText("Status: Encode Stopped.");
                    }
                });
    }

    /**
     * Displays a file dialog box for selecting an existing PCM-F1 encoded MP4 file,
     * routing it into the decoder pipeline for realtime CRC verification and audio
     * playback.
     */
    private void openVideo() {
        Preferences prefs = Preferences.userNodeForPackage(PCMF1SimulatorApp.class);
        String lastDir = prefs.get("lastOpenedDir", null);
        JFileChooser chooser = new JFileChooser(lastDir);
        chooser.setFileFilter(new FileNameExtensionFilter("MP4 Video Files (*.mp4)", "mp4"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION)
            return;
        prefs.put("lastOpenedDir", chooser.getSelectedFile().getParent());
        File file = chooser.getSelectedFile();
        startDecoding(file);
    }

    /**
     * Decodes a recorded STC-007 video file, converting it back into pure digital
     * audio and
     * executing real-time playback perfectly synchronized to the video framerate.
     * <p>
     * This orchestrates a background pipeline that rips every MP4 frame, parses the
     * black-and-white
     * thresholds back into binary boolean grids, calculates identical CRC
     * algorithms to verify line integrity,
     * untangles the 16-line delayed dropout-correction matrices to perfectly repair
     * damaged/missing samples,
     * and streams the resurrected 16-bit PCM data cleanly out to the system's
     * soundcard buffer.
     *
     * @param file the encoded MP4 video file to play hardware-style
     */
    private void startDecoding(File file) {
        currentDecodeFile = file;
        stopCurrentEngineConnections();

        activeDecodeThread = new Thread(() -> {
            SourceDataLine line = null;
            long startTime = System.currentTimeMillis();
            try {
                isRunning = true;
                isPaused = false;
                SwingUtilities.invokeLater(() -> btnPause.setText("Pause"));

                boolean isPal = cmbFormat.getSelectedIndex() == 1;
                int sampleRate = isPal ? 44100 : 44056;
                lblStatus.setText(
                        "Status: Playing STC-007 Audio Stream in Realtime (" + (isPal ? "PAL" : "NTSC") + ")...");

                AudioFormat format = new AudioFormat(sampleRate, 16, 2, true, false);
                DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);

                int selectedMixerIndex = cmbOutputDevice.getSelectedIndex();
                try {
                    Mixer mixer = null;
                    if (selectedMixerIndex > 0 && selectedMixerIndex < outputMixers.length) {
                        mixer = AudioSystem.getMixer(outputMixers[selectedMixerIndex]);
                    }

                    boolean success = false;
                    for (int sr : new int[] { sampleRate, 44100, 48000 }) {
                        try {
                            format = new AudioFormat(sr, 16, 2, true, true);
                            info = new DataLine.Info(SourceDataLine.class, format);

                            if (mixer != null) {
                                line = (SourceDataLine) mixer.getLine(info);
                            } else {
                                line = (SourceDataLine) AudioSystem.getLine(info);
                            }

                            // Increase buffer size to exactly 8820 samples (35280 bytes)
                            // This ensures the audio hardware buffer has ~200ms of slack to absorb slight
                            // decoding jitter or thread pauses without dropping the audio playback,
                            // while still keeping video delivery perfectly synced to the hardware clock.
                            // The byte size MUST perfectly align with frame size (4 bytes).
                            line.open(format, 35280);
                            activeDataLine = line;
                            success = true;
                            if (sr != sampleRate) {
                                System.out.println("Hardware rejected " + sampleRate
                                        + "Hz playback, successfully fell back to " + sr + "Hz");
                            }
                            break;
                        } catch (Exception eFallback) {
                            // Line unavailable or format unsupported, try next sample rate
                        }
                    }

                    if (!success) {
                        throw new IllegalArgumentException("No supported sample rate found for playback device.");
                    }

                    applyVolume();
                    line.start();
                } catch (IllegalArgumentException e) {
                    e.printStackTrace(); // Log diagnostic information to the host terminal
                    SwingUtilities.invokeLater(() -> {
                        JOptionPane.showMessageDialog(this,
                                "The selected Audio Device does not support audio playback.\nPlease select a device marked with [OUT].",
                                "Unsupported Output Device", JOptionPane.ERROR_MESSAGE);
                        lblStatus.setText("Status: Ready");
                        btnPause.setText("Pause");
                    });
                    return; // Terminate thread gracefully
                }

                FrameGrab grab = FrameGrab.createFrameGrab(NIOUtils.readableChannel(file));
                org.jcodec.common.model.Picture picture;

                double totalDuration = 0.0;
                try {
                    totalDuration = grab.getVideoTrack().getMeta().getTotalDuration();
                } catch (Exception e) {
                    System.err.println("Could not extract total video duration: " + e.getMessage());
                }
                final String totalTimeStr = formatElapsedTime((long) (totalDuration * 1000));

                ArrayList<short[]> decodedLines = new ArrayList<>();
                crcErrors = 0;
                correctionsP = 0;
                lostBlocks = 0;
                qLosses = 0;
                long framesProcessed = 0;
                long totalBytesProcessed = 0;

                while (isRunning && null != (picture = grab.getNativeFrame())) {
                    framesProcessed++;
                    while (isPaused && isRunning) {
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    if (!isRunning)
                        break;

                    BufferedImage bi = AWTUtil.toBufferedImage(picture);
                    SwingUtilities.invokeLater(() -> videoPanel.setImage(bi));

                    for (int field = 0; field < 2; field++) {
                        int controlLineY = VBLANK_LINES; // Ideal default location

                        // Dynamically scan for the Sony Control Word to handle analog VCR vertical
                        // jitter
                        for (int fieldY = 0; fieldY < 30; fieldY++) {
                            int y = fieldY * 2 + field;
                            boolean[] bits = new boolean[137];

                            // 1. Calculate Dynamic Threshold for this line
                            int syncBlackSum = 0;
                            // Average of bits 0,2 (solid black in sync 0101)
                            for (int b : new int[] { 0, 2 }) {
                                int midX = PIXEL_OFFSET + b * BIT_WIDTH + (BIT_WIDTH / 2);
                                syncBlackSum += (bi.getRGB(midX - 1, y) & 0xFF) + (bi.getRGB(midX, y) & 0xFF)
                                        + (bi.getRGB(midX + 1, y) & 0xFF);
                            }
                            int avgBlack = syncBlackSum / 6;

                            int refWhiteSum = 0;
                            // Average of bits 132-135 (solid white in ref 11110)
                            for (int b = 132; b <= 135; b++) {
                                int midX = PIXEL_OFFSET + b * BIT_WIDTH + (BIT_WIDTH / 2);
                                refWhiteSum += (bi.getRGB(midX - 1, y) & 0xFF) + (bi.getRGB(midX, y) & 0xFF)
                                        + (bi.getRGB(midX + 1, y) & 0xFF);
                            }
                            int avgWhite = refWhiteSum / 12;

                            // Protect against complete signal loss / solid color lines
                            int threshold = 128;
                            if (avgWhite > avgBlack + 20) {
                                threshold = (avgWhite + avgBlack) / 2;
                            }

                            // 2. Adaptive Horizontal Tracking (Wow/Flutter correction)
                            int leftEdge = -1;
                            // bit 1 should start around PIXEL_OFFSET + 5 (22). Scan safely around it.
                            for (int px = PIXEL_OFFSET - 5; px < PIXEL_OFFSET + 25; px++) {
                                if (px >= 0 && px < WIDTH && (bi.getRGB(px, y) & 0xFF) > threshold) {
                                    leftEdge = px;
                                    break;
                                }
                            }

                            int rightEdge = -1;
                            // bit 135 should end around PIXEL_OFFSET + 136 * 5 (697). Scan safely.
                            int expectedRight = PIXEL_OFFSET + 136 * BIT_WIDTH - 1;
                            for (int px = expectedRight + 15; px > expectedRight - 15; px--) {
                                if (px >= 0 && px < WIDTH && (bi.getRGB(px, y) & 0xFF) > threshold) {
                                    rightEdge = px;
                                    break;
                                }
                            }

                            double detectedBitWidth = (double) BIT_WIDTH;
                            double startOffset = PIXEL_OFFSET;

                            if (leftEdge != -1 && rightEdge != -1) {
                                // distance from start of bit 1 to end of bit 135 is 135 bits
                                detectedBitWidth = (rightEdge - leftEdge) / 135.0;
                                startOffset = leftEdge - detectedBitWidth;
                            }

                            for (int b = 0; b < 137; b++) {
                                int midX = (int) (startOffset + (b * detectedBitWidth) + (detectedBitWidth / 2.0));
                                if (midX - 1 < 0 || midX + 1 >= WIDTH) {
                                    bits[b] = false;
                                } else {
                                    int pxLeft = bi.getRGB(midX - 1, y) & 0xFF;
                                    int px = bi.getRGB(midX, y) & 0xFF;
                                    int pxRight = bi.getRGB(midX + 1, y) & 0xFF;
                                    int avgPx = (pxLeft + px + pxRight) / 3;
                                    bits[b] = avgPx > threshold;
                                }
                            }

                            boolean hasSync = true;
                            for (int i = 0; i < 4; i++)
                                if (bits[i] != (i % 2 != 0))
                                    hasSync = false;
                            if (!hasSync)
                                continue;

                            int expectedCrc = 0;
                            for (int b = 0; b < 16; b++)
                                if (bits[116 + b])
                                    expectedCrc |= (1 << (15 - b));
                            int calcCrc = crc16CCITT(bits, 4, 112);

                            boolean isControl = true;
                            for (int w = 0; w < 8; w++) {
                                short val = 0;
                                for (int b = 0; b < 14; b++)
                                    if (bits[4 + (w * 14) + b])
                                        val |= (1 << (13 - b));
                                if (val != 0x3333)
                                    isControl = false;
                            }

                            if (isControl && expectedCrc == calcCrc) {
                                controlLineY = fieldY;
                                break;
                            }
                        }

                        // Parse the strictly sequenced data lines belonging to this field
                        for (int i = 0; i < DATA_LINES_PER_FIELD; i++) {
                            int fieldY = controlLineY + 1 + i;
                            int y = fieldY * 2 + field;

                            if (fieldY >= HEIGHT / 2 || y >= HEIGHT) {
                                decodedLines.add(null);
                                continue;
                            }

                            // 1. Calculate Dynamic Threshold for this line
                            int syncBlackSum = 0;
                            for (int b : new int[] { 0, 2 }) {
                                int midX = PIXEL_OFFSET + b * BIT_WIDTH + (BIT_WIDTH / 2);
                                syncBlackSum += (bi.getRGB(midX - 1, y) & 0xFF) + (bi.getRGB(midX, y) & 0xFF)
                                        + (bi.getRGB(midX + 1, y) & 0xFF);
                            }
                            int avgBlack = syncBlackSum / 6;

                            int refWhiteSum = 0;
                            for (int b = 132; b <= 135; b++) {
                                int midX = PIXEL_OFFSET + b * BIT_WIDTH + (BIT_WIDTH / 2);
                                refWhiteSum += (bi.getRGB(midX - 1, y) & 0xFF) + (bi.getRGB(midX, y) & 0xFF)
                                        + (bi.getRGB(midX + 1, y) & 0xFF);
                            }
                            int avgWhite = refWhiteSum / 12;

                            int threshold = 128;
                            if (avgWhite > avgBlack + 20) {
                                threshold = (avgWhite + avgBlack) / 2;
                            }

                            boolean[] bits = new boolean[137];
                            // 2. Adaptive Horizontal Tracking (Wow/Flutter correction)
                            int leftEdge = -1;
                            // bit 1 should start around PIXEL_OFFSET + 5. Scan safely around it.
                            for (int px = PIXEL_OFFSET - 5; px < PIXEL_OFFSET + 25; px++) {
                                if (px >= 0 && px < WIDTH && (bi.getRGB(px, y) & 0xFF) > threshold) {
                                    leftEdge = px;
                                    break;
                                }
                            }

                            int rightEdge = -1;
                            // bit 135 should end around PIXEL_OFFSET + 136 * 5. Scan safely.
                            int expectedRight = PIXEL_OFFSET + 136 * BIT_WIDTH - 1;
                            for (int px = expectedRight + 15; px > expectedRight - 15; px--) {
                                if (px >= 0 && px < WIDTH && (bi.getRGB(px, y) & 0xFF) > threshold) {
                                    rightEdge = px;
                                    break;
                                }
                            }

                            double detectedBitWidth = (double) BIT_WIDTH;
                            double startOffset = PIXEL_OFFSET;

                            if (leftEdge != -1 && rightEdge != -1) {
                                // distance from start of bit 1 to end of bit 135 is 135 bits
                                detectedBitWidth = (rightEdge - leftEdge) / 135.0;
                                startOffset = leftEdge - detectedBitWidth;
                            }

                            for (int b = 0; b < 137; b++) {
                                int midX = (int) (startOffset + (b * detectedBitWidth) + (detectedBitWidth / 2.0));
                                if (midX - 1 < 0 || midX + 1 >= WIDTH) {
                                    bits[b] = false;
                                } else {
                                    int pxLeft = bi.getRGB(midX - 1, y) & 0xFF;
                                    int px = bi.getRGB(midX, y) & 0xFF;
                                    int pxRight = bi.getRGB(midX + 1, y) & 0xFF;
                                    int avgPx = (pxLeft + px + pxRight) / 3;
                                    bits[b] = avgPx > threshold;
                                }
                            }

                            boolean hasSync = true;
                            for (int s = 0; s < 4; s++)
                                if (bits[s] != (s % 2 != 0))
                                    hasSync = false;

                            if (!hasSync) {
                                decodedLines.add(null);
                            } else {
                                int expectedCrc = 0;
                                for (int b = 0; b < 16; b++)
                                    if (bits[102 + b])
                                        expectedCrc |= (1 << (15 - b));
                                int calcCrc = crc16CCITT(bits, 4, 98);

                                if (expectedCrc != calcCrc) {
                                    crcErrors++;
                                    decodedLines.add(null);
                                } else {
                                    short[] words = new short[8];
                                    // Extract Audio Data and Parity Word (Words 1-7)
                                    for (int w = 0; w < 7; w++) {
                                        short val = 0;
                                        for (int b = 0; b < 14; b++)
                                            if (bits[4 + (w * 14) + b])
                                                val |= (1 << (13 - b));
                                        words[w] = val;
                                    }
                                    // Extract Q Word (Word 8) located after the CRC
                                    short qVal = 0;
                                    for (int b = 0; b < 14; b++)
                                        if (bits[118 + b])
                                            qVal |= (1 << (13 - b));
                                    words[7] = qVal;

                                    decodedLines.add(words);
                                }
                            }
                        }
                    }

                    // Extract samples block-by-block exactly 112 lines behind the reading edge
                    int blocksThisFrame = Math.max(0, decodedLines.size() - 112);
                    int maxAmpL = 0, maxAmpR = 0;
                    int byteIdx = 0;

                    for (int n = 0; n < blocksThisFrame && isRunning; n++) {
                        short w1 = getWord(decodedLines, n, 0);
                        short w2 = getWord(decodedLines, n + 16, 1);
                        short w3 = getWord(decodedLines, n + 32, 2);
                        short w4 = getWord(decodedLines, n + 48, 3);
                        short w5 = getWord(decodedLines, n + 64, 4);
                        short w6 = getWord(decodedLines, n + 80, 5);
                        short p = getWord(decodedLines, n + 96, 6);
                        short q = getWord(decodedLines, n + 112, 7);

                        int missingCount = 0;
                        if (w1 == -1)
                            missingCount++;
                        if (w2 == -1)
                            missingCount++;
                        if (w3 == -1)
                            missingCount++;
                        if (w4 == -1)
                            missingCount++;
                        if (w5 == -1)
                            missingCount++;
                        if (w6 == -1)
                            missingCount++;

                        if (missingCount == 1 && p != -1) {
                            if (w1 == -1)
                                w1 = (short) (w2 ^ w3 ^ w4 ^ w5 ^ w6 ^ p);
                            if (w2 == -1)
                                w2 = (short) (w1 ^ w3 ^ w4 ^ w5 ^ w6 ^ p);
                            if (w3 == -1)
                                w3 = (short) (w1 ^ w2 ^ w4 ^ w5 ^ w6 ^ p);
                            if (w4 == -1)
                                w4 = (short) (w1 ^ w2 ^ w3 ^ w5 ^ w6 ^ p);
                            if (w5 == -1)
                                w5 = (short) (w1 ^ w2 ^ w3 ^ w4 ^ w6 ^ p);
                            if (w6 == -1)
                                w6 = (short) (w1 ^ w2 ^ w3 ^ w4 ^ w5 ^ p);
                            correctionsP++;
                            missingCount = 0;
                        }

                        if (missingCount > 0) {
                            lostBlocks++;
                            continue; // Unrecoverable block
                        }

                        int l1_e = 0, r1_e = 0, l2_e = 0, r2_e = 0, l3_e = 0, r3_e = 0;
                        if (q != -1) {
                            l1_e = (q >> 12) & 3;
                            r1_e = (q >> 10) & 3;
                            l2_e = (q >> 8) & 3;
                            r2_e = (q >> 6) & 3;
                            l3_e = (q >> 4) & 3;
                            r3_e = (q >> 2) & 3;
                        } else {
                            qLosses++;
                        }

                        short[] finalSamples = new short[] {
                                (short) ((w1 << 2) | l1_e), (short) ((w2 << 2) | r1_e),
                                (short) ((w3 << 2) | l2_e), (short) ((w4 << 2) | r2_e),
                                (short) ((w5 << 2) | l3_e), (short) ((w6 << 2) | r3_e)
                        };

                        for (int i = 0; i < 6; i++) {
                            short sample = finalSamples[i];
                            audioBuffer[byteIdx++] = (byte) ((sample >> 8) & 0xFF);
                            audioBuffer[byteIdx++] = (byte) (sample & 0xFF);

                            if (i % 2 == 0)
                                maxAmpL = Math.max(maxAmpL, Math.abs(sample));
                            else
                                maxAmpR = Math.max(maxAmpR, Math.abs(sample));
                        }
                    }

                    if (blocksThisFrame > 0 && isRunning) {
                        decodedLines.subList(0, blocksThisFrame).clear();
                    }

                    if (byteIdx > 0 && isRunning) {
                        // Writes block thread syncing real-time frame rate to 44.056 kHz audio drainage
                        line.write(audioBuffer, 0, byteIdx);
                        totalBytesProcessed += byteIdx;
                    }

                    if (isRunning) {
                        // Update VUs
                        final int fnMaxL = maxAmpL;
                        final int fnMaxR = maxAmpR;
                        final long loopElapsed = System.currentTimeMillis() - startTime;

                        // Calculate moving FPS and Bitrate
                        final double currentFps = loopElapsed > 0 ? (framesProcessed / (loopElapsed / 1000.0)) : 0.0;
                        final double currentKbps = loopElapsed > 0
                                ? ((totalBytesProcessed * 8.0 / 1000.0) / (loopElapsed / 1000.0))
                                : 0.0;

                        final int snapCrc = crcErrors;
                        final int snapLost = lostBlocks;
                        final int snapCorr = correctionsP;
                        final String fnTotalTime = totalTimeStr;

                        SwingUtilities.invokeLater(() -> {
                            vuLeft.setValue(fnMaxL);
                            vuRight.setValue(fnMaxR);
                            lblStatus.setText(
                                    String.format(
                                            "Status: Decoding... Time: %s/%s [%.1f fps / %.1f kbps] | CRC: %d | Fixed: %d | Dropped: %d",
                                            formatElapsedTime(loopElapsed), fnTotalTime, currentFps, currentKbps,
                                            snapCrc,
                                            snapCorr, snapLost));
                        });
                    }
                }

                if (line != null) {
                    if (isRunning)
                        line.drain();
                    line.close();
                }

                if (isRunning) {
                    long elapsed = System.currentTimeMillis() - startTime;
                    String timeStr = formatElapsedTime(elapsed);

                    vuLeft.setValue(0);
                    vuRight.setValue(0);
                    String stats = String.format(
                            "Audio Streams Resynced & Played:\nTime Elapsed: %s\nCRC Corruptions: %d\nHardware Parity Corrections Applied: %d\nUnrecoverable Blocks Dropped: %d\nQ-Block/16-bit fidelity losses: %d",
                            timeStr, crcErrors, correctionsP, lostBlocks, qLosses);
                    lblStatus.setText("Status: Decoding Complete. (Time: " + timeStr + ")");
                    JOptionPane.showMessageDialog(this, stats, "Decoding Complete", JOptionPane.INFORMATION_MESSAGE);
                }

            } catch (Exception ex) {
                ex.printStackTrace();
                lblStatus.setText("Error: " + ex.getMessage());
            } finally {
                if (line != null && line.isOpen())
                    line.close();
                SwingUtilities.invokeLater(() -> {
                    vuLeft.setValue(0);
                    vuRight.setValue(0);
                });
            }
        });
        activeDecodeThread.start();
    }

    /**
     * Safely retrieves a specific 14-bit data word from a decoded video scanline.
     * Formats missing or unreadable lines into an error state (-1) to flag the
     * error correction loops.
     *
     * @param lines   the historical sequence of decoded data words across the whole
     *                frame
     * @param lineIdx the chronological scanline index mapping to the 16-line
     *                delayed dropout window
     * @param wordIdx the specific array index (0-7) of the 14-bit word on that line
     * @return the extracted data word, or -1 if the scanline dropped or failed CRC
     *         validation
     */
    private short getWord(ArrayList<short[]> lines, int lineIdx, int wordIdx) {
        if (lineIdx >= lines.size())
            return -1;
        short[] line = lines.get(lineIdx);
        if (line == null)
            return -1;
        return line[wordIdx];
    }

    /**
     * Converts a raw millisecond counter into a human-readable HH:MM:SS format
     * for use in the active GUI readout status indicators.
     *
     * @param millis the elapsed duration in milliseconds
     * @return a formatted timestamp string
     */
    private String formatElapsedTime(long millis) {
        long seconds = millis / 1000;
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        return String.format("%02d:%02d:%02d", h, m, s);
    }

    /**
     * Calculates the CRC-16-CCITT checksum for a specific subsequence of a boolean
     * bit array.
     * This is used natively by the STC-007 hardware standard to verify the 112-bit
     * data block
     * embedded inside each horizontal analog scanline.
     *
     * @param bits   the boolean bit array representing the entire scanline data
     * @param offset the starting array index of the data block to be hashed
     * @param len    the amount of sequential bits to include in the checksum
     *               calculation
     * @return the calculated 16-bit integrity checksum
     */
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

    /**
     * A simple GUI panel that takes a raw BufferedImage frame array and rapidly
     * paints it to the screen, providing real-time visual feedback of either
     * the STC-007 encoding progress or the actively playing decodable video stream.
     */
    class VideoPanel extends JPanel {
        private BufferedImage img;

        /**
         * Sets the internal buffer image and requests a repaint from the Swing manager.
         *
         * @param img the pre-rendered black and white data frame
         */
        public void setImage(BufferedImage img) {
            this.img = img;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (img != null)
                g.drawImage(img, 0, 0, getWidth(), getHeight(), null);
        }
    }

    /**
     * Custom GUI component designed to emulate an authentic retro modular LED bar
     * VU Meter,
     * specifically replicating the distinct high-resolution segmented horizontal
     * readouts and floating
     * peak-hold decay behaviors characteristic of early 1980s Sony PCM hardware
     * displays.
     */
    class VUMeterPanel extends JPanel {
        private int value = 0;
        private int peakValue = 0;
        private int peakDecayTimer = 0;
        private String label;

        /**
         * Instantiates the modular LED level meter with a channel label.
         *
         * @param label the visual identifier for the channel (e.g., "L" or "R")
         */
        public VUMeterPanel(String label) {
            this.label = label;
            setPreferredSize(new Dimension(50, HEIGHT));
            setBackground(Color.BLACK);
        }

        /**
         * Pushes a new raw audio amplitude to the meter, intelligently updating the
         * active green/yellow/red
         * segments based on total power, while smoothly decaying the floating peak-hold
         * LED.
         *
         * @param val the raw 16-bit PCM amplitude level (0-32768)
         */
        public void setValue(int val) {
            this.value = val;
            if (val >= peakValue) {
                peakValue = val;
                peakDecayTimer = 20; // Hold peak for approx 20 frames
            } else if (peakDecayTimer > 0) {
                peakDecayTimer--;
            } else {
                peakValue -= 800; // Slow drop-off
                if (peakValue < 0)
                    peakValue = 0;
            }
            repaint();
        }

        /**
         * Zeros out all active LED segments and clears the peak-hold memory cache,
         * returning the meter to black.
         */
        public void reset() {
            this.value = 0;
            this.peakValue = 0;
            this.peakDecayTimer = 0;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            int paddingX = 8;
            int paddingY = 25;
            int segmentHeight = 4;
            int segmentSpacing = 2;
            int numSegments = 75;

            int activeSegments = (int) ((value / 32768.0) * numSegments);
            int peakSegment = (int) ((peakValue / 32768.0) * numSegments);

            int w = getWidth() - (paddingX * 2);
            for (int i = 0; i < numSegments; i++) {
                int y = getHeight() - paddingY - (i * (segmentHeight + segmentSpacing));

                boolean isActive = i < activeSegments;
                boolean isPeak = i == peakSegment - 1;

                Color ledColor;
                if (i >= 65) {
                    ledColor = isActive || isPeak ? Color.RED : new Color(60, 0, 0);
                } else if (i >= 50) {
                    ledColor = isActive || isPeak ? Color.YELLOW : new Color(60, 60, 0);
                } else {
                    ledColor = isActive || isPeak ? Color.GREEN : new Color(0, 60, 0);
                }

                g.setColor(ledColor);
                g.fillRect(paddingX, y, w, segmentHeight);
            }

            g.setColor(Color.WHITE);
            g.drawString(label, getWidth() / 2 - g.getFontMetrics().stringWidth(label) / 2, getHeight() - 5);
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(PCMF1SimulatorApp::new);
    }
}
