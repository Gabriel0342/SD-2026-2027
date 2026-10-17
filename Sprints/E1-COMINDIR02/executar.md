# Executar o COMINDIR02

Estas instruções assumem Windows, PowerShell, Java instalado e que os
comandos são executados a partir de:

```powershell
cd C:\Users\hugoa\Desktop\SDt\SD-2026-2027\Sprints\E1-COMINDIR02
```

## 1. Verificar os pré-requisitos

O programa usa Java 17 ou superior (usa `java.net.http` e `record`) e um
daemon Kubo/IPFS com a API em `127.0.0.1:5001`.

```powershell
java -version
.\ipfs.exe version
```

Se for a primeira utilização do Kubo, inicialize o repositório uma vez:

```powershell
.\ipfs.exe init
```

## 2. Iniciar o daemon IPFS

Abra uma janela PowerShell dedicada, permaneça na pasta
`E1-COMINDIR02` e execute:

```powershell
.\ipfs.exe daemon
```

Mantenha esta janela aberta. Numa segunda janela, confirme que a API está
disponível:

```powershell
Invoke-WebRequest -Method Post http://127.0.0.1:5001/api/v0/id
```

## 3. Compilar

Antes de iniciar os processos, execute todos os comandos de
[compilação](./compiling.md). A compilação coloca as classes em
`out\rep02` e não mistura os artefactos dos três processos.

## 4. Iniciar os líderes

Abra uma janela PowerShell por líder. Com três partições, inicie um líder para
cada `k` (`0`, `1` e `2`):

```powershell
cd C:\Users\hugoa\Desktop\SDt\SD-2026-2027\Sprints\E1-COMINDIR02\rep02-lider
java -cp out rep02.PartitionLeader 0 3 127.0.0.1 http://127.0.0.1:5001/api/v0
```

```powershell
cd C:\Users\hugoa\Desktop\SDt\SD-2026-2027\Sprints\E1-COMINDIR02\rep02-lider
java -cp out rep02.PartitionLeader 1 3 127.0.0.1 http://127.0.0.1:5001/api/v0
```

```powershell
cd C:\Users\hugoa\Desktop\SDt\SD-2026-2027\Sprints\E1-COMINDIR02\rep02-lider
java -cp out rep02.PartitionLeader 2 3 127.0.0.1 http://127.0.0.1:5001/api/v0
```

Os líderes escutam, respetivamente, nos portos TCP `7000`, `7001` e `7002`.
O segundo argumento (`3`) é o número total de partições; os argumentos
seguintes são o endereço anunciado pelo líder e o endereço da API IPFS.

## 5. Iniciar as réplicas

Abra uma janela PowerShell por réplica. Sem argumentos, o programa atribui
automaticamente um identificador e duas partições a cada réplica:

```powershell
cd C:\Users\hugoa\Desktop\SDt\SD-2026-2027\Sprints\E1-COMINDIR02\rep02-replica
java -cp out rep02.PartitionReplica
```

Execute o comando acima em três janelas diferentes. Também é possível fixar
explicitamente o identificador, as partições e o porto da API:

```powershell
java -cp out rep02.PartitionReplica R0 0,1 5001
java -cp out rep02.PartitionReplica R1 1,2 5001
java -cp out rep02.PartitionReplica R2 2,0 5001
```

O identificador e as partições devem ser diferentes entre processos. As
réplicas escrevem os seus ficheiros `replica-*-p*.txt` na pasta de trabalho
da réplica.

## 6. Iniciar o produtor

Abra outra janela PowerShell:

```powershell
cd C:\Users\hugoa\Desktop\SDt\SD-2026-2027\Sprints\E1-COMINDIR02\rep02-produtor
java -cp out rep02.Producer 3 http://127.0.0.1:5001/api/v0
```

Quando aparecer o prompt `>`, podem ser usados os seguintes comandos:

```text
S01 21.5
rajada 100 10
rajada-quente 1000 10
sair
```

`rajada <n> <s>` publica `n` leituras para sensores `S01` a `S<s>`.
`rajada-quente <n> <s>` concentra aproximadamente 80% das leituras em
`S01`, permitindo testar a distribuição de carga.

## 7. Testar recuperação

Para simular perdas na receção PubSub, inicie uma réplica com propriedades
da JVM. Por exemplo, para descartar a primeira mensagem com sequência `5` e
perder 20% das restantes:

```powershell
java -Ddescartar=5 -Dperda=0.2 -cp out rep02.PartitionReplica R3 0,1 5001
```

Quando o limite de registos retidos for atingido, a réplica contacta o líder
por TCP nos portos `7000 + k`, pede o intervalo em falta e aplica os registos
pela ordem da sequência.

## 8. Parar os processos

No produtor escreva `sair`. Nos líderes, réplicas e no daemon IPFS, use
`Ctrl+C` na respetiva janela PowerShell. Não apague os ficheiros de dados se
quiser continuar a execução a partir do último número de sequência.