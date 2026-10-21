@echo off
set JAVA_HOME=C:\Program Files\Java\jdk-17
set PATH=%JAVA_HOME%\bin;%PATH%
cd /d C:\mods\Fragmentation_mechanism
for /f "tokens=2 delims==" %%V in ('findstr /b "mod_version=" gradle.properties') do set MOD_VERSION=%%V
echo Building Fragmentation Mechanism Mod...
.\gradlew.bat --no-daemon build
echo.
echo Build completed!
echo Output jar: build\libs\fragmentation-%MOD_VERSION%.jar
pause
