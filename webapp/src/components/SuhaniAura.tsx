import { useEffect, useState } from "react";
import type { LocalAudioTrack, RemoteAudioTrack } from "livekit-client";
import type { AgentState, TrackReferenceOrPlaceholder } from "@livekit/components-react";
import { AgentAudioVisualizerAura } from "./aura/aura";

/**
 * The single knob for the aura's colour. Swap this one line for a different
 * look - LiveKit's own default is the cyan "#1FD5F9".
 *
 * This is a LUMINOUS rose, not --primary itself. The shader multiplies this
 * colour through its bloom, so a dark accent produces a muddy aura; the token
 * rose (#c2447e) came out dim. Matching the reference's brightness matters
 * more than matching the button's exact hue.
 */
const ACCENT = "#ff4d9e";

/** Responsive: fills a phone without overflowing, caps on wider screens. */
const SIZE_CLASS = "h-[min(74vw,308px)]";

interface SuhaniAuraProps {
  state: AgentState;
  /**
   * Local mic while she is being listened to, the agent's track while Suhani
   * speaks. This is what makes the aura an honest "I can hear you" signal
   * rather than decoration.
   */
  audioTrack?: LocalAudioTrack | RemoteAudioTrack | TrackReferenceOrPlaceholder;
  size?: "sm" | "md" | "lg" | "xl";
}

function useThemeMode(): "dark" | "light" {
  const [mode, setMode] = useState<"dark" | "light">(() =>
    document.documentElement.classList.contains("dark") ? "dark" : "light",
  );

  // The shader takes a different tonemapping path per background, so it has to
  // follow the theme toggle rather than read it once at mount.
  useEffect(() => {
    const observer = new MutationObserver(() =>
      setMode(document.documentElement.classList.contains("dark") ? "dark" : "light"),
    );
    observer.observe(document.documentElement, {
      attributes: true,
      attributeFilter: ["class"],
    });
    return () => observer.disconnect();
  }, []);

  return mode;
}

export function SuhaniAura({ state, audioTrack, size = "lg" }: SuhaniAuraProps) {
  const themeMode = useThemeMode();
  const active = state === "listening" || state === "speaking" || state === "thinking";

  return (
    <div className="relative grid place-items-center">
      {/*
        The glow lives here, not in the shader. The shader's uBloom is pinned
        to 0 upstream because bloom raises alpha across the whole quad and the
        transparent canvas becomes a visible square. A blurred radial behind
        the canvas gives the same halo with no hard edge.
      */}
      <div
        aria-hidden
        className={`pointer-events-none absolute aspect-square w-[62%] rounded-full blur-3xl transition-opacity duration-700 ${
          active ? "opacity-70" : "opacity-40"
        }`}
        style={{ background: `radial-gradient(circle, ${ACCENT}, transparent 68%)` }}
      />
      <AgentAudioVisualizerAura
        size={size}
        state={state}
        color={ACCENT}
        colorShift={0.05}
        themeMode={themeMode}
        audioTrack={audioTrack}
        className={`pointer-events-none relative select-none ${SIZE_CLASS}`}
      />
    </div>
  );
}
