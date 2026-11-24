# TPF — Sprint E2-03: comunicação, colocação e escrita replicada

Este sprint estende a escrita local do E2-02. O líder continua a ser o único
processo que conhece a `master.key`, os registos e o catálogo. As réplicas
voluntárias recebem e guardam apenas os bytes cifrados dos chunks.

## Executar

Requisitos: JDK 17 ou superior e Maven.

Na pasta `Sprints/E2-03`, inicia o líder com um `.env` que contenha
`DISCORD_BOT_TOKEN`:

```powershell
mvn -pl tpf-lider compile
mvn -pl tpf-lider exec:java
```

O líder inicia o hub WebSocket no porto `8765` (`-Dtpf.porto=<porto>`). No
Discord, em `#arquivo`, um administrador com o papel `admin-arquivo` executa
`/replica-token` e entrega o token em privado ao voluntário. A resposta é
efémera e o token só pode ser usado uma vez, nos 10 minutos seguintes.

Arranca cada réplica num terminal separado, também a partir desta pasta:

```powershell
mvn -pl tpf-replica compile
mvn -pl tpf-replica exec:java "-Dexec.args=replica-a ws://localhost:8765 8388608 TOKEN"
mvn -pl tpf-replica exec:java "-Dexec.args=replica-b ws://localhost:8765 2097152 TOKEN"
mvn -pl tpf-replica exec:java "-Dexec.args=replica-c ws://localhost:8765 4194304 TOKEN"
```

Usa um token diferente para cada primeiro registo. Guarda a chave em
`dados-<id>/`; nos arranques seguintes omite o token:

```powershell
mvn -pl tpf-replica exec:java "-Dexec.args=replica-a ws://localhost:8765 8388608"
```

Cada réplica pode usar `-Dtpf.pasta=<pasta>` para escolher a pasta de dados.
Para correr os testes locais do líder: `mvn -pl tpf-lider test`.

## Protocolo

Todas as mensagens são texto UTF-8 com campos separados por `;`. Campos
binários são Base64. O hub aceita ligações de réplicas, não de outros
publicadores:

| Mensagem | Direção | Significado |
|---|---|---|
| `OLA;<id>;<chavePublicaB64>;<token>` | réplica → hub | primeiro registo |
| `OLA;<id>` | réplica → hub | ligação de réplica já registada |
| `DESAFIO;<nonceB64>` | hub → réplica | nonce aleatório de 32 bytes |
| `PROVA;<id>;<assinaturaB64>` | réplica → hub | assinatura Ed25519 de `tpf-registo|<id>|<nonceB64>` |
| `SUB;<tópico>` | réplica → hub | subscrição a `tpf-r-<id>` ou `tpf-ctl` |
| `PUB;<tópico>;<mensagem>` | réplica → hub | publicação apenas em `tpf-lider` |
| `HB;<id>;<usados>;<quota>` | réplica → líder | heartbeat e capacidade local |
| `INVENTARIO;<id>;<chunkId,...>` | réplica → líder | IDs dos chunks guardados |
| `ACK;<id>;<chunkId>;<sha256>` | réplica → líder | confirmação após escrita atómica |
| `STORE;<chunkId>;<hdrB64>;<binB64>` | líder → réplica | guarda cabeçalho e chunk cifrado |

O hub encaminha apenas `PUB` para subscritores do tópico. As publicações em
`tpf-lider` são passadas diretamente ao líder, acompanhadas do ID autenticado
da ligação. Uma réplica só pode subscrever o seu tópico privado e `tpf-ctl`, e
só pode publicar em `tpf-lider`.

## Colocação e escrita

O líder escolhe até `R` réplicas vivas que tenham espaço livre efetivo
(`quota − usados − reservados`) para o chunk cifrado mais a margem. Ordena-as
por rendezvous hashing ponderado:

```text
score(chunk, replica) = livre^ALFA / -ln(u)
u = (v + 0.5) / 2^53
v = os primeiros 53 bits de SHA-256(chunkId + "|" + replicaId)
```

Depois de selar o chunk, o líder grava-o em `selados/`, acrescenta uma entrada
`SELADO` a `catalogo.txt`, reserva capacidade e publica `STORE`. Só aceita um
`ACK` quando o hash coincide com o `chunkId`, o ID declarado é o da ligação
autenticada e essa réplica recebeu o `STORE` daquele chunk. O catálogo passa a
`DURAVEL` com `max(W_MIN, floor(R/2)+1)` confirmações válidas; este estado não
implica que já existam as R cópias. O líder tenta colocar todas as cópias até
`MAX_RONDAS`, aguardando `ACK_TIMEOUT_MS` por ronda. Réplicas indisponíveis,
capacidade insuficiente ou confirmações em falta deixam o chunk
`SUBREPLICADO`.

