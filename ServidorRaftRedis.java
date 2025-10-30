// ServidorRaftRedis.java
// Imports
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import com.google.gson.Gson;
import redis.clients.jedis.JedisPooled;
import io.etcd.jetcd.*;
import io.etcd.jetcd.kv.*;
import io.etcd.jetcd.options.PutOption;
import io.etcd.jetcd.op.*;
import io.etcd.jetcd.kv.TxnResponse;
import io.etcd.jetcd.ByteSequence;

/**
 * ServidorRaftRedis
 *
 * - usa etcd (via jetcd) para claim atômico de cartas raras (consenso Raft)
 * - usa Redis (JedisPooled) para cache, filas e pub/sub
 *
 * Ajuste ETCD_ENDPOINTS e REDIS_HOST conforme sua infra.
 */
public class ServidorRaftRedis {
    // CONFIG
    static final String[] ETCD_ENDPOINTS = new String[] { "http://127.0.0.1:2379", "http://127.0.0.1:2381", "http://127.0.0.1:2383" };
    static final String REDIS_HOST = "127.0.0.1";
    static final int REDIS_PORT = 6379;

    static final String HOST = "0.0.0.0";
    static final int TCP_PORT = 5000;
    static final int UDP_PORT = 6000;

    // Probabilidade de tentar rara por slot do pacote
    static final double RARE_PROB = 0.18;

    // libs
    static Client etcdClient;
    static JedisPooled jedis;
    static Gson gson = new Gson();

    // cartas
    static final List<Card> cartasRaras = Collections.synchronizedList(new ArrayList<>());
    static final List<Card> cartasComuns = Collections.synchronizedList(new ArrayList<>());
    static final Random rnd = new Random();

    // executor
    static final ExecutorService clientPool = Executors.newCachedThreadPool();
    static final ExecutorService workerPool = Executors.newCachedThreadPool();

    // main
    public static void main(String[] args) throws Exception {
        // id do servidor (pode ser hostname/uuid)
        final String serverId = InetAddress.getLocalHost().getHostName() + "-" + UUID.randomUUID().toString().substring(0,6);

        // inicia etcd
        etcdClient = Client.builder().endpoints(ETCD_ENDPOINTS).build();

        // inicia redis
        jedis = new JedisPooled(REDIS_HOST, REDIS_PORT);

        // carrega cartas
        initCards();

        // start UDP responder (ping)
        Thread udp = new Thread(() -> runUdpResponder(UDP_PORT));
        udp.setDaemon(true);
        udp.start();

        // start duel worker (consume fila de duelos via redis)
        Thread duelWorker = new Thread(() -> runDuelWorker());
        duelWorker.setDaemon(true);
        duelWorker.start();

        // subscribe results/events: deliver results back to local players
        Thread sub = new Thread(() -> runSubscribers());
        sub.setDaemon(true);
        sub.start();

        System.out.println("[SERVIDOR] Servidor iniciado. TCP:" + TCP_PORT + " UDP:" + UDP_PORT + " serverId:" + serverId);

        // tcp accept
        try (ServerSocket srv = new ServerSocket(TCP_PORT)) {
            while (true) {
                Socket sock = srv.accept();
                clientPool.submit(new ClientHandler(sock, serverId));
            }
        } finally {
            clientPool.shutdown();
            workerPool.shutdown();
            try { etcdClient.close(); } catch (Exception ignored) {}
            jedis.close();
        }
    }

    // ---------- cartas ----------
    static void initCards() {
        cartasRaras.clear();
        cartasComuns.clear();
        // 15 raras (exemplo)
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
        // 30 comuns
        for (int i = 1; i <= 30; i++) {
            cartasComuns.add(new Card(i, "Comum " + i, 3 + rnd.nextInt(8), 2 + rnd.nextInt(6)));
        }
        System.out.println("[CARDS] raras=" + cartasRaras.size() + " comuns=" + cartasComuns.size());
    }

    // ---------- UDP ping responder ----------
    static void runUdpResponder(int port) {
        try (DatagramSocket ds = new DatagramSocket(port)) {
            byte[] buf = new byte[1024];
            System.out.println("[UDP] pronto em " + port);
            while (true) {
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                ds.receive(pkt);
                // eco
                DatagramPacket resp = new DatagramPacket(pkt.getData(), pkt.getLength(), pkt.getAddress(), pkt.getPort());
                ds.send(resp);
            }
        } catch (IOException e) {
            System.err.println("[UDP] erro: " + e.getMessage());
        }
    }

