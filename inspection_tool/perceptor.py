#!/usr/bin/env python3
"""
Perceptor - Python implementation of the Kotlin Perceptor logic.
Converts raw Accessibility XML into sanitized perception elements.

This is a standalone version for rapid iteration. Once you find optimal
parameters, port the changes back to Perceptor.kt.
"""

import xml.etree.ElementTree as ET
import json
import re
import sys
from dataclasses import dataclass, asdict
from typing import List, Optional
from pathlib import Path


# ============================================================================
# TUNABLE PARAMETERS - Adjust these to find the best balance
# ============================================================================

MAX_ELEMENTS = 80           # Maximum number of elements to keep
MAX_STRING_LENGTH = 60      # Maximum length for text/description strings

# Filter criteria - element is kept if ANY of these conditions are true
FILTER_CLICKABLE = True     # Keep clickable elements
FILTER_EDITABLE = True      # Keep editable elements  
FILTER_SCROLLABLE = True    # Keep scrollable elements
FILTER_HAS_TEXT = True      # Keep elements with text
FILTER_HAS_DESC = True      # Keep elements with content-desc

# Optional additional filters (set to True to enable)
FILTER_HAS_RESOURCE_ID = False  # Keep elements with resource-id
MIN_BOUNDS_AREA = 0             # Minimum pixel area (width * height) to keep

# Class name filtering (empty list = keep all)
EXCLUDE_CLASSES = []  # e.g., ["View", "FrameLayout"] to exclude generic containers

# ============================================================================


@dataclass
class PerceptionElement:
    """Represents a single UI element after perception processing."""
    index: int
    text: str
    resource_id: str
    class_name: str
    description: str
    is_clickable: bool
    is_editable: bool
    is_scrollable: bool
    bounds: List[int]  # [left, top, right, bottom]
    center: List[int]  # [centerX, centerY]
    
    # Extra fields for debugging (not in original Kotlin)
    package: str = ""
    depth: int = 0


def normalize_whitespace(text: str) -> str:
    """Normalize whitespace in a string."""
    if not text:
        return ""
    return re.sub(r'\s+', ' ', text).strip()


def parse_bounds(bounds_str: str) -> Optional[List[int]]:
    """Parse bounds string like '[0,0][1080,2400]' into [left, top, right, bottom]."""
    if not bounds_str:
        return None
    
    match = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', bounds_str)
    if match:
        return [int(x) for x in match.groups()]
    return None


def get_short_class_name(full_class: str) -> str:
    """Extract short class name from full class path."""
    if not full_class:
        return ""
    return full_class.split('.')[-1]


def should_keep_element(node: ET.Element, bounds: List[int]) -> bool:
    """
    Determine if an element should be kept based on filter criteria.
    This is the core filtering logic - modify this to tune perception.
    """
    # Get attributes
    clickable = node.get('clickable', 'false') == 'true'
    editable = node.get('class', '').endswith('EditText') or node.get('password', 'false') == 'true'
    scrollable = node.get('scrollable', 'false') == 'true'
    text = (node.get('text') or '').strip()
    desc = (node.get('content-desc') or '').strip()
    resource_id = node.get('resource-id') or ''
    class_name = get_short_class_name(node.get('class') or '')
    
    # Check minimum bounds area
    if bounds and MIN_BOUNDS_AREA > 0:
        width = bounds[2] - bounds[0]
        height = bounds[3] - bounds[1]
        area = width * height
        if area < MIN_BOUNDS_AREA:
            return False
    
    # Check class exclusion
    if class_name in EXCLUDE_CLASSES:
        return False
    
    # Apply filter criteria (OR logic - keep if ANY condition matches)
    conditions = []
    
    if FILTER_CLICKABLE:
        conditions.append(clickable)
    if FILTER_EDITABLE:
        conditions.append(editable)
    if FILTER_SCROLLABLE:
        conditions.append(scrollable)
    if FILTER_HAS_TEXT:
        conditions.append(bool(text))
    if FILTER_HAS_DESC:
        conditions.append(bool(desc))
    if FILTER_HAS_RESOURCE_ID:
        conditions.append(bool(resource_id))
    
    return any(conditions)


