@echo off
echo ==============================================
echo PCMF1 Simulator - JAR Builder Script
echo ==============================================

set "JDK_BIN=C:\Program Files\Java\jdk-17.0.2\bin"
set "JAR_CMD=%JDK_BIN%\jar.exe"
set "JAVAC_CMD=%JDK_BIN%\javac.exe"

if not exist "%JAR_CMD%" (
    echo WARNING: jar.exe not found at %JAR_CMD%
    echo Falling back to system command...
    set "JAR_CMD=jar"
    set "JAVAC_CMD=javac"
)

echo [1/5] Cleaning old temporary build files...
if exist build_jar rmdir /s /q build_jar
mkdir build_jar
cd build_jar

echo [2/5] Extracting JCodec libraries...
"%JAR_CMD%" xf ..\lib\jcodec-0.2.5.jar
"%JAR_CMD%" xf ..\lib\jcodec-javase-0.2.5.jar

echo [3/5] Removing conflicting META-INF signatures...
if exist META-INF rmdir /s /q META-INF

echo [4/5] Compiling PCMF1SimulatorApp.java for Java 8 compatibility...
"%JAVAC_CMD%" -source 8 -target 8 -cp "..\lib\*" ..\PCMF1SimulatorApp.java -d .

echo [5/5] Packaging PCMF1Simulator.jar...
"%JAR_CMD%" cvfm ..\PCMF1Simulator.jar ..\Manifest.txt .

cd ..
echo Cleaning up...
rmdir /s /q build_jar

echo ==============================================
echo Build Complete! Look for PCMF1Simulator.jar
echo ==============================================
pause
