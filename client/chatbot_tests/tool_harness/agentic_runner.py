import json
import re
import subprocess

from .tool_definitions import ALL_TOOLS
from .scenarios import SYSTEM_PROMPT, GLOBAL_FORBIDDEN_TOOLS

SEP  = "━" * 72
THIN = "─" * 72


# ── Tool summary ─────────────────────────────────────────────────────────────

def _agentic_tools_summary() -> str:
    """Compact tool list for the agentic step prompt. Parameters marked * are required."""
    lines = []
    for tool in ALL_TOOLS:
        schema = tool["input_schema"]
        props  = schema.get("properties", {})
        req    = set(schema.get("required", []))
        params = []
        for pname, spec in props.items():
            ptype = spec.get("type", "any")
            if "enum" in spec:
                ptype = "|".join(spec["enum"])
            marker = "*" if pname in req else ""
            params.append(f"{pname}{marker}:{ptype}")
        desc = tool["description"].split(".")[0].strip()
        lines.append(f"  {tool['name']}({', '.join(params)})")
        lines.append(f"    → {desc}")
    return "\n".join(lines)


_TOOLS_TEXT = _agentic_tools_summary()


# ── Stats helpers ─────────────────────────────────────────────────────────────

def _empty_totals() -> dict:
    return {
        "num_calls":              0,
        "duration_api_ms":        0,
        "input_tokens":           0,
        "cache_creation_tokens":  0,
        "cache_read_tokens":      0,
        "output_tokens":          0,
        "cost_usd":               0.0,
    }


def _accumulate(totals: dict, call_stats: dict) -> None:
    totals["num_calls"]             += 1
    totals["duration_api_ms"]       += call_stats.get("duration_api_ms", 0)
    totals["input_tokens"]          += call_stats.get("input_tokens", 0)
    totals["cache_creation_tokens"] += call_stats.get("cache_creation_tokens", 0)
    totals["cache_read_tokens"]     += call_stats.get("cache_read_tokens", 0)
    totals["output_tokens"]         += call_stats.get("output_tokens", 0)
    totals["cost_usd"]              += call_stats.get("cost_usd", 0.0)


def _fmt_call_stats(s: dict) -> str:
    return (
        f"{s['duration_api_ms']:,}ms  "
        f"in={s['input_tokens']:,}  "
        f"hit={s['cache_read_tokens']:,}  "
        f"new={s['cache_creation_tokens']:,}  "
        f"out={s['output_tokens']:,}  "
        f"${s['cost_usd']:.4f}"
    )


def _fmt_turn_stats(s: dict) -> str:
    n  = s["num_calls"]
    ms = s["duration_api_ms"]
    return (
        f"{n} call{'s' if n != 1 else ''}  ·  {ms / 1000:.1f}s  ·  "
        f"in={s['input_tokens']:,}  hit={s['cache_read_tokens']:,}  "
        f"new={s['cache_creation_tokens']:,}  out={s['output_tokens']:,}  ·  "
        f"${s['cost_usd']:.4f}"
    )


def _fmt_scenario_stats(s: dict) -> str:
    nt = s.get("num_turns", "?")
    nc = s["num_calls"]
    ms = s["duration_api_ms"]
    return (
        f"{nt} turn{'s' if nt != 1 else ''}  ·  "
        f"{nc} call{'s' if nc != 1 else ''}  ·  "
        f"{ms / 1000:.1f}s  ·  "
        f"in={s['input_tokens']:,}  hit={s['cache_read_tokens']:,}  "
        f"new={s['cache_creation_tokens']:,}  out={s['output_tokens']:,}  ·  "
        f"${s['cost_usd']:.4f}"
    )


# ── Subprocess call ───────────────────────────────────────────────────────────

def _extract_json_array(text: str) -> list | None:
    text = re.sub(r"```(?:json)?\s*", "", text)
    text = re.sub(r"```", "", text)
    match = re.search(r"\[.*\]", text, re.DOTALL)
    if not match:
        return None
    try:
        return json.loads(match.group())
    except json.JSONDecodeError:
        return None


