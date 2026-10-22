$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $MyInvocation.MyCommand.Path

foreach ($project in @('rep03-produtor', 'rep03-lider', 'rep03-replica', 'rep03-processador')) {
    $projectPath = Join-Path $root $project
    $outPath = Join-Path $projectPath 'out'
    if (Test-Path $outPath) {
        Remove-Item -LiteralPath $outPath -Recurse -Force
    }
}

$generatedRootFiles = @(
    'IpfsDaemon.class'
)
foreach ($file in $generatedRootFiles) {
    $path = Join-Path $root $file
    if (Test-Path $path) {
        Remove-Item -LiteralPath $path -Force
    }
}

foreach ($project in @('rep03-lider', 'rep03-replica')) {
    $projectPath = Join-Path $root $project
    Get-ChildItem -LiteralPath $projectPath -File -Filter '*.lock' -ErrorAction SilentlyContinue |
        Remove-Item -Force
}

$dataPatterns = @(
    'lider-p*.txt',
    'replica-*.txt',
    'relatorio-causal.txt',
    'vista-lamport.txt'
)
foreach ($project in @('rep03-lider', 'rep03-replica')) {
    $projectPath = Join-Path $root $project
    foreach ($pattern in $dataPatterns) {
        Get-ChildItem -LiteralPath $projectPath -File -Filter $pattern -ErrorAction SilentlyContinue |
            Remove-Item -Force
    }
}

Write-Host 'Ficheiros gerados pela compilacao e execucao removidos.'
Write-Host 'Os ficheiros .java, .md, .properties e ipfs.exe foram preservados.'
