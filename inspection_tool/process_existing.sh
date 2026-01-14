#!/bin/bash

# Process existing XML files with perceptor.py to generate JSON files
# Usage: ./process_existing.sh [data_dir]

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DATA_DIR="${1:-$SCRIPT_DIR/.debug_data}"
PERCEPTOR="$SCRIPT_DIR/perceptor.py"

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

if [ ! -d "$DATA_DIR" ]; then
    echo "Error: Directory not found: $DATA_DIR"
    exit 1
fi

echo "Processing XML files in: $DATA_DIR"
echo ""

count=0
for xml_file in "$DATA_DIR"/*.xml; do
    if [ -f "$xml_file" ]; then
        base_name="${xml_file%.xml}"
        json_file="${base_name}.json"
        
        if [ -f "$json_file" ]; then
            echo -e "${YELLOW}Skip${NC}: $(basename "$json_file") (exists)"
        else
            python3 "$PERCEPTOR" "$xml_file" "$json_file"
            elem_count=$(python3 -c "import json; print(len(json.load(open('$json_file'))))" 2>/dev/null || echo "?")
            echo -e "${GREEN}Created${NC}: $(basename "$json_file") ($elem_count elements)"
            count=$((count + 1))
        fi
    fi
done

echo ""
echo "Processed $count new files"

