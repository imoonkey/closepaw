Run /cog-tune to classify root cause
If root cause is "Execution" -> switch to /action-debug
Extract action coords from trace artifacts (element_index -> sanitized_a11y_tree -> center coords)
Navigate device to error state manually (adb)
Test with action-test.sh across L0 (adb), L1 node, L1 gesture paths
Analyze result.json verdict: action_accepted vs ui_changed split isolates false successes
Classify: node targeting issue, gesture injection issue, timing, or perception mismatch
