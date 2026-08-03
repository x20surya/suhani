"""The voice agent: LiveKit worker wiring Saaras (ears) + the configured brain + Bulbul (voice).
Model versions live in config: SARVAM_STT_MODEL / SARVAM_TTS_MODEL / BRAIN.

Run:
    python -m silai.agent download-files   # once: VAD + turn-detector models
    python -m silai.agent dev              # local dev (hot reload)
    python -m silai.agent start            # production

Note: written against livekit-agents ~=1.5 as documented for the Sarvam plugin.
If the installed version has drifted, the first `dev` run will name the changed
symbol - expected, quick to fix.
"""

import datetime as dt
import logging
import random

from livekit import agents
from livekit.agents import (
    Agent,
    AgentSession,
    JobContext,
    RunContext,
    WorkerOptions,
    cli,
    function_tool,
    inference,
    metrics,
)
from livekit.plugins import anthropic as lk_anthropic
from livekit.plugins import openai as lk_openai
from livekit.plugins import sarvam
from livekit.plugins.sarvam import stt as _sarvam_stt

from . import config, prompts, tools
from .ledger import Ledger

logger = logging.getLogger("silai.agent")

# livekit-plugins-sarvam 1.6.7 predates Saaras v4. Unknown models do stream,
# but the plugin silently DROPS the mode query param for them - which would
# quietly lose codemix (Hinglish) transcription. Register v4 with v3's
# capabilities until a plugin release knows it natively; verified 2026-08-02
# against the streaming endpoint (codemix + vad signals + flush all honoured).
if "saaras:v4" not in _sarvam_stt.MODEL_CONFIGS:
    _sarvam_stt.MODEL_CONFIGS["saaras:v4"] = _sarvam_stt.ModelConfig(
        supports_prompt=True,
        supports_mode=True,
        supports_language=True,
        supports_vad_params=True,
        default_language="en-IN",
        default_mode="transcribe",
        use_translate_endpoint=False,
        use_translate_method=False,
        # v4's language list may grow past v3's - don't gate on a stale copy.
        allowed_languages=None,
    )

# Spoken instantly when a slow (write/briefing) tool starts, so there is never
# dead air while Claude does its second round trip. Kept out of chat history.
WRITE_FILLERS = ["हाँ, लिखती हूँ...", "ठीक है, लिख रही हूँ..."]
LOOKUP_FILLERS = ["देखती हूँ...", "एक पल..."]

ERROR_LINE = "माफ़ कीजिए, थोड़ी दिक्कत आ गई। एक बार फिर बोलिए।"


def _ack(context: RunContext, fillers: list[str]) -> None:
    """Fire-and-forget filler speech; never let UX sugar break a tool call."""
    try:
        session = getattr(context, "session", None)
        if session is None:
            return
        text = random.choice(fillers)
        try:
            session.say(text, add_to_chat_ctx=False)
        except TypeError:  # parameter drift across livekit-agents versions
            session.say(text)
    except Exception:
        logger.debug("filler ack failed", exc_info=True)


# Sarvam-only tool: deterministic date resolution (its worst tested failure mode
# was weekday arithmetic). Claude never needed it, so it is registered per-brain.
@function_tool(name="resolve_due_date")
async def resolve_due_date(
    context: RunContext,
    weekday: str | None = None,
    days_from_today: int | None = None,
    day_of_month: int | None = None,
    month: int | None = None,
) -> str:
    """तारीख़ पक्की करने का calculator। 'अगले मंगलवार', 'दो हफ़्ते बाद', '15 अगस्त' को सही ISO तारीख़ में बदलता है। due_date भेजने से पहले इसी से तारीख़ लो।

    Args:
        weekday: अगला आने वाला यह वार चाहिए तो (सोमवार...रविवार)।
        days_from_today: आज से इतने दिन बाद (कल=1, परसों=2, दो हफ़्ते=14)।
        day_of_month: महीने की तारीख़, जैसे 15।
        month: 1-12, day_of_month के साथ।
    """
    args = {"weekday": weekday, "days_from_today": days_from_today,
            "day_of_month": day_of_month, "month": month}
    return tools.resolve_due_date({k: v for k, v in args.items() if v is not None})


