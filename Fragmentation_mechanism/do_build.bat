@echo off
set JAVA_HOME=C:\Program Files\Java\jdk-17
set PATH=%JAVA_HOME%\bin;%PATH%
cd /d C:\mods\Fragmentation_mechanism
echo Starting build... > build_output.log
call gradlew.bat --no-daemon clean build -x test --console=plain >> build_output.log 2>>&1
echo Build exit code: %ERRORLEVEL% >> build_output.log
echo FINISHED >> build_output.log
