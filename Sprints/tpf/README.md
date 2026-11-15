# TPF — Líder

O líder é um bot Discord (`tpf.LeaderBot`) construído com JDA. O token do bot
vem do ficheiro `.env`, lido pela biblioteca `dotenv-java`.

## 1. Configurar o token

1. No [Portal de Desenvolvedores do Discord](https://discord.com/developers/applications),
   cria a aplicação, vai a **Bot** e copia o **token**.
   Ativa a *intent* **MESSAGE CONTENT** (necessária para o bot ler mensagens).
2. Cria o ficheiro `.env` dentro da pasta `tpf-lider`:

   ```env
   DISCORD_BOT_TOKEN=o-teu-token-aqui
   ```

> ⚠️ Nunca fazes *commit* do `.env` — adiciona-o ao `.gitignore`.

## 2. Arrancar o líder

Com JDK 17 e Maven instalados, a partir desta pasta (`Sprints/tpf`):

```bash
mvn -pl tpf-lider compile
mvn -pl tpf-lider exec:java
```

> Compila primeiro o código (`compile`) e só depois arranca o bot (`exec:java`).
> Se o projeto ainda não tiver sido compilado, o `exec:java` falha porque não
> encontra as classes em `target/classes`.

Ou, a partir da pasta `tpf-lider` (onde está o `.env`):

```bash
cd tpf-lider
mvn compile exec:java
```

O bot liga-se ao Discord e regista os comandos (`/registar`, `/estado`,
`/config`, `/recuperar`, ...) quando entra no servidor. Só responde no canal
`#arquivo`.
