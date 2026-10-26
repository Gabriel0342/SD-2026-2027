$ErrorActionPreference = 'Stop'

# A pasta do script é a raiz do sprint, independentemente da pasta atual.
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$projects = @('rep03-replica', 'rep03-lider', 'rep03-processador', 'rep03-produtor')

# Compila cada projeto isoladamente para que as cópias partilhadas tenham
# exatamente o classpath e o diretório de saída esperados na execução.
foreach ($project in $projects) {
    $projectPath = Join-Path $root $project
    $outPath = Join-Path $projectPath 'out'
    Write-Host "A compilar $project..."
    New-Item -ItemType Directory -Force -Path $outPath | Out-Null
    Push-Location $projectPath
    try {
        javac --release 11 -encoding UTF-8 -d out src\rep03\*.java
        if ($LASTEXITCODE -ne 0) {
            throw "A compilacao de $project falhou."
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

    # Cada componente fica numa janela própria, pois as subscrições são
    # bloqueantes e o produtor precisa de uma consola interativa.
    $terminalCommand = "& { `$Host.UI.RawUI.WindowTitle = '$Title'; $Command }"
    Start-Process -FilePath 'powershell.exe' `
        -WorkingDirectory $WorkingDirectory `
        -ArgumentList @('-NoExit', '-Command', $terminalCommand) | Out-Null
}

$ipfsCommand = 'java IpfsDaemon.java'
# O lançador prepara as duas APIs Kubo antes dos processos da aplicação.
Start-Terminal -WorkingDirectory $root -Command $ipfsCommand -Title 'COMINDIR03 - IPFS'

function Wait-ForIpfsApi {
    param(
        [int]$Port,
        [int]$TimeoutSeconds = 60
    )

    # A aplicação só deve arrancar depois de ambas as APIs responderem.
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        try {
            $ready = Test-NetConnection -ComputerName '127.0.0.1' -Port $Port `
                -InformationLevel Quiet -WarningAction SilentlyContinue
            if (-not $ready) {
                throw "A porta $Port ainda nao esta pronta."
            }
            Write-Host "API IPFS $Port pronta."
            return
        }
        catch {
            Start-Sleep -Seconds 1
        }
    } while ((Get-Date) -lt $deadline)

    throw "A API IPFS $Port nao ficou disponivel dentro de $TimeoutSeconds segundos."
}

function Assert-PortFree {
    param(
        [int]$Port
    )

    # Os líderes precisam dos três portos TCP de recuperação livres.
    $listeners = @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
    if ($listeners.Count -gt 0) {
        $processIds = ($listeners | Select-Object -ExpandProperty OwningProcess -Unique) -join ', '
        throw "O porto TCP $Port ja esta ocupado pelos processos $processIds. Termine a execucao anterior antes de correr este script novamente."
    }
}

# Validar os serviços externos e os portos antes de abrir terminais.
Wait-ForIpfsApi -Port 5001
Wait-ForIpfsApi -Port 5002
Assert-PortFree -Port 7000
Assert-PortFree -Port 7001
Assert-PortFree -Port 7002

# A ordem garante que réplicas/líderes existem antes do produtor e do processador.
$replicaPath = Join-Path $root 'rep03-replica'
Start-Terminal -WorkingDirectory $replicaPath -Command 'java -cp out rep03.PartitionReplica' -Title 'COMINDIR03 - Replica 0'
Start-Terminal -WorkingDirectory $replicaPath -Command 'java -cp out rep03.PartitionReplica' -Title 'COMINDIR03 - Replica 1'
Start-Terminal -WorkingDirectory $replicaPath -Command 'java -cp out rep03.PartitionReplica' -Title 'COMINDIR03 - Replica 2'

$leaderPath = Join-Path $root 'rep03-lider'
Start-Terminal -WorkingDirectory $leaderPath -Command 'java -cp out rep03.PartitionLeader 0' -Title 'COMINDIR03 - Leader p0'
Start-Terminal -WorkingDirectory $leaderPath -Command 'java -cp out rep03.PartitionLeader 1' -Title 'COMINDIR03 - Leader p1'
Start-Terminal -WorkingDirectory $leaderPath -Command 'java -cp out rep03.PartitionLeader 2' -Title 'COMINDIR03 - Leader p2'

$processorPath = Join-Path $root 'rep03-processador'
Start-Terminal -WorkingDirectory $processorPath -Command 'java -cp out rep03.AlarmProcessor 3' -Title 'COMINDIR03 - Alarm Processor'

$producerPath = Join-Path $root 'rep03-produtor'
Start-Terminal -WorkingDirectory $producerPath -Command 'java -cp out rep03.Producer' -Title 'COMINDIR03 - Producer'

Write-Host 'Compilacao concluida. Os terminais foram iniciados pela ordem definida.'
