# Compilar o COMINDIR05

Executa a partir da pasta `E1-COMINDIR05`:

```powershell
cd "C:\Users\gugap\OneDrive\Ambiente de Trabalho\SD-2026-2027\Sprints\E1-COMINDIR05"

Remove-Item -Recurse -Force raft04-no\out -ErrorAction SilentlyContinue
javac --release 11 -encoding UTF-8 -d raft04-no\out (Get-ChildItem -Recurse -Filter *.java raft04-no\src).FullName

Remove-Item -Recurse -Force raft04-processador\out -ErrorAction SilentlyContinue
javac --release 11 -encoding UTF-8 -d raft04-processador\out (Get-ChildItem -Recurse -Filter *.java raft04-processador\src).FullName

Remove-Item -Recurse -Force raft04-produtor\out -ErrorAction SilentlyContinue
javac --release 11 -encoding UTF-8 -d raft04-produtor\out (Get-ChildItem -Recurse -Filter *.java raft04-produtor\src).FullName
```

O script `executar.ps1` faz esta compilacao automaticamente.
