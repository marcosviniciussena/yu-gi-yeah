// ServidorComRedis.java
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;
import com.google.gson.Gson;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.params.SetParams;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

/**
 * Servidor com Redis para coordenação:
 * - claim atômico de cartas raras (SET NX PX)
 * - salvar mão do jogador em Redis (hash: player:hand)
 * - fila de duelos (lista Redis: queue:duels)
 * - pub/sub para eventos de cartas entregues (channel cards:events)
 *
 * Ajuste HOST/PORT e Redis connection conforme necessário.
 */
public class ServidorComRedis {
    // CONFIG
    static final String HOST = "0.0.0.0";
    static final int TCP_PORT = 5000;
    static final int UDP_PORT = 6000;
    static final double RARE_PROBABILITY = 0.15;

    // Redis
    static final String REDIS_HOST = "127.0.0.1";
    static final int REDIS_PORT = 6379;
    static JedisPooled jedisPool;
    static Gson gson = new Gson();

    // cartas locais
    static final List<Card> cartasRaras = Collections.synchronizedList(new ArrayList<>());
    static final List<Card> cartasComuns = Collections.synchronizedList(new ArrayList<>());
    static final ReentrantLock cardsLock = new ReentrantLock();

    // players locais
    static final ConcurrentMap<String, Player> localPlayers = new ConcurrentHashMap<>();

    // executor para resolver partidas localmente se necessário
    static final ExecutorService duelExecutor = Executors.newCachedThreadPool();

    public static void main(String[] args) throws Exception {
        // inicia Redis
        jedisPool = new JedisPooled(REDIS_HOST, REDIS_PORT);

        // inicializa cartas
        initCards();

        // start subscriber para eventos
        Thread subThread = new Thread(() -> runSubscriber());
        subThread.setDaemon(true);
        subThread.start();

        // start worker que consome fila de duelos (poderá rodar em todos os servidores)
        Thread worker = new Thread(() -> runDuelWorker());
        worker.setDaemon(true);
        worker.start();

        // UDP ping responder
        Thread udpThread = new Thread(() -> runUdpServer(UDP_PORT));
        udpThread.setDaemon(true);
        udpThread.start();

        // TCP accept loop
        try (ServerSocket serverSocket = new ServerSocket(TCP_PORT)) {
            System.out.println("[SERVIDOR] TCP ouvindo em " + TCP_PORT + " | UDP: " + UDP_PORT + " | Redis: " + REDIS_HOST + ":" + REDIS_PORT);
            while (true) {
                Socket clientSocket = serverSocket.accept();
                String playerId = clientSocket.getRemoteSocketAddress().toString() + "-" + System.nanoTime();
                Player p = new Player(playerId, clientSocket);
                localPlayers.put(playerId, p);
                new Thread(new ClientHandler(p)).start();
            }
        } finally {
            jedisPool.close();
            duelExecutor.shutdown();
        }
    }

    // ---------- Redis subscriber: escuta cards:events e atualiza cache local ----------
    static void runSubscriber() {
        try (Jedis jedis = new Jedis(REDIS_HOST, REDIS_PORT)) {
            jedis.subscribe(new JedisPubSub() {
                @Override
                public void onMessage(String channel, String message) {
                    if (channel.equals("cards:events")) {
                        // mensagem: DELIVERED <id>
                        if (message.startsWith("DELIVERED ")) {
                            String[] parts = message.split(" ");
                            int cardId = Integer.parseInt(parts[1]);
                            // remove rara localmente se presente
                            cardsLock.lock();
                            try {
                                cartasRaras.removeIf(c -> c.id == cardId);
                            } finally {
                                cardsLock.unlock();
                            }
                            System.out.println("[PUBSUB] carta rara " + cardId + " entregue - removida do cache local");
                        }
                    }
                }
            }, "cards:events");
        } catch (Exception e) {
            System.err.println("[SUBSCRIBER] erro: " + e.getMessage());
        }
    }

