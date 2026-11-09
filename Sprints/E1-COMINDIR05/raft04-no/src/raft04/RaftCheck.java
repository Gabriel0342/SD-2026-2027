package raft04;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Verificacao pos-ensaio da seguranca dos termos e das eleicoes. */
public final class RaftCheck {
    private RaftCheck() { }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) throw new IllegalArgumentException(
                "Uso: RaftCheck <N> <pasta> [falhas.txt]");
        int n = Integer.parseInt(args[0]);
        Path root = Paths.get(args[1]);
        StringBuilder report = new StringBuilder("RaftCheck\n");
        for (int k = 0; k < n; k++) {
            Map<Long, List<String>> leaders = new TreeMap<>();
            for (int i = 0; i < Wire.GRUPO; i++) {
                Path events = root.resolve("eventos-p" + k + "-n" + i + ".txt");
                if (!Files.exists(events)) continue;
                for (String line : Files.readAllLines(events, StandardCharsets.UTF_8)) {
                    if (!line.contains(";LIDER;")) continue;
                    long term = fieldLong(line, "termo");
                    leaders.computeIfAbsent(term, ignored -> new ArrayList<>()).add("n" + i);
                }
            }
            report.append("p").append(k).append(": ");
            if (leaders.isEmpty()) report.append("sem lider");
            else for (Map.Entry<Long, List<String>> entry : leaders.entrySet())
                report.append("termo ").append(entry.getKey()).append("=")
                        .append(entry.getValue()).append("; ");
            long unsafe = leaders.values().stream().filter(v -> v.size() > 1).count();
            report.append("termos com mais de um lider=").append(unsafe).append('\n');
        }
        Path output = root.resolve("relatorio-raft.txt");
        Files.writeString(output, report.toString(), StandardCharsets.UTF_8);
        System.out.print(report);
    }

    private static long fieldLong(String line, String field) {
        int start = line.indexOf(field + "=");
        if (start < 0) return -1;
        start += field.length() + 1;
        int end = line.indexOf(';', start);
        return Long.parseLong(end < 0 ? line.substring(start) : line.substring(start, end));
    }
}
