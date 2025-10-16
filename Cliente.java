import java.io.*;
import java.net.*;
import java.util.*;

public class Cliente {
    private static final String HOST = "127.0.0.1";
    private static final int PORT_TCP = 5000;
    private static final int PORT_UDP = 6000;

    private Socket socket;
    private BufferedReader in;
    private BufferedWriter out;
    private List<Map<String, Object>> minhasCartas = new ArrayList<>();

    public static void main(String[] args) {
        new Cliente().iniciar();
    }

    public void iniciar() {
        try {
            socket = new Socket(HOST, PORT_TCP);
            System.out.println("✅ Conectado ao servidor TCP.");
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));

            // Thread que escuta mensagens do servidor
            new Thread(new ThreadLeitura(in)).start();

            Scanner scanner = new Scanner(System.in);
            System.out.println(
                "Comandos disponíveis:\n" +
                " - listar -> ver cartas disponíveis\n" +
                " - pegar -> receber pacote com 3 cartas\n" +
                " - mao -> ver suas cartas\n" +
                " - duelo -> entrar em uma partida\n" +
                " - ping -> medir latência UDP\n" +
                " - sair -> encerrar\n"
            );

            while (true) {
                System.out.print("> ");
                String msg = scanner.nextLine().trim();

                if (msg.equalsIgnoreCase("sair")) {
                    break;
                }

                if (msg.equalsIgnoreCase("ping")) {
                    medirPing();
                    continue;
                }

                enviarComando(msg);
            }

            fecharConexao();

        } catch (IOException e) {
            System.err.println("❌ Erro de conexão: " + e.getMessage());
        }
    }

    private void enviarComando(String comando) {
        try {
            out.write(comando + "\n");
            out.flush();
        } catch (IOException e) {
            System.err.println("⚠ Erro ao enviar comando: " + e.getMessage());
        }
    }

    private void fecharConexao() {
        try {
            if (socket != null) socket.close();
            System.out.println("❌ Conexão encerrada.");
        } catch (IOException e) {
            System.err.println("Erro ao fechar conexão: " + e.getMessage());
        }
    }

    // ---- Thread que lê as mensagens do servidor ----
    static class ThreadLeitura implements Runnable {
        private BufferedReader in;

        public ThreadLeitura(BufferedReader in) {
            this.in = in;
        }

        @Override
        public void run() {
            try {
                String linha;
                while ((linha = in.readLine()) != null) {
                    System.out.println("\n📩 Servidor: " + linha);
                }
            } catch (IOException e) {
                System.err.println("⚠ Conexão encerrada pelo servidor.");
            }
        }
    }

    // ---- Medição de ping UDP ----
    private void medirPing() {
        try (DatagramSocket udpSocket = new DatagramSocket()) {
            udpSocket.setSoTimeout(2000); // 2 segundos

            byte[] buffer = "ping".getBytes();
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, InetAddress.getByName(HOST), PORT_UDP);

            long inicio = System.nanoTime();
            udpSocket.send(packet);

            byte[] recvBuf = new byte[1024];
            DatagramPacket resposta = new DatagramPacket(recvBuf, recvBuf.length);

            udpSocket.receive(resposta);
            long fim = System.nanoTime();

            String respostaStr = new String(resposta.getData(), 0, resposta.getLength());
            double pingMs = (fim - inicio) / 1_000_000.0;

            System.out.printf("🏓 Resposta UDP: %s | Ping: %.2f ms\n", respostaStr, pingMs);

        } catch (SocketTimeoutException e) {
            System.out.println("⚠ Ping falhou (timeout).");
        } catch (IOException e) {
            System.err.println("Erro UDP: " + e.getMessage());
        }
    }
}
