# Executar o COMINDIR04

Estas instruções assumem Windows, PowerShell e Java instalado. Os comandos
devem ser executados a partir da pasta:

```powershell
cd C:\Projetos\SD-2026-2027\Sprints\E1-COMINDIR04
```

O sistema usa três partições (`N = 3`) e dois nós Kubo/IPFS locais:

- API `5001`: utilizada pelo produtor;
- API `5002`: utilizada pelos líderes, réplicas e processador;
- portos TCP `7000`, `7001` e `7002`: recuperação dos líderes das partições
  `p0`, `p1` e `p2`.

## 1. Pré-requisitos

```powershell
java -version
.\ipfs.exe version
```

O código é compatível com Java 11 ou superior. Se for a primeira utilização
do Kubo, inicializa o repositório:

```powershell
.\ipfs.exe init
```

Os valores predefinidos estão em
[runtime.properties](./runtime.properties). Os argumentos da linha de comandos
substituem os valores desse ficheiro.

## 2. Iniciar o IPFS

O procedimento é o mesmo do COMINDIR02. O lançador inicia ou reutiliza o nó
IPFS na API `5001`, prepara um segundo repositório independente na API
`5002`, com a porta swarm `4002`, e liga os dois nós.

Primeiro compila o lançador:

```powershell
javac -encoding UTF-8 IpfsDaemon.java
```

Depois deixa-o em execução numa janela PowerShell:

```powershell
java -Dipfs.executable=.\ipfs.exe IpfsDaemon
```

Confirma, noutra janela, que as duas APIs estão disponíveis:

```powershell
Invoke-WebRequest -Method Post http://127.0.0.1:5001/api/v0/id
Invoke-WebRequest -Method Post http://127.0.0.1:5002/api/v0/id
```

Não feches a janela do `IpfsDaemon` enquanto os processos da aplicação
estiverem a executar.

## 3. Compilar os quatro projetos

Executa todos os comandos de [compilação](./compiling.md). Cada projeto tem o
seu próprio diretório `out\rep04` e deve ser compilado isoladamente.

Como alternativa, o lançador PowerShell compila e abre todos os terminais na
ordem correta:

```powershell
.\executar.ps1
```

O script espera pelas APIs 5001/5002, verifica os portos TCP 7000--7002 e
arranca réplicas, líderes, processador e produtor. Para limpar classes,
trancas e ficheiros de ensaio depois de parar os processos:

```powershell
.\limpar.ps1
```

## 4. Ordem de arranque

Arranca sempre os componentes pela ordem seguinte:

1. réplicas;
2. líderes;
3. processador de alarmes;
4. produtor.

As réplicas e os líderes devem estar ativos antes de iniciar o produtor.

## 5. Iniciar as réplicas

Abre três janelas PowerShell, uma por réplica. Sem argumentos, a atribuição é
automática; cada réplica guarda duas partições:

```powershell
cd C:\Projetos\SD-2026-2027\Sprints\E1-COMINDIR04\rep04-replica
java -cp out rep04.PartitionReplica
```

Executa o comando em três janelas diferentes. Também podes fixar a atribuição:

```powershell
java -cp out rep04.PartitionReplica R0 0,1
java -cp out rep04.PartitionReplica R1 1,2
java -cp out rep04.PartitionReplica R2 2,0
```

O terceiro argumento opcional substitui o porto da API PubSub:

```powershell
java -cp out rep04.PartitionReplica R0 0,1 5002
```

As réplicas escrevem ficheiros `replica-*-p*.txt` na sua pasta de execução.

## 6. Iniciar os líderes

Abre uma janela PowerShell por líder:

```powershell
cd C:\Projetos\SD-2026-2027\Sprints\E1-COMINDIR04\rep04-lider
java -cp out rep04.PartitionLeader 0
```

```powershell
java -cp out rep04.PartitionLeader 1
```

```powershell
java -cp out rep04.PartitionLeader 2
```

Os líderes escutam nos portos TCP `7000`, `7001` e `7002`. Cada líder
continua os relógios Lamport, vetorial e a sequência a partir do último
registo de `lider-p<k>.txt`.

## 7. Iniciar o processador de alarmes

Numa nova janela PowerShell:

```powershell
cd C:\Projetos\SD-2026-2027\Sprints\E1-COMINDIR04\rep04-processador
java -cp out rep04.AlarmProcessor 3
```

O segundo argumento opcional é a API PubSub:

```powershell
java -cp out rep04.AlarmProcessor 3 http://127.0.0.1:5002/api/v0
```

O processador subscreve os três tópicos de dados e publica leituras
`ALARM-*` quando a temperatura é pelo menos `28.0`.

## 8. Iniciar o produtor

Numa última janela PowerShell:

```powershell
cd C:\Projetos\SD-2026-2027\Sprints\E1-COMINDIR04\rep04-produtor
java -cp out rep04.Producer
```

Exemplos de comandos no prompt:

```text
S01 21.5
S02 29.3
rajada 100 10
rajada-quente 1000 10
sair
```

O produtor envia `L = 0` e um vetor causal inicializado a zeros.

Na `rajada-quente`, 80% das leituras percorrem ciclicamente `S01`, `S02` e
`S03` (ou todos os sensores disponíveis quando `s < 3`), distribuindo os
alarmes pelas partições correspondentes; os restantes 20% usam os sensores
fora desse conjunto quando existirem.

## 9. Analisar os ficheiros dos líderes

Depois de parar o produtor ou de terminar o ensaio, executa o verificador na
pasta onde estão os ficheiros `lider-p*.txt`:

```powershell
cd C:\Projetos\SD-2026-2027\Sprints\E1-COMINDIR04\rep04-replica
java -cp out rep04.CausalCheck 3 C:\caminho\para\ficheiros
```

O comando verifica `V[k] == seq`, classifica os pares entre partições e cria:

- `relatorio-causal.txt`;
- `vista-lamport.txt`.

Para analisar os lotes, heartbeats e eventos dos detetores:

```powershell
java -cp out rep04.HeartbeatCheck 3 C:\Projetos\SD-2026-2027\Sprints\E1-COMINDIR04
```

O relatório usa os ficheiros `hb-p*.txt` dos líderes e `monitor-*.txt` dos
participantes. Para um ensaio com falhas anotadas, acrescenta o nome do
ficheiro, por exemplo `falhas.txt`, no mesmo diretório.

## 10. Simular perdas numa réplica

Para descartar a primeira mensagem com sequência `5` e perder 20% das
mensagens seguintes:

```powershell
java -Ddescartar=5 -Dperda=0.2 -cp out rep04.PartitionReplica R3 0,1 5002
```

Quando existirem registos retidos, a réplica solicita ao líder os registos em
falta através dos portos TCP `7000 + k`.

## 11. Parar os processos

1. No produtor, escreve `sair`.
2. Nos líderes, réplicas, processador e `IpfsDaemon`, usa `Ctrl+C`.

Não apagues os ficheiros `lider-p*.txt` se quiseres que os líderes retomem os
relógios e as sequências a partir do estado anterior.
