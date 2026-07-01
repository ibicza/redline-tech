@echo off
setlocal EnableExtensions DisableDelayedExpansion

rem ============================================================
rem  Redline project archiver
rem  Put this file into the project root and run it.
rem  It does NOT modify the project.
rem ============================================================

rem Project root = folder where this .bat is located.
pushd "%~dp0" >nul || (
    echo ERROR: cannot enter script folder.
    pause
    exit /b 1
)
set "ROOT=%CD%"
for %%I in ("%ROOT%") do set "PROJECT_NAME=%%~nxI"

rem Timestamp for archive name.
for /f "usebackq delims=" %%I in (`powershell -NoProfile -ExecutionPolicy Bypass -Command "Get-Date -Format 'yyyy-MM-dd_HH-mm-ss'"`) do set "STAMP=%%I"
if not defined STAMP set "STAMP=archive"

set "ARCHIVE_DIR=%ROOT%\..\%PROJECT_NAME%_archives"
set "STAGE=%TEMP%\%PROJECT_NAME%_archive_stage_%STAMP%_%RANDOM%"
set "ARCHIVE=%ARCHIVE_DIR%\%PROJECT_NAME%_%STAMP%.zip"

if not exist "%ARCHIVE_DIR%" mkdir "%ARCHIVE_DIR%" >nul 2>nul
if exist "%STAGE%" rmdir /S /Q "%STAGE%" >nul 2>nul
mkdir "%STAGE%" >nul 2>nul

echo Project root: %ROOT%
echo Archive:      %ARCHIVE%
echo.
echo Copying project without heavy folders...
echo Skipped folders: .gradle .idea build run .git out .run repo .vscode bin
echo.

rem Robocopy codes 0..7 are success/warnings, 8+ are errors.
robocopy "%ROOT%" "%STAGE%" /E ^
    /XD .gradle .idea build run .git out .run repo .vscode bin ^
    /XF *.iws *.iml *.ipr .DS_Store ^
    /R:1 /W:1 /NFL /NDL /NP

set "RC=%ERRORLEVEL%"
if %RC% GEQ 8 (
    echo.
    echo ERROR: robocopy failed. Code: %RC%
    echo Temp folder was kept for inspection:
    echo %STAGE%
    popd >nul
    pause
    exit /b 1
)

echo.
echo Creating zip...
where tar >nul 2>nul
if errorlevel 1 (
    echo ERROR: tar.exe was not found. Windows 10/11 normally includes it.
    echo Temp folder was kept for inspection:
    echo %STAGE%
    popd >nul
    pause
    exit /b 1
)

pushd "%STAGE%" >nul || (
    echo ERROR: cannot enter temp folder.
    popd >nul
    pause
    exit /b 1
)

tar -a -c -f "%ARCHIVE%" .
set "TAR_RC=%ERRORLEVEL%"
popd >nul

if not "%TAR_RC%"=="0" (
    echo.
    echo ERROR: archive creation failed. Code: %TAR_RC%
    echo Temp folder was kept for inspection:
    echo %STAGE%
    popd >nul
    pause
    exit /b 1
)

if not exist "%ARCHIVE%" (
    echo.
    echo ERROR: archive file was not created.
    echo Temp folder was kept for inspection:
    echo %STAGE%
    popd >nul
    pause
    exit /b 1
)

rmdir /S /Q "%STAGE%" >nul 2>nul

echo.
echo Done.
echo Archive created:
echo %ARCHIVE%
echo.
popd >nul
pause
exit /b 0
