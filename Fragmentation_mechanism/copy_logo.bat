@echo off
chcp 65001 >nul 2>&1
echo 正在复制模组封面图片...
copy "C:\Users\我的电脑\Desktop\图片\4CA29E18B6988C78FC04769B37BD616C.png" "C:\mods\Fragmentation_mechanism\src\main\resources\logo.png" >nul 2>&1
if %ERRORLEVEL% == 0 (
    echo [成功] logo.png 已复制到资源目录
) else (
    echo [失败] 无法复制图片，请检查桌面"图片"文件夹是否存在
)
pause