def traverse(node: ET.Element, elements: List[PerceptionElement], depth: int = 0) -> None:
    """
    Recursively traverse the accessibility tree and collect elements.
    """
    if len(elements) >= MAX_ELEMENTS:
        return
    
    # Only process 'node' elements
    if node.tag != 'node':
        # Process children of non-node elements (like 'hierarchy')
        for child in node:
            traverse(child, elements, depth)
        return
    
    # Parse bounds
    bounds_str = node.get('bounds')
    bounds = parse_bounds(bounds_str)
    
    if not bounds:
        # Skip nodes without valid bounds
        for child in node:
            traverse(child, elements, depth + 1)
        return
    
    # Check if element should be kept
    if should_keep_element(node, bounds):
        # Extract and process attributes
        text = normalize_whitespace(node.get('text') or '')[:MAX_STRING_LENGTH]
        desc = normalize_whitespace(node.get('content-desc') or '')[:MAX_STRING_LENGTH]
        resource_id = (node.get('resource-id') or '')[:MAX_STRING_LENGTH]
        class_name = get_short_class_name(node.get('class') or '')
        package = node.get('package') or ''
        
        clickable = node.get('clickable', 'false') == 'true'
        editable = class_name.endswith('EditText') or node.get('password', 'false') == 'true'
        scrollable = node.get('scrollable', 'false') == 'true'
        
        # Calculate center
        center = [
            (bounds[0] + bounds[2]) // 2,
            (bounds[1] + bounds[3]) // 2
        ]
        
        element = PerceptionElement(
            index=len(elements),
            text=text,
            resource_id=resource_id,
            class_name=class_name,
            description=desc,
            is_clickable=clickable,
            is_editable=editable,
            is_scrollable=scrollable,
            bounds=bounds,
            center=center,
            package=package,
            depth=depth
        )
        elements.append(element)
    
    # Traverse children
    for child in node:
        traverse(child, elements, depth + 1)


def process_xml(xml_path: str) -> List[PerceptionElement]:
    """
    Process an accessibility XML file and return perception elements.
    """
    try:
        tree = ET.parse(xml_path)
        root = tree.getroot()
    except ET.ParseError as e:
        print(f"Error parsing XML: {e}", file=sys.stderr)
        return []
    
    elements: List[PerceptionElement] = []
    traverse(root, elements)
    
    return elements


def to_json(elements: List[PerceptionElement], pretty: bool = True) -> str:
    """Convert elements to JSON string."""
    data = [asdict(e) for e in elements]
    if pretty:
        return json.dumps(data, indent=2, ensure_ascii=False)
    return json.dumps(data, ensure_ascii=False)


def to_prompt_json(elements: List[PerceptionElement]) -> str:
    """
    Convert to the format used for LLM prompting (matches Kotlin toPromptJson).
    This is a simplified version without extra debug fields.
    """
    data = []
    for elem in elements:
        obj = {
            "index": elem.index,
            "text": elem.text,
            "id": elem.resource_id,
            "class": elem.class_name,
            "desc": elem.description,
            "clickable": elem.is_clickable,
            "editable": elem.is_editable,
            "scrollable": elem.is_scrollable,
            "center": elem.center
        }
        data.append(obj)
    return json.dumps(data, indent=2, ensure_ascii=False)


def get_stats(elements: List[PerceptionElement]) -> dict:
    """Get statistics about the processed elements."""
    return {
        "total_elements": len(elements),
        "clickable": sum(1 for e in elements if e.is_clickable),
        "editable": sum(1 for e in elements if e.is_editable),
        "scrollable": sum(1 for e in elements if e.is_scrollable),
        "with_text": sum(1 for e in elements if e.text),
        "with_desc": sum(1 for e in elements if e.description),
        "unique_classes": len(set(e.class_name for e in elements)),
        "classes": list(set(e.class_name for e in elements))
    }


def main():
    """Main entry point for CLI usage."""
    if len(sys.argv) < 2:
        print("Usage: python perceptor.py <xml_file> [output_json]")
        print("       python perceptor.py <xml_file> --stats")
        sys.exit(1)
    
    xml_path = sys.argv[1]
    
    if not Path(xml_path).exists():
        print(f"Error: File not found: {xml_path}", file=sys.stderr)
        sys.exit(1)
    
    elements = process_xml(xml_path)
    
    # Check for --stats flag
    if len(sys.argv) > 2 and sys.argv[2] == '--stats':
        stats = get_stats(elements)
        print(json.dumps(stats, indent=2))
        return
    
    # Output JSON
    json_output = to_json(elements)
    
    if len(sys.argv) > 2:
        output_path = sys.argv[2]
        with open(output_path, 'w', encoding='utf-8') as f:
            f.write(json_output)
        print(f"Wrote {len(elements)} elements to {output_path}")
    else:
        print(json_output)


if __name__ == '__main__':
    main()

