@echo off
rem ------------------------------------------------------------------
rem Launches the JavaFX tab player UI.
rem ------------------------------------------------------------------
call "%~dp0dev-env.cmd"
if not defined JAVA_HOME (echo dev-env.cmd failed & exit /b 1)

cd /d "%~dp0.."
call mvn -B -q compile
if errorlevel 1 exit /b 1

if not exist target\cp.txt (
    call mvn -B -q dependency:build-classpath -Dmdep.outputFile=target\cp.txt -DincludeScope=runtime
    if errorlevel 1 exit /b 1
)
set /p DEPCP=<target\cp.txt

java --enable-native-access=ALL-UNNAMED %EXTRA_JAVA_ARGS% -cp "target\classes;%DEPCP%" dev.guitar.tabplayer.ui.UiLauncher
