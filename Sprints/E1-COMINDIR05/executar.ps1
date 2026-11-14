$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$nodeProject = Join-Path $root 'raft04-no'
$processorProject = Join-Path $root 'raft04-processador'
$producerProject = Join-Path $root 'raft04-produtor'

function Compile-Project {
    param([string]$ProjectPath)
    $outPath = Join-Path $ProjectPath 'out'
    New-Item -ItemType Directory -Force -Path $outPath | Out-Null
    Push-Location $ProjectPath
    try {
        javac --release 11 -encoding UTF-8 -d out (Get-ChildItem -Recurse -Filter '*.java' src).FullName
        if ($LASTEXITCODE -ne 0) {
            throw "A compilacao falhou em $ProjectPath."
        }
    }
    finally {
        Pop-Location
    }
}

function Start-Terminal {
    param(
        [string]$WorkingDirectory,
        [string]$Command,
        [string]$Title
    )
    $terminalScript = "`$Host.UI.RawUI.WindowTitle = '$Title'; $Command"
    $encodedCommand = [Convert]::ToBase64String(
        [Text.Encoding]::Unicode.GetBytes($terminalScript))
    Start-Process -FilePath 'powershell.exe' -WorkingDirectory $WorkingDirectory `
        -ArgumentList @('-NoExit', '-EncodedCommand', $encodedCommand) | Out-Null
}

function Wait-ForPort {
    param([int]$Port, [int]$TimeoutSeconds = 60)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $ready = Test-NetConnection -ComputerName '127.0.0.1' -Port $Port `
            -InformationLevel Quiet -WarningAction SilentlyContinue
        if ($ready) {
            Write-Host "Porta $Port pronta."
            return
        }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)
    throw "A porta $Port nao ficou disponivel."
}

function Assert-PortFree {
    param([int]$Port)
    $listeners = @(Get-NetTCPConnection -LocalPort $Port -State Listen `
        -ErrorAction SilentlyContinue)
    if ($listeners.Count -gt 0) {
        $ids = ($listeners | Select-Object -ExpandProperty OwningProcess -Unique) -join ', '
        throw "A porta $Port ja esta ocupada pelos processos $ids."
    }
}

Compile-Project $nodeProject
Compile-Project $processorProject
Compile-Project $producerProject

# Compila o lancador na raiz. O comando da janela nova usa argumentos
# explicitamente separados, evitando problemas com espacos no caminho.
Push-Location $root
try {
    javac --release 11 -encoding UTF-8 -d . IpfsDaemon.java
    if ($LASTEXITCODE -ne 0) {
        throw 'A compilacao do IpfsDaemon falhou.'
    }
}
finally {
    Pop-Location
}
Start-Terminal $root "& java '-Dipfs.executable=.\ipfs.exe' -cp . IpfsDaemon" 'COMINDIR05 - IPFS'
Wait-ForPort 5001
Wait-ForPort 5002

for ($port = 7100; $port -le 7108; $port++) {
    Assert-PortFree $port
}

# Os nove nos sao iniciados antes do processador e do produtor.
for ($partition = 0; $partition -lt 3; $partition++) {
    for ($node = 0; $node -lt 3; $node++) {
        $title = "COMINDIR05 - p$partition n$node"
        $command = "java -cp out raft04.PartitionNode $partition $node 3"
        Start-Terminal $nodeProject $command $title
    }
}

Start-Terminal $processorProject 'java -cp out raft04.AlarmProcessor 3' `
    'COMINDIR05 - Processador'
Start-Terminal $producerProject 'java -cp out raft04.Producer 3' `
    'COMINDIR05 - Produtor'

Write-Host 'Compilacao concluida e processos iniciados.'
