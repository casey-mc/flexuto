@echo off
setlocal
rem Installs the debug APK on the phone over Wi-Fi.
rem   deploy-phone.bat          -> install the last built APK
rem   deploy-phone.bat build    -> build first, then install
rem
rem First time: on the phone, Settings > Developer options > Wireless debugging (on),
rem then tap "Pair device with pairing code" and keep that screen open.

set ADB=C:\Users\casey\AppData\Local\Android\Sdk\platform-tools\adb.exe
set PHONE_IP=192.168.50.180
set APK=%~dp0build\outputs\apk\unstable\debug\flexuto-unstable-debug.apk

if /i "%1"=="build" (
    echo === Building APK...
    set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot"
    call "%~dp0gradlew.bat" assembleUnstableDebug --console=plain -q
    if errorlevel 1 (
        echo Build failed.
        pause
        exit /b 1
    )
)

if not exist "%APK%" (
    echo APK not found at %APK%
    echo Run "deploy-phone.bat build" first.
    pause
    exit /b 1
)

echo === Looking for the phone...
"%ADB%" devices | findstr /r "device$" >nul
if not errorlevel 1 goto :install

rem Try the fixed port left over from a previous session.
"%ADB%" connect %PHONE_IP%:5555 | findstr /c:"connected" >nul
if not errorlevel 1 goto :install

echo.
echo Phone not reachable yet. On the phone open Developer options ^> Wireless debugging.
echo.
set /p CONNPORT=Port shown under "IP address & Port" on the Wireless debugging screen:
"%ADB%" connect %PHONE_IP%:%CONNPORT% | findstr /c:"connected" >nul
if not errorlevel 1 goto :install

echo.
echo Still not connected, so the phone probably needs pairing first.
echo Tap "Pair device with pairing code" on the phone.
set /p PAIRPORT=Pairing port (the one in the pairing dialog):
set /p PAIRCODE=Pairing code:
"%ADB%" pair %PHONE_IP%:%PAIRPORT% %PAIRCODE%
if errorlevel 1 (
    echo Pairing failed.
    pause
    exit /b 1
)
"%ADB%" connect %PHONE_IP%:%CONNPORT% | findstr /c:"connected" >nul
if errorlevel 1 (
    echo Could not connect after pairing. Check that the PC and phone are on the same Wi-Fi.
    pause
    exit /b 1
)

:install
rem The same phone can show up twice (USB and Wi-Fi); use the first one listed.
set SERIAL=
for /f "tokens=1,2" %%a in ('"%ADB%" devices') do (
    if "%%b"=="device" if not defined SERIAL set SERIAL=%%a
)
if not defined SERIAL (
    echo No device is ready. Check the phone for an authorization prompt.
    pause
    exit /b 1
)
echo === Installing to %SERIAL%...
"%ADB%" -s %SERIAL% install -r "%APK%"
if errorlevel 1 (
    echo Install failed.
    pause
    exit /b 1
)
echo === Done. Enable Flexuto under Settings ^> System ^> Languages ^& input if you haven't yet.
pause