    // ---------- worker de duelos (consume queue:duels) ----------
    static void runDuelWorker() {
        System.out.println("[WORKER] duel worker iniciado (consume queue:duels)");
        while (true) {
            try {
                // BLPOP blocking (via JedisPooled uses blocking operation through underlying connection)
                // Usamos timeout 0 em BLPOP com JedisPooled não há método direto -> usar Jedis (try-with-resources)
                try (var j = new redis.clients.jedis.Jedis(REDIS_HOST, REDIS_PORT)) {
                    List<String> item1 = j.blpop(0, "queue:duels");
                    if (item1 == null || item1.size() < 2) continue;
                    String p1Json = item1.get(1);
                    List<String> item2 = j.blpop(0, "queue:duels");
                    if (item2 == null || item2.size() < 2) continue;
                    String p2Json = item2.get(1);

                    DuelEntry e1 = gson.fromJson(p1Json, DuelEntry.class);
                    DuelEntry e2 = gson.fromJson(p2Json, DuelEntry.class);

                    workerPool.submit(() -> processDuel(e1, e2));
                }
            } catch (Exception ex) {
                System.err.println("[WORKER] erro: " + ex.getMessage());
                try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            }
        }
    }

    static void processDuel(DuelEntry e1, DuelEntry e2) {
        try {
            // get hands from redis
            String j1 = jedis.hget("player:hand", e1.playerId);
            String j2 = jedis.hget("player:hand", e2.playerId);
            List<Card> mao1 = j1 == null ? new ArrayList<>() : Arrays.asList(gson.fromJson(j1, Card[].class));
            List<Card> mao2 = j2 == null ? new ArrayList<>() : Arrays.asList(gson.fromJson(j2, Card[].class));

            if (mao1.size() < 2 || mao2.size() < 2) {
                // publish message result back
                Map<String,String> r1 = Map.of("playerId", e1.playerId, "owner", e1.serverId, "result", "Um dos jogadores não tem cartas suficientes (min 2).");
                Map<String,String> r2 = Map.of("playerId", e2.playerId, "owner", e2.serverId, "result", "Um dos jogadores não tem cartas suficientes (min 2).");
                jedis.publish("duel:results", gson.toJson(r1));
                jedis.publish("duel:results", gson.toJson(r2));
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

            Map<String,String> r1 = Map.of("playerId", e1.playerId, "owner", e1.serverId, "result", resultado);
            Map<String,String> r2 = Map.of("playerId", e2.playerId, "owner", e2.serverId, "result", resultado);
            jedis.publish("duel:results", gson.toJson(r1));
            jedis.publish("duel:results", gson.toJson(r2));
        } catch (Exception ex) {
            System.err.println("[WORKER] processDuel erro: " + ex.getMessage());
        }
    }

    // ---------- subscribers (duel results and cards events) ----------
    static void runSubscribers() {
        try (var j = new redis.clients.jedis.Jedis(REDIS_HOST, REDIS_PORT)) {
            j.subscribe(new redis.clients.jedis.JedisPubSub() {
                @Override
                public void onMessage(String channel, String message) {
                    if ("cards:events".equals(channel)) {
                        // message: DELIVERED <cardId> <playerId> <serverId>
                        System.out.println("[PUBSUB cards:events] " + message);
                        // remove rare from local cache (if present)
                        try {
                            String[] parts = message.split(" ");
                            if (parts.length >= 2 && parts[0].equals("DELIVERED")) {
                                int cardId = Integer.parseInt(parts[1]);
                                cartasRaras.removeIf(c -> c.id == cardId);
                            }
                        } catch (Exception ignored) {}
                    } else if ("duel:results".equals(channel)) {
                        try {
                            Map res = gson.fromJson(message, Map.class);
                            String owner = (String) res.get("owner");
                            String playerId = (String) res.get("playerId");
                            String result = (String) res.get("result");
                            // if owner matches this host? We don't know owner mapping here; we send anyway into a redis hash for owner
                            // Better: publish and let each server check if player is local
                            // For simplicity we publish to a hash results:playerId -> result; local servers will pick it up if needed
                            jedis.hset("duel:result:" + playerId, "result", result);
                        } catch (Exception ex) {
                            System.err.println("[SUB duel:results] parse error: " + ex.getMessage());
                        }
                    }
                }
            }, "cards:events", "duel:results");
        } catch (Exception e) {
            System.err.println("[SUBSCRIBER] erro: " + e.getMessage());
        }
    }

    // ---------- try claim rare using etcd transaction ----------
    static boolean tryClaimRareWithEtcd(int cardId, String playerId, String serverId) {
        try {
            KV kv = etcdClient.getKVClient();
            String keyStr = "card:rare:" + cardId;
            ByteSequence key = ByteSequence.from(keyStr, StandardCharsets.UTF_8);
            String valueStr = serverId + ":" + playerId + ":" + System.currentTimeMillis();
            ByteSequence value = ByteSequence.from(valueStr, StandardCharsets.UTF_8);

            // Compare: create_revision(key) == 0 => key doesn't exist yet
            Cmp cmp = new Cmp(key, Cmp.Op.EQUAL, CmpTarget.version(0));
            Txn txn = kv.txn();
            CompletableFuture<TxnResponse> f = txn.If(cmp)
                    .Then(Op.put(key, value, PutOption.DEFAULT))
                    .commit();
            TxnResponse resp = f.get(3, TimeUnit.SECONDS);
            if (resp.isSucceeded()) {
                // mark delivered persistently in redis hash and publish event
                jedis.hset("cards:delivered", String.valueOf(cardId), valueStr);
                jedis.publish("cards:events", "DELIVERED " + cardId + " " + playerId + " " + serverId);
                System.out.println("[ETCD] claimed rare " + cardId + " for player " + playerId + " by " + serverId);
                return true;
            } else {
                return false;
            }
        } catch (Exception e) {
            System.err.println("[ETCD] claim error: " + e.getMessage());
            return false;
        }
    }

    // ---------- sortear pacote (3 cartas) ----------
    static List<Card> sortearPacote(String playerId, String serverId) {
        List<Card> pacote = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            boolean tryRare = rnd.nextDouble() < RARE_PROB;
            if (tryRare) {
                Card chosen = null;
                synchronized (cartasRaras) {
                    // iterate and try claim in etcd
                    for (Card c : new ArrayList<>(cartasRaras)) {
                        if (tryClaimRareWithEtcd(c.id, playerId, serverId)) {
                            chosen = c.clone();
                            cartasRaras.removeIf(x -> x.id == c.id);
                            break;
                        }
                    }
                }
                if (chosen != null) { pacote.add(chosen); continue; }
            }
            // fallback: random common (can repeat)
            Card base = cartasComuns.get(rnd.nextInt(cartasComuns.size()));
            pacote.add(base.clone());
        }
        return pacote;
    }

