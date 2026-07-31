import { useState } from "react";
import { VoiceScreen } from "./components/VoiceScreen";
import { LedgerScreen } from "./components/LedgerScreen";

export default function App() {
  const [view, setView] = useState<"voice" | "ledger">("voice");

  return (
    <main className="mx-auto w-full max-w-md">
      {view === "voice" ? (
        <VoiceScreen onOpenLedger={() => setView("ledger")} />
      ) : (
        <LedgerScreen onBack={() => setView("voice")} />
      )}
    </main>
  );
}
