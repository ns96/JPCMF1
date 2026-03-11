@echo off
setlocal

:: Check if a file was dragged and dropped onto the script
if "%~1"=="" (
    echo [ERROR] No input file provided.
    echo Please drag and drop your clean PCMF1 encoded MP4 file onto this batch script.
    pause
    exit /b 1
)

:: Get input file details
set "INPUT_FILE=%~1"
set "INPUT_DIR=%~dp1"
set "INPUT_NAME=%~n1"
set "INPUT_EXT=%~x1"

:: Define output file path
set "OUTPUT_FILE=%INPUT_DIR%%INPUT_NAME%_VHS_Simulated%INPUT_EXT%"

echo =======================================================
echo PCMF1 VHS Tape Degradation Simulator
echo =======================================================
echo Input:  %INPUT_FILE%
echo Output: %OUTPUT_FILE%
echo.
echo Applying analog VHS noise, tracking blur, and interlace artifacts...
echo Please wait, this may take a moment depending on the file length.
echo.

:: Run FFmpeg with analog degradation filters (Adjusted for better PCM-F1 decoding)
ffmpeg -i "%INPUT_FILE%" -vf "noise=alls=10:allf=t, gblur=sigma=0.5:steps=1, unsharp=5:5:0.5:5:5:0.0, curves=m='0.05/0.1 0.95/0.9'" -c:v libx264 -preset slow -crf 20 -pix_fmt yuv420p "%OUTPUT_FILE%"

echo.
if %errorlevel% equ 0 (
    echo =======================================================
    echo [SUCCESS] VHS degradation complete!
    echo Your tape-simulated file is ready: 
    echo %OUTPUT_FILE%
    echo.
    echo Try feeding this back into the PCMF1 Simulator decoder!
    echo =======================================================
) else (
    echo [ERROR] FFmpeg encountered an issue during simulation.
    echo Make sure FFmpeg is installed and accessible in your system PATH.
)

pause
