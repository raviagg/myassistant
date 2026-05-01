#!/usr/bin/env python3
"""
MCP Tool Harness — validates tool selection in three modes.

Each scenario is a multi-turn conversation. Validation checks that expected tools
are called and forbidden tools are absent.

Usage:
    # mock-plan (default): claude -p subprocess, validates planned tool names
    python -m tool_harness.harness
    python -m tool_harness.harness --scenario 2
    python -m tool_harness.harness --all
    python -m tool_harness.harness --scenario 4 --verbose

    # Parallel: run all scenarios concurrently (mock-plan or mock-loop)
    python -m tool_harness.harness --all --parallel
    python -m tool_harness.harness --all --parallel --workers 8

    # mock-loop: claude subprocess agentic loop, static mock responses (no services needed)
    python -m tool_harness.harness --mode mock-loop
    python -m tool_harness.harness --mode mock-loop --scenario 1

    # live-loop: claude subprocess agentic loop, real http_server + PostgreSQL
    python -m tool_harness.harness --mode live-loop --scenario 1
    python -m tool_harness.harness --mode live-loop --all
"""
import argparse
import json
import re
import shutil
import subprocess
import sys
import threading
from concurrent.futures import ThreadPoolExecutor, as_completed

from .tool_definitions import ALL_TOOLS
from .scenarios import SCENARIOS, SYSTEM_PROMPT, GLOBAL_FORBIDDEN_TOOLS
from .agentic_runner import AgenticRunner, bedrock_plan_scenario
from .executors import MockExecutor, LiveExecutor
from .server_manager import managed_server, auth_token

SEP  = "━" * 72
THIN = "─" * 72

_print_lock = threading.Lock()


def _tprint(*args, **kwargs) -> None:
    """Thread-safe print."""
    with _print_lock:
        print(*args, **kwargs)


# ── Prompt construction ──────────────────────────────────────────────────

def _tools_summary() -> str:
    """Compact human-readable summary of all 42 tools for the planning prompt."""
    lines = []
    current_group = None

    group_map = {
        "create_person": "1a Person", "get_person": "1a Person",
        "search_persons": "1a Person", "update_person": "1a Person",
        "delete_person": "1a Person",
        "create_household": "1b Household", "get_household": "1b Household",
        "search_households": "1b Household", "update_household": "1b Household",
        "delete_household": "1b Household",
        "add_person_to_household": "1c Person-Household",
        "remove_person_from_household": "1c Person-Household",
        "list_household_members": "1c Person-Household",
        "list_person_households": "1c Person-Household",
        "create_relationship": "1d Relationship",
        "get_relationship": "1d Relationship",
        "list_relationships": "1d Relationship",
        "update_relationship": "1d Relationship",
        "delete_relationship": "1d Relationship",
        "resolve_kinship": "1d Relationship",
        "create_document": "2a Document", "get_document": "2a Document",
        "list_documents": "2a Document", "search_documents": "2a Document",
        "create_fact": "2b Fact", "get_fact_history": "2b Fact",
        "get_current_fact": "2b Fact", "list_current_facts": "2b Fact",
        "search_current_facts": "2b Fact",
        "list_entity_type_schemas": "3 Schema",
        "get_entity_type_schema": "3 Schema",
        "get_current_entity_type_schema": "3 Schema",
        "create_entity_type_schema": "3 Schema",
        "update_entity_type_schema": "3 Schema",
        "deactivate_entity_type_schema": "3 Schema",
        "list_domains": "4 Reference",
        "list_source_types": "4 Reference",
        "list_kinship_aliases": "4 Reference",
        "save_file": "5 Files", "extract_text_from_file": "5 Files",
        "get_file": "5 Files", "delete_file": "5 Files",
    }

    for tool in ALL_TOOLS:
        name = tool["name"]
        group = group_map.get(name, "Other")
        if group != current_group:
            lines.append(f"\n### Group {group}")
            current_group = group

        schema = tool["input_schema"]
        props  = schema.get("properties", {})
        req    = set(schema.get("required", []))

        param_parts = []
        for pname, spec in props.items():
            marker = "*" if pname in req else ""
            ptype  = spec.get("type", "any")
            if "enum" in spec:
                ptype = "|".join(spec["enum"])
            param_parts.append(f"{pname}{marker}:{ptype}")

        params_str = f"({', '.join(param_parts)})" if param_parts else "()"
        desc = tool["description"].split(".")[0].strip()
        lines.append(f"  {name}{params_str}")
        lines.append(f"    → {desc}")

    return "\n".join(lines)


