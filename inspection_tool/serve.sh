#!/bin/bash

# Simple HTTP server to serve the viewer
# This is needed because browsers require HTTP for folder picker functionality

PORT=${1:-8080}

echo "Starting inspection viewer on http://localhost:${PORT}"
echo "Press Ctrl+C to stop"
echo ""

# Check if Python is available
if command -v python3 &> /dev/null; then
    cd "$(dirname "$0")"
    python3 -m http.server $PORT
elif command -v python &> /dev/null; then
    cd "$(dirname "$0")"
    python -m SimpleHTTPServer $PORT
else
    echo "Error: Python is required to run the server"
    exit 1
fi

