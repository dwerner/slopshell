#!/bin/bash
# Smart deployment script that alternates between ConnectBot A and B

source android-env.sh

LAST_DEPLOYMENT_FILE=".last-deployment"

# Read last deployment
if [ -f "$LAST_DEPLOYMENT_FILE" ]; then
    LAST_DEPLOYMENT=$(cat "$LAST_DEPLOYMENT_FILE")
else
    LAST_DEPLOYMENT=""
fi

# Determine which version to deploy
if [ "$LAST_DEPLOYMENT" = "A" ]; then
    echo "📋 Last deployment was A, now deploying B..."
    echo "======================================="
    ./gradlew deployB
    if [ $? -eq 0 ]; then
        echo "B" > "$LAST_DEPLOYMENT_FILE"
    fi
else
    echo "📋 Last deployment was ${LAST_DEPLOYMENT:-none}, now deploying A..."
    echo "======================================="
    ./gradlew deployA
    if [ $? -eq 0 ]; then
        echo "A" > "$LAST_DEPLOYMENT_FILE"
    fi
fi