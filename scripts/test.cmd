@echo off
rem ------------------------------------------------------------------
rem Builds the native DLL, then compiles and runs the Java tests.
rem ------------------------------------------------------------------
call "%~dp0dev-env.cmd"
if not defined JAVA_HOME (echo dev-env.cmd failed & exit /b 1)

call "%~dp0build-native.cmd"
if errorlevel 1 exit /b 1

cd /d "%~dp0.."
call mvn -B test
