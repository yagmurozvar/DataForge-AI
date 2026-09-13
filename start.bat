@echo off
setlocal EnableExtensions EnableDelayedExpansion
chcp 65001 >nul
cd /d "%~dp0"

set "LANG_FILE=%~dp0src\main\resources\i18n\tr_TR.json"
set "APP_TITLE=Pilsan Datasheet"
set "L_SETUP_PREPARING=İlk kurulum hazırlanıyor."
set "L_SETUP_LOG=Kurulum kaydı: {log}"
set "L_JDK_DOWNLOADING=[1/2] Eclipse Temurin JDK 21 indiriliyor..."
set "L_JDK_READY=JDK 21 hazır."
set "L_MAVEN_DOWNLOADING=[2/2] Apache Maven {version} indiriliyor..."
set "L_MAVEN_READY=Maven {version} hazır."
set "L_ENVIRONMENT_READY=Çalışma ortamı hazırlandı. Uygulama başlatılıyor..."
set "L_SETUP_FAILED=Kurulum tamamlanamadı."
set "L_DETAILS=Ayrıntılı hata kaydı:"
set "L_SEND_LOG=Bu dosyayı paylaşarak hangi adımın hata verdiğini kontrol edebilirsiniz."
set "L_SETUP_ERROR=Gerekli çalışma ortamı hazırlanamadı. Ayrıntılar proje klasöründeki tarihli log dosyasına yazıldı."
set "L_RUN_ERROR=Uygulama başlatılamadı. Ayrıntılar: {log}"
if exist "%LANG_FILE%" (
    for /f "usebackq tokens=1,* delims=|" %%A in (`powershell -NoProfile -ExecutionPolicy Bypass -Command "$j=Get-Content -LiteralPath $env:LANG_FILE -Raw -Encoding UTF8 ^| ConvertFrom-Json; @('APP_TITLE^|'+$j.app.title,'L_SETUP_PREPARING^|'+$j.launcher.setupPreparing,'L_SETUP_LOG^|'+$j.launcher.setupLog,'L_JDK_DOWNLOADING^|'+$j.launcher.jdkDownloading,'L_JDK_READY^|'+$j.launcher.jdkReady,'L_MAVEN_DOWNLOADING^|'+$j.launcher.mavenDownloading,'L_MAVEN_READY^|'+$j.launcher.mavenReady,'L_ENVIRONMENT_READY^|'+$j.launcher.environmentReady,'L_SETUP_FAILED^|'+$j.launcher.setupFailed,'L_DETAILS^|'+$j.launcher.details,'L_SEND_LOG^|'+$j.launcher.sendLog,'L_SETUP_ERROR^|'+$j.launcher.setupErrorDialog,'L_RUN_ERROR^|'+$j.launcher.runErrorDialog)"`) do set "%%A=%%B"
)

if not defined PILSAN_LOG_FILE (
    for /f "usebackq delims=" %%I in (`powershell -NoProfile -ExecutionPolicy Bypass -Command "Get-Date -Format 'ddMMyyyy_HHmm'"`) do set "PILSAN_LOG_FILE=%~dp0%%I_logs.log"
)
set "LOG_FILE=%PILSAN_LOG_FILE%"
set "PILSAN_PROJECT_DIR=%~dp0"
set "TOOLS_DIR=%~dp0.tools"
set "JDK_DIR=%TOOLS_DIR%\jdk-21"
set "JDK_ZIP=%TOOLS_DIR%\jdk-21.zip"
set "JDK_EXTRACT=%TOOLS_DIR%\jdk-extract"
set "JDK_URL=https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse"
set "MAVEN_VERSION=3.9.16"
set "MAVEN_DIR=%TOOLS_DIR%\apache-maven-%MAVEN_VERSION%"
set "MAVEN_ZIP=%TOOLS_DIR%\apache-maven-%MAVEN_VERSION%-bin.zip"
set "MAVEN_SHA=%TOOLS_DIR%\apache-maven-%MAVEN_VERSION%-bin.zip.sha512"
set "MAVEN_URL=https://dlcdn.apache.org/maven/maven-3/%MAVEN_VERSION%/binaries/apache-maven-%MAVEN_VERSION%-bin.zip"
set "MVN=%MAVEN_DIR%\bin\mvn.cmd"

