import json
import os
import re
import subprocess
import time

from .tool_definitions import ALL_TOOLS
from .scenarios import SYSTEM_PROMPT, GLOBAL_FORBIDDEN_TOOLS

SEP  = "━" * 72
THIN = "─" * 72


# ── Tool summary (claude-p backend only) ──────────────────────────────────────

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
    cost = f"${s['cost_usd']:.4f}" if s["cost_usd"] else "(cost N/A)"
    return (
        f"{s['duration_api_ms']:,}ms  "
        f"in={s['input_tokens']:,}  "
        f"hit={s['cache_read_tokens']:,}  "
        f"new={s['cache_creation_tokens']:,}  "
        f"out={s['output_tokens']:,}  "
        f"{cost}"
    )


def _fmt_turn_stats(s: dict) -> str:
    n    = s["num_calls"]
    ms   = s["duration_api_ms"]
    cost = f"${s['cost_usd']:.4f}" if s["cost_usd"] else "(cost N/A)"
    return (
        f"{n} call{'s' if n != 1 else ''}  ·  {ms / 1000:.1f}s  ·  "
        f"in={s['input_tokens']:,}  hit={s['cache_read_tokens']:,}  "
        f"new={s['cache_creation_tokens']:,}  out={s['output_tokens']:,}  ·  "
        f"{cost}"
    )


def _fmt_scenario_stats(s: dict) -> str:
    nt   = s.get("num_turns", "?")
    nc   = s["num_calls"]
    ms   = s["duration_api_ms"]
    cost = f"${s['cost_usd']:.4f}" if s["cost_usd"] else "(cost N/A)"
    return (
        f"{nt} turn{'s' if nt != 1 else ''}  ·  "
        f"{nc} call{'s' if nc != 1 else ''}  ·  "
        f"{ms / 1000:.1f}s  ·  "
        f"in={s['input_tokens']:,}  hit={s['cache_read_tokens']:,}  "
        f"new={s['cache_creation_tokens']:,}  out={s['output_tokens']:,}  ·  "
        f"{cost}"
    )


# ── Verbose formatting helpers ────────────────────────────────────────────────

def _fmt_request_messages(messages: list[dict]) -> list[str]:
    """Format the messages array for verbose request display (no system prompt / tools)."""
    # Build tool_use_id → name map from assistant messages so results can show the name.
    id_to_name: dict[str, str] = {}
    for msg in messages:
        if msg["role"] == "assistant":
            for block in (msg.get("content") or []):
                if hasattr(block, "type") and block.type == "tool_use":
                    id_to_name[block.id] = block.name

    lines = []
    for msg in messages:
        role    = msg["role"]
        content = msg["content"]
        tag     = "user" if role == "user" else "asst"

        if isinstance(content, str):
            preview = content.replace("\n", " ")
            if len(preview) > 120:
                preview = preview[:117] + "..."
            lines.append(f"        {tag}  \"{preview}\"")
        elif isinstance(content, list):
            for block in content:
                if hasattr(block, "type"):           # SDK object (assistant content)
                    if block.type == "tool_use":
                        lines.append(f"        {tag}  [tool_use] {block.name}")
                    elif block.type == "text" and block.text.strip():
                        preview = block.text.replace("\n", " ")
                        if len(preview) > 120:
                            preview = preview[:117] + "..."
                        lines.append(f"        {tag}  [text] \"{preview}\"")
                elif isinstance(block, dict):        # tool_result (user content)
                    name    = id_to_name.get(block.get("tool_use_id", ""), "?")
                    raw     = block.get("content", "")
                    preview = raw[:100] + ("..." if len(raw) > 100 else "")
                    lines.append(f"        {tag}  [result] {name} → {preview}")
    return lines


def _fmt_response_blocks(content) -> list[str]:
    """Format response content blocks (tool_use and text) for verbose output."""
    lines = []
    for block in (content or []):
        if not hasattr(block, "type"):
            continue
        if block.type == "tool_use":
            params = json.dumps(block.input)
            if len(params) > 120:
                params = params[:117] + "..."
            lines.append(f"        tool  {block.name}({params})")
        elif block.type == "text" and block.text.strip():
            text = block.text.replace("\n", " ")
            if len(text) > 200:
                text = text[:197] + "..."
            lines.append(f"        text  \"{text}\"")
    return lines


