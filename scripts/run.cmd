@echo off
rem ------------------------------------------------------------------
rem Builds everything and runs the Phase 0 bridge smoke-test app.
rem ------------------------------------------------------------------
call "%~dp0dev-env.cmd"
if not defined JAVA_HOME (echo dev-env.cmd failed & exit /b 1)

call "%~dp0build-native.cmd"
if errorlevel 1 exit /b 1

cd /d "%~dp0.."
call mvn -B -q compile
if errorlevel 1 exit /b 1

java --enable-native-access=ALL-UNNAMED -cp "target\classes" dev.guitar.tabplayer.App
