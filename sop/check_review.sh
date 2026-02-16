#!/bin/bash

# check_review.sh
# Usage: ./check_review.sh <path_to_design_doc>
#
# Helper script to monitor the review status of a design document.
# As per sop/product_ux_work.md and sop/system_work.md:
# "The first line of your design doc will be status: xxx."
#
# Valid statuses:
# - draft: Still pending review. Script will wait.
# - reviewed: Master has reviewed. Proceed to address feedback.
# - approved: Master has approved. Proceed to implementation.
#
# The script loops until the status becomes "reviewed" or "approved".

if [ -z "$1" ]; then
  echo "Usage: $0 <path_to_design_doc>"
  exit 1
fi

TARGET_FILE="$1"

if [ ! -f "$TARGET_FILE" ]; then
  echo "Error: File '$TARGET_FILE' not found."
  exit 1
fi

echo "Monitoring review status for: $TARGET_FILE"
echo "Press Ctrl+C to stop monitoring."

while true; do
  # Read the first line of the file
  FIRST_LINE=$(head -n 1 "$TARGET_FILE")
  
  # Check for status using grep (case-insensitive)
  # Expected format: "status: <status>"
  # Allowing for optional leading whitespace
  CURRENT_STATUS=$(echo "$FIRST_LINE" | grep -i "^[[:space:]]*status:" | awk -F: '{print $2}' | xargs)
  
  if [ -z "$CURRENT_STATUS" ]; then
    echo "[$(date '+%H:%M:%S')] Warning: No 'status: ...' found on the first line. Line content: '$FIRST_LINE'"
  else
    # Normalize to lowercase for comparison
    STATUS_LOWER=$(echo "$CURRENT_STATUS" | tr '[:upper:]' '[:lower:]')
    
    if [[ "$STATUS_LOWER" == "reviewed" || "$STATUS_LOWER" == "approved" ]]; then
      echo "[$(date '+%H:%M:%S')] Status is '$CURRENT_STATUS'. Review complete! Proceeding."
      exit 0
    elif [[ "$STATUS_LOWER" == "draft" ]]; then
      echo "[$(date '+%H:%M:%S')] Status is '$CURRENT_STATUS'. Waiting for review..."
    else
        # Handle unexpected statuses or yolo if written to file
        echo "[$(date '+%H:%M:%S')] Status is '$CURRENT_STATUS'. (Waiting for 'reviewed' or 'approved')"
    fi
  fi
  
  # Sleep before next check
  sleep 10
done
