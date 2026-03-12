#!/usr/bin/env python3
"""Measure prompt token counts across all three prompt layers.

Extracts text from:
  1. System prompt: StandaloneAgentDef.kt (trimIndent block)
  2. Tool descriptions: tool/impl/*Tool.kt (description + parameterSchema)
  3. App skills: app/src/main/assets/app_skills/*/SKILL.md

Outputs:
  doc/autotune/meta/token_counts.json  (SOT)
  doc/autotune/meta/token_counts.md    (rendered view)

Token counting: tiktoken (o200k_base, gpt-4o family) if available, else chars / 4.

Usage:
  python scripts/token_counts.py
"""

import json
import re
import sys
from pathlib import Path

try:
    import tiktoken
    _enc = tiktoken.get_encoding("o200k_base")
    _USE_TIKTOKEN = True
except ImportError:
    _enc = None
    _USE_TIKTOKEN = False

REPO_ROOT = Path(__file__).resolve().parent.parent
OUTPUT_DIR = REPO_ROOT / "doc" / "autotune" / "meta"

AGENT_DEF = REPO_ROOT / "app/src/main/kotlin/com/moonkey/androidagent/agent/definition/StandaloneAgentDef.kt"
TOOL_DIR = REPO_ROOT / "app/src/main/kotlin/com/moonkey/androidagent/tool/impl"
SKILLS_DIR = REPO_ROOT / "app/src/main/assets/app_skills"


def estimate_tokens(text: str) -> int:
    """Count tokens using tiktoken (o200k_base) if available, else chars / 4."""
    if _USE_TIKTOKEN:
        return len(_enc.encode(text))
    return len(text) // 4


def extract_kotlin_string_block(source: str, marker: str) -> str:
    """Extract a trimIndent triple-quoted string following a marker line."""
    idx = source.find(marker)
    if idx == -1:
        return ""
    start = source.find('"""', idx)
    if start == -1:
        return ""
    start += 3
    end = source.find('"""', start)
    if end == -1:
        return ""
    return source[start:end].strip()


def extract_param_schema_block(source: str) -> str:
    """Extract the parameterSchema block from Kotlin source.

    Looks for 'override val parameterSchema' or 'val parameterSchema' and
    extracts everything until the balanced closing of the top-level JSONObject.
    Returns the raw Kotlin code, whose length approximates the serialized JSON
    the LLM sees (property names, types, descriptions, enums).
    """
    for marker in ["override val parameterSchema", "val parameterSchema"]:
        idx = source.find(marker)
        if idx != -1:
            break
    else:
        return ""

    # For lazy schemas like `by lazy { buildSchema() }`, find the buildSchema function
    rest = source[idx:]
    if "by lazy" in rest[:100]:
        # Find the builder function name
        m = re.search(r'by lazy\s*\{\s*(\w+)\(\)', rest)
        if m:
            func_name = m.group(1)
            func_marker = f"private fun {func_name}()"
            func_idx = source.find(func_marker)
            if func_idx != -1:
                rest = source[func_idx:]
            else:
                return ""
        else:
            return ""

    # Find the opening brace of the JSONObject
    brace_start = rest.find("{")
    if brace_start == -1:
        return ""

    # Balance braces
    depth = 0
    for i, ch in enumerate(rest[brace_start:], brace_start):
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return rest[brace_start : i + 1]
    return ""


def extract_param_descriptions(schema_block: str) -> str:
    """Extract all string literals from a param schema block.

    These are the property names, types, descriptions, and enum values
    that the LLM sees in the function schema JSON.
    """
    # Extract all quoted strings — these map to JSON keys/values the LLM reads
    strings = re.findall(r'"([^"]*)"', schema_block)
    return "\n".join(strings)


def measure_system_prompt() -> dict:
    """Measure system prompt from StandaloneAgentDef.kt."""
    if not AGENT_DEF.exists():
        return {"chars": 0, "tokens": 0, "lines": 0}
    source = AGENT_DEF.read_text()
    text = extract_kotlin_string_block(source, "override val systemPrompt")
    return {"chars": len(text), "tokens": estimate_tokens(text), "lines": text.count("\n") + 1}


