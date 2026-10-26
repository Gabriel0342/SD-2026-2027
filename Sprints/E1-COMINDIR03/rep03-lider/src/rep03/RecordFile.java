package rep03;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/** Persistência append-only dos registos de um líder e leitura de intervalos. */
public class RecordFile {
    private final Path path;

    /** Associa o objeto ao ficheiro local indicado. */
    public RecordFile(String fileName) {
        this.path = Paths.get(fileName);
    }

    /** Acrescenta uma linha UTF-8 ao fim do ficheiro, criando-o se necessário. */
    public synchronized void append(SensorRecord r) throws IOException {
        byte[] line = (r.toLine() + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE)) {
            long start = channel.size();
            channel.position(start);
            try {
                ByteBuffer buffer = ByteBuffer.wrap(line);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            } catch (IOException failure) {
                try {
                    channel.truncate(start);
                    channel.force(true);
                } catch (IOException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            }
        }
    }

    /** Obtém a última sequência, permitindo ao líder retomar após reinício. */
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

    /** Lê o último registo completo, incluindo o vetor de dimensão N. */
    public synchronized SensorRecord last(int n) throws IOException {
        if (!Files.exists(path)) {
            return null;
        }
        SensorRecord last = null;
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                last = SensorRecord.fromLine(line, n);
            }
        }
        return last;
    }
    /** Seleciona o intervalo pedido por uma réplica, mantendo a ordem persistida. */
    public synchronized List<SensorRecord> range(long from, long to) throws IOException {
        List<SensorRecord> out = new ArrayList<>();
        if (!Files.exists(path)) {
            return out;
        }
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            SensorRecord r = SensorRecord.fromLine(line);
            if (r.getSeq() >= from && r.getSeq() <= to) {
                out.add(r);
            }
        }
        return out;
    }
}
