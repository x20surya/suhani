import { Room, RoomEvent } from "livekit-client";

/**
 * Force playback out of the phone's loudspeaker instead of the earpiece.
 *
 * WHY THIS IS WEIRD: Chrome on Android has no way to choose an audio OUTPUT.
 * `setSinkId` is not implemented there, and `room.switchActiveDevice('audiooutput', ...)`
 * does not fail softly - it THROWS ("cannot switch audio output, the current
 * browser does not support it", livekit-client 2.21.0). Never call it here.
 *
 * The lever that does exist is the INPUT list. While a mic track with
 * echoCancellation is live, Chromium puts Android into MODE_IN_COMMUNICATION,
 * where playback rides the voice-call stream and the platform prefers the
 * earpiece. Chromium then picks a "communication device" whose choice governs
 * BOTH capture and playback, and exposes it as a pseudo-microphone labelled
 * "Speakerphone". Selecting that input therefore moves the output too.
 *
 * Chromium's own default order is headset > USB > Bluetooth > speakerphone and
 * never the earpiece, so this is usually a no-op that simply confirms the good
 * state. It matters on handsets where that selection fails.
 */

// Chromium labels the Android communication devices in English, but OEM builds
// and locales vary, so match generously rather than on one exact string.
const SPEAKER = /speaker|loud/i;
const HEADSET = /(wired headset|bluetooth|usb audio|headphone|earpiece|headset)/i;

/** Everything we know about audio routing, for on-device diagnosis. */
export interface AudioDiagnostics {
  /** Every audioinput device the browser will admit to. */
  inputs: { label: string; deviceId: string; isActive: boolean }[];
  /** Whether labels are populated at all (they need mic permission + a live stream). */
  labelsVisible: boolean;
  activeLabel: string | null;
  speakerFound: boolean;
  headsetFound: boolean;
  userAgent: string;
}

export async function audioDiagnostics(room: Room): Promise<AudioDiagnostics> {
  const inputs = await audioInputs();
  const activeId = room.getActiveDevice("audioinput");
  const active = inputs.find((d) => d.deviceId === activeId);
  return {
    inputs: inputs.map((d) => ({
      label: d.label || "(no label)",
      deviceId: d.deviceId.slice(0, 8),
      isActive: d.deviceId === activeId,
    })),
    labelsVisible: inputs.some((d) => Boolean(d.label)),
    activeLabel: active?.label || null,
    speakerFound: inputs.some((d) => SPEAKER.test(d.label)),
    headsetFound: inputs.some((d) => HEADSET.test(d.label)),
    userAgent: navigator.userAgent,
  };
}

export type RouteResult =
  /** Speakerphone is now the active communication device. */
  | "speaker"
  /** Already on speakerphone; no track was restarted. */
  | "already"
  /** A headset is present - deliberately left alone. */
  | "headset"
  /** No speakerphone pseudo-device: desktop, iOS, or labels not yet granted. */
  | "unsupported"
  /** Selection was attempted and refused by the OS. */
  | "failed";

async function audioInputs(): Promise<MediaDeviceInfo[]> {
  try {
    const devices = await navigator.mediaDevices.enumerateDevices();
    return devices.filter((d) => d.kind === "audioinput");
  } catch {
    return [];
  }
}

export async function forceLoudspeaker(room: Room): Promise<RouteResult> {
  const inputs = await audioInputs();
  if (inputs.length === 0) return "unsupported";

  // Never hijack a headset she plugged in on purpose.
  if (inputs.some((d) => HEADSET.test(d.label))) return "headset";

  const speaker = inputs.find((d) => SPEAKER.test(d.label));
  if (!speaker) return "unsupported";

  // Idempotent: switching restarts the mic track, so do nothing if already set.
  // Without this the visibilitychange listener would cut her mic every time she
  // glanced at another app.
  if (room.getActiveDevice("audioinput") === speaker.deviceId) return "already";

  const previous = room.getActiveDevice("audioinput");
  try {
    const ok = await room.switchActiveDevice("audioinput", speaker.deviceId, true);
    return ok ? "speaker" : "failed";
  } catch (err) {
    // exact:true means an unavailable device fails getUserMedia outright, which
    // would leave her with no microphone at all. Put it back.
    console.warn("[audio] speakerphone selection failed, restoring mic", err);
    try {
      await room.switchActiveDevice("audioinput", previous ?? "default", false);
    } catch (restoreErr) {
      console.error("[audio] mic restore also failed", restoreErr);
    }
    return "failed";
  }
}

/** Which communication device is live, by label. Android only; null elsewhere. */
export async function currentRoute(room: Room): Promise<{ label: string; isSpeaker: boolean } | null> {
  const id = room.getActiveDevice("audioinput");
  if (!id) return null;
  const device = (await audioInputs()).find((d) => d.deviceId === id);
  if (!device) return null;
  return { label: device.label, isSpeaker: SPEAKER.test(device.label) };
}

/**
 * Keep the loudspeaker selected for the life of the room.
 * Android re-evaluates routing when a real call arrives, when Bluetooth
 * connects, and when the app is resumed, so this is not a one-shot.
 */
export function installLoudspeakerGuard(
  room: Room,
  onResult?: (result: RouteResult) => void,
): () => void {
  let disposed = false;
  const run = () => {
    void forceLoudspeaker(room).then((result) => {
      if (!disposed) onResult?.(result);
    });
  };
  const onVisible = () => {
    if (document.visibilityState === "visible") run();
  };

  room.on(RoomEvent.MediaDevicesChanged, run);
  room.on(RoomEvent.LocalTrackPublished, run);
  navigator.mediaDevices?.addEventListener?.("devicechange", run);
  document.addEventListener("visibilitychange", onVisible);
  run();

  return () => {
    disposed = true;
    room.off(RoomEvent.MediaDevicesChanged, run);
    room.off(RoomEvent.LocalTrackPublished, run);
    navigator.mediaDevices?.removeEventListener?.("devicechange", run);
    document.removeEventListener("visibilitychange", onVisible);
  };
}
