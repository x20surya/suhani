"""Headless E2E probe: joins the room like the browser would, publishes
speech-like audio bursts, and counts audio frames received back from the agent.
Reproduces the full media path without a browser or microphone.

Run: python -m silai.probe_participant [room-name]
"""

import asyncio
import sys

import numpy as np

from livekit import api as lk_api
from livekit import rtc

from . import config

ROOM = sys.argv[1] if len(sys.argv) > 1 else "probe-room"
SR = 48000
FRAME_MS = 20
DURATION_S = 25


async def main() -> None:
    token = (
        lk_api.AccessToken(config.LIVEKIT_API_KEY, config.LIVEKIT_API_SECRET)
        .with_identity("probe")
        .with_name("probe")
        .with_grants(lk_api.VideoGrants(room_join=True, room=ROOM))
        .to_jwt()
    )

    room = rtc.Room()
    received = {"frames": 0, "seconds": 0.0}
    tasks: list[asyncio.Task] = []

    @room.on("track_subscribed")
    def on_sub(track, pub, participant):
        print(f"SUBSCRIBED: {participant.identity} published {track.kind}", flush=True)
        if track.kind == rtc.TrackKind.KIND_AUDIO:
            async def read() -> None:
                stream = rtc.AudioStream(track)
                async for ev in stream:
                    received["frames"] += 1
                    received["seconds"] += ev.frame.samples_per_channel / ev.frame.sample_rate
            tasks.append(asyncio.create_task(read()))

    @room.on("participant_connected")
    def on_join(p):
        print(f"PARTICIPANT JOINED: {p.identity}", flush=True)

    @room.on("disconnected")
    def on_disc(*a):
        print("DISCONNECTED from room", flush=True)

    await room.connect(config.LIVEKIT_URL, token)
    print(f"connected to {ROOM}; existing participants: "
          f"{[p.identity for p in room.remote_participants.values()]}", flush=True)

    source = rtc.AudioSource(SR, 1)
    track = rtc.LocalAudioTrack.create_audio_track("mic", source)
    await room.local_participant.publish_track(
        track, rtc.TrackPublishOptions(source=rtc.TrackSource.SOURCE_MICROPHONE)
    )
    print("published audio track", flush=True)

    samples_per_frame = SR * FRAME_MS // 1000
    t = np.arange(samples_per_frame) / SR
    n_frames = DURATION_S * 1000 // FRAME_MS
    for i in range(n_frames):
        # ~1s bursts of tone separated by ~2s silence: enough energy to tickle
        # VAD/turn-detection, silence gaps so the agent takes turns.
        in_burst = (i // 50) % 3 == 0
        amp = 9000 if in_burst else 0
        samples = (amp * np.sin(2 * np.pi * 230 * (t + i * FRAME_MS / 1000))).astype(np.int16)
        frame = rtc.AudioFrame(
            data=samples.tobytes(), sample_rate=SR, num_channels=1,
            samples_per_channel=samples_per_frame,
        )
        await source.capture_frame(frame)
        if i % 250 == 0:
            print(f"t={i * FRAME_MS / 1000:.0f}s agent_audio={received['seconds']:.1f}s", flush=True)

    await asyncio.sleep(3)
    print(f"RESULT: received {received['seconds']:.1f}s of agent audio "
          f"in {received['frames']} frames", flush=True)
    verdict = "PROBE PASS - agent is audible" if received["seconds"] > 1 else "PROBE FAIL - agent silent"
    print(verdict, flush=True)
    for task in tasks:
        task.cancel()
    await room.disconnect()


if __name__ == "__main__":
    asyncio.run(main())
