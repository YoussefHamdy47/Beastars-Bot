@echo off
title BeastarsBot Local Test

set DELAY=5
set MAX_DELAY=60
set ATTEMPT=0

:CHECK_INTERNET
cls
echo  BeastarsBot - Waiting for Internet Connection...
echo.

:: Check DNS resolution by curling discord.com 
curl -s --max-time 5 --head https://discord.com >nul 2>&1

if errorlevel 1 (
    set /a ATTEMPT+=1
    echo  Attempt #%ATTEMPT% - No connection yet.
    echo  Retrying in %DELAY% seconds...
    echo.

    if %DELAY% GEQ %MAX_DELAY% (
        echo  Max wait time reached
        echo  Press any key to reset and try again...
        pause >nul
        set DELAY=5
        set ATTEMPT=0
        goto CHECK_INTERNET
    )

    timeout /t %DELAY% >nul
    set /a DELAY+=5
    goto CHECK_INTERNET
)

cls
echo  Discord reachable. Initializing Bot...

:: Ensure .env is present in the current directory for the bot to read
if not exist ".env" (
    echo  WARNING: No .env file found in this directory!
    echo  The bot might fail to start if it cannot find the database or token.
    echo.
)

:: Run the shaded jar from the target folder
java -jar target\BeastarsBot-2.0.jar

echo.
echo Bot stopped or crashed. Press any key to exit...
pause >nul