if not exist "%TOOLS_DIR%" mkdir "%TOOLS_DIR%" >nul 2>nul

if /I "%~1"=="--setup" goto setup

if exist "%JDK_DIR%\bin\java.exe" if exist "%MVN%" goto run_app

call "%~f0" --setup
if errorlevel 1 exit /b 1

:run_app
echo Uygulama başlatiliyor. Lütfen bekleyin...
set "JAVA_HOME=%JDK_DIR%"
set "PATH=%JAVA_HOME%\bin;%MAVEN_DIR%\bin;%PATH%"

>> "%LOG_FILE%" echo [%date% %time%] Pilsan Datasheet baslatiliyor.
>> "%LOG_FILE%" echo JAVA_HOME=%JAVA_HOME%
"%JAVA_HOME%\bin\java.exe" -version >> "%LOG_FILE%" 2>&1
call "%MVN%" -version >> "%LOG_FILE%" 2>&1

echo Derleme ve çaliştirma işlemleri başladi.
call "%MVN%" clean javafx:run
set "EXIT_CODE=%ERRORLEVEL%"

if not "%EXIT_CODE%"=="0" (
    echo.
    echo =======================================================
    echo UYGULAMA ÇALIŞTIRILIRKEN BIR HATA OLUŞTU!
    echo =======================================================
    echo.
    echo Lütfen yukaridaki Maven hata mesajini kontrol edin.
    pause
    goto run_failed
)
exit /b 0

:setup
cls
echo !L_SETUP_PREPARING!
echo.
set "MSG=!L_SETUP_LOG:{log}=%LOG_FILE%!"
echo !MSG!
echo.
> "%LOG_FILE%" echo [%date% %time%] Ilk kurulum baslatildi.

if not exist "%JDK_DIR%\bin\java.exe" (
    call :install_jdk
    if errorlevel 1 goto setup_failed
) else (
    echo !L_JDK_READY!
    >> "%LOG_FILE%" echo JDK 21 zaten mevcut.
)

if not exist "%MVN%" (
    call :install_maven
    if errorlevel 1 goto setup_failed
) else (
    set "MSG=!L_MAVEN_READY:{version}=%MAVEN_VERSION%!"
echo !MSG!
    >> "%LOG_FILE%" echo Maven zaten mevcut.
)

echo.
echo !L_ENVIRONMENT_READY!
>> "%LOG_FILE%" echo [%date% %time%] Ilk kurulum basariyla tamamlandi.
timeout /t 1 /nobreak >nul
exit /b 0

:install_jdk
echo !L_JDK_DOWNLOADING!
>> "%LOG_FILE%" echo [%date% %time%] JDK indirme basladi: %JDK_URL%
if exist "%JDK_ZIP%" del /q "%JDK_ZIP%" >nul 2>nul
if exist "%JDK_EXTRACT%" rmdir /s /q "%JDK_EXTRACT%"
if exist "%JDK_DIR%" rmdir /s /q "%JDK_DIR%"

call :download "%JDK_URL%" "%JDK_ZIP%"
if errorlevel 1 (
    >> "%LOG_FILE%" echo JDK indirilemedi.
    exit /b 1
)

powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $file=Get-Item -LiteralPath '%JDK_ZIP%'; if($file.Length -lt 50000000){throw 'Indirilen JDK arsivi beklenenden kucuk.'}; Expand-Archive -LiteralPath '%JDK_ZIP%' -DestinationPath '%JDK_EXTRACT%' -Force; $root=Get-ChildItem -LiteralPath '%JDK_EXTRACT%' -Directory | Where-Object { Test-Path (Join-Path $_.FullName 'bin\java.exe') } | Select-Object -First 1; if($null -eq $root){throw 'JDK arsivinde java.exe bulunamadi.'}; Move-Item -LiteralPath $root.FullName -Destination '%JDK_DIR%' -Force" >> "%LOG_FILE%" 2>&1
if errorlevel 1 exit /b 1

rmdir /s /q "%JDK_EXTRACT%" >nul 2>nul
del /q "%JDK_ZIP%" >nul 2>nul

