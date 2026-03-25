# Sony PCM-F1 & PCM-48K Simulator

Welcome to the **Sony PCM-F1 Simulator**. This application provides a window into early digital audio and modern high-fidelity "over-video" protocols. It allows you to encode 16-bit audio into professional video signals, ready to record onto physical VHS hardware.

> [!NOTE]
> This application was coded by Gemini 3.1 Pro and has not been tested on actual hardware or PCM encoded videos. It is meant primarily as a proof of concept and educational tool. If you are looking for a production-ready application, please use the original Sony PCM-F1.

---

## Anatomy of a PCM-F1 Video Frame

To understand how this encoder works, we must look at how digital data is mapped onto a television screen. An entire interlaced video frame (such as NTSC) is divided into specialized sections to allow VHS recording without data loss.

1.  **VBLANK (Vertical Blanking Interval):** The top 17 lines are forced to black. This originally allowed the CRT electron beam to reset and gives VCR switching heads time to align.
2.  **Control Word Header:** The first active line contains a repeating `0x3333` signature. A hardware decoder hunts for this to align its vertical phase clock.
3.  **PCM Audio Data Block:** The main part of the screen is a dense flickering matrix encompassing the raw 16-Bit interleaved PCM audio data, parities, and checksums.
4.  **Horizontal Pillarboxing:** Deep black borders prevent data loss due to *overscan* on older CRT displays.

![PCM-F1 Frame Structure](frame.png)

### Inside a Single Row of Audio Data
Each of the 240 active audio lines contains exactly **137 bits** of data:

*   **STC Sync Pulse (9 Bits):** A `101010101` wave that calibrates the hardware clock.
*   **Audio Words (84 Bits):** Six 14-bit data words carrying the actual PCM sampled audio (interleaved L/R stereo pairs).
*   **Parity P/Q (28 Bits):** Error correction blocks used to reconstruct damaged audio from VHS dropouts.
*   **CRC-16 (16 Bits):** A checksum used to verify if the line was corrupted by noise.

---

## The PCM-48K Protocol (High Fidelity)

For modern users, the simulator includes a custom **PCM-48K** protocol. This mode trades the legacy error correction for pure audio fidelity, allowing for 48kHz / 16-bit stereo playback with zero pulsating artifacts.

### PCM-48K Specifications
*   **Sample Rate:** 48,000 Hz (Professional Standard).
*   **Resolution:** 16-Bit Linear PCM.
*   **Line Density:** 8 samples per line (4 stereo pairs).
*   **Frame Structure:** 400 active audio lines per frame.
*   **UI Features:** Includes real-time vertical VU meters and metadata telemetry injected into the video padding.

> [!TIP]
> **Fidelity vs. Robustness:** While PCM-48K sounds better, it lacks the P/Q parity of the Sony PCM-F1. This means it is "fragile"—a single VHS dropout will cause a pop or skip, whereas the PCM-F1 mode can "calculate" its way through tape damage.

---

## 1. Prerequisites & Installation

### Install Java Runtime Environment (JRE)
This software is a standalone Java Archive (`.jar`). You must have **Java 8 or newer** installed.
*   Download from the [Java Downloads Page](https://www.java.com/en/download/manual.jsp).
*   Install the **Windows Offline (64-bit)** version.

### Install FFmpeg (Optional, Highly Recommended)
While the app has built-in Java codecs for video streams, **FFmpeg** is required to multiplex a synchronous audio track into your final video file.

*   **Windows (Winget):** `winget install ffmpeg`
*   **Manual:** Download from [ffmpeg.org](https://ffmpeg.org/download.html).

---

## 2. Running the Simulator

### Option A: Double Click
If Java is correctly associated with `.jar` files, simply **Double-Click** `PCMF1Simulator.jar`.

### Option B: Command Line
If double-clicking fails, open a terminal (cmd/PowerShell) and run:
```bash
java -jar PCMF1Simulator.jar
```

---

## 3. Usage Instructions

### A. Encoding Audio to Video (For VHS Recording)
1.  Open the application and select your **Protocol** (Legacy PCM-F1 or High-Fidelity PCM-48K).
2.  Choose an **Encoder** (JCodec for pure Java, or FFmpeg for full audio/video muxing).
3.  Select your **Format** (NTSC for USA/Japan 44,056Hz, PAL for Europe 44,100Hz).
4.  Click **Encode Audio** and select your source file (`.WAV`, `.FLAC`, `.MP3`, etc.).
5.  The app will generate the video matrix in real-time. When complete, a `.mp4` will be saved.

### B. Decoding Video to Audio (Realtime Playback)
1.  Click **Decode Audio** and select a valid STC-007 compliant MP4.
2.  The application will play the audio through your speakers in real-time, syncing to the video framerate.
3.  The GUI tracks corrected P/Q Parity dropout errors simulating hardware recovery.

![JPCMF1](JPCMF1.png)

---

## 4. Hardware VHS Recording

> [!CAUTION]
> **Attention Video Enthusiasts:** You cannot use cheap scaling "HDMI-to-RCA" converter boxes. These "smear" the picture, destroying the binary data.

To record successfully to tape, you must output a raw, unscaled **480i 15kHz signal** (e.g., using a Raspberry Pi's native composite jack).