# ── Bedrock helpers ───────────────────────────────────────────────────────────

# Cache_control on the last tool caches all 43 tool definitions as one prefix block.
_BEDROCK_TOOLS = [
    {**t, "cache_control": {"type": "ephemeral"}} if i == len(ALL_TOOLS) - 1 else t
    for i, t in enumerate(ALL_TOOLS)
]

_BEDROCK_SYSTEM = [
    {"type": "text", "text": SYSTEM_PROMPT, "cache_control": {"type": "ephemeral"}}
]


def _to_bedrock_json(content) -> list:
    """Serialize SDK content objects or plain strings into JSON-serializable dicts."""
    if isinstance(content, str):
        return [{"type": "text", "text": content}]
    if not isinstance(content, list):
        return content
    result = []
    for item in content:
        if isinstance(item, dict):
            result.append(item)
        elif hasattr(item, "model_dump"):
            result.append(item.model_dump(exclude_none=True))
        else:
            result.append({"type": "text", "text": str(item)})
    return result


class _BedrockBearerClient:
    """
    Minimal Bedrock client using long-lived Bearer token auth (BEDROCK_API_KEY).
    Calls /model/{model}/invoke with the native Anthropic request/response format.
    Interface mirrors AnthropicBedrock so AgenticRunner needs no changes.
    """

    def __init__(self, api_key: str, region: str):
        import httpx
        self._api_key = api_key
        self._region  = region
        self._http    = httpx.Client(timeout=120)
        self.messages = self

    def create(self, *, model, max_tokens, system, tools, messages):
        import anthropic
        url = (
            f"https://bedrock-runtime.{self._region}.amazonaws.com"
            f"/model/{model}/invoke"
        )
        body = {
            "anthropic_version": "bedrock-2023-05-31",
            "max_tokens": max_tokens,
            "system":   _to_bedrock_json(system),
            "tools":    tools,
            "messages": [
                {"role": m["role"], "content": _to_bedrock_json(m["content"])}
                for m in messages
            ],
        }
        resp = self._http.post(url, json=body, headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {self._api_key}",
        })
        resp.raise_for_status()
        return anthropic.types.Message.model_validate(resp.json())


def _make_bedrock_client(region: str | None = None):
    """
    Returns a Bedrock client.

    If BEDROCK_API_KEY is set: uses Bearer token auth — long-lived key, no
    AWS credentials needed.

    Otherwise: uses AnthropicBedrock with SigV4 (requires
    AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY / AWS_SESSION_TOKEN).
    """
    r = region or os.environ.get("AWS_REGION", "us-west-2")

    api_key = os.environ.get("BEDROCK_API_KEY")
    if api_key:
        return _BedrockBearerClient(api_key=api_key, region=r)

    try:
        import anthropic
        return anthropic.AnthropicBedrock(aws_region=r)
    except ImportError:
        raise RuntimeError(
            "boto3/botocore not installed. Run: pip install boto3\n"
            "Or set BEDROCK_API_KEY to use Bearer token auth instead."
        )


# ── claude-p subprocess helpers ───────────────────────────────────────────────

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


# ── Bedrock mock-plan ────────────────────────────────────────────────────────

