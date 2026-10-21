# Compilar o COMINDIR03

Executa os comandos a partir de `E1-COMINDIR03`. Cada projeto e compilado
isoladamente e usa apenas a biblioteca standard do Java.

```powershell
cd rep03-lider
Remove-Item -Recurse -Force out\rep03 -ErrorAction SilentlyContinue
javac -encoding UTF-8 -d out src\rep03\*.java
cd ..

cd rep03-produtor
Remove-Item -Recurse -Force out\rep03 -ErrorAction SilentlyContinue
javac -encoding UTF-8 -d out src\rep03\*.java
cd ..

cd rep03-replica
Remove-Item -Recurse -Force out\rep03 -ErrorAction SilentlyContinue
javac -encoding UTF-8 -d out src\rep03\*.java
cd ..

cd rep03-processador
Remove-Item -Recurse -Force out\rep03 -ErrorAction SilentlyContinue
javac -encoding UTF-8 -d out src\rep03\*.java
cd ..
```

A ordem de arranque é: réplicas, líderes, processador e produtor.
