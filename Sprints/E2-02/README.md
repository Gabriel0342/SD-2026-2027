# TPF — Líder

O líder é um bot Discord (`tpf.LeaderBot`) construído com JDA. O token do bot
vem do ficheiro `.env`, lido pela biblioteca `dotenv-java`, ou da variável de
ambiente `DISCORD_BOT_TOKEN`.

## 1. Configurar o token

1. No [Portal de Desenvolvedores do Discord](https://discord.com/developers/applications),
   cria a aplicação, vai a **Bot** e copia o **token**.
   Ativa a *intent* **MESSAGE CONTENT** (necessária para o bot ler mensagens).
2. Cria o ficheiro `.env` na pasta a partir da qual arrancas o líder
   (por exemplo `Sprints/E2-02`):

   ```env
   DISCORD_BOT_TOKEN=o-teu-token-aqui
   ```

> ⚠️ Nunca fazes *commit* do `.env` nem da `master.key`: o `.gitignore` já os exclui.

## 2. Arrancar o líder

Com JDK 17 e Maven instalados, a partir desta pasta (`Sprints/E2-02`):

```bash
mvn -pl tpf-lider compile
mvn -pl tpf-lider exec:java
```

> Compila primeiro o código (`compile`) e só depois arranca o bot (`exec:java`).
> Se o projeto ainda não tiver sido compilado, o `exec:java` falha porque não
> encontra as classes em `target/classes`.

O bot liga-se ao Discord e só responde no canal `#arquivo`. Os comandos já
estão registados no servidor; se for preciso registá-los de novo (por exemplo,
porque mudaram), arranca uma vez com `-Dtpf.registarComandos=true`.

Todos os parâmetros são propriedades de sistema com o prefixo `tpf.`:

```bash
mvn -pl tpf-lider exec:java -Dtpf.selagem=2000 -Dtpf.chunkMax=16384
```

| Propriedade | Omissão | Significado |
| --- | --- | --- |
| `tpf.chunkMax` | 65536 | `CHUNK_MAX_BYTES`: o chunk é selado quando os registos serializados atingem este tamanho |
| `tpf.pad` | 4096 | `PAD_BYTES`: o chunk serializado é completado até um múltiplo deste valor |
| `tpf.selagem` | 5000 | `SELAGEM_MS`: o chunk é selado este tempo depois do seu primeiro registo |
| `tpf.pasta` | `.` | pasta de dados do líder (ver abaixo) |
| `tpf.ecoEventos` | `true` | escreve também cada evento na consola |
| `tpf.registarComandos` | `false` | regista de novo os *slash commands* no servidor |

## 3. Sprint E2-02 — registo durável, chunks e cifra

Este sprint implementa o caminho de escrita local do líder. As réplicas, a
colocação, o catálogo, o manifesto e a recuperação ficam para os sprints
seguintes, por isso `/estado`, `/config`, `/recuperar`, `/replica-token` e
`/replica-remover` mantêm a resposta provisória.

```
/registar, mensagem em #arquivo, /rajada
  └─► registar(autor, canal, texto)    [método sincronizado, único para os três]
        ├─ LSN seguinte
        ├─ append a registos.log + fsync (FileChannel.force(true))
        ├─ evento REGISTO, e só então resposta com o LSN
        └─ chunk aberto ──► selado por tamanho (CHUNK_MAX_BYTES) ou por tempo (SELAGEM_MS)
                              └─► serializar + padding ──► AES-256-GCM (DEK derivada da master.key)
                                    └─► selados/<chunkId>.bin + .hdr ──► evento SELADO
```

### 3.1 Ficheiros na pasta do líder

A pasta do líder é a pasta de onde é arrancado (ou a indicada em `-Dtpf.pasta`).

| Ficheiro | Conteúdo |
| --- | --- |
| `master.key` | 32 bytes aleatórios; gerada no primeiro arranque com `SecureRandom` e só de leitura para o dono (`chmod 400` em Linux/macOS, ACL equivalente no Windows). Se existir mas não tiver 32 bytes, o líder recusa arrancar |
| `registos.log` | uma linha por registo: `lsn;instante;autor;canal;texto em Base64` |
| `eventos-lider.txt` | uma linha por evento: `instante;evento;detalhe` |
| `selados/<chunkId>.bin` | o texto cifrado do chunk (com a *tag* GCM) |
| `selados/<chunkId>.hdr` | `keyVersion;salt;nonce` |

O `instante` é sempre em milissegundos desde 1970-01-01 (UTC). Também os
ficheiros de dados gerados estão no `.gitignore`.

### 3.2 `registos.log`

```
1;1795036607211;hugo;arquivo;cmVnaXN0by1zZWNyZXRvLWRlLXRlc3RlLTEyMw==
```

- Só o texto vai em Base64. No `autor` e no `canal`, os caracteres `;` e as
  quebras de linha são substituídos, para que uma linha nunca se parta.
- Cada registo é acrescentado e forçado para disco (`fsync`) **antes** de o bot
  responder. Se a escrita falhar, o ficheiro é reposto como estava e o LSN não é
  consumido.
- No arranque, o líder lê o ficheiro e continua a partir do último LSN. Exige
  LSN a começar em 1 e consecutivos, e recusa arrancar se não forem. Uma última
  linha sem `\n` é o resto de uma escrita interrompida por uma falha (nunca foi
  confirmada ao utilizador) e é descartada.

### 3.3 Formato binário do chunk (antes de cifrar)

Inteiros em *big-endian*, texto em UTF-8.

| Campo | Tamanho | Descrição |
| --- | --- | --- |
| **Cabeçalho** | **9 bytes** | |
| `magic` | 4 | `"TPFC"` (`0x54504643`) |
| `versao` | 1 | versão do formato, atualmente `1` |
| `n` | 4 | número de registos (`int`) |
| **Registo** (repete-se `n` vezes) | **28 + textos** | |
| `lsn` | 8 | `long` |
| `instante` | 8 | `long`, ms desde a época |
| `autorLen` + `autor` | 4 + `autorLen` | comprimento (`int`) seguido dos bytes |
| `canalLen` + `canal` | 4 + `canalLen` | comprimento (`int`) seguido dos bytes |
| `textoLen` + `texto` | 4 + `textoLen` | comprimento (`int`) seguido dos bytes |
| **Padding** | 0 a `PAD_BYTES − 1` | bytes a zero até ao múltiplo seguinte de `PAD_BYTES` |

A leitura inversa (`Chunk.ler`) lê o cabeçalho e os `n` registos e ignora o
resto, ou seja, o padding.

**Selagem.** O tamanho do chunk é o cabeçalho mais os registos, sem padding.
Quando um registo faz esse tamanho atingir `CHUNK_MAX_BYTES`, o chunk é selado
de imediato e inclui esse registo. Por isso, um chunk selado por tamanho pode
ter um pouco mais de `CHUNK_MAX_BYTES` (com os valores por omissão, 69632 bytes
depois do padding). Caso contrário, é selado `SELAGEM_MS` depois do seu primeiro
registo. Depois de selado, o chunk é imutável e o registo seguinte abre um chunk
novo.

### 3.4 Cifra, `.bin` e `.hdr`

```
salt      = 32 bytes aleatórios (novos em cada chunk)
nonce     = 12 bytes aleatórios (novos em cada chunk)
DEK       = HKDF-SHA256(MK, salt, info = "tpf-chunk-v1")   extração + 1 bloco de expansão (HMAC com 0x01)
aad       = "tpf-v1" || keyVersion (4 bytes) || salt || nonce,   keyVersion = 1
cifrado   = AES-256-GCM(DEK, nonce, chunk com padding, aad)    tag de 128 bits no fim
chunkId   = SHA-256(cifrado), em hexadecimal
```

- `selados/<chunkId>.bin` contém exatamente `cifrado`, pelo que **o nome do
  ficheiro é o SHA-256 do seu conteúdo**. O seu tamanho é o tamanho com padding
  (múltiplo de `PAD_BYTES`) mais os 16 bytes da *tag* GCM.
- `selados/<chunkId>.hdr` é uma linha de texto `keyVersion;salt;nonce`, com a
  versão em decimal e o `salt` e o `nonce` em Base64, por exemplo
  `1;Lq3v4BcAgNI4YNlCgKwM1nk/tYLvDvYr74Va4feGHuY=;n7+/rUuoI9dw7lg5`.
- `open` verifica, por esta ordem, o hash contra o `chunkId`, a versão da chave e
  a autenticação GCM, e lança uma exceção em qualquer falha.
- Os dois ficheiros são escritos num ficheiro temporário, com `fsync` e
  `ATOMIC_MOVE`; o `.hdr` primeiro e o `.bin` por último. Um chunk existe por
  inteiro ou não existe.

### 3.5 Eventos

| Evento | Detalhe |
| --- | --- |
| `REGISTO` | `lsn=<lsn> autor=<autor> canal=<canal>` |
| `SELADO` | `chunk=<chunkId> lsn=<de>-<ate> registos=<n> tamanho=<bytes com padding> cifrado=<bytes do .bin>` |
| `ERRO` | descrição do erro (escrita falhada, chunk ilegível no arranque, ...) |

### 3.6 Concorrência e falhas

- Um único *lock* protege o próximo LSN, o append a `registos.log` e o chunk
  aberto, pelo que a ordem dos LSN, do ficheiro e dos chunks é sempre a mesma.
  Concorrem a *thread* do JDA (comandos e mensagens), a da `/rajada` e a de
  selagem.
- A cifra e a escrita de um chunk selado correm na *thread* de selagem, fora do
  *lock*: selar um chunk de 64 KB não atrasa o `fsync` dos registos seguintes. Um
  contador de geração impede que o temporizador de um chunk já selado por
  tamanho sele antes do tempo o chunk seguinte.
- Se o líder parar antes de selar o chunk aberto, os registos continuam em
  `registos.log`. No arranque seguinte, os que não pertencem a nenhum chunk de
  `selados/` voltam ao chunk aberto e são selados. Enquanto o catálogo não
  existir (sprint seguinte), `selados/` é a referência do que já foi selado.

### 3.7 `/rajada <n> <intervaloMs>`

Só para `admin-arquivo`. Injeta `n` registos sintéticos pelo mesmo método de
`/registar`, um a cada `intervaloMs`, numa *thread* própria. Cada texto tem 400
caracteres (`rajada <id> <i>/<n>` seguido de letras e dígitos aleatórios), para
que `/rajada 500 0` encha vários chunks por tamanho. No fim, o bot indica o
intervalo de LSN no canal.

## 4. Testes e verificação

### 4.1 Testes

Os testes não usam bibliotecas externas: o projeto só pode usar JDA,
Java-WebSocket e o JDK. Estão em `tpf-lider/test/tpf` e correm com:

```bash
mvn -pl tpf-lider test
```

- `ChunkCryptoTest`: cifrar e decifrar; byte alterado no texto cifrado (detetado
  pelo hash e, com o `id` recalculado, pela autenticação GCM); `salt`, `nonce` e
  `keyVersion` diferentes; outra `master.key`; cabeçalhos trocados entre chunks;
  HKDF contra o vetor de teste do RFC 5869; formato do `aad`.
- `ChunkTest`: serialização, padding, leitura inversa e linhas de `registos.log`.
- `LeaderWritePathTest`: o caminho de escrita completo, sem Discord, numa pasta
  temporária. Cobre a criação da `master.key`, o `fsync` antes da resposta, a
  selagem por tempo e por tamanho, os LSN depois de reiniciar, os registos por
  selar depois de uma paragem, a linha incompleta, a adulteração, outra chave e
  a recusa de arranque.

### 4.2 `SealedCheck`

Ferramenta offline, que pode correr com o líder parado ou a correr e não altera
nenhum ficheiro. Para cada chunk de `selados/`, verifica que o nome é o SHA-256
do conteúdo, que abre com a `master.key`, que o tamanho com padding é múltiplo
de `PAD_BYTES`, que os registos são exatamente os de `registos.log`, que o
intervalo coincide com o evento `SELADO` e que nenhum texto aparece em claro.

```bash
# a partir da pasta do líder (por omissão, Sprints/E2-02)
mvn -pl tpf-lider exec:java -Dexec.mainClass=tpf.SealedCheck

# indicando a pasta e, opcionalmente, outra chave (todos os chunks devem falhar)
mvn -pl tpf-lider exec:java -Dexec.mainClass=tpf.SealedCheck -Dexec.args="pasta/do/lider outra.key"

# sem Maven
java -cp tpf-lider/target/classes tpf.SealedCheck [pastaLider] [masterKey]
```

### 4.3 Critérios de aceitação

| Critério | Como verificar |
| --- | --- |
| `/registar` responde com o LSN e a linha já está em `registos.log` | `/registar olá` → «Registado com LSN n»; `tail -1 registos.log` |
| `#arquivo` usa o mesmo caminho e ignora bots e outros canais | escrever em `#arquivo` e noutro canal; só a primeira mensagem gera `REGISTO` |
| LSN continuam depois de reiniciar | parar o líder, arrancar e registar: o LSN é o último de `registos.log` + 1 |
| 1 registo → chunk selado ~5 s depois; `/rajada 500 0` → vários chunks por tamanho | eventos `SELADO`; `tamanho` é sempre múltiplo de 4096 |
| Nome = SHA-256 do conteúdo e nada em claro | `sha256sum selados/*.bin` (PowerShell: `Get-FileHash selados\*.bin`); `grep -r "texto registado" selados/` não devolve nada |
| O líder abre cada chunk e obtém os registos do evento `SELADO` | `SealedCheck` → `RESULT: OK` |
| Byte alterado, outro `salt`/`nonce`/`keyVersion` ou outra chave fazem `open` falhar | `mvn -pl tpf-lider test`; alterar um byte de um `.bin` ou passar outra chave ao `SealedCheck` → `FAIL` |
| Eventos `REGISTO`/`SELADO` e README com os formatos | `eventos-lider.txt`; secções 3.3 e 3.4 |