_TOOLS_TEXT = _tools_summary()  # computed once


def build_prompt(scenario: dict, turn_idx: int, prior_turns_data: list[dict]) -> str:
    """
    Build the planning prompt for turn `turn_idx` (0-based) of `scenario`.

    prior_turns_data: list of {"user_message": str, "tool_calls": list[dict]}
      representing completed earlier turns. Tool call names are embedded as
      conversation history so Claude knows what happened before.
    """
    turns = scenario["turns"]
    current_turn = turns[turn_idx]
    total_turns = len(turns)

    parts = [SYSTEM_PROMPT]
    parts.append(f"\n{'─' * 72}")
    parts.append(f"TOOLS ({len(ALL_TOOLS)} total)\nParameters marked * are required.")
    parts.append(_TOOLS_TEXT)

    if prior_turns_data:
        parts.append(f"\n{'─' * 72}")
        parts.append("CONVERSATION HISTORY")
        for i, prior in enumerate(prior_turns_data, 1):
            tool_names = [tc.get("tool", tc.get("name", "?")) for tc in prior["tool_calls"]]
            parts.append(f"\nTurn {i}")
            parts.append(f'User: "{prior["user_message"]}"')
            parts.append(f"Agent called: {json.dumps(tool_names)}")

    parts.append(f"\n{'─' * 72}")
    label = f"CURRENT TURN ({turn_idx + 1} of {total_turns})"
    parts.append(label)
    parts.append(f'User: "{current_turn["user_message"]}"')
    parts.append(
        "\nOutput ONLY a JSON array of the tool calls you would make for this turn, in order:\n"
        "[\n"
        '  {"tool": "tool_name", "params": {}},\n'
        "  ...\n"
        "]\n"
        "Output only the JSON array — no explanation, no markdown fences."
    )

    return "\n".join(parts)


# ── Subprocess runner ────────────────────────────────────────────────────

def _check_claude_available() -> None:
    if not shutil.which("claude"):
        print("Error: 'claude' not found in PATH.", file=sys.stderr)
        print("Make sure Claude Code CLI is installed and on your PATH.", file=sys.stderr)
        sys.exit(1)


def _extract_json_array(text: str) -> list | None:
    """Extract the first JSON array from claude's output."""
    text = re.sub(r"```(?:json)?\s*", "", text)
    text = re.sub(r"```", "", text)
    match = re.search(r"\[.*\]", text, re.DOTALL)
    if not match:
        return None
    try:
        return json.loads(match.group())
    except json.JSONDecodeError:
        return None


def _run_claude(prompt: str, verbose: bool = False) -> tuple[list[dict] | None, str | None]:
    """Call the claude subprocess with a single prompt. Returns (tool_calls, error)."""
    try:
        result = subprocess.run(
            ["claude", "-p", prompt],
            capture_output=True,
            text=True,
            timeout=120,
        )
    except subprocess.TimeoutExpired:
        return None, "Timed out after 120s"
    except FileNotFoundError:
        return None, "'claude' not found in PATH"

    if verbose:
        print("\n  [RAW OUTPUT]")
        for line in result.stdout.splitlines():
            print(f"  | {line}")

    if result.returncode != 0:
        err = (result.stderr or result.stdout or "").strip()[:300]
        return None, f"claude exited {result.returncode}: {err}"

    calls = _extract_json_array(result.stdout)
    if calls is None:
        snippet = result.stdout.strip()[:400]
        return None, f"Could not parse JSON from output:\n{snippet}"

    return calls, None


