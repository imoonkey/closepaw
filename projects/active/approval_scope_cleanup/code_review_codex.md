# Review: Operation Approval Scope Cleanup

## Summary

Reviewed the approval cleanup implementation across protocol, ToolRouter,
PolicyEngine, AgentSession, capsule rendering, tests, and docs.

The main review finding was fixed before this report was finalized:
`open_app` approvals were persisted against the destination package, but the
PolicyEngine allow-list check still only looked at the current foreground
package. That would have caused repeated prompts after a destination app was
approved. The fix uses `destinationPackage ?: packageName` for allow-list lookup
and adds PolicyEngine regression coverage.

## Critical

None.

## High

None remaining.

## Medium

None.

## Low

None.

## Recommendation

APPROVE.
