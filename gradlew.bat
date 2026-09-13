@echo off
setlocal
set VERSION=9.6.0
set CHECKSUM=bbaeb2fef8710818cf0e261201dab964c572f92b942812df0c3620d62a529a01
set BASE=%USERPROFILE%\.gradle\wrapper\dists\localai-gradle-%VERSION%
set GRADLE_BIN=%BASE%\gradle-%VERSION%\bin\gradle.bat
set ZIP=%BASE%\gradle-%VERSION%-bin.zip
set MARKER=%BASE%\.verified-%CHECKSUM%

if exist "%GRADLE_BIN%" if exist "%MARKER%" goto run

if not exist "%BASE%" mkdir "%BASE%"
if exist "%BASE%\gradle-%VERSION%" rmdir /s /q "%BASE%\gradle-%VERSION%"
if exist "%MARKER%" del /q "%MARKER%"

powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $u='https://services.gradle.org/distributions/gradle-%VERSION%-bin.zip'; $z='%ZIP%'; $expected='%CHECKSUM%'; Invoke-WebRequest -UseBasicParsing $u -OutFile $z; $actual=(Get-FileHash -Algorithm SHA256 $z).Hash.ToLowerInvariant(); if ($actual -ne $expected) { Remove-Item -Force $z; throw ('Checksum SHA-256 inválido. Esperado {0}, recebido {1}' -f $expected,$actual) }; Expand-Archive -Force $z '%BASE%'; New-Item -ItemType File -Force '%MARKER%' | Out-Null"
if errorlevel 1 exit /b 1
if not exist "%GRADLE_BIN%" (
  echo Gradle %VERSION% não foi extraído corretamente. 1>&2
  exit /b 1
)

:run
call "%GRADLE_BIN%" %*
set EXIT_CODE=%ERRORLEVEL%
endlocal & exit /b %EXIT_CODE%
