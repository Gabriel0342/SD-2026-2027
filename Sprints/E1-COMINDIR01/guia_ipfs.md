Todos os comandos devem ser feitos nesta diretoria:

```powershell
cd "C:\Users\hugoa\Desktop\SDt\SD-2026-2027\Sprints\E1-COMINDIR0"
```

Num terminal PS:

```powershell
.\ipfs.exe init
```

```powershell
.\ipfs.exe config --json Pubsub.Enabled true
```

```powershell
.\ipfs.exe daemon 
```

Num segundo terminal PS:

```powershell
.\ipfs.exe pubsub sub rep01-temperaturas #Fica à espera de mensagens
```