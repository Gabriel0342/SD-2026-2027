<#
.SYNOPSIS
    Compiles and runs the TPF leader (Sprint E2-03).

.DESCRIPTION
    Run it from anywhere; it always works from the folder where this script lives
    (Sprints/E2-03), which is also the leader's data folder (master.key, registos.log,
    eventos-lider.txt, selados/).

.EXAMPLE
    .\executar.ps1                          # compile and start the bot
    .\executar.ps1 -Test                    # compile and run the automatic tests (no Discord)
    .\executar.ps1 -Check                   # verify every chunk in selados/ (SealedCheck)
    .\executar.ps1 -Selagem 2000            # seal chunks 2 s after the first record
    .\executar.ps1 -ChunkMax 16384          # seal chunks at 16 KB
    .\executar.ps1 -RegistarComandos        # register the slash commands again

    If PowerShell blocks the script:
    powershell -ExecutionPolicy Bypass -File .\executar.ps1
#>
param(
    [switch]$Test,              # run "mvn test" instead of starting the bot
    [switch]$Check,             # run the offline SealedCheck tool
    [switch]$RegistarComandos,  # -Dtpf.registarComandos=true
    [int]$Selagem,              # -Dtpf.selagem=<ms>   (SELAGEM_MS, default 5000)
    [int]$ChunkMax              # -Dtpf.chunkMax=<bytes> (CHUNK_MAX_BYTES, default 65536)
)

$ErrorActionPreference = 'Stop'

# Always work from this folder, no matter where the script was called from.
Set-Location $PSScriptRoot

# --- 1. Prerequisites --------------------------------------------------------

foreach ($tool in 'java', 'mvn') {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
        Write-Host "ERRO: '$tool' nao esta instalado ou nao esta no PATH." -ForegroundColor Red
        exit 1
    }
}

# The bot needs the Discord token, either in .env or in an environment variable.
$hasToken = $env:DISCORD_BOT_TOKEN -or
            ((Test-Path '.env') -and (Select-String -Path '.env' -Pattern '^\s*DISCORD_BOT_TOKEN\s*=\s*\S' -Quiet))
if (-not $Test -and -not $Check -and -not $hasToken) {
    Write-Host 'ERRO: falta o token. Cria o ficheiro .env nesta pasta com:' -ForegroundColor Red
    Write-Host '      DISCORD_BOT_TOKEN=o-teu-token-aqui'
    exit 1
}

# master.key is created by the leader on its very first start.
if (Test-Path 'master.key') {
    Write-Host 'master.key encontrada (32 bytes). Nao a apagues: sem ela os chunks ja selados deixam de abrir.'
} else {
    Write-Host 'master.key ainda nao existe: o lider cria-a sozinho no primeiro arranque.'
}

# --- 2. Compile ----------------------------------------------------------------

Write-Host "`n>> mvn -pl tpf-lider compile" -ForegroundColor Cyan
& mvn -pl tpf-lider compile
if ($LASTEXITCODE -ne 0) {
    Write-Host 'ERRO: a compilacao falhou.' -ForegroundColor Red
    exit $LASTEXITCODE
}

# --- 3. Run --------------------------------------------------------------------

# Arguments are built as an array of strings: PowerShell 5.1 would otherwise split
# "-Dtpf.selagem=2000" at the dot and Maven would reject it.
if ($Test) {
    $mvnArgs = @('-pl', 'tpf-lider', 'test')
} elseif ($Check) {
    $mvnArgs = @('-pl', 'tpf-lider', 'exec:java', '-Dexec.mainClass=tpf.SealedCheck')
} else {
    $mvnArgs = @('-pl', 'tpf-lider', 'exec:java')
    if ($PSBoundParameters.ContainsKey('Selagem'))  { $mvnArgs += "-Dtpf.selagem=$Selagem" }
    if ($PSBoundParameters.ContainsKey('ChunkMax')) { $mvnArgs += "-Dtpf.chunkMax=$ChunkMax" }
    if ($RegistarComandos)                          { $mvnArgs += '-Dtpf.registarComandos=true' }
}

Write-Host ("`n>> mvn " + ($mvnArgs -join ' ')) -ForegroundColor Cyan
if (-not $Test -and -not $Check) {
    Write-Host '(o bot corre ate carregares Ctrl+C)' -ForegroundColor DarkGray
}
& mvn @mvnArgs
exit $LASTEXITCODE