class SilaiAgent(Agent):
    """All ledger operations exposed as voice tools. Thin wrappers over silai.ledger."""

    def __init__(self, ledger: Ledger) -> None:
        notes = prompts.notes_context(ledger.list_notes())
        parts = [prompts.STABLE_SYSTEM]
        if config.BRAIN == "sarvam":
            # The bake-off's winning "fixed" config: guards + no-arithmetic calendar.
            parts += [prompts.SARVAM_GUARDS, prompts.calendar_table()]
        if notes:
            parts.append(notes)
        parts.append(prompts.date_context())
        super().__init__(
            instructions="\n\n".join(parts),
            tools=[resolve_due_date] if config.BRAIN == "sarvam" else [],
        )
        self.ledger = ledger

    async def llm_node(self, chat_ctx, tools_, model_settings):
        """Pass-through for Claude; for Sarvam, one deterministic retry when the
        model returns nothing at all (rare reasoning-starvation case, ~0.6% in
        testing) - dead air is the worst failure a voice agent can have."""
        saw_output = False
        async for chunk in Agent.default.llm_node(self, chat_ctx, tools_, model_settings):
            if not saw_output:
                if isinstance(chunk, str):
                    saw_output = bool(chunk.strip())
                else:
                    delta = getattr(chunk, "delta", None)
                    saw_output = bool(getattr(delta, "content", None) or getattr(delta, "tool_calls", None))
            yield chunk
        if config.BRAIN == "sarvam" and not saw_output:
            logger.warning("empty LLM reply - retrying once")
            async for chunk in Agent.default.llm_node(self, chat_ctx, tools_, model_settings):
                yield chunk

    # Each tool returns a JSON string; the model narrates it in Hindi per the system prompt.

    @function_tool()
    async def find_client(self, context: RunContext, name: str) -> str:
        """ग्राहक को नाम से ढूँढो (fuzzy)। किसी भी ग्राहक वाले काम से पहले हमेशा यह चलाओ।

        Args:
            name: जैसा नाम सुना, वैसा ही।
        """
        return tools.dispatch(self.ledger, "find_client", {"name": name})

    @function_tool()
    async def add_client(self, context: RunContext, name: str, phone: str | None = None) -> str:
        """नई ग्राहक जोड़ो - सिर्फ़ जब find_client से कोई ना मिले और मालकिन ने पक्का किया हो।

        Args:
            name: ग्राहक का नाम।
            phone: फ़ोन नंबर, अगर बताया हो।
        """
        return tools.dispatch(self.ledger, "add_client", {"name": name, "phone": phone})

    @function_tool()
    async def add_order(
        self,
        context: RunContext,
        client_id: int,
        garment: str,
        price: float,
        due_date: str | None = None,
        advance: float = 0,
        notes: str | None = None,
    ) -> str:
        """नया ऑर्डर लिखो - 'लिख दूँ?' पर हाँ मिलने के बाद ही।

        Args:
            client_id: find_client से मिला id।
            garment: कपड़ा, जैसे ब्लाउज़ / साड़ी फॉल-पीको।
            price: कुल दाम रुपये में।
            due_date: ISO तारीख़ YYYY-MM-DD।
            advance: एडवांस मिला हो तो कितना।
            notes: नाप या ख़ास बात।
        """
        _ack(context, WRITE_FILLERS)
        return tools.dispatch(self.ledger, "add_order", {
            "client_id": client_id, "garment": garment, "price": price,
            "due_date": due_date, "advance": advance, "notes": notes,
        })

    @function_tool()
    async def update_order(
        self,
        context: RunContext,
        order_id: int,
        garment: str | None = None,
        price: float | None = None,
        due_date: str | None = None,
        notes: str | None = None,
        status: str | None = None,
    ) -> str:
        """ऑर्डर बदलो: दाम/तारीख़/notes, या status - open, done, delivered, cancelled।

        Args:
            order_id: ऑर्डर का id।
            garment: नया कपड़ा-नाम।
            price: नया दाम।
            due_date: नई ISO तारीख़।
            notes: notes।
            status: open | done | delivered | cancelled (रहने दो/कैंसिल = cancelled)।
        """
        args = {"order_id": order_id, "garment": garment, "price": price,
                "due_date": due_date, "notes": notes, "status": status}
        return tools.dispatch(self.ledger, "update_order", args)

    @function_tool()
    async def orders_for_client(self, context: RunContext, client_id: int) -> str:
        """एक ग्राहक के खुले ऑर्डर और हर एक का बाकी पैसा।

        Args:
            client_id: ग्राहक का id।
        """
        return tools.dispatch(self.ledger, "orders_for_client", {"client_id": client_id})

    @function_tool()
    async def list_open_orders(self, context: RunContext) -> str:
        """सारे खुले ऑर्डर (ग्राहक, कपड़ा, दाम, तारीख़, notes) - detail से ढूँढने के लिए ('वो maroon वाला किसका?')।"""
        return tools.dispatch(self.ledger, "list_open_orders", {})

    @function_tool()
    async def search_orders(self, context: RunContext, query: str) -> str:
        """सारे ऑर्डर में ढूँढो - बंद (delivered/cancelled) भी। पुराने/दिए जा चुके काम की बात हो तो यही चलाओ।

        Args:
            query: ढूँढने के शब्द, जैसे 'maroon ब्लाउज़' या 'गोटा पत्ती लहंगा'।
        """
        return tools.dispatch(self.ledger, "search_orders", {"query": query})

    @function_tool()
    async def orders_due(self, context: RunContext, date_from: str, date_to: str) -> str:
        """तारीख़-सीमा में देने वाले ऑर्डर। 'आज क्या देना है' = आज से आज।

        Args:
            date_from: ISO तारीख़।
            date_to: ISO तारीख़।
        """
        return tools.dispatch(self.ledger, "orders_due", {"date_from": date_from, "date_to": date_to})

    @function_tool()
    async def check_dues(self, context: RunContext, client_id: int | None = None) -> str:
        """किसका कितना पैसा बाकी है। client_id ना दो तो सबका।

        Args:
            client_id: optional ग्राहक id।
        """
        return tools.dispatch(self.ledger, "check_dues", {"client_id": client_id} if client_id else {})

    @function_tool()
    async def mark_paid(
        self, context: RunContext, client_id: int, amount: float,
        order_id: int | None = None, note: str | None = None,
    ) -> str:
        """पैसे मिले - दर्ज करो (मालकिन से पक्का करके)।

        Args:
            client_id: ग्राहक id।
            amount: कितने रुपये मिले।
            order_id: पता हो तो किस ऑर्डर के।
            note: कोई note।
        """
        _ack(context, WRITE_FILLERS)
        return tools.dispatch(self.ledger, "mark_paid", {
            "client_id": client_id, "amount": amount, "order_id": order_id, "note": note,
        })

    @function_tool()
    async def daily_briefing(self, context: RunContext) -> str:
        """आज का सार: आज देना, कल देना, लेट ऑर्डर, कुल बाकी पैसा।"""
        _ack(context, LOOKUP_FILLERS)
        return tools.dispatch(self.ledger, "daily_briefing", {})

    @function_tool()
    async def remember(self, context: RunContext, content: str) -> str:
        """मालकिन की निजी बात की छोटी याद लिखो - अगली बातचीतों में याद रहेगी। ऑर्डर/पैसे यहाँ नहीं।

        Args:
            content: एक छोटा वाक्य।
        """
        return tools.dispatch(self.ledger, "remember", {"content": content})

    @function_tool()
    async def forget_note(self, context: RunContext, note_id: int) -> str:
        """गलत या पुरानी याद हटाओ।

        Args:
            note_id: यादों की सूची में [id] वाला नंबर।
        """
        return tools.dispatch(self.ledger, "forget_note", {"note_id": note_id})


