import { useCallback, useEffect, useRef, useState } from "react";
import {
  LiveKitRoom,
  useAudioPlayback,
  useConnectionState,
  useLocalParticipant,
  useRoomContext,
  useVoiceAssistant,
} from "@livekit/components-react";
import { ConnectionState, MediaDeviceFailure } from "livekit-client";
import type { AgentState } from "@livekit/components-react";
import { fetchToken, UnauthorizedError, type TokenResponse } from "../lib/api";
import {
  forceLoudspeaker,
  installLoudspeakerGuard,
  type RouteResult,
} from "../audio/forceLoudspeaker";
import { BoostedAgentAudio } from "../audio/BoostedAgentAudio";
import { loadGain, saveGain, MIN_GAIN, MAX_GAIN } from "../audio/volume";
import { SuhaniAura } from "./SuhaniAura";
import { Briefing } from "./Briefing";

type Phase = "off" | "connecting" | "live" | "failed";

type Mood = "idle" | "connecting" | "listening" | "thinking" | "speaking" | "error";

/** Our resolved mood -> the state the Aura shader animates against. */
const MOOD_TO_AGENT_STATE: Record<Mood, AgentState> = {
  idle: "idle",
  connecting: "connecting",
  listening: "listening",
  thinking: "thinking",
  speaking: "speaking",
  error: "failed",
};

export function VoiceScreen({ onOpenLedger }: { onOpenLedger: () => void }) {
  const [phase, setPhase] = useState<Phase>("off");
  const [creds, setCreds] = useState<TokenResponse | null>(null);
  const [errorText, setErrorText] = useState<string | null>(null);
  // Guards the double-tap race: a second tap during connect must not start a
  // second room, and must never leave the mic open under an idle-looking UI.
  const busy = useRef(false);

  const connect = useCallback(async () => {
    if (busy.current) return;
    busy.current = true;
    setErrorText(null);
    setPhase("connecting");
    try {
      setCreds(await fetchToken());
    } catch (err) {
      console.error(err);
      setPhase("failed");
      // "Not set up on this device" and "the server is down" are different
      // problems with different fixes, so they must not read the same.
      setErrorText(
        err instanceof UnauthorizedError
          ? "This device is not set up - open the setup link again"
          : "Couldn't connect - tap again",
      );
      busy.current = false;
    }
  }, []);

  const disconnect = useCallback(() => {
    setCreds(null);
    setPhase("off");
    busy.current = false;
  }, []);

  return (
    <div className="flex min-h-dvh flex-col gap-4 px-5 pb-[max(env(safe-area-inset-bottom),1.25rem)] pt-[max(env(safe-area-inset-top),1.75rem)]">
      <Header onOpenLedger={onOpenLedger} />

      {creds ? (
        <LiveKitRoom
          serverUrl={creds.url}
          token={creds.token}
          connect
          audio={{ echoCancellation: true, noiseSuppression: true, autoGainControl: true }}
          className="contents"
          onConnected={() => {
            busy.current = false;
            setPhase("live");
          }}
          onDisconnected={disconnect}
          onError={(err) => {
            console.error(err);
            setErrorText("Couldn't connect - tap again");
            setPhase("failed");
            setCreds(null);
            busy.current = false;
          }}
          onMediaDeviceFailure={(failure) => {
            setErrorText(
              failure === MediaDeviceFailure.PermissionDenied
                ? "Microphone blocked - allow it in your browser settings"
                : "No microphone found",
            );
          }}
        >
          {/* No RoomAudioRenderer: LiveSession plays Suhani through a gain
              graph instead, because an <audio> element cannot exceed volume
              1.0 and her audio rides Android's quiet voice-call stream. */}
          <LiveSession externalError={errorText} onHangUp={disconnect} />
        </LiveKitRoom>
      ) : (
        <IdleStage phase={phase} errorText={errorText} onConnect={connect} />
      )}
    </div>
  );
}

function Header({ onOpenLedger }: { onOpenLedger: () => void }) {
  const today = new Date().toLocaleDateString("en-IN", {
    weekday: "long",
    day: "numeric",
    month: "long",
  });

  return (
    <header className="flex items-start justify-between">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Suhani</h1>
        <p className="mt-0.5 text-sm text-[var(--muted-foreground)]">{today}</p>
      </div>
      <button
        onClick={onOpenLedger}
        className="min-h-12 rounded-[var(--radius)] border px-4 text-sm font-medium transition-colors hover:bg-[var(--muted)] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--primary)]"
      >
        Ledger
      </button>
    </header>
  );
}