def measure_tool_descriptions() -> dict:
    """Measure all tool description + parameter schema properties."""
    if not TOOL_DIR.exists():
        return {"total": {"chars": 0, "tokens": 0}, "tools": {}}
    tools = {}
    total_chars = 0
    for kt_file in sorted(TOOL_DIR.glob("*Tool.kt")):
        source = kt_file.read_text()
        desc_text = extract_kotlin_string_block(source, "override val description")
        if not desc_text:
            continue
        schema_block = extract_param_schema_block(source)
        param_text = extract_param_descriptions(schema_block) if schema_block else ""
        combined = desc_text + "\n" + param_text if param_text else desc_text
        chars = len(combined)
        desc_chars = len(desc_text)
        param_chars = len(param_text)
        tools[kt_file.stem] = {
            "chars": chars,
            "tokens": estimate_tokens(combined),
            "lines": combined.count("\n") + 1,
            "description_tokens": estimate_tokens(desc_text),
            "params_tokens": estimate_tokens(param_text),
        }
        total_chars += chars
    total_tokens = sum(t["tokens"] for t in tools.values())
    return {
        "total": {"chars": total_chars, "tokens": total_tokens},
        "tools": tools,
    }


def measure_app_skills() -> dict:
    """Measure all app skill files."""
    if not SKILLS_DIR.exists():
        return {"total": {"chars": 0, "tokens": 0}, "count": 0, "avg_tokens": 0, "skills": {}}
    skills = {}
    total_chars = 0
    for skill_file in sorted(SKILLS_DIR.glob("*/SKILL.md")):
        text = skill_file.read_text()
        chars = len(text)
        pkg = skill_file.parent.name
        skills[pkg] = {"chars": chars, "tokens": estimate_tokens(text), "lines": text.count("\n") + 1}
        total_chars += chars
    count = len(skills)
    total_tokens = sum(s["tokens"] for s in skills.values())
    return {
        "total": {"chars": total_chars, "tokens": total_tokens},
        "count": count,
        "avg_tokens": total_tokens // max(count, 1),
        "skills": skills,
    }


def render_markdown(data: dict) -> str:
    """Render token counts as markdown."""
    lines = ["# Prompt Token Counts", ""]

    sp = data["system_prompt"]
    td = data["tool_descriptions"]
    sk = data["app_skills"]
    always_on = sp["tokens"] + td["total"]["tokens"]

    lines.append("## Summary")
    lines.append("")
    lines.append("| Layer | Tokens | Lines |")
    lines.append("|-------|--------|-------|")
    lines.append(f"| System prompt | {sp['tokens']:,} | {sp['lines']} |")
    lines.append(f"| Tool descriptions (total) | {td['total']['tokens']:,} | — |")
    lines.append(f"| App skills (total) | {sk['total']['tokens']:,} | — |")
    lines.append(f"| App skills (avg per skill) | {sk['avg_tokens']:,} | — |")
    lines.append(f"| **Always-on (prompt + tools)** | **{always_on:,}** | — |")
    lines.append("")

    lines.append("## Tool Descriptions + Params")
    lines.append("")
    lines.append("| Tool | Desc | Params | Total |")
    lines.append("|------|------|--------|-------|")
    for name, info in sorted(td["tools"].items(), key=lambda x: -x[1]["tokens"]):
        lines.append(f"| {name} | {info['description_tokens']:,} | {info['params_tokens']:,} | {info['tokens']:,} |")
    lines.append("")

    lines.append("## App Skills")
    lines.append("")
    lines.append("| Package | Tokens | Lines |")
    lines.append("|---------|--------|-------|")
    for pkg, info in sorted(sk["skills"].items(), key=lambda x: -x[1]["tokens"]):
        lines.append(f"| {pkg} | {info['tokens']:,} | {info['lines']} |")
    lines.append("")

    return "\n".join(lines)


def main():
    data = {
        "system_prompt": measure_system_prompt(),
        "tool_descriptions": measure_tool_descriptions(),
        "app_skills": measure_app_skills(),
    }

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

    json_path = OUTPUT_DIR / "token_counts.json"
    json_path.write_text(json.dumps(data, indent=2) + "\n")

    md_path = OUTPUT_DIR / "token_counts.md"
    md_path.write_text(render_markdown(data) + "\n")

    # Print summary
    sp_tok = data["system_prompt"]["tokens"]
    td_tok = data["tool_descriptions"]["total"]["tokens"]
    sk_tok = data["app_skills"]["total"]["tokens"]
    always_on = sp_tok + td_tok
    print(f"System prompt:    {sp_tok:>6,} tokens")
    print(f"Tool descriptions:{td_tok:>6,} tokens")
    print(f"App skills:       {sk_tok:>6,} tokens (avg {data['app_skills']['avg_tokens']:,}/skill)")
    print(f"Always-on:        {always_on:>6,} tokens")


if __name__ == "__main__":
    main()
