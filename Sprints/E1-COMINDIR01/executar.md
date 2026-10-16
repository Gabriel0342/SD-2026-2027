# Executar réplica

```powershell
cd rep01-replica
java -cp out rep01.PubSubReplica 1
```
 
```powershell
cd rep01-replica
java -cp out rep01.PubSubReplica 2
```
 
```powershell
cd rep01-replica
java -cp out rep01.PubSubReplica 3
```
 
# Executar líder

```powershell
cd rep01-lider
java -cp out rep01.PubSubLeader
```