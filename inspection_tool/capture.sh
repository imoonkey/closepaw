#!/bin/bash

# Android Screen + Accessibility Tree Capture Script
# Automatically captures screenshots and UI hierarchy when changes are detected
# Saves to .debug_data/ folder with timestamps

set -e

# Configuration
OUTPUT_DIR=".debug_data"
POLL_INTERVAL=${1:-2}  # Default: check every 2 seconds
DEVICE_DUMP_PATH="/sdcard/window_dump.xml"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PERCEPTOR_SCRIPT="${SCRIPT_DIR}/perceptor.py"

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Create output directory
mkdir -p "$OUTPUT_DIR"

# Store the last XML hash to detect changes
LAST_XML_HASH=""
CAPTURE_COUNT=0

echo -e "${BLUE}================================${NC}"
echo -e "${BLUE}Android Inspection Tool${NC}"
echo -e "${BLUE}================================${NC}"
echo -e "Output directory: ${GREEN}$OUTPUT_DIR${NC}"
echo -e "Poll interval: ${GREEN}${POLL_INTERVAL}s${NC}"
echo -e "Press ${YELLOW}Ctrl+C${NC} to stop"
echo ""

# Check adb connection
if ! adb devices | grep -q "device$"; then
    echo -e "${YELLOW}Warning: No Android device detected. Please connect a device.${NC}"
    echo "Waiting for device..."
    adb wait-for-device
fi

echo -e "${GREEN}Device connected!${NC}"
echo ""

# Function to capture current state
capture_state() {
    local timestamp=$(date +"%Y%m%d_%H%M%S")
    local temp_xml="/tmp/ui_dump_${timestamp}.xml"
    
    # Dump UI hierarchy
    adb shell uiautomator dump "$DEVICE_DUMP_PATH" > /dev/null 2>&1
    adb pull "$DEVICE_DUMP_PATH" "$temp_xml" > /dev/null 2>&1
    
    # Check if XML was captured successfully
    if [ ! -f "$temp_xml" ] || [ ! -s "$temp_xml" ]; then
        echo -e "${YELLOW}[${timestamp}] Failed to capture UI hierarchy, skipping...${NC}"
        rm -f "$temp_xml"
        return 1
    fi
    
    # Calculate hash of XML content (use md5 on macOS, md5sum on Linux)
    if command -v md5 &> /dev/null; then
        local current_hash=$(md5 -q "$temp_xml")
    else
        local current_hash=$(md5sum "$temp_xml" | cut -d' ' -f1)
    fi
    
    # Check if content changed
    if [ "$current_hash" = "$LAST_XML_HASH" ]; then
        rm -f "$temp_xml"
        return 0
    fi
    
    # Content changed - save screenshot and XML
    LAST_XML_HASH="$current_hash"
    CAPTURE_COUNT=$((CAPTURE_COUNT + 1))
    
    local base_name="${timestamp}_${CAPTURE_COUNT}"
    local screenshot_path="${OUTPUT_DIR}/${base_name}.png"
    local xml_path="${OUTPUT_DIR}/${base_name}.xml"
    local json_path="${OUTPUT_DIR}/${base_name}.json"
    
    # Capture screenshot
    adb exec-out screencap -p > "$screenshot_path"
    
    # Move XML to final location
    mv "$temp_xml" "$xml_path"
    
    # Run Perceptor to generate sanitized JSON
    if [ -f "$PERCEPTOR_SCRIPT" ]; then
        python3 "$PERCEPTOR_SCRIPT" "$xml_path" "$json_path" 2>/dev/null || true
    fi
    
    echo -e "${GREEN}[${timestamp}] Captured #${CAPTURE_COUNT}${NC}"
    echo -e "  Screenshot: ${screenshot_path}"
    echo -e "  XML: ${xml_path}"
    if [ -f "$json_path" ]; then
        local elem_count=$(python3 -c "import json; print(len(json.load(open('$json_path'))))" 2>/dev/null || echo "?")
        echo -e "  JSON: ${json_path} (${elem_count} elements)"
    fi
    
    return 0
}

# Function to cleanup on exit
cleanup() {
    echo ""
    echo -e "${BLUE}================================${NC}"
    echo -e "Capture session ended"
    echo -e "Total captures: ${GREEN}${CAPTURE_COUNT}${NC}"
    echo -e "Files saved to: ${GREEN}${OUTPUT_DIR}${NC}"
    echo -e "${BLUE}================================${NC}"
    exit 0
}

trap cleanup SIGINT SIGTERM

# Initial capture
echo "Starting capture loop..."
capture_state

# Main loop
while true; do
    sleep "$POLL_INTERVAL"
    capture_state
done

