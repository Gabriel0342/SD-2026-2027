$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$projects = @(
    (Join-Path $root 'raft04-no'),
    (Join-Path $root 'raft04-processador'),
    (Join-Path $root 'raft04-produtor')
)

foreach ($project in $projects) {
    $out = Join-Path $project 'out'
    if (Test-Path $out) {
        Remove-Item -LiteralPath $out -Recurse -Force
    }
}

$patterns = @(
    'no-p*-n*.txt',
    'estado-p*-n*.txt',
    'eventos-p*-n*.txt',
    'hb-p*.txt',
    'monitor-*.txt',
    'relatorio-*.txt',
    '*.tmp'
)

foreach ($project in $projects) {
    foreach ($pattern in $patterns) {
        Get-ChildItem -LiteralPath $project -File -Filter $pattern `
            -ErrorAction SilentlyContinue | Remove-Item -Force
    }
}

$rootClass = Join-Path $root 'IpfsDaemon.class'
if (Test-Path $rootClass) {
    Remove-Item -LiteralPath $rootClass -Force
}

Write-Host 'Classes e ficheiros gerados removidos. Fontes, configuracao e ipfs.exe preservados.'
