import { useEffect, useRef } from "react";
import type { TrackReference } from "@livekit/components-react";

/**
 * Plays Suhani's audio through a Web Audio GainNode so it can be amplified
 * BEYOND the normal maximum.
 *
 * WHY: an <audio> element's .volume is hard-capped at 1.0, so neither
 * RoomAudioRenderer's `volume` prop nor anything else in the DOM can make her
 * louder than the source. On Android her audio also rides the voice-CALL
 * stream (a live mic capture puts the device in communication mode), which has
 * its own volume slider separate from media volume - so a phone that looks
 * "turned up" can still play her quietly.
 *
 * A GainNode with gain > 1 is real amplification and sidesteps both limits.
 *
 * Kept deliberately conservative: past ~3x, Bulbul's output starts clipping on
 * a small phone speaker, which is worse than quiet.
 */

interface Props {
  track: TrackReference | undefined;
  /** 1 = untouched. 2 = twice the amplitude. */
  gain: number;
}

export function BoostedAgentAudio({ track, gain }: Props) {
  const elementRef = useRef<HTMLAudioElement | null>(null);
  const ctxRef = useRef<AudioContext | null>(null);
  const gainRef = useRef<GainNode | null>(null);
  const sourceRef = useRef<MediaStreamAudioSourceNode | null>(null);

  // Attach the track to a muted element and route it through the gain graph.
  useEffect(() => {
    const publication = track?.publication;
    const mediaTrack = publication?.track;
    const el = elementRef.current;
    if (!mediaTrack || !el) return;

    mediaTrack.attach(el);

    let cancelled = false;
    try {
      const ctx = new AudioContext();
      ctxRef.current = ctx;

      const stream = new MediaStream([mediaTrack.mediaStreamTrack]);
      const source = ctx.createMediaStreamSource(stream);
      const gainNode = ctx.createGain();
      gainNode.gain.value = gain;
      source.connect(gainNode).connect(ctx.destination);
      sourceRef.current = source;
      gainRef.current = gainNode;

      // The element is only the attachment point; the audible path is the
      // gain graph. Leaving both unmuted would play her twice.
      el.muted = true;

      // Chrome starts contexts suspended until a gesture. She has already
      // tapped to start the call, so this normally resumes immediately.
      void ctx.resume().catch(() => {
        /* falls back below */
      });
    } catch (err) {
      // No Web Audio: play the element directly rather than going silent.
      console.warn("[audio] gain graph unavailable, playing unboosted", err);
      if (!cancelled) el.muted = false;
    }

    return () => {
      cancelled = true;
      try {
        sourceRef.current?.disconnect();
        gainRef.current?.disconnect();
        void ctxRef.current?.close();
      } catch {
        /* already torn down */
      }
      ctxRef.current = null;
      gainRef.current = null;
      sourceRef.current = null;
      mediaTrack.detach(el);
    };
  }, [track, gain]);

  // Live gain changes without rebuilding the graph.
  useEffect(() => {
    if (gainRef.current) gainRef.current.gain.value = gain;
  }, [gain]);

  // Autoplay recovery. room.startAudio() resumes LiveKit's own elements, not
  // this AudioContext, so without this a blocked context stays suspended and
  // she gets total silence with every other indicator looking healthy.
  useEffect(() => {
    const resume = () => {
      const ctx = ctxRef.current;
      if (ctx && ctx.state === "suspended") void ctx.resume().catch(() => {});
    };
    const events: (keyof DocumentEventMap)[] = ["pointerdown", "touchstart", "keydown"];
    events.forEach((e) => document.addEventListener(e, resume, { passive: true }));
    document.addEventListener("visibilitychange", resume);
    return () => {
      events.forEach((e) => document.removeEventListener(e, resume));
      document.removeEventListener("visibilitychange", resume);
    };
  }, []);

  return <audio ref={elementRef} autoPlay playsInline />;
}