def _call_claude(
    prompt: str, verbose: bool = False
) -> tuple[list | None, str | None, dict | None]:
    """
    Run `claude -p --output-format json` with the given prompt.
    Returns (tool_calls, error, call_stats).
    call_stats contains duration_api_ms, token counts, and cost.
    """
    try:
        result = subprocess.run(
            ["claude", "-p", "--output-format", "json", prompt],
            capture_output=True, text=True, timeout=120,
        )
    except subprocess.TimeoutExpired:
        return None, "Timed out after 120s", None
    except FileNotFoundError:
        return None, "'claude' not found in PATH", None

    if result.returncode != 0:
        err = (result.stderr or result.stdout or "").strip()[:300]
        return None, f"claude exited {result.returncode}: {err}", None

    try:
        envelope = json.loads(result.stdout)
    except json.JSONDecodeError:
        snippet = result.stdout.strip()[:400]
        return None, f"Could not parse JSON envelope:\n{snippet}", None

    if envelope.get("is_error") or envelope.get("subtype") != "success":
        err = str(envelope.get("result", envelope))[:300]
        return None, f"claude error: {err}", None

    usage = envelope.get("usage", {})
    call_stats = {
        "duration_api_ms":       envelope.get("duration_api_ms", 0),
        "input_tokens":          usage.get("input_tokens", 0),
        "cache_creation_tokens": usage.get("cache_creation_input_tokens", 0),
        "cache_read_tokens":     usage.get("cache_read_input_tokens", 0),
        "output_tokens":         usage.get("output_tokens", 0),
        "cost_usd":              envelope.get("total_cost_usd", 0.0),
    }

    response_text = envelope.get("result", "")

    if verbose:
        print(f"\n  [RAW] {response_text[:300]}")

    calls = _extract_json_array(response_text)
    if calls is None:
        return None, f"Could not parse tool calls:\n{response_text[:400]}", call_stats

    return calls, None, call_stats


# ── Runner ────────────────────────────────────────────────────────────────────

