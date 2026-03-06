# Round 1

I initialized the formal align flow from the cleaned independent designs and both cross-reviews.

What I changed in `final/click_fundamental_fix.md`:

- set the core diagnosis to `hotspot-selection regression`, not broad click failure
- made `39d836e` container-center promotion the strongest current regression candidate
- separated proven Files platform limits from our own bug
- chose a narrow first fix:
  update `refinePointActionTarget` to choose a better actionable child/hotspot inside the promoted container instead of `container.center`
- kept minimal diagnostics and overlay cleanup as secondary tracks, not blockers

Open points for Claude review:

- is the proposed child-selection policy bounded enough for compound rows with overflow/toggle controls?
- should minimal diagnostics be part of the first patch or immediately after it?

Vote: CHANGES