def run_scenario(
    scenario: dict, verbose: bool = False
) -> tuple[list[list[dict]] | None, str | None]:
    """
    Run all turns of a scenario sequentially, passing completed turns as history.
    Returns (list_of_turn_call_lists, error). Each element is the tool calls for one turn.
    """
    turns = scenario["turns"]
    all_turn_calls: list[list[dict]] = []
    prior_turns_data: list[dict] = []

    for turn_idx, turn in enumerate(turns):
        prompt = build_prompt(scenario, turn_idx, prior_turns_data)
        calls, error = _run_claude(prompt, verbose)
        if error:
            return None, f"Turn {turn_idx + 1}: {error}"
        all_turn_calls.append(calls)
        prior_turns_data.append({
            "user_message": turn["user_message"],
            "tool_calls": calls,
        })

    return all_turn_calls, None


# ── Validation helpers ───────────────────────────────────────────────────

def _to_tool_names(all_turn_calls: list[list]) -> list[list[str]]:
    """Normalize list[list[dict|str]] → list[list[str]] of tool names."""
    result = []
    for turn_calls in all_turn_calls:
        names = []
        for tc in turn_calls:
            if isinstance(tc, str):
                names.append(tc)
            else:
                names.append(tc.get("tool", tc.get("name", "")))
        result.append(names)
    return result


def _scenario_passed(scenario: dict, tool_names_per_turn: list[list[str]]) -> bool:
    """Return True if all validation checks pass. No output."""
    if tool_names_per_turn is None:
        return False
    turns = scenario["turns"]
    scenario_forbidden = scenario.get("forbidden_tools", [])
    global_forbidden = list(dict.fromkeys(GLOBAL_FORBIDDEN_TOOLS + scenario_forbidden))
    for turn, actual in zip(turns, tool_names_per_turn):
        expected = turn.get("expected_tools", [])
        turn_forbidden = turn.get("forbidden_tools", [])
        all_forbidden = list(dict.fromkeys(global_forbidden + turn_forbidden))
        for exp in expected:
            if exp not in actual:
                return False
        for forb in all_forbidden:
            if forb in actual:
                return False
    return True


# ── Output formatting ────────────────────────────────────────────────────

def _fmt_params(params: dict, max_line: int = 68) -> str:
    if not params:
        return "()"
    parts = []
    for k, v in params.items():
        vs = json.dumps(v) if not isinstance(v, str) else f'"{v}"'
        if len(vs) > 60:
            vs = vs[:57] + '..."'
        parts.append(f"{k}={vs}")
    joined = ", ".join(parts)
    if len(joined) <= max_line:
        return f"({joined})"
    indent = "       "
    inner = (",\n" + indent).join(parts)
    return f"(\n{indent}{inner})"


