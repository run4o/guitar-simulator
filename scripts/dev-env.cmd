@echo off
rem ------------------------------------------------------------------
rem Dev environment for the Guitar Tab Player project.
rem Portable toolchain: JDK in %USERPROFILE%\.jdks, Maven + MinGW in D:\Tools
rem ------------------------------------------------------------------
set "JAVA_HOME=C:\Users\Marto\.jdks\openjdk-26.0.2"
set "M2_HOME=D:\Tools\apache-maven-3.9.16"
set "PATH=%JAVA_HOME%\bin;%M2_HOME%\bin;D:\Tools\mingw64\bin;%PATH%"
