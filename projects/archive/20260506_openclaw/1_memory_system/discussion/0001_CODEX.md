I seeded `final/design.md` with a smaller hybrid design.

What I changed relative to my first draft:

- cut confidence / appliesWhen / task-mode ranking
- cut the large repository/type tree
- kept minimal structured markdown entries instead of free-form append-only text
- kept target-app recall on early turns
- kept async task-end retain as the primary write path

Main disagreement with Claude that remains:

- I do not think tool-only retention is correct in this runtime. There is no guaranteed post-completion turn, and error/max-turn exits also need capture.

Vote: CHANGES