def bedrock_plan_scenario(
    scenario: dict,
    model: str = "us.anthropic.claude-sonnet-4-6",
    verbose: bool = False,
    executor=None,
) -> tuple[list[list[dict]] | None, str | None]:
    """
    Run mock-plan for all turns using Bedrock native tool use.

    Runs a full tool-call mini-loop per turn (same as AgenticRunner) so that
    sequential gather chains (list_domains → list_entity_type_schemas → …) are
    fully resolved within a single turn.  Tool calls are executed through the
    executor (MockExecutor) so real results flow into each subsequent call.
    The turn ends when Claude's stop_reason is not "tool_use" (i.e. it gave a
    text response — typically the gather summary / write confirmation).

    Returns (list_of_turn_call_lists, error) — same shape as harness.run_scenario().
    """
    client = _make_bedrock_client()
    all_turn_calls: list[list[dict]] = []
    messages:       list[dict]       = []
    call_num = 0

    for turn_idx, turn in enumerate(scenario["turns"]):
        messages.append({"role": "user", "content": turn["user_message"]})
        turn_calls: list[dict] = []

        while True:
            call_num += 1
            try:
                t0       = time.perf_counter()
                response = client.messages.create(
                    model      = model,
                    max_tokens = 4096,
                    system     = _BEDROCK_SYSTEM,
                    tools      = _BEDROCK_TOOLS,
                    messages   = messages,
                )
                duration_ms = int((time.perf_counter() - t0) * 1000)
            except Exception as exc:
                return None, f"Turn {turn_idx + 1}: {exc}"

            if verbose:
                u = response.usage
                print(
                    f"  [turn {turn_idx + 1} call {call_num}] {duration_ms:,}ms  "
                    f"in={u.input_tokens}  "
                    f"hit={getattr(u, 'cache_read_input_tokens', 0)}  "
                    f"new={getattr(u, 'cache_creation_input_tokens', 0)}  "
                    f"out={u.output_tokens}"
                )

            tool_blocks = [b for b in response.content if b.type == "tool_use"]
            for b in tool_blocks:
                turn_calls.append({"tool": b.name, "params": b.input})

            if response.stop_reason != "tool_use" or not tool_blocks or executor is None:
                # Claude is done with this turn (gave text response), or no executor
                messages.append({"role": "assistant", "content": response.content})
                break

            # Execute tools and loop
            tool_results = []
            for block in tool_blocks:
                try:
                    result = executor.call(block.name, block.input)
                except Exception as exc:
                    result = {"error": str(exc)}
                tool_results.append({
                    "type":        "tool_result",
                    "tool_use_id": block.id,
                    "content":     json.dumps(result),
                })
            messages.append({"role": "assistant", "content": response.content})
            messages.append({"role": "user",      "content": tool_results})

        all_turn_calls.append(turn_calls)

    return all_turn_calls, None


# ── Runner ────────────────────────────────────────────────────────────────────