/** Not in a room: the orb is the button. */
function IdleStage({
  phase,
  errorText,
  onConnect,
}: {
  phase: Phase;
  errorText: string | null;
  onConnect: () => void;
}) {
  const connecting = phase === "connecting";

  return (
    <>
      <div className="flex flex-1 flex-col items-center justify-center gap-6">
        <button
          onClick={onConnect}
          disabled={connecting}
          aria-label="Talk to Suhani"
          className="rounded-full transition-transform active:scale-[0.97] disabled:cursor-wait focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-[var(--primary)]"
        >
          <SuhaniAura state={connecting ? "connecting" : "idle"} />
        </button>

        <Caption
          text={connecting ? "Connecting..." : (errorText ?? "Tap to talk")}
          tone={phase === "failed" ? "error" : "normal"}
        />
      </div>
      <Briefing />
    </>
  );
}

/** Inside the room: real agent state, real audio levels. */
function LiveSession({
  externalError,
  onHangUp,
}: {
  externalError: string | null;
  onHangUp: () => void;
}) {
  const room = useRoomContext();
  const connectionState = useConnectionState();
  const { agent, state: agentState, audioTrack: agentTrack } = useVoiceAssistant();
  const { localParticipant, microphoneTrack, isMicrophoneEnabled } = useLocalParticipant();
  const { canPlayAudio, startAudio } = useAudioPlayback(room);

  const micTrack = microphoneTrack?.audioTrack;

  // If the worker is down, no agent ever joins and agentState never leaves its
  // initial value. Without this she would be told "Listening" into an empty
  // room forever. Fail loudly instead, same 12s budget as before.
  const [waitedTooLong, setWaitedTooLong] = useState(false);
  useEffect(() => {
    if (agent) {
      setWaitedTooLong(false);
      return;
    }
    const timer = setTimeout(() => setWaitedTooLong(true), 12_000);
    return () => clearTimeout(timer);
  }, [agent]);

  const reconnecting = connectionState === ConnectionState.Reconnecting;
  const micDead = !isMicrophoneEnabled || !micTrack || microphoneTrack?.isMuted === true;

  const { mood, caption, tone } = describe({
    agentState,
    agentPresent: Boolean(agent),
    waitedTooLong,
    reconnecting,
    micDead,
    externalError,
  });

  // The aura is driven by OUR resolved mood, not the raw agent state, so a
  // dead mic or a dropped network cannot leave it happily pulsing "listening".
  const auraState: AgentState = MOOD_TO_AGENT_STATE[mood];
  // While Suhani speaks, visualize her voice. Otherwise visualize her mother's
  // own mic, which is the only honest answer to "is it hearing me?".
  const auraTrack = mood === "speaking" ? agentTrack : micTrack;

  useEffect(() => {
    if (!localParticipant) return;
    void localParticipant.setMicrophoneEnabled(true).catch(() => {
      /* surfaced through onMediaDeviceFailure */
    });
  }, [localParticipant]);

  // Keep audio on the loudspeaker: she puts the phone down on the table while
  // she sews, and Android's voice-call routing would otherwise send Suhani to
  // the earpiece where she cannot hear a thing.
  const [route, setRoute] = useState<RouteResult | null>(null);
  useEffect(() => installLoudspeakerGuard(room, setRoute), [room]);

  const [gain, setGain] = useState(loadGain);
  const changeGain = (value: number) => {
    setGain(value);
    saveGain(value);
  };

  if (!canPlayAudio) {
    return (
      <div className="flex flex-1 flex-col items-center justify-center gap-6">
        <SuhaniAura state="idle" />
        <div className="flex flex-col items-center gap-4">
          <Caption text="Sound is blocked" tone="error" />
          <button
            onClick={() => void startAudio()}
            className="min-h-12 rounded-[var(--radius)] bg-[var(--primary)] px-6 font-medium text-[var(--primary-foreground)] transition-transform active:scale-[0.98]"
          >
            Enable sound
          </button>
        </div>
      </div>
    );
  }

  return (
    <>
      <BoostedAgentAudio track={agentTrack} gain={gain} />

      <div className="flex flex-1 flex-col items-center justify-center gap-6">
        {/* The aura alone. It already encodes every state through shape, speed
            and brightness, so a mic glyph or bars on top of it are noise. */}
        <SuhaniAura state={auraState} audioTrack={auraTrack} />
        <Caption text={caption} tone={tone} />
      </div>

      <div className="flex w-full flex-col items-center gap-3 pb-2">
        {/* Loudness control. Her audio rides Android's voice-call stream, whose
            volume slider is separate from media volume and only movable with
            the hardware rocker DURING a call - a control she will not find.
            This is a real gain stage, so it works regardless of that slider. */}
        <div className="flex w-full max-w-xs items-center gap-3">
          <span aria-hidden className="text-sm opacity-60">🔈</span>
          <input
            type="range"
            aria-label="Suhani's loudness"
            min={MIN_GAIN}
            max={MAX_GAIN}
            step={0.25}
            value={gain}
            onChange={(e) => changeGain(Number(e.target.value))}
            className="h-11 flex-1 accent-[var(--primary)]"
          />
          <span aria-hidden className="text-lg opacity-60">🔊</span>
        </div>

        {/* Shown whenever we are NOT confidently on the loudspeaker. Previously
            this only appeared on "failed", so a phone that exposed no
            speakerphone device got no control and no explanation at all. */}
        {(route === "failed" || route === "unsupported") && (
          <button
            onClick={() => void forceLoudspeaker(room).then(setRoute)}
            className="min-h-12 rounded-[var(--radius)] bg-[var(--primary)] px-6 text-sm font-medium text-[var(--primary-foreground)] transition-transform active:scale-[0.98]"
          >
            🔊 Play out loud
          </button>
        )}
        <button
          onClick={onHangUp}
          className="min-h-12 rounded-[var(--radius)] border px-6 text-sm font-medium text-[var(--muted-foreground)] transition-colors hover:bg-[var(--muted)] active:scale-[0.98]"
        >
          End conversation
        </button>
      </div>
    </>
  );
}

