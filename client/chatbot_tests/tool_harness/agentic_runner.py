import json
import re
import subprocess

from .tool_definitions import ALL_TOOLS
from .scenarios import SYSTEM_PROMPT, GLOBAL_FORBIDDEN_TOOLS

SEP  = "━" * 72
THIN = "─" * 72


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


def _call_claude(prompt: str, verbose: bool = False) -> tuple[list | None, str | None]:
    """Run claude -p with the given prompt. Returns (tool_calls, error)."""
    try:
        result = subprocess.run(
            ["claude", "-p", prompt],
            capture_output=True, text=True, timeout=120,
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


class AgenticRunner:
    """
    Runs scenarios using a claude subprocess agentic loop.

    Each turn iterates: build step prompt → claude -p → parse tool calls →
    execute via executor → repeat until Claude returns [].
    Tool calls are routed to the injected executor (MockExecutor or LiveExecutor).
    """

    def __init__(self, executor, model: str = "claude-sonnet-4-6"):
        self._executor = executor
        self._model    = model  # passed through for informational use; claude CLI uses its default

    # ── Public API ────────────────────────────────────────────────────────

    def run_scenario(
        self, scenario: dict, verbose: bool = False
    ) -> tuple[list[list[str]] | None, str | None]:
        """
        Run all turns of a scenario.
        Returns (list_of_tool_name_lists_per_turn, error).
        """
        prior_turns: list[dict] = []
        all_turn_tool_names: list[list[str]] = []

        for turn_idx, turn in enumerate(scenario["turns"]):
            try:
                tool_names = self._run_turn(turn["user_message"], prior_turns, verbose)
            except Exception as exc:
                return None, f"Turn {turn_idx + 1}: {exc}"
            all_turn_tool_names.append(tool_names)
            prior_turns.append({
                "user_message": turn["user_message"],
                "tool_names":   tool_names,
            })

        return all_turn_tool_names, None

    # ── Internal ──────────────────────────────────────────────────────────

    def _run_turn(
        self, user_message: str, prior_turns: list[dict], verbose: bool
    ) -> list[str]:
        """
        Drive the subprocess loop until Claude returns an empty tool list.
        Returns the list of tool names called during this turn.
        """
        tool_names:   list[str]  = []
        step_results: list[dict] = []

        while True:
            prompt = self._build_step_prompt(user_message, prior_turns, step_results)
            calls, error = _call_claude(prompt, verbose)

            if error:
                raise RuntimeError(error)
            if not calls:  # [] = Claude is done with this turn
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

        return tool_names

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

    # ── Output (same format as harness.py) ───────────────────────────────

    def print_result(
        self,
        scenario: dict,
        all_turn_tool_names: list[list[str]] | None,
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

        status = "PASS ✓" if scenario_pass else "FAIL ✗"
        print(f"  SCENARIO {status}")
