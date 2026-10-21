@echo off
REM ============================================================
REM  InspectWeaponCondition.bat
REM  Scans the tacz_tactical_breaching JAR for "weapon_condition"
REM  and related keywords, then disassembles matching classes.
REM  Output: weapon_condition_scan.txt
REM ============================================================

cd /d "C:\mods\Fragmentation_mechanism"

echo [1/3] Compiling InspectWeaponCondition.java...
javac InspectWeaponCondition.java
if errorlevel 1 (
    echo ERROR: Compilation failed. Make sure Java 17 JDK is installed.
    pause
    exit /b 1
)

echo [2/3] Running inspection on the JAR...
java InspectWeaponCondition "C:\Users\我的电脑\Desktop\兼容\tacz_tactical_breaching-forge-1.20.1-1.0.4.jar"

echo [3/3] Done. Full results saved to: weapon_condition_scan.txt
echo.
echo Opening weapon_condition_scan.txt for review...
type weapon_condition_scan.txt | more
pause
