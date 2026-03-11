#!/bin/bash

# PCMF1 VHS Tape Degradation Simulator (macOS / Linux)

# Check if a file was provided as the first argument
if [ "$#" -ne 1 ]; then
    echo "[ERROR] No input file provided."
    echo "Usage: ./simulate_vhs.sh <path_to_clean_pcmf1_mp4>"
    exit 1
fi

INPUT_FILE="$1"

# Verify the input file actually exists
if [ ! -f "$INPUT_FILE" ]; then
    echo "[ERROR] File not found: $INPUT_FILE"
    exit 1
fi

# Extract directory, filename, and extension safely
INPUT_DIR="$(dirname "$INPUT_FILE")"
INPUT_BASE="$(basename "$INPUT_FILE")"
INPUT_NAME="${INPUT_BASE%.*}"
INPUT_EXT="${INPUT_BASE##*.}"

# If there is no extension, set it correctly
if [ "$INPUT_NAME" = "$INPUT_EXT" ]; then
    INPUT_EXT=""
else
    INPUT_EXT=".$INPUT_EXT"
fi

# Define output file path
OUTPUT_FILE="$INPUT_DIR/${INPUT_NAME}_VHS_Simulated${INPUT_EXT}"

echo "======================================================="
echo "PCMF1 VHS Tape Degradation Simulator"
echo "======================================================="
echo "Input:  $INPUT_FILE"
echo "Output: $OUTPUT_FILE"
echo ""
echo "Applying analog VHS noise, tracking blur, and interlace artifacts..."
echo "Please wait, this may take a moment depending on the file length."
echo ""

# Run FFmpeg with analog degradation filters (Adjusted for better PCM-F1 decoding)
ffmpeg -y -i "$INPUT_FILE" -vf "noise=alls=10:allf=t, gblur=sigma=0.5:steps=1, unsharp=5:5:0.5:5:5:0.0, curves=m='0.05/0.1 0.95/0.9'" -c:v libx264 -preset slow -crf 20 -pix_fmt yuv420p "$OUTPUT_FILE"

# Check exit status
if [ $? -eq 0 ]; then
    echo ""
    echo "======================================================="
    echo "[SUCCESS] VHS degradation complete!"
    echo "Your tape-simulated file is ready:"
    echo "$OUTPUT_FILE"
    echo ""
    echo "Try feeding this back into the PCMF1 Simulator decoder!"
    echo "======================================================="
else
    echo ""
    echo "[ERROR] FFmpeg encountered an issue during simulation."
    echo "Make sure FFmpeg is installed and accessible in your system PATH."
    exit 1
fi