    // ---------- Client Handler ----------
    static class ClientHandler implements Runnable {
        private final Socket sock;
        private final String serverId;
        private BufferedReader in;
        private BufferedWriter out;
        private final String playerId;

        ClientHandler(Socket sock, String serverId) {
            this.sock = sock;
            this.serverId = serverId;
            this.playerId = sock.getRemoteSocketAddress().toString() + "-" + UUID.randomUUID().toString().substring(0,6);
            try {
                in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
                out = new BufferedWriter(new OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void run() {
            try {
                send("Bem-vindo! playerId=" + playerId);
                String line;
                while ((line = in.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    System.out.println("[" + playerId + "] " + line);
                    String lc = line.toLowerCase();
                    if (lc.equals("listar")) {
                        StringBuilder sb = new StringBuilder();
                        sb.append("Raras:\n");
                        synchronized (cartasRaras) { for (Card c : cartasRaras) sb.append(c.toString()).append("\n"); }
                        sb.append("Comuns (ex.):\n");
                        synchronized (cartasComuns) { for (int i = 0; i < Math.min(10, cartasComuns.size()); i++) sb.append(cartasComuns.get(i).toString()).append("\n"); }
                        send(sb.toString());
                    } else if (lc.equals("pegar")) {
                        List<Card> pacote = sortearPacote(playerId, serverId);
                        // merge with existing hand in redis
                        String existing = jedis.hget("player:hand", playerId);
                        List<Card> mao = existing == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(gson.fromJson(existing, Card[].class)));
                        mao.addAll(pacote);
                        jedis.hset("player:hand", playerId, gson.toJson(mao));
                        send("CARTAS_RECEBIDAS:");
                        for (Card c : pacote) send(c.toString());
                    } else if (lc.equals("mao")) {
                        String j = jedis.hget("player:hand", playerId);
                        if (j == null) send("Sua mão está vazia.");
                        else {
                            Card[] arr = gson.fromJson(j, Card[].class);
                            send("Sua mão:");
                            for (int i = 0; i < arr.length; i++) send((i+1) + ". " + arr[i].toString());
                        }
                    } else if (lc.equals("duelo")) {
                        // publish to queue
                        DuelEntry e = new DuelEntry(); e.serverId = serverId; e.playerId = playerId;
                        jedis.rpush("queue:duels", gson.toJson(e));
                        send("Você entrou na fila de duelo (distribuída).");
                    } else if (lc.equals("sair")) {
                        send("Até mais.");
                        break;
                    } else {
                        send("Comandos: listar, pegar, mao, duelo, sair");
                    }
                }
            } catch (IOException e) {
                System.err.println("[HANDLER] " + e.getMessage());
            } finally {
                try { sock.close(); } catch (IOException ignored) {}
                System.out.println("[DISCONNECT] " + playerId);
            }
        }

        void send(String msg) {
            try {
                synchronized (out) {
                    out.write(msg + "\n");
                    out.write("END\n");
                    out.flush();
                }
            } catch (IOException e) {
                System.err.println("[SEND] " + e.getMessage());
            }
        }
    }

    // ---------- Data types ----------
    static class Card {
        final int id; final String nome; final int ataque; final int defesa;
        Card(int id, String nome, int ataque, int defesa) { this.id=id; this.nome=nome; this.ataque=ataque; this.defesa=defesa; }
        @Override public Card clone() { return new Card(id, nome, ataque, defesa); }
        @Override public String toString() { return id + ": " + nome + " (ATK " + ataque + " / DEF " + defesa + ")"; }
    }

    static class DuelEntry {
        public String serverId;
        public String playerId;
    }
}
