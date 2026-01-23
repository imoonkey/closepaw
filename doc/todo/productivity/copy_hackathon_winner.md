I am trying to improve my cursor/claude code coding process, this mp @androidagent/.reference/everything-claude-code  has a lot of good stuff, but also many redundant things. I want to adopt some of the best practices. Applying the 80-20 rule here, probably a few key things out of this will give me the most productivity boost, help me find these. E.g., some commands, subagents and skills maybe most helpful compared to other cmds/subagents/skills. And skills are better levers compared to rules which are older practice now and less effective.

Some of my existing practices:
1. active context docs are maintained under doc/main and doc/dev. both are periodically updated after devs.
2. I have some std sops being defined under @androidagent/sop .
3. I manually start from design, write the code and rely on some reviews, and @androidagent/doc/dev/development.md  debugging to manually test.

Given the repo is for web frontend/backend developlement projects, some of them are not fully pertinent to my current repo. So do your research in two stages:
1. first ignore the exact coding context, think at the high-level in a meta-way just about a good workflow. Just try to strip down the repo, to find the minimal most helpful subset of files, and the corresponding best workflow practice.
2. define todo tasks for adapting each of those files to my current android agent developing repo. these will be handled later by separate subagents, not here in this session.

Output to ./claude, and also generate a symlink for ./codex ./cursor, as they pretty much adopt the same format. Search online if you are not sure about some of the concepts (e.g., skills, hooks, commands etc.)