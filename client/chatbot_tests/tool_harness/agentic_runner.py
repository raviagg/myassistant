import json
import anthropic

from .tool_definitions import ALL_TOOLS
from .scenarios import SYSTEM_PROMPT, GLOBAL_FORBIDDEN_TOOLS

SEP  = "━" * 72
THIN = "─" * 72


class AgenticRunner:
    """
    Runs scenarios using the Anthropic SDK agentic loop.
    Tool calls are routed to the injected executor (MockExecutor or LiveExecutor).
    """

    def __init__(self, executor, model: str = "claude-sonnet-4-6"):
        self._executor = executor
        self._model    = model
        self._client   = None  # created lazily so tests can patch anthropic.Anthropic

    def _get_client(self):
        if self._client is None:
            self._client = anthropic.Anthropic()
        return self._client

    # ── Public API ────────────────────────────────────────────────────────

    def run_scenario(
        self, scenario: dict, verbose: bool = False
    ) -> tuple[list[list[str]] | None, str | None]:
        """
        Run all turns of a scenario.
        Returns (list_of_tool_name_lists_per_turn, error).
        """
        messages: list[dict] = []
        all_turn_tool_names: list[list[str]] = []

        for turn_idx, turn in enumerate(scenario["turns"]):
            messages.append({"role": "user", "content": turn["user_message"]})
            try:
                tool_names, messages = self._run_turn(messages, verbose)
            except Exception as exc:
                return None, f"Turn {turn_idx + 1}: {exc}"
            all_turn_tool_names.append(tool_names)

        return all_turn_tool_names, None

    # ── Internal ──────────────────────────────────────────────────────────

    def _run_turn(
        self, messages: list[dict], verbose: bool
    ) -> tuple[list[str], list[dict]]:
        """
        Drive the API loop until stop_reason == 'end_turn'.
        Returns (tool_names_called_this_turn, updated_messages).
        """
        tool_names: list[str] = []

        while True:
            response = self._get_client().messages.create(
                model=self._model,
                max_tokens=4096,
                system=SYSTEM_PROMPT,
                tools=ALL_TOOLS,
                messages=messages,
            )

            tool_use_blocks = [b for b in response.content if b.type == "tool_use"]
            tool_names.extend(b.name for b in tool_use_blocks)

            if verbose:
                for b in tool_use_blocks:
                    print(f"  [tool] {b.name}({json.dumps(b.input)[:120]})")

            # append assistant message to history
            messages = messages + [{"role": "assistant", "content": response.content}]

            if response.stop_reason == "end_turn" or not tool_use_blocks:
                break

            # execute tool calls and collect results
            tool_results = []
            for block in tool_use_blocks:
                try:
                    result = self._executor.call(block.name, block.input)
                    content = json.dumps(result)
                except Exception as exc:
                    content = str(exc)
                tool_results.append({
                    "type": "tool_result",
                    "tool_use_id": block.id,
                    "content": content,
                })

            messages = messages + [{"role": "user", "content": tool_results}]

        return tool_names, messages

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
                print(f"  VALIDATION")
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
                extra = [n for n in tool_names if n not in expected and n not in all_forbidden]
                for nm in extra:
                    print(f"    ?  {nm}  (not in expected list — may be fine)")
                print(f"    → {'all checks passed ✓' if turn_ok else 'ISSUES FOUND'}")
                print()

        status = "PASS ✓" if scenario_pass else "FAIL ✗"
        print(f"  SCENARIO {status}")
