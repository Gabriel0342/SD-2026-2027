# Compilar o COMINDIR04

Executa os comandos a partir de `E1-COMINDIR04`. Cada projeto e compilado
isoladamente e usa apenas a biblioteca standard do Java.

```powershell
cd rep04-lider
Remove-Item -Recurse -Force out -ErrorAction SilentlyContinue
javac -encoding UTF-8 -d out src\rep04\*.java
cd ..

cd rep04-produtor
Remove-Item -Recurse -Force out -ErrorAction SilentlyContinue
javac -encoding UTF-8 -d out src\rep04\*.java
cd ..

cd rep04-replica
Remove-Item -Recurse -Force out -ErrorAction SilentlyContinue
javac -encoding UTF-8 -d out src\rep04\*.java
cd ..

cd rep04-processador
Remove-Item -Recurse -Force out -ErrorAction SilentlyContinue
javac -encoding UTF-8 -d out src\rep04\*.java
cd ..
```

A ordem de arranque é: réplicas, líderes, processador e produtor.