def print_result(
    scenario: dict,
    all_turn_calls: list[list[dict]] | None,
    error: str | None,
) -> None:
    turns = scenario["turns"]
    scenario_forbidden = scenario.get("forbidden_tools", [])
    global_forbidden = list(dict.fromkeys(GLOBAL_FORBIDDEN_TOOLS + scenario_forbidden))

    print(f"\n{SEP}")
    print(f"  {scenario['name']}")
    print(SEP)

    if error:
        print(f"  ERROR: {error}")
        print()
        return

    scenario_pass = True

    for turn_idx, (turn, calls) in enumerate(zip(turns, all_turn_calls)):
        n_turns = len(turns)
        print(f"\n  ── Turn {turn_idx + 1} of {n_turns} " + "─" * (53 - len(str(n_turns))))
        print(f"  USER: \"{turn['user_message']}\"")
        print()

        print(f"  TOOL CALLS  ({len(calls)} total)")
        print(f"  {THIN}")
        for n, tc in enumerate(calls, 1):
            tool   = tc.get("tool", tc.get("name", "?"))
            params = tc.get("params", tc.get("parameters", tc.get("input", {})))
            call_str = f"{tool}{_fmt_params(params)}"
            lines = call_str.splitlines()
            for i, line in enumerate(lines):
                prefix = f"  {n:2d}. " if i == 0 else "       "
                print(f"{prefix}{line}")
        print()

        expected      = turn.get("expected_tools", [])
        turn_forbidden = turn.get("forbidden_tools", [])
        all_forbidden  = list(dict.fromkeys(global_forbidden + turn_forbidden))

        if expected or all_forbidden:
            actual = [tc.get("tool", tc.get("name", "")) for tc in calls]
            label  = f"Turn {turn_idx + 1}" if n_turns > 1 else ""
            print(f"  VALIDATION{' — ' + label if label else ''}")
            print(f"  {THIN}")
            turn_ok = True
            for exp in expected:
                found = exp in actual
                if not found:
                    turn_ok = False
                    scenario_pass = False
                print(f"    {'✓' if found else '✗'}  {exp}")
            for forb in all_forbidden:
                present = forb in actual
                if present:
                    turn_ok = False
                    scenario_pass = False
                print(f"    {'✗ SHOULD NOT appear:' if present else '✓ (absent)'}  {forb}")
            extra = [nm for nm in actual if nm not in expected and nm not in all_forbidden]
            for nm in extra:
                print(f"    ?  {nm}  (not in expected list — may be fine)")
            print(f"    → {'all checks passed ✓' if turn_ok else 'ISSUES FOUND'}")
            print()

    status = "PASS ✓" if scenario_pass else "FAIL ✗"
    print(f"  SCENARIO {status}")


def print_summary(results: list[tuple[dict, bool, str | None]]) -> None:
    """Print a final pass/fail table for all scenarios."""
    passed = sum(1 for _, p, _ in results if p)
    total  = len(results)

    print(f"\n{SEP}")
    print(f"  SUMMARY  ({passed}/{total} passed)")
    print(SEP)
    for scenario, ok, error in results:
        status = "PASS ✓" if ok else "FAIL ✗"
        suffix = f"  — {error}" if error else ""
        print(f"  {status}  {scenario['name']}{suffix}")
    print(SEP)


# ── Parallel runner ──────────────────────────────────────────────────────

def _run_one_mock_plan(
    scenario: dict, backend: str, model: str
) -> tuple[list[list[str]] | None, str | None]:
    """Run a single mock-plan scenario. Returns (tool_names_per_turn, error)."""
    if backend == "bedrock":
        executor = MockExecutor()
        executor.set_scenario(scenario)
        raw, error = bedrock_plan_scenario(
            scenario, system_prompt=SYSTEM_PROMPT, model=model, verbose=False, executor=executor
        )
    else:
        raw, error = run_scenario(scenario, verbose=False)
    if error or raw is None:
        return None, error
    return _to_tool_names(raw), None


def _run_one_mock_loop(
    scenario: dict, model: str | None, backend: str
) -> tuple[list[list[str]] | None, str | None]:
    """Run a single mock-loop scenario with its own executor/runner."""
    executor = MockExecutor()
    runner   = AgenticRunner(
        executor=executor, system_prompt=SYSTEM_PROMPT,
        model=model, backend=backend, global_forbidden=GLOBAL_FORBIDDEN_TOOLS,
    )
    tool_names, _, error = runner.run_scenario(scenario, verbose=False)
    return tool_names, error


