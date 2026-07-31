# सिलाई बही - local dev helper (Windows PowerShell)
# Usage:
#   .\dev.ps1 setup    -> create venv, install deps, copy .env, generate push keys, run tests
#   .\dev.ps1 web      -> start the web server (PWA + debug chat) on http://localhost:8080
#   .\dev.ps1 agent    -> start the voice agent worker (needs keys in .env)
#   .\dev.ps1 status   -> is web + agent + tunnel actually up? run this FIRST when voice fails
#   .\dev.ps1 chat     -> text chat with the brain in this terminal (needs ANTHROPIC key)
#   .\dev.ps1 test     -> run the ledger test suite (no keys needed)
#   .\dev.ps1 ui       -> build the React PWA (webapp/ -> web/); run after any UI edit
#   .\dev.ps1 ui-dev   -> Vite dev server with HMR on :5173, API proxied to :8080
#   .\dev.ps1 tunnel   -> HTTPS tunnel so your phone can open the app (needs cloudflared)

param([Parameter(Position = 0)][string]$cmd = "help")

$venvPy = ".\.venv\Scripts\python.exe"

function Ensure-Venv {
    if (-not (Test-Path $venvPy)) {
        Write-Host "Creating venv..." -ForegroundColor Cyan
        py -3 -m venv .venv
    }
}

switch ($cmd) {
    "setup" {
        Ensure-Venv
        & $venvPy -m pip install --upgrade pip
        & $venvPy -m pip install -r requirements.txt
        if (-not (Test-Path ".env")) {
            Copy-Item ".env.example" ".env"
            Write-Host ".env created - paste your 3 keys into it now." -ForegroundColor Yellow
        }
        Write-Host "Generating web-push keys (paste output into .env):" -ForegroundColor Cyan
        & $venvPy -m silai.genvapid
        Write-Host "Downloading voice models (VAD + turn detector)..." -ForegroundColor Cyan
        & $venvPy -m silai.agent download-files
        & $venvPy -m unittest discover tests
    }
    "web"   { Ensure-Venv; & $venvPy -m uvicorn silai.web:app --host 0.0.0.0 --port 8080 }
    # Supervised, because on Windows the turn-detector inference process dies
    # after a session (DuplexClosed / WinError 64) and takes the worker with it.
    # The web server survives, so the only symptom is "Suhani didn't answer".
    # agent-once = single unsupervised run, for debugging the worker itself.
    "agent" { Ensure-Venv; & powershell -ExecutionPolicy Bypass -File "deploy\agent-supervisor.ps1" -Python $venvPy }
    "agent-once" { Ensure-Venv; & $venvPy -m silai.agent start }
    "agent-dev" { Ensure-Venv; & $venvPy -m silai.agent dev }
    "chat"  { Ensure-Venv; & $venvPy -m silai.brain_text }
    "test"  { Ensure-Venv; & $venvPy -m unittest discover tests -v }
    "demo"  { Ensure-Venv; & $venvPy -m silai.seed_demo @args }
    "voices" { Ensure-Venv; & $venvPy -m silai.voice_demo }
    "keys"  { Ensure-Venv; & $venvPy -m silai.check_keys }
    "genkey" { Ensure-Venv; & $venvPy -m silai.genkey }
    "status" {
        # Checks the AGENT specifically. "some python is running" is not good
        # enough: the web server alone satisfies that while Suhani is dead, and
        # the only symptom is "Suhani didn't answer" on her phone.
        $ok = $true
        try {
            $h = Invoke-WebRequest "http://127.0.0.1:8080/healthz" -UseBasicParsing -TimeoutSec 5
            Write-Host "web server    UP   $($h.Content)" -ForegroundColor Green
        } catch { Write-Host "web server    DOWN  -> .\dev.ps1 web" -ForegroundColor Red; $ok = $false }

        # Ask the worker's OWN health endpoint, never just "is the process there".
        # After the webrtc-sys panic the process survives as a zombie that takes
        # no jobs, so process existence is a false positive.
        $agentProcs = Get-CimInstance Win32_Process -Filter "Name='python.exe'" -EA SilentlyContinue |
            Where-Object { $_.CommandLine -like "*silai.agent*" }
        $healthy = $false
        try { $healthy = (Invoke-WebRequest "http://127.0.0.1:8081/" -UseBasicParsing -TimeoutSec 5).StatusCode -eq 200 } catch { $healthy = $false }

        if ($healthy) {
            Write-Host "agent worker  UP   pid $($agentProcs.ProcessId -join ', ')  (health :8081 OK)" -ForegroundColor Green
        } elseif ($agentProcs) {
            Write-Host "agent worker  WEDGED  pid $($agentProcs.ProcessId -join ', ') alive but health :8081 failing" -ForegroundColor Red
            Write-Host "              -> the supervisor should kill it within ~30s; if not, .\dev.ps1 agent" -ForegroundColor Yellow
            $ok = $false
        } else {
            Write-Host "agent worker  DOWN -> .\dev.ps1 agent   (this is why 'Suhani didn't answer')" -ForegroundColor Red
            $ok = $false
        }

        $cf = Get-Process cloudflared -EA SilentlyContinue
        if ($cf) { Write-Host "tunnel        UP   pid $($cf.Id -join ', ')" -ForegroundColor Green }
        else { Write-Host "tunnel        DOWN -> .\dev.ps1 tunnel  (only needed for phone access)" -ForegroundColor Yellow }

        if ($ok) { Write-Host "`nReady. Press the orb." -ForegroundColor Cyan }
        else { Write-Host "`nNot ready - start whatever is DOWN above." -ForegroundColor Yellow }
    }
    "link" {
        Ensure-Venv
        $k = (Select-String -Path .env -Pattern '^APP_KEY=(.+)$').Matches.Groups[1].Value
        if (-not $k) { Write-Host "APP_KEY not set in .env - run: .\dev.ps1 genkey" -ForegroundColor Red; break }
        Write-Host "Append this to whatever host you are serving on:" -ForegroundColor Cyan
        Write-Host "  /?k=$k"
    }
    "ui" {
        Push-Location webapp
        try {
            if (-not (Test-Path "node_modules")) { npm install --no-audit --no-fund }
            npm run build
        } finally { Pop-Location }
    }
    "ui-dev" {
        Push-Location webapp
        try {
            if (-not (Test-Path "node_modules")) { npm install --no-audit --no-fund }
            Write-Host "Start the API too (.\dev.ps1 web) - Vite proxies /api to :8080" -ForegroundColor Yellow
            npm run dev
        } finally { Pop-Location }
    }
    "smoke" { Ensure-Venv; $env:DB_PATH="data/smoke.db"; try { & $venvPy -m silai.smoke_chat } finally { Remove-Item Env:\DB_PATH } }
    "tunnel" {
        Write-Host "If cloudflared is missing:  winget install Cloudflare.cloudflared" -ForegroundColor Yellow
        cloudflared tunnel --url http://localhost:8080
    }
    default {
        Write-Host "Commands: setup | web | agent | status | chat | test | demo | voices | keys | genkey | link | smoke | ui | ui-dev | tunnel"
    }
}
