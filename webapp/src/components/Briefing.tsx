import { useEffect, useState } from "react";
import {
  fetchBriefing,
  formatRupees,
  UnauthorizedError,
  type BriefingResponse,
} from "../lib/api";

type Status = "loading" | "ready" | "error" | "locked";

/**
 * Always visible. "Nothing due today" and "the ledger is unreachable" are
 * different facts and must never look the same.
 */
export function Briefing() {
  const [status, setStatus] = useState<Status>("loading");
  const [data, setData] = useState<BriefingResponse | null>(null);

  useEffect(() => {
    let alive = true;
    fetchBriefing()
      .then((b) => alive && (setData(b), setStatus("ready")))
      .catch((err) => alive && setStatus(err instanceof UnauthorizedError ? "locked" : "error"));
    return () => {
      alive = false;
    };
  }, []);

  const totalDue = data?.money_pending.reduce((sum, d) => sum + d.due, 0) ?? 0;
  const rows = [
    ...(data?.due_today ?? []).map((o) => ({ tag: "Today", order: o, late: false })),
    ...(data?.overdue ?? []).map((o) => ({ tag: "Late", order: o, late: true })),
  ];

  return (
    <section className="mb-2 rounded-[var(--radius)] border bg-[var(--card)] p-4 shadow-sm">
      <h2 className="mb-2 text-sm font-semibold tracking-tight">Today's work</h2>

      {status === "loading" && (
        <p className="py-1 text-sm text-[var(--muted-foreground)]">Loading...</p>
      )}

      {status === "error" && (
        <p className="py-1 text-sm text-[var(--destructive)]">Ledger unreachable</p>
      )}

      {status === "locked" && (
        <p className="py-1 text-sm text-[var(--destructive)]">
          This device is not set up. Open the setup link again.
        </p>
      )}

      {status === "ready" && rows.length === 0 && totalDue === 0 && (
        <p className="py-1 text-sm text-[var(--muted-foreground)]">Nothing due today</p>
      )}

      {status === "ready" && (
        <ul className="flex flex-col gap-1.5">
          {rows.map(({ tag, order, late }, i) => (
            <li key={i} className="flex items-baseline justify-between gap-3 text-[15px]">
              <span lang="hi" className="min-w-0 truncate">
                {order.client_name} <span className="text-[var(--muted-foreground)]">- {order.garment}</span>
              </span>
              <span
                className={`shrink-0 text-xs font-medium ${
                  late ? "text-[var(--destructive)]" : "text-[var(--muted-foreground)]"
                }`}
              >
                {tag}
              </span>
            </li>
          ))}
          {totalDue > 0 && (
            <li className="mt-1 flex items-baseline justify-between border-t pt-2 text-[15px]">
              <span className="text-[var(--muted-foreground)]">Total due</span>
              <span className="font-semibold tabular-nums">{formatRupees(totalDue)}</span>
            </li>
          )}
        </ul>
      )}
    </section>
  );
}
