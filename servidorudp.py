import asyncio
import json

# ==== CONFIG ====
HOST = "127.0.0.1"
PORT_TCP = 5000
PORT_UDP = 6000

# ==== ESTADO DO JOGO ====
BARALHO = [
    {"id": 1, "nome": "Dragão", "ataque": 8, "defesa": 5},
    {"id": 2, "nome": "Mago", "ataque": 6, "defesa": 4},
    {"id": 3, "nome": "Guerreiro", "ataque": 7, "defesa": 6},
    {"id": 4, "nome": "Elfo", "ataque": 5, "defesa": 7},
]

maos = {}
fila_duelo = []


# ==== HANDLER TCP (JOGO) ====
async def handle_client(reader, writer):
    addr = writer.get_extra_info("peername")
    print(f"Cliente conectado: {addr}")
    maos[writer] = []

    while True:
        data = await reader.readline()
        if not data:
            break

        msg = data.decode().strip()
        print(f"[{addr}] {msg}")

        if msg == "listar":
            resposta = "Cartas disponíveis:\n"
            for c in BARALHO:
                resposta += f"{c['id']}: {c['nome']} (ATK {c['ataque']} / DEF {c['defesa']})\n"
            resposta += "END\n"
            writer.write(resposta.encode())

        elif msg.startswith("pegar "):
            try:
                cid = int(msg.split()[1])
                carta = next((c for c in BARALHO if c["id"] == cid), None)
                if carta:
                    BARALHO.remove(carta)
                    maos[writer].append(carta)
                    writer.write((f"CARTA {json.dumps(carta)}\n").encode())
                else:
                    writer.write("Carta não disponível\n".encode())
            except:
                writer.write("Comando inválido\n".encode())

        elif msg == "mao":
            mao = maos.get(writer, [])
            if mao:
                lista = "\n".join(
                    f"{i+1}. {c['nome']} (ATK {c['ataque']} / DEF {c['defesa']})"
                    for i, c in enumerate(mao)
                )
                writer.write(f"Sua mão:\n{lista}\nEND\n".encode())
            else:
                writer.write("Você não tem cartas.\nEND\n".encode())

        elif msg == "duelo":
            if writer not in fila_duelo:
                fila_duelo.append(writer)
                writer.write("Você entrou na fila de duelo...\n".encode())

            if len(fila_duelo) >= 2:
                p1 = fila_duelo.pop(0)
                p2 = fila_duelo.pop(0)
                await resolver_partida(p1, p2)

        else:
            writer.write("Comandos: listar, pegar <id>, mao, duelo, sair\n".encode())

        await writer.drain()

    print(f"Cliente desconectado: {addr}")
    maos.pop(writer, None)
    writer.close()
    await writer.wait_closed()


async def resolver_partida(p1, p2):
    mao1 = maos.get(p1, [])
    mao2 = maos.get(p2, [])

    if len(mao1) < 2 or len(mao2) < 2:
        msg = "Um dos jogadores não tem cartas suficientes (mínimo 2).\n"
        p1.write(msg.encode())
        p2.write(msg.encode())
        await p1.drain()
        await p2.drain()
        return

    atk1 = sum(c["ataque"] for c in mao1)
    def1 = sum(c["defesa"] for c in mao1)
    atk2 = sum(c["ataque"] for c in mao2)
    def2 = sum(c["defesa"] for c in mao2)

    dano1 = atk1 - def2
    dano2 = atk2 - def1

    if dano1 > dano2:
        resultado = "Jogador 1 venceu!"
    elif dano2 > dano1:
        resultado = "Jogador 2 venceu!"
    else:
        resultado = "Empate!"

    p1.write(f"Resultado do duelo: {resultado}\n".encode())
    p2.write(f"Resultado do duelo: {resultado}\n".encode())
    await p1.drain()
    await p2.drain()


# ==== HANDLER UDP (PING) ====
class PingServerProtocol:
    def connection_made(self, transport):
        self.transport = transport
        print(f"Servidor UDP pronto em {HOST}:{PORT_UDP}")

    def datagram_received(self, data, addr):
        mensagem = data.decode()
        print(f"Ping recebido: {mensagem!r} de {addr}")
        self.transport.sendto(data, addr)  # eco


# ==== MAIN ====
async def main():
    # TCP (jogo)
    tcp_server = await asyncio.start_server(handle_client, HOST, PORT_TCP)
    print(f"Servidor TCP rodando em {HOST}:{PORT_TCP}")

    # UDP (ping)
    loop = asyncio.get_running_loop()
    transport, protocol = await loop.create_datagram_endpoint(
        lambda: PingServerProtocol(),
        local_addr=(HOST, PORT_UDP),
    )

    async with tcp_server:
        await tcp_server.serve_forever()


if __name__ == "__main__":
    asyncio.run(main())