class AgenticRunner:
    """
    Runs scenarios using an agentic loop.

    backend="bedrock"  — Anthropic SDK via AWS Bedrock (default).
                         Uses native tool use: structured output, no wasted
                         explanation tokens, prompt caching on system + tools.
                         Requires AWS credentials in environment.
    backend="claude-p" — claude -p subprocess loop.
                         No AWS credentials needed; claude CLI must be on PATH.
                         Claude outputs JSON text; explanation tokens are wasted.

    Tool calls are routed to the injected executor (MockExecutor or LiveExecutor).
    """

    def __init__(self, executor, model: str | None = None, backend: str = "bedrock"):
        self._executor = executor
        self._backend  = backend
        if backend == "bedrock":
            self._model   = model or "us.anthropic.claude-sonnet-4-6"
            self._bedrock = _make_bedrock_client()
        else:
            self._model   = model or "claude-sonnet-4-6"
            self._bedrock = None

    # ── Public API ────────────────────────────────────────────────────────

    def run_scenario(
        self, scenario: dict, verbose: bool = False
    ) -> tuple[list[list[str]] | None, dict | None, str | None]:
        """
        Run all turns of a scenario.
        Returns (list_of_tool_name_lists_per_turn, scenario_stats, error).
        """
        if hasattr(self._executor, "set_scenario"):
            self._executor.set_scenario(scenario)

        # Bedrock: persistent full-fidelity message history carried across turns so
        # Claude can reference prior tool results without re-fetching them.
        self._bedrock_messages: list[dict] = []

        prior_turns:         list[dict]      = []
        all_turn_tool_names: list[list[str]] = []
        all_turn_stats:      list[dict]      = []
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

    def _call_log_interaction(self, user_message: str) -> None:
        """Fire-and-forget audit call — not an MCP tool, not visible to Claude."""
        try:
            self._executor.call("log_interaction", {
                "message_text": user_message,
                "response_text": "[harness]",
                "status": "success",
            })
        except Exception:
            pass

    # ── Internal dispatch ─────────────────────────────────────────────────

    def _run_turn(
        self, user_message: str, prior_turns: list[dict], verbose: bool
    ) -> tuple[list[str], dict]:
        if self._backend == "bedrock":
            return self._run_turn_bedrock(user_message, prior_turns, verbose)
        return self._run_turn_claude_p(user_message, prior_turns, verbose)

    # ── Bedrock backend ───────────────────────────────────────────────────

    def _run_turn_bedrock(
        self, user_message: str, prior_turns: list[dict], verbose: bool
    ) -> tuple[list[str], dict]:
        """
        Native tool-use agentic loop via Anthropic SDK + Bedrock.
        Maintains a proper messages array; no explanation tokens wasted.
        System prompt and tool definitions are prompt-cached.
        """
        tool_names:  list[str] = []
        turn_totals = _empty_totals()
        call_num    = 0

        # Continue from accumulated full history (tool results included) so Claude
        # doesn't re-read data gathered in prior turns.
        messages: list[dict] = list(self._bedrock_messages)
        messages.append({"role": "user", "content": user_message})

        while True:
            call_num += 1

            if verbose:
                n = len(messages)
                print(f"\n    [call {call_num}]")
                print(f"      request  ({n} message{'s' if n != 1 else ''})")
                for line in _fmt_request_messages(messages):
                    print(line)

            t0 = time.perf_counter()
            response = self._bedrock.messages.create(
                model      = self._model,
                max_tokens = 4096,
                system     = _BEDROCK_SYSTEM,
                tools      = _BEDROCK_TOOLS,
                messages   = messages,
            )
            duration_ms = int((time.perf_counter() - t0) * 1000)

            usage = response.usage
            call_stats = {
                "duration_api_ms":       duration_ms,
                "input_tokens":          usage.input_tokens,
                "cache_creation_tokens": getattr(usage, "cache_creation_input_tokens", 0),
                "cache_read_tokens":     getattr(usage, "cache_read_input_tokens", 0),
                "output_tokens":         usage.output_tokens,
                "cost_usd":              0.0,  # Bedrock does not return cost
            }
            _accumulate(turn_totals, call_stats)

            if verbose:
                print(f"      response  {_fmt_call_stats(call_stats)}")
                for line in _fmt_response_blocks(response.content):
                    print(line)

            tool_blocks = [b for b in response.content if b.type == "tool_use"]

            if response.stop_reason != "tool_use" or not tool_blocks:
                messages.append({"role": "assistant", "content": response.content})
                break

            tool_results = []
            for block in tool_blocks:
                name   = block.name
                params = block.input
                tool_names.append(name)
                try:
                    result = self._executor.call(name, params)
                except Exception as exc:
                    result = {"error": str(exc)}
                tool_results.append({
                    "type":        "tool_result",
                    "tool_use_id": block.id,
                    "content":     json.dumps(result),
                })

            messages.append({"role": "assistant", "content": response.content})
            messages.append({"role": "user",      "content": tool_results})

        # Persist full history for the next turn (tool results, proposal text, etc.)
        self._bedrock_messages = messages
        self._call_log_interaction(user_message)
        return tool_names, turn_totals

    # ── claude-p backend ──────────────────────────────────────────────────

    def _run_turn_claude_p(
        self, user_message: str, prior_turns: list[dict], verbose: bool
    ) -> tuple[list[str], dict]:
        """
        Subprocess agentic loop via claude -p --output-format json.
        Rebuilds a full text prompt each step. No AWS credentials needed.
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

        self._call_log_interaction(user_message)
        return tool_names, turn_totals

    def _build_step_prompt(
        self,
        user_message: str,
        prior_turns:  list[dict],
        step_results: list[dict],
    ) -> str:
        """Build the prompt for one claude-p agentic loop iteration."""
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
