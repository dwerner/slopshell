#!/bin/bash

# Git Monitor Server Launch Script
# This runs on your workstation to monitor the git repository

echo "🔴 Git Monitor Server Launcher"
echo "=============================="

# Default values
PORT=${PORT:-8080}
REPO=${REPO:-.}

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        -p|--port)
            PORT="$2"
            shift 2
            ;;
        -r|--repo)
            REPO="$2"
            shift 2
            ;;
        -h|--help)
            echo "Usage: $0 [options]"
            echo "Options:"
            echo "  -p, --port PORT    Server port (default: 8080)"
            echo "  -r, --repo PATH    Repository path (default: current directory)"
            echo "  -h, --help         Show this help message"
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            exit 1
            ;;
    esac
done

# Check if we're in the slopshell directory
if [ ! -f "settings.gradle" ]; then
    echo "⚠️  Warning: Not in slopshell root directory"
    echo "   Current directory: $(pwd)"
fi

# Build the server if needed
echo "📦 Building Git Monitor Server..."
./gradlew :git-monitor-server:build

if [ $? -ne 0 ]; then
    echo "❌ Build failed"
    exit 1
fi

# Run the server
echo "🚀 Starting server on port $PORT for repository: $REPO"
echo "   Access the web interface at: http://localhost:$PORT"
echo "   Press Ctrl+C to stop the server"
echo ""

# Run with gradle
./gradlew :git-monitor-server:run --args="--port $PORT --repo $REPO"