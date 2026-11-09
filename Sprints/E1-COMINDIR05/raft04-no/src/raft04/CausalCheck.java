package raft04;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Confirma que as sequencias de cada ficheiro nao tem buracos ou regressoes. */
public final class CausalCheck {
    private CausalCheck() { }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) throw new IllegalArgumentException(
                "Uso: CausalCheck <N> <pasta>");
        int n = Integer.parseInt(args[0]);
        Path root = Paths.get(args[1]);
        int errors = 0;
        for (int k = 0; k < n; k++) {
            for (int i = 0; i < Wire.GRUPO; i++) {
                Path path = root.resolve("no-p" + k + "-n" + i + ".txt");
                long expected = 1;
                if (!Files.exists(path)) continue;
                for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) continue;
                    SensorRecord record = SensorRecord.fromLine(line, n);
                    if (record.getSeq() != expected++) errors++;
                }
            }
        }
        System.out.println("CausalCheck: " + (errors == 0 ? "OK" : errors + " inconsistencias"));
        if (errors != 0) System.exit(1);
    }
}
