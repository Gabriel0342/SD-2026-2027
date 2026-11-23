package tpf;

/**
 * Runs every test of the leader. Started by mvn test (see the pom), or directly:
 * mvn -pl tpf-lider test
 */
public final class AllTests {

    private AllTests() {
    }

    public static void main(String[] args) throws Exception {
        // A shorter seal timeout keeps the tests fast. Wire reads the system properties
        // once, when the class is first used, so this must happen before anything else.
        if (System.getProperty("tpf.selagem") == null) {
            System.setProperty("tpf.selagem", "500");
        }
        // A burst of 500 records would print 500 REGISTO events; keep the test output readable.
        if (System.getProperty("tpf.ecoEventos") == null) {
            System.setProperty("tpf.ecoEventos", "false");
        }

        ChunkTest.run();
        ChunkCryptoTest.run();
        LeaderWritePathTest.run();

        System.out.println();
        System.out.println("All " + TestSupport.passed + " checks passed.");
    }
}
