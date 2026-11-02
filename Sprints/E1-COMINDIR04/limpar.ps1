$ErrorActionPreference = 'Stop'

# Todos os caminhos são relativos à pasta do sprint, não à pasta atual.
$root = Split-Path -Parent $MyInvocation.MyCommand.Path

# Os diretórios out contêm apenas classes compiladas e podem ser recriados.
foreach ($project in @('rep04-produtor', 'rep04-lider', 'rep04-replica', 'rep04-processador')) {
    $projectPath = Join-Path $root $project
    $outPath = Join-Path $projectPath 'out'
    if (Test-Path $outPath) {
        Remove-Item -LiteralPath $outPath -Recurse -Force
    }
}

$generatedRootFiles = @(
    'IpfsDaemon.class'
)
# Limpar também a classe gerada pelo lançador que foi compilado na raiz.
foreach ($file in $generatedRootFiles) {
    $path = Join-Path $root $file
    if (Test-Path $path) {
        Remove-Item -LiteralPath $path -Force
    }
}

# As trancas desaparecem quando os processos terminam; remover resíduos permite
# iniciar novas atribuições automáticas de réplicas.
foreach ($project in @('rep04-lider', 'rep04-replica')) {
    $projectPath = Join-Path $root $project
    Get-ChildItem -LiteralPath $projectPath -File -Filter '*.lock' -ErrorAction SilentlyContinue |
        Remove-Item -Force
}

# Estes ficheiros são estado/saída de ensaios, não fontes necessários ao projeto.
$dataPatterns = @(
    'lider-p*.txt',
    'hb-p*.txt',
    'replica-*.txt',
    'monitor-*.txt',
    'relatorio-causal.txt',
    'vista-lamport.txt',
    'relatorio-heartbeat.txt'
)
foreach ($project in @('rep04-lider', 'rep04-replica')) {
    $projectPath = Join-Path $root $project
    foreach ($pattern in $dataPatterns) {
        Get-ChildItem -LiteralPath $projectPath -File -Filter $pattern -ErrorAction SilentlyContinue |
            Remove-Item -Force
    }
}

# Monitores do processador e produtor ficam nas respetivas pastas de trabalho.
foreach ($project in @('rep04-lider', 'rep04-replica', 'rep04-processador', 'rep04-produtor')) {
    $projectPath = Join-Path $root $project
    Get-ChildItem -LiteralPath $projectPath -File -Filter 'monitor-*.txt' -ErrorAction SilentlyContinue |
        Remove-Item -Force
}

Write-Host 'Ficheiros gerados pela compilacao e execucao removidos.'
Write-Host 'Os ficheiros .java, .md, .properties e ipfs.exe foram preservados.'
