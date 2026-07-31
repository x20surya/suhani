# Keeps the voice agent worker actually WORKING on Windows.
#
# WHY THIS EXISTS: the worker does not fail cleanly here. Two distinct deaths:
#
#   1. The turn detector runs in a separate "inference" process whose IPC pipe
#      dies after a session (DuplexClosed / WinError 64).
#   2. The native WebRTC layer panics:
#         thread 'tokio-rt-worker' panicked at webrtc-sys\src\rtc_error.rs:
#         malformed serialized RtcError
#      followed by "worker process is not responding.. worker crashed?".
#
# After (2) the PROCESS IS STILL ALIVE but accepts no jobs - a zombie. So
# "is the process running" is a useless health signal, and a supervisor that
# only restarts on exit never fires. The single visible symptom is
# "Suhani didn't answer" on her phone while the web server looks perfectly fine.
#
# livekit-agents serves a health endpoint on :8081 in production mode which
# returns 503 when the inference process is gone, and stops answering entirely
# when the worker is wedged. We poll that and hard-restart on failure.
#
# Linux does not hit either failure, so on the VPS docker-compose
# `restart: unless-stopped` is enough and this script is unnecessary.

param(
    [string]$Python = ".\.venv\Scripts\python.exe",
    [int]$HealthPort = 8081,
    # Tight, because every second the worker is wedged is a tap that fails.
    [int]$PollSeconds = 4,
    [int]$FailuresBeforeRestart = 2,
    [int]$StartupGraceSeconds = 90
)

$ErrorActionPreference = "Continue"
$restarts = 0

function Test-WorkerHealthy {
    try {
        $r = Invoke-WebRequest "http://127.0.0.1:$HealthPort/" -UseBasicParsing -TimeoutSec 5
        return $r.StatusCode -eq 200
    } catch {
        return $false
    }
}

function Stop-AgentProcesses {
    Get-CimInstance Win32_Process -Filter "Name='python.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -like "*silai.agent*" } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}

while ($true) {
    Stop-AgentProcesses
    Start-Sleep -Seconds 1

    Write-Host "[supervisor] starting worker $(Get-Date -Format 'HH:mm:ss') (restart #$restarts)"
    # Per-run log files. -RedirectStandardOutput TRUNCATES, so a single fixed
    # filename means every restart destroys the previous run's logs - including
    # the real conversation you were trying to debug. Keep the last 20 runs.
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $proc = Start-Process -FilePath $Python -ArgumentList "-m", "silai.agent", "start" `
        -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput "data\logs\agent-$stamp.out.log" `
        -RedirectStandardError "data\logs\agent-$stamp.err.log"
    Get-ChildItem "data\logs\agent-*.out.log" -EA SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -Skip 20 |
        ForEach-Object {
            Remove-Item $_.FullName -Force -EA SilentlyContinue
            Remove-Item ($_.FullName -replace '\.out\.log$', '.err.log') -Force -EA SilentlyContinue
        }

    # Model loading takes a while; do not judge it before it has had a chance.
    $healthyBy = (Get-Date).AddSeconds($StartupGraceSeconds)
    $up = $false
    while ((Get-Date) -lt $healthyBy) {
        if ($proc.HasExited) { break }
        if (Test-WorkerHealthy) { $up = $true; break }
        Start-Sleep -Seconds 3
    }

    if ($up) {
        Write-Host "[supervisor] worker healthy on :$HealthPort"
        $consecutiveFailures = 0
        while ($true) {
            Start-Sleep -Seconds $PollSeconds
            if ($proc.HasExited) {
                Write-Host "[supervisor] worker exited (code $($proc.ExitCode))"
                break
            }
            if (Test-WorkerHealthy) {
                $consecutiveFailures = 0
            } else {
                $consecutiveFailures++
                Write-Host "[supervisor] health check failed ($consecutiveFailures/$FailuresBeforeRestart)"
                if ($consecutiveFailures -ge $FailuresBeforeRestart) {
                    Write-Host "[supervisor] worker is wedged - killing it"
                    break
                }
            }
        }
    } else {
        Write-Host "[supervisor] worker never became healthy within ${StartupGraceSeconds}s"
    }

    $restarts++
    Start-Sleep -Seconds 3
}
