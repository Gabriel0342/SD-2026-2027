# Compilar o COMINDIR02

Os comandos seguintes devem ser executados numa janela PowerShell a partir da
pasta `E1-COMINDIR02`. Cada processo tem o seu próprio projeto, tal como no
COMINDIR01: o código fonte fica em `src\rep02` e os ficheiros compilados ficam
em `out\rep02`.

## Líder

```powershell
cd rep02-lider
Remove-Item -Recurse -Force out\rep02 -ErrorAction SilentlyContinue
javac -encoding UTF-8 -d out src\rep02\*.java
cd ..
```

## Produtor

```powershell
cd rep02-produtor
Remove-Item -Recurse -Force out\rep02 -ErrorAction SilentlyContinue
javac -encoding UTF-8 -d out src\rep02\*.java
cd ..
```

## Réplica

```powershell
cd rep02-replica
Remove-Item -Recurse -Force out\rep02 -ErrorAction SilentlyContinue
javac -encoding UTF-8 -d out src\rep02\*.java
cd ..
```

## Utilitário opcional do daemon

O `IpfsDaemon.java` não pertence a nenhum dos três projetos. Para o compilar:

```powershell
javac -encoding UTF-8 IpfsDaemon.java
```

Confirme a compilação dos três projetos:

```powershell
Get-ChildItem rep02-lider\out\rep02\*.class
Get-ChildItem rep02-produtor\out\rep02\*.class
Get-ChildItem rep02-replica\out\rep02\*.class
```