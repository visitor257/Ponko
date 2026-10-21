@echo off
REM Ponko Android build helper - MUST stay pure ASCII.
REM
REM Why vcvars is required:
REM   ggml-vulkan builds vulkan-shaders-gen as a Windows host tool via
REM   ExternalProject. Without the MSVC environment CMAKE_MAKE_PROGRAM is
REM   empty and CMake fails with:
REM     "CMake was unable to find a build program corresponding to Ninja"
REM
REM Why no Chinese characters in this file:
REM   cmd.exe parses .bat using the OEM codepage (GBK on this machine).
REM   A UTF-8 Chinese comment gets mangled, breaks the if-block structure,
REM   and the script loops forever re-running itself (seen: 22 MB of log).
REM
REM Why the project lives in C:\PonkoBuild:
REM   AGP refuses to build when the path contains non-ASCII characters.
REM
REM Usage:  tools\build-android.bat :app:assembleRelease
REM         tools\build-android.bat :plugin-echo:assembleRelease

setlocal
call "C:\Program Files (x86)\Microsoft Visual Studio\18\BuildTools\VC\Auxiliary\Build\vcvars64.bat" >nul 2>&1
if errorlevel 1 goto :novc

set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"
set "ANDROID_HOME=C:\Android"
set "ANDROID_SDK_ROOT=C:\Android"
set "PATH=C:\Android\cmake\3.31.6\bin;%PATH%"

if not exist "C:\PonkoBuild\settings.gradle.kts" goto :nosandbox
cd /d C:\PonkoBuild
call C:\gradle\gradle-9.7.1\bin\gradle.bat %* --console=plain --no-daemon
exit /b %errorlevel%

:novc
echo ERROR: vcvars64.bat not found or failed to load.
exit /b 1

:nosandbox
echo ERROR: C:\PonkoBuild not found. Sync the project there first
echo        ^(AGP rejects non-ASCII build paths^).
exit /b 1
