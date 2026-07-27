@echo off
chcp 65001 >nul

echo ================================================
echo BTRemote 编译脚本
echo ================================================
echo.

set "PROJECT_DIR=%~dp0"
set "GRADLE_DIR=%PROJECT_DIR%tools\gradle-8.2\bin"
set "SDK_DIR=%PROJECT_DIR%sdk"

if exist "%SDK_DIR%" (
    echo [OK] Android SDK 路径: %SDK_DIR%
) else (
    echo [WARN] Android SDK 未找到: %SDK_DIR%
    echo        请设置 ANDROID_HOME 环境变量
    pause
    exit /b 1
)

set "JAVA_HOME=C:\Program Files\Java\jdk-17"
if exist "%JAVA_HOME%" (
    echo [OK] JDK 路径: %JAVA_HOME%
) else (
    echo [WARN] JDK 未找到: %JAVA_HOME%
    echo        请设置 JAVA_HOME 环境变量
    pause
    exit /b 1
)

set "ANDROID_HOME=%SDK_DIR%"
set "ANDROID_SDK_ROOT=%SDK_DIR%"

echo.
echo 正在编译 debug APK...
echo ================================================

cd /d "%PROJECT_DIR%"
"%GRADLE_DIR%\gradle.bat" assembleDebug --no-daemon --console=plain

if %errorlevel% equ 0 (
    echo.
    echo ================================================
    echo 编译成功！
    echo APK 位置: app\build\outputs\apk\debug\app-debug.apk
    echo ================================================
) else (
    echo.
    echo ================================================
    echo 编译失败！
    echo ================================================
)

pause