@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"

call :findJdk
if not defined JDKBIN goto :nojdk
echo Using JDK: "%JDKBIN%"

REM ---------- 1. master icon ----------
if not exist assets mkdir assets
if not exist assets\icon.png (
    echo.
    echo Missing master icon.
    echo    1. Place a square PNG at:  %CD%\assets\icon.png
    echo    2. Re-run this script.
    echo.
    exit /b 1
)

REM ---------- 2. compile ----------
if exist out rmdir /s /q out
mkdir out
dir /s /b src\*.java > sources.txt
"%JDKBIN%\javac.exe" -d out -encoding UTF-8 @sources.txt
if errorlevel 1 goto :fail

REM ---------- 3. icons from assets\icon.png ----------
echo Building icons...
"%JDKBIN%\java.exe" -cp out com.plupot.build.IconBuilder
if errorlevel 1 goto :fail

REM ---------- 4. resources onto classpath output ----------
if exist out\com\plupot\res rmdir /s /q out\com\plupot\res
xcopy /E /I /Y src\com\plupot\res out\com\plupot\res >nul

REM ---------- 5. jar ----------
"%JDKBIN%\jar.exe" --create --file Plupot.jar --main-class com.plupot.Main -C out .
if errorlevel 1 goto :fail
echo Wrote Plupot.jar

REM ---------- 6. reset saved high score for a fresh build ----------
"%JDKBIN%\java.exe" -cp out com.plupot.build.ResetSave
if errorlevel 1 goto :fail

REM ---------- 7. package ----------
if not exist "%JDKBIN%\jpackage.exe" (
    echo.
    echo jpackage not found ^(needs JDK 14+^).
    echo Jar is ready: run it with  java -jar Plupot.jar
    goto :eof
)

if exist app  rmdir /s /q app
if exist dist rmdir /s /q dist
mkdir app
copy /Y Plupot.jar app\Plupot.jar >nul

"%JDKBIN%\jpackage.exe" --type app-image --name Plupot --app-version 1.0.0 ^
  --input app --main-jar Plupot.jar --main-class com.plupot.Main ^
  --add-modules java.desktop --icon icon\icon.ico --dest dist
if errorlevel 1 goto :fail

echo.
echo Built: dist\Plupot\Plupot.exe
goto :eof

REM ================= helpers =================

:findJdk
set "JDKBIN="
if defined JAVA_HOME call :tryBin "%JAVA_HOME%\bin"

for /f "delims=" %%I in ('where javac 2^>nul') do (
    set "cand=%%~dpI"
    call :tryBin "!cand:~0,-1!"
)
for /f "delims=" %%I in ('where java 2^>nul') do (
    set "cand=%%~dpI"
    call :tryBin "!cand:~0,-1!"
)

for /d %%D in ("C:\Program Files\Java\jdk*") do call :tryBin "%%~D\bin"
for /d %%D in ("C:\Program Files\Eclipse Adoptium\jdk*") do call :tryBin "%%~D\bin"
for /d %%D in ("C:\Program Files\Microsoft\jdk*") do call :tryBin "%%~D\bin"
for /d %%D in ("C:\Program Files\Amazon Corretto\jdk*") do call :tryBin "%%~D\bin"
for /d %%D in ("%USERPROFILE%\.jdks\*") do call :tryBin "%%~D\bin"
for /d %%D in ("%LOCALAPPDATA%\Programs\Eclipse Adoptium\jdk*") do call :tryBin "%%~D\bin"
goto :eof

:tryBin
if defined JDKBIN goto :eof
if exist "%~1\javac.exe" if exist "%~1\jar.exe" set "JDKBIN=%~1"
goto :eof

:nojdk
echo.
echo ERROR: no JDK found.
echo   - Install JDK 17+ : https://adoptium.net
echo   - or set JAVA_HOME to the JDK folder, e.g.
echo         set "JAVA_HOME=C:\Program Files\Java\jdk-21"
echo.
exit /b 1

:fail
echo BUILD FAILED
exit /b 1