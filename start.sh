#!/bin/bash
# BeastarsBot Telemetry Engine - Linux Edition

DELAY=5
MAX_DELAY=60
ATTEMPT=0

while true; do
    clear
    echo " BeastarsBot - Waiting for Internet Connection..."
    echo ""

    if curl -s --max-time 5 --head https://discord.com >/dev/null 2>&1; then
        clear
        echo " Discord reachable. Initializing Telemetry Engine..."
        
        cd "$(dirname "$0")" || exit
        
        # Ensure the .env file is present
        if [ ! -f ".env" ]; then
            echo " WARNING: No .env file found! The bot will fail to start."
        fi
        
        java -jar BeastarsBot-2.0.jar
        
        echo "Bot stopped. Press [Enter] to close..."
        read -r
        exit 0
    else
        ((ATTEMPT++))
        echo " Attempt #$ATTEMPT - No connection yet."
        echo " Retrying in $DELAY seconds..."
        echo ""

        if [ "$DELAY" -ge "$MAX_DELAY" ]; then
            echo " Max wait time reached."
            echo " Press [Enter] to reset and try again..."
            read -r
            DELAY=5
            ATTEMPT=0
            continue
        fi

        sleep "$DELAY"
        ((DELAY+=5))
    fi
done