if not exist "%JDK_DIR%\bin\java.exe" exit /b 1
"%JDK_DIR%\bin\java.exe" -version >> "%LOG_FILE%" 2>&1
if errorlevel 1 exit /b 1

echo !L_JDK_READY!
>> "%LOG_FILE%" echo JDK kurulumu tamamlandi.
exit /b 0

:install_maven
set "MSG=!L_MAVEN_DOWNLOADING:{version}=%MAVEN_VERSION%!"
echo !MSG!
>> "%LOG_FILE%" echo [%date% %time%] Maven indirme basladi: %MAVEN_URL%
if exist "%MAVEN_ZIP%" del /q "%MAVEN_ZIP%" >nul 2>nul
if exist "%MAVEN_SHA%" del /q "%MAVEN_SHA%" >nul 2>nul
if exist "%MAVEN_DIR%" rmdir /s /q "%MAVEN_DIR%"

call :download "%MAVEN_URL%" "%MAVEN_ZIP%"
if errorlevel 1 exit /b 1
call :download "%MAVEN_URL%.sha512" "%MAVEN_SHA%"
if errorlevel 1 exit /b 1

powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $expected=(Get-Content -LiteralPath '%MAVEN_SHA%' -Raw).Trim().Split()[0].ToUpperInvariant(); $actual=(Get-FileHash -Algorithm SHA512 -LiteralPath '%MAVEN_ZIP%').Hash.ToUpperInvariant(); if($actual -ne $expected){throw 'Maven SHA-512 dogrulamasi basarisiz.'}; Expand-Archive -LiteralPath '%MAVEN_ZIP%' -DestinationPath '%TOOLS_DIR%' -Force" >> "%LOG_FILE%" 2>&1
if errorlevel 1 exit /b 1

del /q "%MAVEN_ZIP%" >nul 2>nul
del /q "%MAVEN_SHA%" >nul 2>nul

if not exist "%MVN%" exit /b 1
set "JAVA_HOME=%JDK_DIR%"
call "%MVN%" -version >> "%LOG_FILE%" 2>&1
if errorlevel 1 exit /b 1

set "MSG=!L_MAVEN_READY:{version}=%MAVEN_VERSION%!"
echo !MSG!
>> "%LOG_FILE%" echo Maven kurulumu tamamlandi.
exit /b 0

:download
set "DOWNLOAD_URL=%~1"
set "DOWNLOAD_FILE=%~2"
>> "%LOG_FILE%" echo Indiriliyor: %DOWNLOAD_URL%

powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; [Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -UseBasicParsing -Uri '%DOWNLOAD_URL%' -OutFile '%DOWNLOAD_FILE%'" >> "%LOG_FILE%" 2>&1
if not errorlevel 1 if exist "%DOWNLOAD_FILE%" exit /b 0

>> "%LOG_FILE%" echo PowerShell indirmesi basarisiz, curl deneniyor.
where curl.exe >nul 2>nul
if errorlevel 1 exit /b 1
curl.exe -fL --retry 3 --retry-delay 2 --connect-timeout 30 -o "%DOWNLOAD_FILE%" "%DOWNLOAD_URL%" >> "%LOG_FILE%" 2>&1
if errorlevel 1 exit /b 1
if not exist "%DOWNLOAD_FILE%" exit /b 1
exit /b 0

:setup_failed
echo.
echo !L_SETUP_FAILED!
echo !L_DETAILS!
echo %LOG_FILE%
echo.
echo !L_SEND_LOG!
powershell -NoProfile -ExecutionPolicy Bypass -Command "Add-Type -AssemblyName PresentationFramework; [System.Windows.MessageBox]::Show($env:L_SETUP_ERROR,$env:APP_TITLE,'OK','Error') | Out-Null"
pause
exit /b 1

:run_failed
set "EXIT_CODE=%ERRORLEVEL%"
set "L_RUN_ERROR_RENDERED=!L_RUN_ERROR:{log}=%LOG_FILE%!"
powershell -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -Command "Add-Type -AssemblyName PresentationFramework; [System.Windows.MessageBox]::Show($env:L_RUN_ERROR_RENDERED,$env:APP_TITLE,'OK','Error') | Out-Null"
exit /b %EXIT_CODE%