@echo off
rem ------------------------------------------------------------------
rem Builds native/guitar_engine.dll into build\native\ using CMake + Ninja.
rem ------------------------------------------------------------------
call "%~dp0dev-env.cmd"
if not defined JAVA_HOME (echo dev-env.cmd failed & exit /b 1)

set "ROOT=%~dp0.."
cmake -S "%ROOT%\native" -B "%ROOT%\build\native" -G Ninja -DCMAKE_BUILD_TYPE=Release
if errorlevel 1 exit /b 1
cmake --build "%ROOT%\build\native"
if errorlevel 1 exit /b 1
echo.
echo Built %ROOT%\build\native\guitar_engine.dll