async def entrypoint(ctx: JobContext) -> None:
    await ctx.connect()
    ledger = Ledger()

    if config.BRAIN == "sarvam":
        # Sarvam-105B at the bake-off's winning fixed/low config (avg ~1.6-2.2s/call).
        # reasoning_effort + max_tokens ride in extra_body exactly as tested; the
        # api-subscription-key header is Sarvam's auth scheme.
        brain = lk_openai.LLM(
            model=config.SARVAM_CHAT_MODEL,
            base_url=config.SARVAM_CHAT_BASE_URL,
            api_key=config.SARVAM_API_KEY,
            extra_headers={"api-subscription-key": config.SARVAM_API_KEY},
            extra_body={"reasoning_effort": config.SARVAM_CHAT_EFFORT,
                        "max_tokens": config.SARVAM_CHAT_MAX_TOKENS},
        )
    else:
        brain = lk_anthropic.LLM(
            model=config.CLAUDE_MODEL,
            max_tokens=512,           # replies are two spoken sentences; keep TTFT low
            caching="ephemeral",      # prompt-cache the system prompt + tools (10x cheaper, faster TTFT)
        )
    logger.info("brain: %s", config.SARVAM_CHAT_MODEL if config.BRAIN == "sarvam" else config.CLAUDE_MODEL)

    session = AgentSession(
        stt=sarvam.STT(
            language="hi-IN",
            model=config.SARVAM_STT_MODEL,
            mode="codemix",           # Hinglish-native transcription
            sample_rate=16000,
            high_vad_sensitivity=True,
            flush_signal=True,
        ),
        llm=brain,
        tts=sarvam.TTS(
            target_language_code="hi-IN",
            model=config.SARVAM_TTS_MODEL,
            speaker=config.VOICE_SPEAKER,
            speech_sample_rate=24000,
            pace=config.VOICE_PACE,               # slightly brisk = upbeat
            temperature=config.VOICE_TEMPERATURE,  # more expressive delivery
        ),
        # Native in-process VAD and end-of-turn detection, both bundled in
        # livekit-local-inference (already a core dependency). This replaces
        # silero.VAD.load() + the deprecated MultilingualModel and removes the
        # separate inference SUBPROCESS - the component whose lost IPC pipe
        # wedged the worker after every session on Windows.
        #
        # Also drops a 461 MB model download from the image build.
        #
        # Cost check: TurnDetector resolves to the cloud "turn-detector-v1"
        # model only when is_hosted() or is_dev_mode(); is_hosted() is just
        # os.getenv("LIVEKIT_REMOTE_EOT_URL") is not None, which LiveKit Cloud
        # sets and we do not. Running `start` on our own box it resolves to
        # turn-detector-v1-mini, which runs LOCALLY and free.
        # Hindi is first class there: inference/eot/languages.py ships a tuned
        # "hi" threshold of 0.3050.
        # activation_threshold raised from the 0.5 default: her workroom has a
        # TV, family and customers in it, and at 0.5 quiet background speech
        # from across the room registers as her talking.
        vad=inference.VAD(model="silero", activation_threshold=0.65),
        # turn_handling, not turn_detection: the latter is deprecated in 1.6.7.
        turn_handling={
            "turn_detection": inference.TurnDetector(),
            # Barge-in stays ON - she must be able to cut Suhani off. These
            # only raise the bar for what COUNTS as an interruption, because
            # the defaults (min_words=0, min_duration=0.5) mean literally any
            # detected speech stops her mid-sentence, including a stray word
            # from someone else in the room.
            "interruption": {
                "enabled": True,
                # NOTE: mode "adaptive" (the ML backchannel detector) is NOT set
                # here on purpose. It requires an STT reporting
                # capabilities.aligned_transcript, and the Sarvam plugin
                # hardcodes False for every model, so livekit disables it and logs
                # "interruption_detection ... not compatible". Setting it just
                # produces a warning and no behaviour. backchannel_boundary is
                # adaptive-only and therefore moot for the same reason.
                #
                # Require real transcribed words, not just sound. This is the
                # single most effective setting for a noisy household: 0 (the
                # default) means any vocal noise wins.
                "min_words": 2,
                # And require it to be sustained. Costs her ~0.3s of extra
                # delay when she genuinely does interrupt - a fair trade.
                "min_duration": 0.8,
                # If the "interruption" turns out to be nothing, pick the
                # sentence back up instead of leaving her in silence.
                "resume_false_interruption": True,
                "false_interruption_timeout": 2.0,
                # Do NOT throw away what she says while Suhani is mid-sentence
                # and uninterruptible. The default (True) substitutes SILENCE on
                # the STT path, so her words simply vanish and she has to repeat
                # herself - which reads as "it messed up".
                "discard_audio_if_uninterruptible": False,
            },
        },
        # Default is 3.0s, during which ALL of her audio is replaced with
        # silence before reaching STT so the echo canceller can converge on the
        # agent's own voice. Suhani no longer speaks first, so at session start
        # there is no agent audio to echo and nothing to converge on - three
        # seconds of deafness at exactly the moment she taps and starts talking
        # is a pure cost. Kept slightly above zero as cheap insurance.
        aec_warmup_duration=0.4,
    )

    # Per-stage latency numbers (STT / end-of-turn / LLM TTFT / TTS TTFB) land in
    # the worker logs - this is the "measured, not hoped" instrumentation.
    usage = metrics.UsageCollector()

    @session.on("metrics_collected")
    def _on_metrics(ev) -> None:
        try:
            metrics.log_metrics(ev.metrics)
            usage.collect(ev.metrics)
        except Exception:
            logger.debug("metrics logging failed", exc_info=True)

    @session.on("error")
    def _on_error(ev) -> None:
        # Never leave dead air: speak a short Hindi line on pipeline errors
        # (e.g. a Sarvam outage) instead of going silent.
        logger.error("session error: %s", getattr(ev, "error", ev))
        try:
            recoverable = getattr(getattr(ev, "error", None), "recoverable", False)
            if not recoverable:
                session.say(ERROR_LINE)
        except Exception:
            logger.debug("error line failed", exc_info=True)

    async def _log_usage() -> None:
        try:
            logger.info("session usage summary: %s", usage.get_summary())
        except Exception:
            logger.debug("usage summary failed", exc_info=True)

    ctx.add_shutdown_callback(_log_usage)

    started = dt.datetime.now()
    await session.start(room=ctx.room, agent=SilaiAgent(ledger))
    logger.info("session started in %.2fs", (dt.datetime.now() - started).total_seconds())

    # NO proactive greeting. It used to be
    #     await session.generate_reply(instructions=prompts.GREETING_INSTRUCTION)
    # which is a full LLM round trip plus TTS before मालकिन could say anything -
    # she had to wait through "waking" then "thinking" then a spoken briefing,
    # and anything she said during it was discarded (see aec_warmup_duration).
    #
    # Suhani now simply listens from the moment the session starts. The screen
    # already says "Listening", and today's work is already on the home card, so
    # nothing is lost by not speaking it aloud unprompted. She can still ask
    # "आज क्या देना है?" whenever she wants.


if __name__ == "__main__":
    cli.run_app(WorkerOptions(entrypoint_fnc=entrypoint))
