# Executar o COMINDIR02

Estas instruções assumem Windows, PowerShell, Java instalado e que os
comandos são executados a partir de:

```powershell
cd C:\Users\hugoa\Desktop\SDt\SD-2026-2027\Sprints\E1-COMINDIR02
```

## Configuração partilhada entre IDEs

Os valores predefinidos estão em [`runtime.properties`](./runtime.properties),
versionado com o projeto. Sem argumentos, os três programas usam esse ficheiro:

- produtor: API Kubo `5001`;
- líderes: API Kubo `5002` e endereço anunciado `127.0.0.1`;
- réplicas: API Kubo `5002`;
- número de partições: `3`.

O ficheiro é localizado a partir do diretório de execução ou da localização
das classes compiladas. Se necessário, pode indicar-se o caminho com a
propriedade JVM `-Dcomindir02.config=caminho/para/runtime.properties`.
Argumentos passados ao programa continuam a substituir os valores do ficheiro.

## 1. Verificar os pré-requisitos

O programa usa Java 17 ou superior (usa `java.net.http` e `record`) e dois
nós Kubo/IPFS locais: API `5001` para o produtor e API `5002` para líderes e
réplicas.

```powershell
java -version
.\ipfs.exe version
```

Se for a primeira utilização do Kubo, inicialize o repositório uma vez:

```powershell
.\ipfs.exe init
```

## 2. Iniciar os nós IPFS

O lançador inicia ou reutiliza o nó 1 na API `5001`, prepara um repositório
independente para o nó 2 na API `5002` e liga os dois pela rede swarm. A porta
swarm do segundo nó é `4002`; o respetivo repositório fica na pasta pessoal do
utilizador, em `.ipfs-sd-comindir02-node2`.

```powershell
javac -encoding UTF-8 IpfsDaemon.java
java -Dipfs.executable=.\ipfs.exe IpfsDaemon
```

Deixe o processo do lançador ativo. Confirme que ambas as APIs respondem:

```powershell
Invoke-WebRequest -Method Post http://127.0.0.1:5001/api/v0/id
Invoke-WebRequest -Method Post http://127.0.0.1:5002/api/v0/id
```

## 3. Compilar as aplicações

Antes de iniciar os processos, execute todos os comandos de
[compilação](./compiling.md). A compilação coloca as classes em
`out\rep02` e não mistura os artefactos dos três processos.

## 4. Iniciar os líderes

Abra uma janela PowerShell por líder. Com três partições, inicie um líder para
cada `k` (`0`, `1` e `2`):

```powershell
cd C:\Users\hugoa\Desktop\SDt\SD-2026-2027\Sprints\E1-COMINDIR02\rep02-lider
java -cp out rep02.PartitionLeader 0
```

```powershell
cd C:\Users\hugoa\Desktop\SDt\SD-2026-2027\Sprints\E1-COMINDIR02\rep02-lider
java -cp out rep02.PartitionLeader 1
```

```powershell
cd C:\Users\hugoa\Desktop\SDt\SD-2026-2027\Sprints\E1-COMINDIR02\rep02-lider
java -cp out rep02.PartitionLeader 2
```

Os líderes escutam, respetivamente, nos portos TCP `7000`, `7001` e `7002`.
Sem argumentos adicionais, leem o número de partições, o endereço anunciado e
a API PubSub de `runtime.properties`. Argumentos fornecidos na linha de
comandos substituem os valores correspondentes.

## 5. Iniciar as réplicas

Abra uma janela PowerShell por réplica. Sem argumentos, o programa atribui
automaticamente um identificador e duas partições a cada réplica. A API Kubo
predefinida é a `5002`, conforme `runtime.properties`:

```powershell
cd C:\Users\hugoa\Desktop\SDt\SD-2026-2027\Sprints\E1-COMINDIR02\rep02-replica
java -cp out rep02.PartitionReplica
```

Execute o comando acima em três janelas diferentes. Também é possível fixar
explicitamente o identificador e as partições; o terceiro argumento opcional
substitui o porto da API:

```powershell
java -cp out rep02.PartitionReplica R0 0,1
java -cp out rep02.PartitionReplica R1 1,2
java -cp out rep02.PartitionReplica R2 2,0
```

O identificador e as partições devem ser diferentes entre processos. As
réplicas escrevem os seus ficheiros `replica-*-p*.txt` na pasta de trabalho
da réplica.

## 6. Iniciar o produtor

Abra outra janela PowerShell:

```powershell
cd C:\Users\hugoa\Desktop\SDt\SD-2026-2027\Sprints\E1-COMINDIR02\rep02-produtor
java -cp out rep02.Producer
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
perder 20% das restantes; aqui o terceiro argumento substitui a API predefinida
e liga esta réplica ao nó `5001`:

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