def run_parallel(
    to_run: list[dict],
    mode: str,
    backend: str,
    model: str | None,
    workers: int,
    live_executor=None,
) -> list[tuple[dict, bool, str | None]]:
    """
    Run scenarios in parallel. Prints one line per scenario start and one per finish.
    Returns results sorted in original scenario order.
    """
    order = {s["name"]: i for i, s in enumerate(to_run)}
    results: list[tuple[dict, bool, str | None]] = []

    def run_one(scenario: dict) -> tuple[dict, bool, str | None]:
        name = scenario["name"]
        _tprint(f"  →  {name}")
        if mode == "mock-plan":
            tool_names, error = _run_one_mock_plan(scenario, backend, model)
        elif mode == "mock-loop":
            tool_names, error = _run_one_mock_loop(scenario, model, backend)
        else:  # live-loop
            runner = AgenticRunner(
                executor=live_executor, system_prompt=SYSTEM_PROMPT,
                model=model, backend=backend, global_forbidden=GLOBAL_FORBIDDEN_TOOLS,
            )
            tool_names, _, error = runner.run_scenario(scenario, verbose=False)
        passed = _scenario_passed(scenario, tool_names) if not error else False
        status = "PASS ✓" if passed else "FAIL ✗"
        suffix = f"  — {error}" if error else ""
        _tprint(f"  {status}  {name}{suffix}")
        return scenario, passed, error

    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = [pool.submit(run_one, s) for s in to_run]
        for future in as_completed(futures):
            results.append(future.result())

    results.sort(key=lambda r: order[r[0]["name"]])
    return results


# ── Main ─────────────────────────────────────────────────────────────────

