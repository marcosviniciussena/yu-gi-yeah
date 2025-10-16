import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;

/**
 * Servidor de jogo (TCP + UDP) em Java usando threads.
 *
 * Funcionalidades:
 * - TCP para comandos do jogo: listar, pegar, mao, duelo, sair
 * - UDP para ping (eco)
 * - Pacote de 3 cartas no comando "pegar" (mais chance de comum que rara)
 * - Cartas raras são únicas (removidas globalmente)
 * - Duelo 1vs1: soma ataque/defesa das mãos (mínimo 2 cartas por jogador)
 *
 * Compilar:
 *   javac Servidor.java
 * Rodar:
 *   java Servidor
 *
 * Ajuste portas e probabilidades logo abaixo.
 */
public class Servidor {
    // ===== CONFIGURAÇÃO =====
    static final String HOST = "0.0.0.0";
    static final int TCP_PORT = 5000;
    static final int UDP_PORT = 6000;

    // Probabilidade de sortear uma carta rara (0.0 - 1.0)
    // Ex.: 0.15 = 15% de chance de rara, 85% comum
    static final double RARE_PROBABILITY = 0.15;

    // ===== ESTADO COMPARTILHADO =====
    // Listas de cartas. cartasRaras: únicas (removidas ao entregar).
    // cartasComuns: repetíveis.
    static final List<Card> cartasRaras = Collections.synchronizedList(new ArrayList<>());
    static final List<Card> cartasComuns = Collections.synchronizedList(new ArrayList<>());

    // Lock para operações complexas com cartas (ex.: sortear e remover rara)
    static final ReentrantLock cardsLock = new ReentrantLock();

    // Mapeamento de clientes ativos (para eventual uso/log)
    static final ConcurrentMap<Socket, Player> players = new ConcurrentHashMap<>();

    // Fila de duelo (thread-safe)
    static final BlockingQueue<Player> duelQueue = new LinkedBlockingQueue<>();

    // Executor para tarefas que resolvem partidas sem bloquear threads de IO
    static final ExecutorService duelExecutor = Executors.newCachedThreadPool();

    public static void main(String[] args) throws Exception {
        // Inicializa cartas (exemplo pronto)
        initCards();

        // Inicia thread UDP para ping/echo
        Thread udpThread = new Thread(() -> runUdpServer(UDP_PORT));
        udpThread.setDaemon(true);
        udpThread.start();

        // Inicia TCP server
        try (ServerSocket serverSocket = new ServerSocket(TCP_PORT)) {
            System.out.println("[SERVIDOR] TCP ouvindo em " + TCP_PORT + " | UDP: " + UDP_PORT);
            while (true) {
                Socket clientSocket = serverSocket.accept();
                clientSocket.setTcpNoDelay(true);
                Player p = new Player(clientSocket);
                players.put(clientSocket, p);
                Thread t = new Thread(new ClientHandler(p));
                t.start();
            }
        } finally {
            duelExecutor.shutdown();
        }
    }

    // ============================
    // UDP server (ping/echo)
    // ============================
    static void runUdpServer(int port) {
        try (DatagramSocket ds = new DatagramSocket(port)) {
            System.out.println("[UDP] Servidor UDP pronto em porta " + port);
            byte[] buf = new byte[1024];
            while (true) {
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                ds.receive(pkt);
                // Echo back (resposta imediata)
                DatagramPacket resp = new DatagramPacket(pkt.getData(), pkt.getLength(), pkt.getAddress(), pkt.getPort());
                ds.send(resp);
            }
        } catch (IOException e) {
            System.err.println("[UDP] Erro UDP: " + e.getMessage());
        }
    }

