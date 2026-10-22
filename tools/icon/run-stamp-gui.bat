@echo off
REM Ponko icon signature stamper -- double-click to run.
REM
REM Why a .bat: a GUI launched from an agent session can be reclaimed when that
REM session ends. Double-clicking starts it under Explorer instead, so it lives
REM as long as you keep it open.
REM
REM Pure ASCII only. cmd.exe parses this file with the OEM codepage (GBK), and a
REM UTF-8 Chinese comment gets mangled mid-line, which can break if-blocks.

setlocal
cd /d "%~dp0"

set PY=python
where python >nul 2>&1
if errorlevel 1 (
    if exist "C:\Python314\python.exe" (
        set PY=C:\Python314\python.exe
    ) else (
        echo ERROR: python not found.
        pause
        exit /b 1
    )
)

echo Starting stamp tool...
echo.
"%PY%" stamp_gui.py
set RC=%errorlevel%

echo.
if not "%RC%"=="0" (
    echo Exited with code %RC%.
) else (
    echo Done. Rebuild the APK to see the result:
    echo   cd C:\PonkoBuild ^&^& tools\build-android.bat :app:assembleRelease
)
pause
