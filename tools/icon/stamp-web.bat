@echo off
REM Ponko web icon stamper - MUST stay pure ASCII.
REM
REM Why no Chinese in this file: cmd.exe parses .bat with the OEM codepage (GBK),
REM so UTF-8 Chinese breaks if-blocks and can make a script re-run itself.
REM
REM Why %~dp0 instead of a literal path: this repo lives under a directory whose
REM name contains Chinese characters. %~dp0 is expanded by cmd itself, so the
REM non-ASCII path never appears in the file.

cd /d "%~dp0..\.."

if not exist "%~dp0stamp-web.py" goto :noscript

start "" "C:\Python314\pythonw.exe" "%~dp0stamp-web.py"
exit /b 0

:noscript
echo ERROR: stamp-web.py not found next to this .bat
pause
exit /b 1
