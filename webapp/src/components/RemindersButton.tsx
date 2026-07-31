import { useEffect, useState } from "react";
import { enablePushReminders, pushAlreadyEnabled } from "../lib/api";

type State = "unknown" | "off" | "working" | "on" | "denied" | "unsupported" | "error";

const LABEL: Record<State, string> = {
  unknown: " ",
  off: "Turn on reminders",
  working: "Turning on...",
  on: "Reminders are on",
  denied: "Notifications are blocked in settings",
  unsupported: "Reminders not supported on this browser",
  error: "Could not turn on reminders - tap to retry",
};

/**
 * Enrols the device for the daily "what's due" push that reminders.py sends.
 * Without this the server-side reminder loop has nobody to deliver to.
 */
export function RemindersButton() {
  const [state, setState] = useState<State>("unknown");

  useEffect(() => {
    let alive = true;
    pushAlreadyEnabled()
      .then((on) => alive && setState(on ? "on" : "off"))
      .catch(() => alive && setState("off"));
    return () => {
      alive = false;
    };
  }, []);

  const enable = async () => {
    setState("working");
    try {
      const result = await enablePushReminders();
      setState(
        result === "enabled"
          ? "on"
          : result === "denied"
            ? "denied"
            : result === "unsupported"
              ? "unsupported"
              : "error",
      );
    } catch {
      setState("error");
    }
  };

  const interactive = state === "off" || state === "error";

  return (
    <button
      onClick={interactive ? enable : undefined}
      disabled={!interactive}
      className={`min-h-12 w-full rounded-[var(--radius)] text-sm font-medium transition-colors ${
        interactive
          ? "border hover:bg-[var(--muted)] text-[var(--muted-foreground)]"
          : "text-[var(--muted-foreground)] opacity-70"
      }`}
    >
      {state === "on" ? "✓ " : ""}
      {LABEL[state]}
    </button>
  );
}
