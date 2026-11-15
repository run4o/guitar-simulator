@echo off
rem ------------------------------------------------------------------
rem Builds a standalone desktop app image (build\installer\GuitarTabPlayer)
rem with jpackage: bundles the JRE runtime, JavaFX jars, and the native DLL.
rem (WiX is only needed for .msi installers; app-image requires none.)
rem ------------------------------------------------------------------
call "%~dp0dev-env.cmd"
if not defined JAVA_HOME (echo dev-env.cmd failed & exit /b 1)

cd /d "%~dp0.."
call mvn -B -q -DskipTests package
if errorlevel 1 exit /b 1
if exist build\pkg rmdir /s /q build\pkg
mkdir build\pkg
copy /y target\guitar-tab-player-0.1.0-SNAPSHOT.jar build\pkg\ >nul
copy /y build\native\guitar_engine.dll build\pkg\ >nul

if exist build\installer rmdir /s /q build\installer
jpackage ^
  --type app-image ^
  --name GuitarTabPlayer ^
  --app-version 0.1.0 ^
  --input build\pkg ^
  --main-jar guitar-tab-player-0.1.0-SNAPSHOT.jar ^
  --dest build\installer ^
  --java-options "--enable-native-access=ALL-UNNAMED"
if errorlevel 1 exit /b 1

echo.
echo Built build\installer\GuitarTabPlayer\GuitarTabPlayer.exe
