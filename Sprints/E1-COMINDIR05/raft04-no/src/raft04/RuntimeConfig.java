package raft04;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;

/** Localiza e carrega a configuracao comum dos processos. */
final class RuntimeConfig {
    // Todos os processos procuram a mesma configuracao para manter APIs e N
    // consistentes durante um ensaio.
    private static final String FILE_NAME = "runtime.properties";

    private RuntimeConfig() { }

    /** Le runtime.properties em UTF-8. */
    static Properties load() throws IOException {
        // A leitura explicita em UTF-8 evita depender da codificacao do sistema.
        Path file = findFile();
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    /** Obtem uma propriedade nao vazia, usando o fallback indicado. */
    static String value(Properties properties, String key, String fallback) {
        // Valores vazios sao erros, mesmo quando a propriedade existe.
        String value = properties.getProperty(key, fallback).trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Configuracao vazia: " + key);
        }
        return value;
    }

    /** Procura caminho explicito e depois diretorios ancestrais conhecidos. */
    private static Path findFile() throws IOException {
        // Primeiro respeita um caminho fornecido por -Draft04.config; depois
        // procura a partir da pasta atual e da localizacao das classes.
        String explicitPath = System.getProperty("raft04.config");
        if (explicitPath != null && !explicitPath.isBlank()) {
            Path file = Path.of(explicitPath).toAbsolutePath().normalize();
            if (Files.isRegularFile(file)) {
                return file;
            }
            throw new IOException("Ficheiro de configuracao nao encontrado: " + file);
        }

        List<Path> roots = new ArrayList<>();
        addAncestors(roots, Path.of(System.getProperty("user.dir")));
        try {
            Path classes = Path.of(RuntimeConfig.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            addAncestors(roots, classes);
        } catch (Exception e) {
            throw new IOException("Nao foi possivel localizar runtime.properties.", e);
        }

        for (Path root : new LinkedHashSet<>(roots)) {
            Path direct = root.resolve(FILE_NAME);
            if (Files.isRegularFile(direct)) {
                return direct;
            }
            Path repository = root.resolve("Sprints/E1-COMINDIR05").resolve(FILE_NAME);
            if (Files.isRegularFile(repository)) {
                return repository;
            }
        }
        throw new IOException("runtime.properties nao encontrado. Execute a partir do "
                + "E1-COMINDIR05 ou defina -Draft04.config=<caminho>.");
    }

    private static void addAncestors(List<Path> roots, Path path) {
        // Adiciona a pasta indicada e todos os seus pais para localizar o sprint.
        Path current = path.toAbsolutePath().normalize();
        while (current != null) {
            roots.add(current);
            current = current.getParent();
        }
    }
}
