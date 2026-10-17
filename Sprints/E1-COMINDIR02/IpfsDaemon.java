// IpfsDaemon.java â€” arranca o Kubo se a API nÃ£o estiver a responder mas requer a instalaÃ§Ã£o do Kubo
import java.net.URI;
import java.net.http.*;

public class IpfsDaemon {
    public static void main(String[] args) throws Exception {
        if (isUp()) {
            System.out.println("Kubo jÃ¡ estÃ¡ a correr.");
            return;
        }
        new ProcessBuilder("ipfs", "daemon").inheritIO().start();

        for (int i = 0; i < 30 && !isUp(); i++){
            Thread.sleep(1000);
        }
        System.out.println(isUp() ? "Kubo pronto." : "Kubo nÃ£o respondeu em 30 s.");
    }
    static boolean isUp() {
        try {
            HttpRequest r = HttpRequest.newBuilder(URI.create("http://127.0.0.1:5001/api/v0/id")).POST(HttpRequest.BodyPublishers.noBody()).build();
            return HttpClient.newHttpClient().send(r, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (Exception e) { return false; }
    }
}