    // ============================
    // Inicialização de cartas (15 raras, 30 comuns conforme pedido)
    // ============================
    static void initCards() {
        cartasRaras.clear();
        cartasComuns.clear();

        // Cartas raras (IDs começando em 101)
        cartasRaras.add(new Card(101, "Dragão Lendário", 14, 12));
        cartasRaras.add(new Card(102, "Fênix de Fogo", 13, 9));
        cartasRaras.add(new Card(103, "Mago Supremo", 12, 11));
        cartasRaras.add(new Card(104, "Titã de Pedra", 10, 15));
        cartasRaras.add(new Card(105, "Serpente Marinha", 11, 10));
        cartasRaras.add(new Card(106, "Cavaleiro Negro", 13, 11));
        cartasRaras.add(new Card(107, "Anjo da Guarda", 9, 14));
        cartasRaras.add(new Card(108, "Demônio Ancestral", 15, 9));
        cartasRaras.add(new Card(109, "Dragão de Gelo", 12, 13));
        cartasRaras.add(new Card(110, "Fada Suprema", 10, 12));
        cartasRaras.add(new Card(111, "Besta Colossal", 14, 10));
        cartasRaras.add(new Card(112, "Samurai Fantasma", 13, 10));
        cartasRaras.add(new Card(113, "Guardião Celestial", 11, 14));
        cartasRaras.add(new Card(114, "Minotauro Real", 12, 12));
        cartasRaras.add(new Card(115, "Fera Mística", 13, 13));

        // Cartas comuns (IDs 1..30)
        Random rnd = new Random();
        for (int i = 1; i <= 30; i++) {
            // variação de poder
            int atk = 3 + rnd.nextInt(7);  // 3..9
            int def = 2 + rnd.nextInt(7);  // 2..8
            cartasComuns.add(new Card(i, "Comum " + i, atk, def));
        }

        System.out.println("[CARDS] Inicializadas cartas: raras=" + cartasRaras.size() + " comuns=" + cartasComuns.size());
    }

    // ============================
    // Sortear pacote de 3 cartas
    // ============================
    static List<Card> sortearPacote() {
        List<Card> pacote = new ArrayList<>(3);
        Random rnd = new Random();

        // Proteger operações que podem alterar cartasRaras
        cardsLock.lock();
        try {
            for (int i = 0; i < 3; i++) {
                boolean tryRare = !cartasRaras.isEmpty() && rnd.nextDouble() < RARE_PROBABILITY;
                if (tryRare) {
                    // pega e remove a primeira rara disponível (poderia ser aleatória)
                    Card c = cartasRaras.remove(0);
                    pacote.add(c.clone()); // copia antes de dar ao jogador
                } else {
                    // comum: permite repetição — devolve cópia
                    Card base = cartasComuns.get(rnd.nextInt(cartasComuns.size()));
                    pacote.add(base.clone());
                }
            }
        } finally {
            cardsLock.unlock();
        }
        return pacote;
    }

    // ============================
    // Resolver partida entre 2 jogadores
    // ============================
    static void resolverPartida(Player p1, Player p2) {
        // Executa numa thread do executor
        duelExecutor.submit(() -> {
            try {
                // Valida que ainda estão conectados
                if (!p1.isConnected() || !p2.isConnected()) {
                    if (p1.isConnected()) p1.send("Oponente desconectado.");
                    if (p2.isConnected()) p2.send("Oponente desconectado.");
                    return;
                }

                List<Card> mao1 = p1.getHand();
                List<Card> mao2 = p2.getHand();

                if (mao1.size() < 2 || mao2.size() < 2) {
                    String msg = "Um dos jogadores não tem cartas suficientes (mínimo 2).";
                    p1.send(msg);
                    p2.send(msg);
                    return;
                }

                int atk1 = mao1.stream().mapToInt(c -> c.ataque).sum();
                int def1 = mao1.stream().mapToInt(c -> c.defesa).sum();
                int atk2 = mao2.stream().mapToInt(c -> c.ataque).sum();
                int def2 = mao2.stream().mapToInt(c -> c.defesa).sum();

                int dano1 = atk1 - def2;
                int dano2 = atk2 - def1;

                String resultado;
                if (dano1 > dano2) resultado = "Jogador 1 venceu!";
                else if (dano2 > dano1) resultado = "Jogador 2 venceu!";
                else resultado = "Empate!";

                p1.send("Resultado do duelo: " + resultado);
                p2.send("Resultado do duelo: " + resultado);
            } catch (Exception e) {
                System.err.println("[DUEL] Erro ao resolver partida: " + e.getMessage());
            }
        });
    }

    // ============================
    // Player e Card classes
    // ============================
    static class Player {
        final Socket socket;
        final BufferedReader in;
        final BufferedWriter out;
        final List<Card> hand = Collections.synchronizedList(new ArrayList<>());
        volatile boolean connected = true;

        Player(Socket socket) throws IOException {
            this.socket = socket;
            this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            this.out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"));
        }

        void send(String msg) {
            try {
                synchronized (out) {
                    out.write(msg + "\n");
                    out.write("END\n"); // marcador de fim de resposta
                    out.flush();
                }
            } catch (IOException e) {
                System.err.println("[SEND] Erro enviando a " + getPeerName() + ": " + e.getMessage());
                close();
            }
        }

