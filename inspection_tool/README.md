# Android Perception Inspector

A comprehensive tool for capturing, viewing, and tuning Android accessibility perception. Designed to help optimize the Perceptor's filtering logic by providing visual feedback on what UI elements are preserved.

## Components

### 1. `capture.sh` - Screen Capture Script

Automatically captures screenshots, UI accessibility trees, and runs Perceptor to generate filtered JSON.

**Usage:**
```bash
./capture.sh          # Poll every 2 seconds
./capture.sh 5        # Poll every 5 seconds
```

**Output (per capture):**
- `{timestamp}.png` - Screenshot
- `{timestamp}.xml` - Raw accessibility tree
- `{timestamp}.json` - Perceptor-filtered elements

### 2. `perceptor.py` - Python Perceptor Implementation

Standalone Python version of `Perceptor.kt` for rapid iteration. Modify the tunable parameters at the top of the file:

```python
# TUNABLE PARAMETERS
MAX_ELEMENTS = 80           # Maximum elements to keep
MAX_STRING_LENGTH = 60      # Max text length
FILTER_CLICKABLE = True     # Keep clickable elements
FILTER_EDITABLE = True      # Keep editable elements
FILTER_SCROLLABLE = True    # Keep scrollable elements
FILTER_HAS_TEXT = True      # Keep elements with text
FILTER_HAS_DESC = True      # Keep elements with content-desc
FILTER_HAS_RESOURCE_ID = False  # Keep elements with resource-id
MIN_BOUNDS_AREA = 0         # Minimum pixel area
EXCLUDE_CLASSES = []        # Classes to exclude
```

**CLI Usage:**
```bash
# Process single file
python3 perceptor.py input.xml output.json

# Get statistics
python3 perceptor.py input.xml --stats
```

### 3. `viewer.html` - 4-Panel Web Viewer

Interactive viewer with four synchronized panels:

| Panel | Description |
|-------|-------------|
| **Original Screenshot** | Raw screenshot, click to find element |
| **Raw XML** | Full accessibility tree |
| **Rendered View** | Screenshot with element overlays |
| **Perceptor JSON** | Filtered elements list |

**Features:**
- Click XML/JSON → highlight on screenshot
- Click screenshot → find matching element
- Color-coded overlays: Green (clickable), Orange (editable), Purple (scrollable)
- Stats bar showing element counts
- Keyboard navigation: ← →

### 4. `process_existing.sh` - Batch Process Existing Files

Generate JSON files for existing XML captures:

```bash
./process_existing.sh debug_data
```

## Quick Start

```bash
cd inspection_tool

# 1. Capture (with device connected)
./capture.sh

# 2. (Optional) Process existing XML files
./process_existing.sh debug_data

# 3. View results
./serve.sh
# Open http://localhost:8080/viewer.html
# Select the debug_data folder
```

## Workflow for Tuning Perceptor

1. **Capture diverse screens** - Navigate through different apps/screens
2. **Open viewer** - Compare original vs filtered views
3. **Identify issues** - Missing important elements? Too much noise?
4. **Edit `perceptor.py`** - Adjust parameters
5. **Re-process** - `./process_existing.sh debug_data`
6. **Refresh viewer** - Check improvements
7. **Port to Kotlin** - Update `Perceptor.kt` with optimal settings

## File Structure

```
inspection_tool/
├── capture.sh           # Capture script
├── perceptor.py         # Python Perceptor (editable)
├── process_existing.sh  # Batch processor
├── serve.sh             # HTTP server
├── viewer.html          # 4-panel viewer
├── README.md            # This file
└── debug_data/          # Captured data
    ├── {timestamp}.png
    ├── {timestamp}.xml
    └── {timestamp}.json
```

