@echo off
setlocal
set VERSION=9.6.0
set BASE=%USERPROFILE%\.gradle\wrapper\dists\localai-gradle-%VERSION%
set GRADLE_BIN=%BASE%\gradle-%VERSION%\bin\gradle.bat
if not exist "%GRADLE_BIN%" (
  if not exist "%BASE%" mkdir "%BASE%"
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$u='https://services.gradle.org/distributions/gradle-%VERSION%-bin.zip'; $z='%BASE%\gradle-%VERSION%-bin.zip'; Invoke-WebRequest -UseBasicParsing $u -OutFile $z; Expand-Archive -Force $z '%BASE%'"
  if errorlevel 1 exit /b 1
)
call "%GRADLE_BIN%" %*
endlocal
