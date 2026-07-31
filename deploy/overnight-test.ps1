# Overnight soak test for the Dockerised stack.
#
# Runs a REAL conversation every 30 minutes and records the result alongside
# container restart counts, so by morning there is evidence rather than a
# feeling. The point is to compare against the Windows-native run, where the
# worker wedged after roughly every session.
#
#   powershell -ExecutionPolicy Bypass -File deploy\overnight-test.ps1
#
# Cost note: each probe is ~26s of Sarvam STT + TTS + one LLM turn. At 30-minute
# intervals that is well under an hour of audio a night - a few rupees.

param(
    [int]$IntervalMinutes = 30,
    [string]$Log = "data\logs\overnight.log"
)

$ErrorActionPreference = "Continue"
Set-Location (Split-Path $PSScriptRoot -Parent)
$COMPOSE = @("compose", "-f", "deploy/docker-compose.yml", "-f", "deploy/docker-compose.local.yml")

function Note($text) {
    $line = "{0}  {1}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $text
    Add-Content -Path $Log -Value $line
    Write-Host $line
}

Note "=== overnight soak started (interval ${IntervalMinutes}m) ==="
$n = 0
$pass = 0
$fail = 0

while ($true) {
    $n++
    $room = "soak-$(Get-Date -Format 'HHmmss')"

    $out = & docker @COMPOSE exec -T agent python -m silai.probe_participant $room 2>&1 | Out-String
    $ok = $out -match "PROBE PASS"
    if ($ok) { $pass++ } else { $fail++ }

    # Restart counts are the real tell: on Windows this climbed every session.
    $agentRestarts = (& docker inspect deploy-agent-1 --format '{{.RestartCount}}' 2>$null)
    $agentHealth   = (& docker inspect deploy-agent-1 --format '{{.State.Health.Status}}' 2>$null)
    $webRestarts   = (& docker inspect deploy-web-1 --format '{{.RestartCount}}' 2>$null)

    Note ("probe #{0}: {1}  | pass={2} fail={3} | agent restarts={4} health={5} | web restarts={6}" -f `
        $n, $(if ($ok) { "PASS" } else { "FAIL" }), $pass, $fail, $agentRestarts, $agentHealth, $webRestarts)

    if (-not $ok) {
        # Capture why, while the evidence still exists.
        $tail = & docker @COMPOSE logs --tail 25 agent 2>&1 | Out-String
        Note "  --- agent log tail on failure ---"
        Add-Content -Path $Log -Value $tail
    }

    Start-Sleep -Seconds ($IntervalMinutes * 60)
}