def main() -> None:
    parser = argparse.ArgumentParser(description="MCP Tool Harness")
    parser.add_argument("--scenario", type=int, metavar="N", help="Run scenario N (1-based)")
    parser.add_argument("--all",      action="store_true",   help="Run all scenarios")
    parser.add_argument("--verbose",  action="store_true",   help="Show raw output per turn")
    parser.add_argument("--parallel", action="store_true",   help="Run scenarios in parallel")
    parser.add_argument("--workers",  type=int, default=5,   help="Parallel worker count (default 5)")
    parser.add_argument(
        "--mode",
        choices=["mock-plan", "mock-loop", "live-loop"],
        default="mock-plan",
        help=(
            "mock-plan: claude -p subprocess, validates planned tool names (default). "
            "mock-loop: agentic loop with static mock responses (no services needed). "
            "live-loop: agentic loop with real http_server + PostgreSQL."
        ),
    )
    parser.add_argument(
        "--backend",
        choices=["bedrock", "claude-p"],
        default="bedrock",
        help=(
            "bedrock: Anthropic SDK via AWS Bedrock — native tool use, prompt caching, "
            "no wasted explanation tokens (default). Requires AWS credentials. "
            "claude-p: claude -p subprocess — no AWS credentials needed, claude CLI must be on PATH."
        ),
    )
    parser.add_argument(
        "--model",
        default=None,
        help=(
            "Claude model ID. Defaults: bedrock → us.anthropic.claude-sonnet-4-6, "
            "claude-p → claude-sonnet-4-6."
        ),
    )
    args = parser.parse_args()

    if args.scenario:
        idx = args.scenario - 1
        if not 0 <= idx < len(SCENARIOS):
            print(f"Error: --scenario must be 1–{len(SCENARIOS)}", file=sys.stderr)
            sys.exit(1)
        to_run = [SCENARIOS[idx]]
    elif args.all:
        to_run = SCENARIOS
    else:
        to_run = SCENARIOS[:3]
        print(f"Running first 3 of {len(SCENARIOS)} scenarios. "
              f"Use --all for all, --scenario N for one.")

    total_turns = sum(len(s["turns"]) for s in to_run)
    print(f"\n{len(ALL_TOOLS)} tools defined · {len(SCENARIOS)} scenarios available "
          f"· {total_turns} turns to run · mode={args.mode} · backend={args.backend}"
          + (f" · parallel={args.workers} workers" if args.parallel else ""))

    model = args.model or ("us.anthropic.claude-sonnet-4-6" if args.backend == "bedrock" else None)

    if args.backend == "claude-p" or args.mode in ("mock-loop", "live-loop"):
        _check_claude_available()

    # ── Parallel path ────────────────────────────────────────────────────
    if args.parallel:
        if args.verbose:
            print("Note: --verbose is ignored in --parallel mode.")
        if args.mode == "live-loop":
            print("Note: live-loop parallel may have DB contention; use with caution.")
            with managed_server() as base_url:
                token        = auth_token()
                live_executor = LiveExecutor(base_url=base_url, auth_token=token)
                try:
                    results = run_parallel(to_run, args.mode, args.backend, model, args.workers, live_executor)
                finally:
                    live_executor.close()
        else:
            results = run_parallel(to_run, args.mode, args.backend, model, args.workers)
        print_summary(results)
        return

    # ── Serial path ──────────────────────────────────────────────────────
    if args.mode == "mock-plan":
        if args.backend == "bedrock":
            for scenario in to_run:
                n_turns = len(scenario["turns"])
                turn_label = f"{n_turns} turn{'s' if n_turns > 1 else ''}"
                print(f"\nRunning {scenario['name']} ({turn_label})...", end=" ", flush=True)
                executor = MockExecutor()
                executor.set_scenario(scenario)
                all_turn_calls, error = bedrock_plan_scenario(
                    scenario, system_prompt=SYSTEM_PROMPT,
                    model=model, verbose=args.verbose, executor=executor,
                )
                print("done" if not error else "error")
                print_result(scenario, all_turn_calls, error)
        else:
            for scenario in to_run:
                n_turns = len(scenario["turns"])
                turn_label = f"{n_turns} turn{'s' if n_turns > 1 else ''}"
                print(f"\nRunning {scenario['name']} ({turn_label})...", end=" ", flush=True)
                all_turn_calls, error = run_scenario(scenario, verbose=args.verbose)
                print("done" if not error else "error")
                print_result(scenario, all_turn_calls, error)
        print(SEP)
        return

    if args.mode == "mock-loop":
        executor = MockExecutor()
        runner   = AgenticRunner(
            executor=executor, system_prompt=SYSTEM_PROMPT,
            model=args.model, backend=args.backend, global_forbidden=GLOBAL_FORBIDDEN_TOOLS,
        )
        for scenario in to_run:
            n_turns = len(scenario["turns"])
            turn_label = f"{n_turns} turn{'s' if n_turns > 1 else ''}"
            print(f"\nRunning {scenario['name']} ({turn_label})...", end=" ", flush=True)
            tool_names_per_turn, scenario_stats, error = runner.run_scenario(scenario, verbose=args.verbose)
            print("done" if not error else "error")
            runner.print_result(scenario, tool_names_per_turn, scenario_stats, error)
        print(SEP)
        return

    # live-loop
    with managed_server() as base_url:
        token    = auth_token()
        executor = LiveExecutor(base_url=base_url, auth_token=token)
        runner   = AgenticRunner(
            executor=executor, system_prompt=SYSTEM_PROMPT,
            model=args.model, backend=args.backend, global_forbidden=GLOBAL_FORBIDDEN_TOOLS,
        )
        try:
            for scenario in to_run:
                n_turns = len(scenario["turns"])
                turn_label = f"{n_turns} turn{'s' if n_turns > 1 else ''}"
                print(f"\nRunning {scenario['name']} ({turn_label})...", end=" ", flush=True)
                tool_names_per_turn, scenario_stats, error = runner.run_scenario(scenario, verbose=args.verbose)
                print("done" if not error else "error")
                runner.print_result(scenario, tool_names_per_turn, scenario_stats, error)
        finally:
            executor.close()
    print(SEP)


if __name__ == "__main__":
    main()