        String readLine() throws IOException {
            return in.readLine();
        }

        void close() {
            if (!connected) return;
            connected = false;
            try { socket.close(); } catch (IOException ignored) {}
            players.remove(socket);
            duelQueue.remove(this); // remove da fila se estiver esperando
        }

        boolean isConnected() { return connected && !socket.isClosed(); }

        List<Card> getHand() { return hand; }

        String getPeerName() {
            try {
                return socket.getRemoteSocketAddress().toString();
            } catch (Exception e) {
                return socket.toString();
            }
        }
    }

    static class Card {
        final int id;
        final String nome;
        final int ataque;
        final int defesa;

        Card(int id, String nome, int ataque, int defesa) {
            this.id = id;
            this.nome = nome;
            this.ataque = ataque;
            this.defesa = defesa;
        }

        @Override
        public Card clone() {
            return new Card(id, nome, ataque, defesa);
        }

        @Override
        public String toString() {
            return String.format("[%d] %s (ATK %d / DEF %d)", id, nome, ataque, defesa);
        }
    }

    // ============================
    // Handler por cliente (thread)
    // ============================
    static class ClientHandler implements Runnable {
        private final Player player;

        ClientHandler(Player p) {
            this.player = p;
        }

        @Override
        public void run() {
            Socket s = player.socket;
            System.out.println("[CONEXÃO] Novo cliente: " + player.getPeerName());
            player.send("Bem-vindo ao servidor de cartas!");

            try {
                String line;
                while ((line = player.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;

                    System.out.println("[" + player.getPeerName() + "] " + line);
                    String lower = line.toLowerCase();

                    if (lower.equals("listar")) {
                        // Lista raras e algumas comuns
                        StringBuilder sb = new StringBuilder();
                        sb.append("Cartas raras disponíveis:\n");
                        synchronized (cartasRaras) {
                            for (Card c : cartasRaras) sb.append(c.toString()).append("\n");
                        }
                        sb.append("\nCartas comuns (exemplo):\n");
                        synchronized (cartasComuns) {
                            for (int i = 0; i < Math.min(10, cartasComuns.size()); i++) {
                                sb.append(cartasComuns.get(i).toString()).append("\n");
                            }
                        }
                        player.send(sb.toString());
                    } else if (lower.equals("pegar")) {
                        // Sorteia pacote de 3 cartas
                        List<Card> pacote = sortearPacote();
                        // adiciona à mão do jogador
                        player.getHand().addAll(pacote);
                        // envia pacote em JSON-like (simples) ou linhas
                        StringBuilder sb = new StringBuilder();
                        sb.append("CARTAS_RECEBIDAS:\n");
                        for (Card c : pacote) sb.append(c.toString()).append("\n");
                        player.send(sb.toString());
                    } else if (lower.equals("mao")) {
                        List<Card> mao = player.getHand();
                        if (mao.isEmpty()) {
                            player.send("Sua mão está vazia.");
                        } else {
                            StringBuilder sb = new StringBuilder();
                            sb.append("Sua mão:\n");
                            synchronized (mao) {
                                for (int i = 0; i < mao.size(); i++) {
                                    sb.append((i+1) + ". " + mao.get(i).toString()).append("\n");
                                }
                            }
                            player.send(sb.toString());
                        }
                    } else if (lower.equals("duelo")) {
                        // Adiciona à fila; se houver par, resolve
                        if (!duelQueue.contains(player)) {
                            duelQueue.add(player);
                            player.send("Você entrou na fila de duelo. Aguardando adversário...");
                        } else {
                            player.send("Você já está na fila de duelo.");
                        }

                        if (duelQueue.size() >= 2) {
                            Player p1 = duelQueue.poll();
                            Player p2 = duelQueue.poll();
                            if (p1 != null && p2 != null) {
                                resolverPartida(p1, p2);
                            } else {
                                // volta para a fila os que existirem
                                if (p1 != null) duelQueue.offer(p1);
                                if (p2 != null) duelQueue.offer(p2);
                            }
                        }
                    } else if (lower.equals("sair")) {
                        player.send("Encerrando conexão. Até mais!");
                        break;
                    } else {
                        player.send("Comandos: listar, pegar, mao, duelo, sair");
                    }
                }
            } catch (IOException e) {
                System.err.println("[CLIENT HANDLER] Erro com " + player.getPeerName() + ": " + e.getMessage());
            } finally {
                System.out.println("[DESCONECTADO] " + player.getPeerName());
                player.close();
            }
        }
    }
}