function describe({
  agentState,
  agentPresent,
  waitedTooLong,
  reconnecting,
  micDead,
  externalError,
}: {
  agentState: string;
  agentPresent: boolean;
  waitedTooLong: boolean;
  reconnecting: boolean;
  micDead: boolean;
  externalError: string | null;
}): { mood: Mood; caption: string; tone: "normal" | "error" } {
  // Network and microphone truth outrank whatever the agent last told us:
  // no attribute updates arrive while the connection is down, so a stale
  // "listening" would freeze the caption mid-lie.
  if (reconnecting) {
    return { mood: "connecting", caption: "Network dropped, reconnecting...", tone: "error" };
  }
  if (externalError) return { mood: "error", caption: externalError, tone: "error" };
  if (micDead) return { mood: "error", caption: "Can't hear you", tone: "error" };

  // Nobody on the other end. Never claim to be listening.
  if (!agentPresent || agentState === "disconnected" || agentState === "failed") {
    return waitedTooLong
      ? { mood: "error", caption: "Suhani didn't answer - tap again", tone: "error" }
      : { mood: "connecting", caption: "Waking Suhani...", tone: "normal" };
  }

  switch (agentState) {
    case "connecting":
    case "initializing":
    case "pre-connect-buffering":
      return { mood: "connecting", caption: "Waking Suhani...", tone: "normal" };
    case "thinking":
      return { mood: "thinking", caption: "Thinking...", tone: "normal" };
    case "speaking":
      return { mood: "speaking", caption: "Suhani is speaking", tone: "normal" };
    case "listening":
    case "idle":
    default:
      return { mood: "listening", caption: "Listening", tone: "normal" };
  }
}

function Caption({ text, tone }: { text: string; tone: "normal" | "error" }) {
  return (
    <p
      role="status"
      aria-live="polite"
      className={`min-h-9 text-center text-[1.75rem] font-medium leading-tight tracking-tight ${
        tone === "error" ? "text-[var(--destructive)]" : "text-[var(--foreground)]"
      }`}
    >
      {text}
    </p>
  );
}