`catalogo.txt` é apenas do líder; cada linha tem o formato:

```text
chunkId;lsnDe;lsnAte;tamanho;replicas;estado
```

`replicas` contém IDs separados por vírgulas. `replicas.txt` guarda
`id;chavePublicaB64`. Cada réplica mantém os ficheiros `.bin` e `.hdr` em
`dados-<id>/`, a chave Ed25519 e `eventos-<id>.txt`. A chave privada fica com
permissões só de leitura do dono. Não se copia a master key, o catálogo nem
registos em claro para a pasta de uma réplica.

## Parâmetros

Todos os parâmetros aceitam propriedades de sistema com prefixo `tpf.`.

| Propriedade | Predefinição |
|---|---:|
| `tpf.porto` | 8765 |
| `tpf.periodo` | 2000 ms |
| `tpf.suspeita` | 6000 ms |
| `tpf.morta` | 10000 ms |
| `tpf.tokenValidade` | 600000 ms |
| `tpf.r` | 3 |
| `tpf.wMin` | 2 |
| `tpf.ackTimeout` | 4000 ms |
| `tpf.maxRondas` | 3 |
| `tpf.margem` | 65536 bytes |
| `tpf.alfa` | 1.0 |
| `tpf.backoffMin` / `tpf.backoffMax` | 1000 / 30000 ms |
| `tpf.chunkMax` / `tpf.pad` / `tpf.selagem` | 65536 bytes / 4096 bytes / 5000 ms |

## Concorrência e durabilidade

Os callbacks de JDA, do hub, do temporizador de selagem e os temporizadores de
ACK correm em threads distintas. `ReplicaRegistry.reserveAndChoose` seleciona
as réplicas e reserva os bytes na mesma secção sincronizada; o ACK transfere a
reserva para espaço usado, e o timeout liberta-a. `Catalog` sincroniza a
entrada, as confirmações, o estado e a gravação atómica. O hub protege as
chaves, tokens e subscrições; cada callback de mensagem autentica a ligação
antes de chamar o líder. O processo da réplica sincroniza as escritas aos
chunks e ao contador de espaço.

## Limites deste sprint

Continuam provisórios `/config` e `/recuperar`. Este sprint não faz manifesto
cifrado, reconciliação, remoção (`DELETE`), desafios de posse, `FETCH`,
recuperação a partir das réplicas nem análise offline com `ArquivoCheck`.

## Questões de reflexão

1. O token autoriza o primeiro registo e limita quem pode associar uma chave
   pública nova. Se for intercetado durante a validade, outra pessoa pode
   tentar consumi-lo primeiro; a prova Ed25519 ainda exige a chave privada
   correspondente.
2. Assinar o prefixo, o ID e o nonce vincula a prova ao protocolo, à identidade
   e à ligação atual; assinar só o nonce permitiria reutilizar a prova noutro
   contexto ou tentar associá-la a outro ID.
3. Sem reservar junto da escolha, duas threads poderiam observar os mesmos
   bytes livres e ambas enviar chunks que, em conjunto, excedem a quota.
4. `W_MIN` impede que R pequeno torne uma única confirmação suficiente; a
   maioria faz o mínimo crescer com R (por exemplo, três ACK com R = 5). W = 1
   expõe dados a uma única falha de réplica; exigir R aumenta a espera e reduz
   a disponibilidade se uma réplica estiver lenta.
5. A maioria aqui define quando uma cópia imutável é considerada durável; não
   resolve conflitos entre escritores, porque há um único líder e cada chunk
   tem um ID de conteúdo.
6. α controla o peso do espaço livre: α = 0 remove o peso da ordenação, mas a
   réplica continua a ter de caber no filtro de capacidade; α = 1 pondera
   proporcionalmente ao espaço livre; α = 2 favorece mais as réplicas maiores.
7. Gravar ambos os ficheiros atomicamente antes do ACK evita que o líder conte
   uma confirmação cuja réplica ainda não tem o chunk completo em disco.
8. O ID autenticado vem da sessão que provou a chave privada. Confiar no ID do
   texto do ACK permitiria a uma réplica atribuir a cópia a outra identidade.
