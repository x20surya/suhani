import { useEffect, useState } from "react";
import {
  fetchOrders,
  formatRupees,
  formatShortDate,
  UnauthorizedError,
  type OrdersResponse,
} from "../lib/api";
import { RemindersButton } from "./RemindersButton";

const LOCKED_MESSAGE = "This device is not set up. Open the setup link again.";

const today = new Date().toISOString().slice(0, 10);

export function LedgerScreen({ onBack }: { onBack: () => void }) {
  const [data, setData] = useState<OrdersResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    fetchOrders()
      .then((d) => alive && setData(d))
      .catch((err) => {
        if (!alive) return;
        setError(err instanceof UnauthorizedError ? LOCKED_MESSAGE : "Couldn't reach the server");
      });
    return () => {
      alive = false;
    };
  }, []);

  const totalDue = data?.dues.reduce((sum, d) => sum + d.due, 0) ?? 0;

  return (
    <div className="mx-auto flex min-h-dvh w-full max-w-2xl flex-col gap-4 px-5 pb-[max(env(safe-area-inset-bottom),1.5rem)] pt-[max(env(safe-area-inset-top),1rem)]">
      <h1 className="text-2xl font-semibold tracking-tight">Ledger</h1>

      {error && (
        <Card title="Ledger">
          <p className="text-sm text-[var(--destructive)]">{error}</p>
        </Card>
      )}

      <Card title="Open work">
        <Rows
          empty="No open work"
          items={data?.open_orders}
          render={(o, i) => (
            <Row key={i}>
              <span lang="hi" className="min-w-0">
                <span className="font-medium">{o.client_name}</span>
                <span className="text-[var(--muted-foreground)]"> - {o.garment}</span>
              </span>
              <span className="flex shrink-0 items-baseline gap-3">
                <span
                  className={`text-xs ${
                    o.due_date && o.due_date < today
                      ? "font-semibold text-[var(--destructive)]"
                      : "text-[var(--muted-foreground)]"
                  }`}
                >
                  {formatShortDate(o.due_date)}
                </span>
                <span className="font-semibold tabular-nums">{formatRupees(o.balance)}</span>
              </span>
            </Row>
          )}
        />
      </Card>

      <Card title="Who owes what">
        <Rows
          empty="Nothing outstanding"
          items={data?.dues}
          render={(d, i) => (
            <Row key={i}>
              <span lang="hi" className="font-medium">
                {d.name}
              </span>
              <span className="font-semibold tabular-nums">{formatRupees(d.due)}</span>
            </Row>
          )}
          footer={
            totalDue > 0 ? (
              <Row bare>
                <span className="font-medium text-[var(--muted-foreground)]">Total</span>
                <span className="font-semibold tabular-nums">{formatRupees(totalDue)}</span>
              </Row>
            ) : null
          }
        />
      </Card>

      <Card title="Recent payments">
        <Rows
          empty="Nothing yet"
          items={data?.recent_payments}
          render={(p, i) => (
            <Row key={i}>
              <span className="min-w-0">
                <span lang="hi" className="font-medium">
                  {p.client_name}
                </span>
                <span className="ml-2 text-xs text-[var(--muted-foreground)]">
                  {formatShortDate((p.created_at ?? "").slice(0, 10) || null)}
                </span>
              </span>
              <span className="font-semibold tabular-nums">{formatRupees(p.amount)}</span>
            </Row>
          )}
        />
      </Card>

      <Card title="Suhani's notes">
        <Rows
          empty="No notes yet"
          items={data?.notes}
          render={(n, i) => (
            <Row key={i}>
              <span lang="hi" className="min-w-0">
                {n.content}
              </span>
              <span className="shrink-0 text-xs text-[var(--muted-foreground)]">
                {formatShortDate((n.created_at ?? "").slice(0, 10) || null)}
              </span>
            </Row>
          )}
        />
      </Card>

      <RemindersButton />

      {/* Sticky, not mt-auto: with twenty open orders the only way back must
          not be a long scroll away. Thumb-height, full width, always there. */}
      <div className="sticky bottom-0 -mx-5 mt-2 bg-linear-to-t from-[var(--background)] from-60% to-transparent px-5 pb-2 pt-6">
        <button
          onClick={onBack}
          className="min-h-13 w-full rounded-[var(--radius)] bg-[var(--primary)] font-medium text-[var(--primary-foreground)] shadow-lg transition-transform active:scale-[0.99]"
        >
          Back to Suhani
        </button>
      </div>
    </div>
  );
}

function Card({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="rounded-[var(--radius)] border bg-[var(--card)] p-4 shadow-sm">
      <h2 className="mb-2 text-sm font-semibold tracking-tight">{title}</h2>
      {children}
    </section>
  );
}

function Rows<T>({
  items,
  empty,
  render,
  footer,
}: {
  items: T[] | undefined;
  empty: string;
  render: (item: T, index: number) => React.ReactNode;
  footer?: React.ReactNode;
}) {
  if (items === undefined) {
    return <p className="py-1 text-sm text-[var(--muted-foreground)]">Loading...</p>;
  }
  if (items.length === 0) {
    return <p className="py-1 text-sm text-[var(--muted-foreground)]">{empty}</p>;
  }
  return (
    <div className="flex flex-col">
      {items.map(render)}
      {footer}
    </div>
  );
}

function Row({ children, bare = false }: { children: React.ReactNode; bare?: boolean }) {
  return (
    <div
      className={`flex items-baseline justify-between gap-3 py-2 text-[15px] ${
        bare ? "mt-1 border-t pt-2.5" : "border-b last:border-b-0"
      }`}
    >
      {children}
    </div>
  );
}
