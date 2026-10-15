package rep01;

// Persistencia local: a escrita acontece antes da propagacao para a rede.

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

// synchronized evita escritas concorrentes no mesmo RecordFile.

public class RecordFile {
    private final Path path;

    public RecordFile(String fileName) {
        this.path = Paths.get(fileName);
    }

    // append e local e nao confirma que alguma replica recebeu o registo.
    public synchronized void append(SensorRecord r) throws IOException {
        Files.writeString(path, r.toLine() + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    // Permite retomar a numeracao depois de reiniciar o lider ou uma replica.
    public synchronized long lastSeq() throws IOException {
        if (!Files.exists(path)) {
            return 0;
        }
        long last = 0;
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                last = SensorRecord.fromLine(line).getSeq();
            }
        }
        return last;
    }
}