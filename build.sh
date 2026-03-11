#!/bin/bash
echo "PCMF1 Simulator - JAR Builder Script (Unix)"
echo ==============================================

# Ensure javac and jar are available
if ! command -v javac &> /dev/null || ! command -v jar &> /dev/null; then
    echo "[ERROR] Java development tools (javac/jar) were not found."
    echo "Please ensure a JDK is installed and in your PATH."
    exit 1
fi

echo "[1/5] Cleaning old temporary build files..."
rm -rf build_jar
mkdir -p build_jar
cd build_jar || exit 1

echo "[2/5] Extracting JCodec libraries..."
jar xf ../lib/jcodec-0.2.5.jar
jar xf ../lib/jcodec-javase-0.2.5.jar

echo "[3/5] Removing conflicting META-INF signatures..."
rm -rf META-INF

echo "[4/5] Compiling PCMF1SimulatorApp.java for Java 8 compatibility..."
# Note: Wildcard in classpath must be quoted to prevent shell expansion
javac -source 8 -target 8 -cp "../lib/*" ../PCMF1SimulatorApp.java -d .

echo "[5/5] Packaging PCMF1Simulator.jar..."
jar cvfm ../PCMF1Simulator.jar ../Manifest.txt .

cd ..
echo "Cleaning up..."
rm -rf build_jar

echo "=============================================="
echo "Build Complete! Look for PCMF1Simulator.jar"
echo "=============================================="