class AgenticRunner:
    """
    Runs scenarios using a claude subprocess agentic loop.

    Each turn iterates: build step prompt → claude -p → parse tool calls →
    execute via executor → repeat until Claude returns [].
    Tool calls are routed to the injected executor (MockExecutor or LiveExecutor).
    """

    def __init__(self, executor, model: str = "claude-sonnet-4-6"):
        self._executor = executor
        self._model    = model  # informational; claude CLI uses its own default

    # ── Public API ────────────────────────────────────────────────────────

    def run_scenario(
        self, scenario: dict, verbose: bool = False
    ) -> tuple[list[list[str]] | None, dict | None, str | None]:
        """
        Run all turns of a scenario.
        Returns (list_of_tool_name_lists_per_turn, scenario_stats, error).
        scenario_stats: {"turns": [turn_stats, ...], "total": {...}}
        """
        prior_turns:        list[dict]       = []
        all_turn_tool_names: list[list[str]] = []
        all_turn_stats:     list[dict]       = []
        scenario_total = _empty_totals()

        for turn_idx, turn in enumerate(scenario["turns"]):
            try:
                tool_names, turn_stats = self._run_turn(
                    turn["user_message"], prior_turns, verbose
                )
            except Exception as exc:
                return None, None, f"Turn {turn_idx + 1}: {exc}"

            all_turn_tool_names.append(tool_names)
            all_turn_stats.append(turn_stats)

            for key in ("num_calls", "duration_api_ms", "input_tokens",
                        "cache_creation_tokens", "cache_read_tokens",
                        "output_tokens"):
                scenario_total[key] += turn_stats[key]
            scenario_total["cost_usd"] += turn_stats["cost_usd"]

            prior_turns.append({
                "user_message": turn["user_message"],
                "tool_names":   tool_names,
            })

        scenario_total["num_turns"] = len(scenario["turns"])
        return (
            all_turn_tool_names,
            {"turns": all_turn_stats, "total": scenario_total},
            None,
        )

    # ── Internal ──────────────────────────────────────────────────────────

    def _run_turn(
        self, user_message: str, prior_turns: list[dict], verbose: bool
    ) -> tuple[list[str], dict]:
        """
        Drive the subprocess loop until Claude returns an empty tool list.
        Returns (tool_names_called, turn_stats).
        """
        tool_names:   list[str]  = []
        step_results: list[dict] = []
        turn_totals = _empty_totals()
        call_num = 0

        while True:
            prompt = self._build_step_prompt(user_message, prior_turns, step_results)
            calls, error, call_stats = _call_claude(prompt, verbose)
            call_num += 1

            if call_stats:
                _accumulate(turn_totals, call_stats)
                if verbose:
                    print(f"  [call {call_num}] {_fmt_call_stats(call_stats)}")

            if error:
                raise RuntimeError(error)
            if not calls:
                break

            for call in calls:
                name   = call.get("tool", call.get("name", "?"))
                params = call.get("params", call.get("parameters", call.get("input", {})))
                tool_names.append(name)
                if verbose:
                    print(f"  [tool] {name}({json.dumps(params)[:120]})")
                try:
                    result = self._executor.call(name, params)
                except Exception as exc:
                    result = {"error": str(exc)}
                step_results.append({"tool": name, "params": params, "result": result})

        return tool_names, turn_totals

    def _build_step_prompt(
        self,
        user_message: str,
        prior_turns:  list[dict],
        step_results: list[dict],
    ) -> str:
        """Build the prompt for one agentic loop iteration."""
        parts = [SYSTEM_PROMPT]
        parts.append(f"\n{'─' * 72}")
        parts.append(f"TOOLS ({len(ALL_TOOLS)} total)\nParameters marked * are required.")
        parts.append(_TOOLS_TEXT)

        if prior_turns:
            parts.append(f"\n{'─' * 72}")
            parts.append("CONVERSATION HISTORY")
            for i, t in enumerate(prior_turns, 1):
                parts.append(f"\nTurn {i}")
                parts.append(f'User: "{t["user_message"]}"')
                parts.append(f"Tools called: {json.dumps(t['tool_names'])}")

        parts.append(f"\n{'─' * 72}")
        parts.append("CURRENT TURN")
        parts.append(f'User: "{user_message}"')

        if step_results:
            parts.append("\nPrevious steps this turn (already executed):")
            for i, s in enumerate(step_results, 1):
                params_str = json.dumps(s["params"])[:100]
                result_str = json.dumps(s["result"])[:300]
                parts.append(f"  Step {i}: {s['tool']}({params_str}) → {result_str}")

        parts.append(
            "\nOutput ONLY a JSON array of the next tool calls to make, "
            "or [] if you are done:\n"
            '[{"tool": "tool_name", "params": {"param*": "value"}}, ...]\n'
            "Output only the JSON array — no explanation, no markdown fences."
        )
        return "\n".join(parts)

    # ── Output ────────────────────────────────────────────────────────────

    def print_result(
        self,
        scenario:            dict,
        all_turn_tool_names: list[list[str]] | None,
        scenario_stats:      dict | None,
        error:               str | None,
    ) -> None:
        turns = scenario["turns"]
        scenario_forbidden = scenario.get("forbidden_tools", [])
        global_forbidden   = list(dict.fromkeys(GLOBAL_FORBIDDEN_TOOLS + scenario_forbidden))

        print(f"\n{SEP}")
        print(f"  {scenario['name']}")
        print(SEP)

        if error:
            print(f"  ERROR: {error}")
            print()
            return

        scenario_pass = True

        for turn_idx, (turn, tool_names) in enumerate(zip(turns, all_turn_tool_names)):
            n_turns = len(turns)
            print(f"\n  ── Turn {turn_idx + 1} of {n_turns} " + "─" * (53 - len(str(n_turns))))
            print(f"  USER: \"{turn['user_message']}\"")
            print()
            print(f"  TOOL CALLS  ({len(tool_names)} total)")
            print(f"  {THIN}")
            for n, name in enumerate(tool_names, 1):
                print(f"  {n:2d}. {name}")
            print()

            if scenario_stats and turn_idx < len(scenario_stats["turns"]):
                ts = scenario_stats["turns"][turn_idx]
                print(f"  TURN STATS   {_fmt_turn_stats(ts)}")
                print()

            expected       = turn.get("expected_tools", [])
            turn_forbidden = turn.get("forbidden_tools", [])
            all_forbidden  = list(dict.fromkeys(global_forbidden + turn_forbidden))

            if expected or all_forbidden:
                turn_ok = True
                label = f"Turn {turn_idx + 1}" if n_turns > 1 else ""
                print(f"  VALIDATION{' — ' + label if label else ''}")
                print(f"  {THIN}")
                for exp in expected:
                    found = exp in tool_names
                    if not found:
                        turn_ok = False
                        scenario_pass = False
                    print(f"    {'✓' if found else '✗'}  {exp}")
                for forb in all_forbidden:
                    present = forb in tool_names
                    if present:
                        turn_ok = False
                        scenario_pass = False
                    print(f"    {'✗ SHOULD NOT appear:' if present else '✓ (absent)'}  {forb}")
                extra = [nm for nm in tool_names if nm not in expected and nm not in all_forbidden]
                for nm in extra:
                    print(f"    ?  {nm}  (not in expected list — may be fine)")
                print(f"    → {'all checks passed ✓' if turn_ok else 'ISSUES FOUND'}")
                print()

        if scenario_stats:
            print(f"  SCENARIO STATS   {_fmt_scenario_stats(scenario_stats['total'])}")
        status = "PASS ✓" if scenario_pass else "FAIL ✗"
        print(f"  SCENARIO {status}")