    // ---------- worker que consome fila de duelos e resolve ----------
    static void runDuelWorker() {
        try (Jedis jedis = new Jedis(REDIS_HOST, REDIS_PORT)) {
            System.out.println("[WORKER] Duel worker ativo (consome queue:duels)");
            while (true) {
                // BLPOP espera por um item (timeout 0 = bloqueante)
                List<String> item1 = jedis.blpop(0, "queue:duels");
                String p1Json = item1.get(1);
                List<String> item2 = jedis.blpop(0, "queue:duels");
                String p2Json = item2.get(1);

                DuelEntry e1 = gson.fromJson(p1Json, DuelEntry.class);
                DuelEntry e2 = gson.fromJson(p2Json, DuelEntry.class);

                duelExecutor.submit(() -> {
                    try {
                        // obtém mãos dos jogadores do Redis
                        List<Card> mao1 = getHandFromRedis(e1.playerId);
                        List<Card> mao2 = getHandFromRedis(e2.playerId);

                        if (mao1.size() < 2 || mao2.size() < 2) {
                            // responde aos servidores donos
                            publishResultToOwner(e1, e2, "Um dos jogadores não tem cartas suficientes (mínimo 2).");
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

                        publishResultToOwner(e1, e2, "Resultado do duelo: " + resultado);
                    } catch (Exception ex) {
                        System.err.println("[WORKER] erro ao resolver duelo: " + ex.getMessage());
                    }
                });
            }
        } catch (Exception e) {
            System.err.println("[WORKER] erro de conexão Redis: " + e.getMessage());
        }
    }

    // envia resultado de volta aos servidores "donos" via chave Redis pub/sub específica ou hash
    // aqui simplificamos publicando no channel "duel:result" com payload JSON contendo owner (serverId) e playerId
    static void publishResultToOwner(DuelEntry e1, DuelEntry e2, String resultado) {
        // payloads simples
        Map<String, String> r1 = new HashMap<>();
        r1.put("playerId", e1.playerId);
        r1.put("owner", e1.serverId);
        r1.put("result", resultado);

        Map<String, String> r2 = new HashMap<>();
        r2.put("playerId", e2.playerId);
        r2.put("owner", e2.serverId);
        r2.put("result", resultado);

        jedisPool.publish("duel:results", gson.toJson(r1));
        jedisPool.publish("duel:results", gson.toJson(r2));
    }

    // ---------- UDP ping echo ----------
    static void runUdpServer(int port) {
        try (DatagramSocket ds = new DatagramSocket(port)) {
            System.out.println("[UDP] pronto em " + port);
            byte[] buf = new byte[1024];
            while (true) {
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                ds.receive(pkt);
                DatagramPacket resp = new DatagramPacket(pkt.getData(), pkt.getLength(), pkt.getAddress(), pkt.getPort());
                ds.send(resp);
            }
        } catch (IOException e) {
            System.err.println("[UDP] erro: " + e.getMessage());
        }
    }

    // ---------- init cards ----------
    static void initCards() {
        cartasRaras.clear();
        cartasComuns.clear();
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
        Random rnd = new Random();
        for (int i = 1; i <= 30; i++) {
            cartasComuns.add(new Card(i, "Comum " + i, 3 + rnd.nextInt(7), 2 + rnd.nextInt(7)));
        }
        System.out.println("[CARDS] raras=" + cartasRaras.size() + " comuns=" + cartasComuns.size());
    }

    // ---------- sortear pacote agora tenta claim via Redis ----------
    static List<Card> sortearPacote(String playerId) {
        List<Card> pacote = new ArrayList<>();
        Random rnd = new Random();

        for (int i = 0; i < 3; i++) {
            boolean tryRare = rnd.nextDouble() < RARE_PROBABILITY;
            if (tryRare) {
                // tentativa de claim de alguma rara (varremos localmente por simplicidade)
                Card claimed = null;
                cardsLock.lock();
                try {
                    Iterator<Card> it = cartasRaras.iterator();
                    while (it.hasNext()) {
                        Card c = it.next();
                        boolean ok = tryClaimRareInRedis(c.id, playerId);
                        if (ok) {
                            // remove localmente (já claimado)
                            it.remove();
                            claimed = c;
                            break;
                        }
                    }
                } finally {
                    cardsLock.unlock();
                }
                if (claimed != null) {
                    pacote.add(claimed.clone());
                    continue;
                }
            }
            // se não conseguiu rare -> comum aleatória (repete)
            Card base = cartasComuns.get(rnd.nextInt(cartasComuns.size()));
            pacote.add(base.clone());
        }
        return pacote;
    }

    // ---------- claim atômico no Redis ----------
    static boolean tryClaimRareInRedis(int cardId, String playerId) {
        String key = "card:rare:" + cardId;
        String value = playerId + ":" + System.currentTimeMillis();
        SetParams params = SetParams.setParams().nx().px(60000); // 60s TTL
        String res = jedisPool.set(key, value, params);
        if ("OK".equalsIgnoreCase(res)) {
            // marca entregue de forma persistente
            jedisPool.hset("cards:delivered", String.valueOf(cardId), value);
            // publica evento para outros servidores atualizarem cache
            jedisPool.publish("cards:events", "DELIVERED " + cardId);
            System.out.println("[REDIS] claimed rare " + cardId + " for " + playerId);
            return true;
        }
        return false;
    }

    // ---------- salvar mão do jogador em Redis (JSON) ----------
    static void saveHandToRedis(String playerId, List<Card> hand) {
        String json = gson.toJson(hand);
        jedisPool.hset("player:hand", playerId, json);
    }

    static List<Card> getHandFromRedis(String playerId) {
        String json = jedisPool.hget("player:hand", playerId);
        if (json == null) return new ArrayList<>();
        Card[] arr = gson.fromJson(json, Card[].class);
        return new ArrayList<>(Arrays.asList(arr));
    }

    // ---------- DTOs ----------
    static class DuelEntry {
        String serverId;
        String playerId;
    }

    static class Player {
        final String playerId;
        final Socket socket;
        final BufferedReader in;
        final BufferedWriter out;
        volatile boolean connected = true;

        Player(String playerId, Socket socket) throws IOException {
            this.playerId = playerId;
            this.socket = socket;
            this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            this.out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"));
        }

        void send(String msg) {
            try {
                synchronized (out) {
                    out.write(msg + "\n");
                    out.write("END\n");
                    out.flush();
                }
            } catch (IOException e) {
                System.err.println("[SEND] erro: " + e.getMessage());
                close();
            }
        }
        String readLine() throws IOException { return in.readLine(); }
        void close() {
            connected = false;
            try { socket.close(); } catch (IOException ignored) {}
            localPlayers.remove(playerId);
        }
    }

    static class Card {
        final int id;
        final String nome;
        final int ataque;
        final int defesa;
        Card(int id, String nome, int ataque, int defesa) {
            this.id = id; this.nome = nome; this.ataque = ataque; this.defesa = defesa;
        }
        @Override public Card clone() { return new Card(id, nome, ataque, defesa); }
        @Override public String toString() { return String.format("[%d] %s (ATK %d / DEF %d)", id, nome, ataque, defesa); }
    }

    // ---------- handler por cliente ----------
    static class ClientHandler implements Runnable {
        final Player player;
        ClientHandler(Player p) { this.player = p; }

        @Override
        public void run() {
            System.out.println("[CONEXÃO] " + player.playerId);
            player.send("Bem-vindo (id=" + player.playerId + ")");

            try {
                String line;
                while ((line = player.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    System.out.println("[" + player.playerId + "] " + line);
                    String lc = line.toLowerCase();

                    if (lc.equals("listar")) {
                        StringBuilder sb = new StringBuilder();
                        sb.append("Cartas raras disponíveis:\n");
                        synchronized (cartasRaras) { for (Card c : cartasRaras) sb.append(c.toString()).append("\n"); }
                        sb.append("\nCartas comuns (exemplo):\n");
                        synchronized (cartasComuns) { for (int i = 0; i < Math.min(10, cartasComuns.size()); i++) sb.append(cartasComuns.get(i).toString()).append("\n"); }
                        player.send(sb.toString());

                    } else if (lc.equals("pegar")) {
                        List<Card> pacote = sortearPacote(player.playerId);
                        // salva mão no redis (concorrente)
                        // pegamos a mão atual do redis e mesclamos com pacote (ou sobrescrever conforme sua regra)
                        List<Card> mao = getHandFromRedis(player.playerId);
                        mao.addAll(pacote);
                        saveHandToRedis(player.playerId, mao);
                        // também mantemos localmente (útil se você quiser enviar direto)
                        player.send("CARTAS_RECEBIDAS:");
                        for (Card c : pacote) player.send(c.toString());
                    } else if (lc.equals("mao")) {
                        List<Card> mao = getHandFromRedis(player.playerId);
                        if (mao.isEmpty()) player.send("Sua mão está vazia.");
                        else {
                            player.send("Sua mão:");
                            for (int i = 0; i < mao.size(); i++) player.send((i+1) + ". " + mao.get(i).toString());
                        }
                    } else if (lc.equals("duelo")) {
                        // publica na fila distribuída
                        DuelEntry e = new DuelEntry();
                        e.serverId = InetAddress.getLocalHost().getHostName(); // ou id do servidor
                        e.playerId = player.playerId;
                        String json = gson.toJson(e);
                        jedisPool.rpush("queue:duels", json);
                        player.send("Você entrou na fila de duelo (distributed).");
                    } else if (lc.equals("sair")) {
                        player.send("Até mais.");
                        break;
                    } else {
                        player.send("Comandos: listar, pegar, mao, duelo, sair");
                    }
                }
            } catch (IOException ex) {
                System.err.println("[HANDLER] erro: " + ex.getMessage());
            } finally {
                player.close();
                System.out.println("[DESCONECTADO] " + player.playerId);
            }
        }
    }
}
