.\executar.ps1# Executar o COMINDIR05

Executa PowerShell a partir desta pasta:

```powershell
cd "C:\Users\gugap\OneDrive\Ambiente de Trabalho\SD-2026-2027\Sprints\E1-COMINDIR05"
.\executar.ps1
```

O script:

1. compila os tres projetos;
2. inicia o `IpfsDaemon` com as APIs `5001` e `5002`;
3. verifica as portas de recuperacao `7100` a `7108`;
4. inicia os nove nos, tres por particao;
5. inicia o processador e o produtor.

Para limpar classes e ficheiros de ensaio depois de parar os processos:

```powershell
.\limpar.ps1
```

Para verificar a eleicao, executa na pasta `raft04-no`:

```powershell
java -cp out raft04.RaftCheck 3 .
java -cp out raft04.CausalCheck 3 .
```

O produtor aceita, entre outros, estes comandos:

```text
S01 21.5
S02 29.3
rajada 100 10
rajada-quente 1000 10
sair
```

Os processos devem ser terminados com `Ctrl+C`, incluindo o `IpfsDaemon`.
