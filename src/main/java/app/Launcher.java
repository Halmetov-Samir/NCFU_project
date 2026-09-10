package app;

import java.util.Arrays;

/**
 * Единая точка входа. Запускает либо сервер, либо воркер.
 * Использование:
 *   java -jar app.jar server
 *   java -jar app.jar worker
 */
public class Launcher {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("Использование: java -jar app.jar [server|worker]");
            System.exit(1);
        }

        switch (args[0].toLowerCase()) {
            case "server" -> ServerApplication.main(new String[0]);
            case "worker" -> WorkerApplication.main(
                    Arrays.copyOfRange(args, 1, args.length));
            default -> {
                System.err.println("Неизвестный режим: " + args[0]);
                System.err.println("Доступно: server, worker");
                System.exit(1);
            }
        }
    }
}