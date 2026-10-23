package rep03;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

public class RecordFile {
    private final Path path;

    public RecordFile(String fileName) {
        this.path = Paths.get(fileName);
    }

    // acrescenta uma linha ao fim do ficheiro (cria-o se nao existir)
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

    // numero de sequencia do ultimo registo guardado (0 se o ficheiro nao existir)
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
    // registos com from <= seq <= to, pela ordem do ficheiro (lista vazia se o ficheiro nao existir)
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
