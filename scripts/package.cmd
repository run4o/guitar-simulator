@echo off
rem ------------------------------------------------------------------
rem Builds a standalone desktop app image (build\installer\GuitarTabPlayer)
rem with jpackage: bundles the JRE runtime, the fat jar, and the native DLL.
rem Aborts loudly on any step failure and kills running instances first,
rem so a stale app image can never be produced.
rem ------------------------------------------------------------------
call "%~dp0dev-env.cmd"
if not defined JAVA_HOME (echo dev-env.cmd failed & exit /b 1)

taskkill /F /IM GuitarTabPlayer.exe >nul 2>&1

cd /d "%~dp0.."
call mvn -B -q -DskipTests clean package
if errorlevel 1 (echo MVN PACKAGE FAILED & exit /b 1)
if not exist "target\guitar-tab-player-0.1.0-SNAPSHOT.jar" (echo JAR MISSING & exit /b 1)

if exist build\pkg rmdir /s /q build\pkg
if exist build\pkg (echo PKG DIR LOCKED & exit /b 1)
mkdir build\pkg
copy /y target\guitar-tab-player-0.1.0-SNAPSHOT.jar build\pkg\ >nul
if errorlevel 1 (echo JAR COPY FAILED & exit /b 1)
copy /y build\native\guitar_engine.dll build\pkg\ >nul
if errorlevel 1 (echo DLL COPY FAILED & exit /b 1)

if exist build\installer rmdir /s /q build\installer
if exist build\installer (echo INSTALLER DIR LOCKED & exit /b 1)
jpackage ^
  --type app-image ^
  --name GuitarTabPlayer ^
  --app-version 0.1.0 ^
  --input build\pkg ^
  --main-jar guitar-tab-player-0.1.0-SNAPSHOT.jar ^
  --dest build\installer ^
  --java-options "--enable-native-access=ALL-UNNAMED"
if errorlevel 1 (echo JPACKAGE FAILED & exit /b 1)

echo.
echo Built build\installer\GuitarTabPlayer\GuitarTabPlayer.exe
