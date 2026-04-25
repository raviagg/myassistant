#!/usr/bin/env python3
"""
MCP Tool Harness — validates tool selection before building the real MCP server.

Runs test scenarios through the Anthropic SDK using all 44 tool definitions
and a mock server. Prints which tools were called and with what parameters,
then validates against the expected tool list for each scenario.

Usage:
    # Run first 3 scenarios (default)
    python -m tests.tool_harness.harness

    # Run a specific scenario (1-based index)
    python -m tests.tool_harness.harness --scenario 2

    # Run all 10 scenarios
    python -m tests.tool_harness.harness --all

    # Show full tool I/O including mock responses
    python -m tests.tool_harness.harness --scenario 2 --verbose
"""
import argparse
import json
import os
import sys
import textwrap

import anthropic

from .tool_definitions import ALL_TOOLS
from .mock_server import MockServer
from .scenarios import SCENARIOS, SYSTEM_PROMPT

MODEL = "claude-opus-4-7"
SEP   = "━" * 72
THIN  = "─" * 72


def _fmt_val(v, max_len: int = 70) -> str:
    if isinstance(v, list):
        if len(v) > 6:
            return f"[...{len(v)} items]"
        return json.dumps(v)
    if isinstance(v, dict):
        s = json.dumps(v, separators=(",", ":"))
        return s if len(s) <= max_len else s[:max_len - 3] + "..."
    if isinstance(v, str) and len(v) > max_len:
        return f'"{v[:max_len - 4]}..."'
    return json.dumps(v)


def _fmt_call(name: str, inp: dict) -> str:
    parts = [f"{k}={_fmt_val(v)}" for k, v in inp.items()]
    args = ", ".join(parts)
    line = f"{name}({args})"
    if len(line) <= 90:
        return line
    # wrap long calls
    indent = " " * (len(name) + 1)
    wrapped = (",\n" + indent).join(parts)
    return f"{name}({wrapped})"


def run_scenario(scenario: dict, mock: MockServer, verbose: bool = False) -> tuple[list[dict], str]:
    """Run one scenario through the full agentic loop. Returns (tool_calls_log, final_text)."""
    client = anthropic.Anthropic()
    messages: list[dict] = [{"role": "user", "content": scenario["user_message"]}]
    tool_calls_log: list[dict] = []

    while True:
        response = client.messages.create(
            model=MODEL,
            max_tokens=4096,
            system=SYSTEM_PROMPT,
            tools=ALL_TOOLS,
            messages=messages,
        )

        tool_uses  = [b for b in response.content if b.type == "tool_use"]
        text_blocks= [b for b in response.content if b.type == "text"]

        if not tool_uses or response.stop_reason == "end_turn":
            final_text = " ".join(b.text for b in text_blocks).strip()
            return tool_calls_log, final_text

        messages.append({"role": "assistant", "content": response.content})

        tool_results = []
        for tu in tool_uses:
            result = mock.handle(tu.name, tu.input)
            tool_calls_log.append({"name": tu.name, "input": tu.input, "result": result})
            if verbose:
                print(f"      → {tu.name}()")
                print(f"        input:  {json.dumps(tu.input, separators=(',', ':'))[:200]}")
                print(f"        result: {json.dumps(result, separators=(',', ':'))[:200]}")
            tool_results.append({
                "type": "tool_result",
                "tool_use_id": tu.id,
                "content": json.dumps(result),
            })

        messages.append({"role": "user", "content": tool_results})


def print_result(scenario: dict, tool_calls: list[dict], final_text: str, idx: int) -> None:
    expected = scenario.get("expected_tools", [])

    print(f"\n{SEP}")
    print(f"  {scenario['name']}")
    print(SEP)
    print(f"  USER: \"{scenario['user_message']}\"")
    print()

    print(f"  TOOL CALLS  ({len(tool_calls)} total)")
    print(f"  {THIN}")
    for n, tc in enumerate(tool_calls, 1):
        call_str = _fmt_call(tc["name"], tc["input"])
        for i, line in enumerate(call_str.splitlines()):
            prefix = f"  {n:2d}. " if i == 0 else "       "
            print(f"{prefix}{line}")
    print()

    print("  FINAL RESPONSE")
    print(f"  {THIN}")
    wrapped = textwrap.fill(final_text, width=68, initial_indent="  ", subsequent_indent="  ")
    print(wrapped)
    print()

    if expected:
        actual_names = [tc["name"] for tc in tool_calls]
        print("  VALIDATION")
        print(f"  {THIN}")
        all_ok = True
        for exp in expected:
            found = exp in actual_names
            if not found:
                all_ok = False
            mark = "✓" if found else "✗"
            print(f"    {mark}  {exp}")
        unexpected = [n for n in actual_names if n not in expected]
        for name in unexpected:
            print(f"    ?  {name}  (not in expected list)")
        if all_ok:
            print("    → all expected tools called")
        print()


def main() -> None:
    parser = argparse.ArgumentParser(description="MCP Tool Harness")
    parser.add_argument("--scenario", type=int, metavar="N", help="Run scenario N (1-based)")
    parser.add_argument("--all",      action="store_true",   help="Run all scenarios")
    parser.add_argument("--verbose",  action="store_true",   help="Show full tool I/O")
    args = parser.parse_args()

    if not os.environ.get("ANTHROPIC_API_KEY"):
        print("Error: ANTHROPIC_API_KEY not set.", file=sys.stderr)
        sys.exit(1)

    if args.scenario:
        idx = args.scenario - 1
        if not 0 <= idx < len(SCENARIOS):
            print(f"Error: --scenario must be 1–{len(SCENARIOS)}", file=sys.stderr)
            sys.exit(1)
        to_run = [(idx + 1, SCENARIOS[idx])]
    elif args.all:
        to_run = list(enumerate(SCENARIOS, 1))
    else:
        to_run = list(enumerate(SCENARIOS[:3], 1))
        print(f"Running first 3 of {len(SCENARIOS)} scenarios. "
              f"Use --all to run all or --scenario N for one.")

    mock = MockServer()
    print(f"\n{len(ALL_TOOLS)} tools defined · model: {MODEL}")

    for idx, scenario in to_run:
        print(f"\nRunning {scenario['name']}...", end=" ", flush=True)
        try:
            tool_calls, final_text = run_scenario(scenario, mock, verbose=args.verbose)
            print("done")
            print_result(scenario, tool_calls, final_text, idx)
        except Exception as e:
            print(f"ERROR: {e}")

    print(SEP)


if __name__ == "__main__":
    main()
