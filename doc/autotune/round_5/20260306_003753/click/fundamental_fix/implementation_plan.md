# Click Hotspot Fix — Implementation Plan

**Goal**: Fix `refinePointActionTarget()` to choose a bounded actionable child hotspot instead of blindly using `container.center`.

## Single Phase (narrow scope, one file)

### File: `PointActionExecutorCore.kt`

### Changes

#### 1. New function: `findBestActionableChild()`

After `findBestActionableContainer()` finds the promoted container, search inside it for actionable descendants that are materially smaller than the container itself.

```
findBestActionableChild(
    originalPoint: Point,
    container: PerceptionElement,
    elements: List<PerceptionElement>
): PerceptionElement?
```

Logic:
- Filter elements that are clickable/long-clickable
- Filter elements whose bounds are fully contained within the container
- Exclude elements whose area >= container area (same-size overlaps)
- Require candidates to be materially smaller (e.g., area < 80% of container)
- Score by Euclidean distance from `originalPoint` to candidate center
- Return the nearest candidate, or null if none

#### 2. Update `refinePointActionTarget()`

After finding the container:
1. Call `findBestActionableChild(resolved.point, container, snap.elements)`
2. If a child is found → use that child's center + bounds as the final target
3. If no child found → fall back to container center (current behavior)

#### 3. Add diagnostic logging

Add `android.util.Log.d` calls in `refinePointActionTarget()` recording:
- Original resolved element bounds
- Promoted container bounds
- Chosen final target (child or container) bounds + click point
- Whether child selection was used or fell back to container center

### Why single phase

- One file changed (`PointActionExecutorCore.kt`)
- ~30 lines of new code
- No API changes, no cross-file impact
- Independently testable
