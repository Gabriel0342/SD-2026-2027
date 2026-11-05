package raft04;

public final class VoteReply {
    private final long term;
    private final int candidate;
    private final int voter;
    private final boolean granted;

    public VoteReply(long term, int candidate, int voter, boolean granted) {
        this.term = term; this.candidate = candidate;
        this.voter = voter; this.granted = granted;
    }
    public long term() { return term; }
    public int candidate() { return candidate; }
    public int voter() { return voter; }
    public boolean granted() { return granted; }

    public String encode() {
        return "VOTO;" + term + ";" + candidate + ";" + voter + ";" + granted;
    }

    public static VoteReply decode(String text) {
        String[] f = text.trim().split(";", -1);
        if (f.length != 5 || !"VOTO".equals(f[0])) {
            throw new IllegalArgumentException("VOTO mal formado");
        }
        return new VoteReply(Long.parseLong(f[1]), Integer.parseInt(f[2]),
                Integer.parseInt(f[3]), Boolean.parseBoolean(f[4]));
    }
}
