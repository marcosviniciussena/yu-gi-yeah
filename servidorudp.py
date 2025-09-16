import asyncio
import json
import random

# ==== CONFIG ====
HOST = "127.0.0.1"
PORT_TCP = 5000
PORT_UDP = 6000

# ==== ESTADO DO JOGO ====
cartas_raras = [
    {"id": 101, "nome": "Dragão Lendário", "ataque": 14, "defesa": 12},
    {"id": 102, "nome": "Fênix de Fogo", "ataque": 13, "defesa": 9},
    {"id": 103, "nome": "Mago Supremo", "ataque": 12, "defesa": 11},
    {"id": 104, "nome": "Titã de Pedra", "ataque": 10, "defesa": 15},
    {"id": 105, "nome": "Serpente Marinha", "ataque": 11, "defesa": 10},
    {"id": 106, "nome": "Cavaleiro Negro", "ataque": 13, "defesa": 11},
    {"id": 107, "nome": "Anjo da Guarda", "ataque": 9, "defesa": 14},
    {"id": 108, "nome": "Demônio Ancestral", "ataque": 15, "defesa": 9},
    {"id": 109, "nome": "Dragão de Gelo", "ataque": 12, "defesa": 13},
    {"id": 110, "nome": "Fada Suprema", "ataque": 10, "defesa": 12},
    {"id": 111, "nome": "Besta Colossal", "ataque": 14, "defesa": 10},
    {"id": 112, "nome": "Samurai Fantasma", "ataque": 13, "defesa": 10},
    {"id": 113, "nome": "Guardião Celestial", "ataque": 11, "defesa": 14},
    {"id": 114, "nome": "Minotauro Real", "ataque": 12, "defesa": 12},
    {"id": 115, "nome": "Fera Mística", "ataque": 13, "defesa": 13},
]

cartas_comuns = [
    {"id": 1, "nome": "Guerreiro", "ataque": 7, "defesa": 6},
    {"id": 2, "nome": "Arqueiro", "ataque": 5, "defesa": 4},
    {"id": 3, "nome": "Orc", "ataque": 6, "defesa": 5},
    {"id": 4, "nome": "Elfo", "ataque": 5, "defesa": 7},
    {"id": 5, "nome": "Goblin", "ataque": 4, "defesa": 3},
    {"id": 6, "nome": "Ladrão", "ataque": 5, "defesa": 4},
    {"id": 7, "nome": "Bárbaro", "ataque": 8, "defesa": 5},
    {"id": 8, "nome": "Camponês", "ataque": 3, "defesa": 3},
    {"id": 9, "nome": "Troll", "ataque": 6, "defesa": 7},
    {"id": 10, "nome": "Lobo", "ataque": 4, "defesa": 4},
    {"id": 11, "nome": "Esqueleto", "ataque": 5, "defesa": 3},
    {"id": 12, "nome": "Bandido", "ataque": 6, "defesa": 4},
    {"id": 13, "nome": "Xamã", "ataque": 7, "defesa": 5},
    {"id": 14, "nome": "Gnomo", "ataque": 4, "defesa": 5},
    {"id": 15, "nome": "Sátiro", "ataque": 5, "defesa": 6},
    {"id": 16, "nome": "Caçador", "ataque": 6, "defesa": 5},
    {"id": 17, "nome": "Sentinela", "ataque": 4, "defesa": 6},
    {"id": 18, "nome": "Espadachim", "ataque": 7, "defesa": 5},
    {"id": 19, "nome": "Zumbi", "ataque": 5, "defesa": 4},
    {"id": 20, "nome": "Kobold", "ataque": 4, "defesa": 4},
    {"id": 21, "nome": "Campeão", "ataque": 8, "defesa": 6},
    {"id": 22, "nome": "Clérigo", "ataque": 5, "defesa": 7},
    {"id": 23, "nome": "Lanceiro", "ataque": 6, "defesa": 6},
    {"id": 24, "nome": "Escudeiro", "ataque": 4, "defesa": 7},
    {"id": 25, "nome": "Gigante", "ataque": 9, "defesa": 5},
    {"id": 26, "nome": "Mercenário", "ataque": 6, "defesa": 5},
    {"id": 27, "nome": "Domador de Feras", "ataque": 5, "defesa": 6},
    {"id": 28, "nome": "Espião", "ataque": 4, "defesa": 5},
    {"id": 29, "nome": "Bruxo", "ataque": 7, "defesa": 4},
    {"id": 30, "nome": "Mímico", "ataque": 6, "defesa": 6},
]


maos = {}
fila_duelo = []


# ==== HANDLER TCP (JOGO) ====
async def handle_client(reader, writer):
    addr = writer.get_extra_info("peername")
    print(f"✅ Cliente conectado: {addr}")
    maos[writer] = []

    try:
        while True:
            try:
                data = await reader.readline()
            except (ConnectionResetError, OSError):
                print(f"⚠ Conexão perdida com {addr}")
                break

            if not data:
                break

            msg = data.decode(errors="ignore").strip()
            print(f"[{addr}] {msg}")

            if msg == "listar":
                resposta = "Cartas disponíveis:\n"
                resposta += "\n".join([f"- {c['nome']} (RARA)" for c in cartas_raras])
                resposta += "\n" + "\n".join([f"- {c['nome']}" for c in cartas_comuns])
                resposta += "\nEND\n"
                writer.write(resposta.encode())
                await writer.drain()

            elif msg.startswith("pegar "):
                try:
                    pacote = sortear_pacote()
                    maos[writer].extend(pacote)
                    resposta = f"CARTAS {json.dumps(pacote, ensure_ascii=False)}\nEND\n"
                    writer.write(resposta.encode())
                    await writer.drain()
                except Exception:
                    writer.write("Comando inválido\nEND\n".encode())

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
                    writer.write("Você entrou na fila de duelo...\nEND\n".encode())

                if len(fila_duelo) >= 2:
                    p1 = fila_duelo.pop(0)
                    p2 = fila_duelo.pop(0)
                    await resolver_partida(p1, p2)

            else:
                writer.write("Comandos: listar, pegar <id>, mao, duelo, sair\nEND\n".encode())

            try:
                await writer.drain()
            except (ConnectionResetError, BrokenPipeError, OSError):
                print(f"⚠ Erro ao enviar dados para {addr}")
                break

    finally:
        # limpeza
        maos.pop(writer, None)
        writer.close()
        try:
            await writer.wait_closed()
        except (ConnectionResetError, OSError):
            pass
        print(f"❌ Cliente desconectado: {addr}")

def sortear_pacote():
    """Sorteia um pacote de 3 cartas, priorizando comuns"""
    pacote = []
    global cartas_raras

    for _ in range(3):
        if cartas_raras and random.random() < 0.2:  # 20% chance de rara
            carta = cartas_raras.pop(0)  # pega a primeira rara disponível
        else:
            carta = random.choice(cartas_comuns)
        pacote.append(carta)

    return pacote


async def resolver_partida(p1, p2):
    mao1 = maos.get(p1, [])
    mao2 = maos.get(p2, [])

    if len(mao1) < 2 or len(mao2) < 2:
        msg = "Um dos jogadores não tem cartas suficientes (mínimo 2).\nEND\n"
        for p in (p1, p2):
            try:
                p.write(msg.encode())
                await p.drain()
            except Exception:
                pass
        return

    atk1, def1 = sum(c["ataque"] for c in mao1), sum(c["defesa"] for c in mao1)
    atk2, def2 = sum(c["ataque"] for c in mao2), sum(c["defesa"] for c in mao2)

    dano1, dano2 = atk1 - def2, atk2 - def1

    if dano1 > dano2:
        resultado = "Jogador 1 venceu!"
    elif dano2 > dano1:
        resultado = "Jogador 2 venceu!"
    else:
        resultado = "Empate!"

    for p in (p1, p2):
        try:
            p.write(f"Resultado do duelo: {resultado}\nEND\n".encode())
            await p.drain()
        except Exception:
            pass


# ==== HANDLER UDP (PING) ====
class PingServerProtocol:
    def connection_made(self, transport):
        self.transport = transport
        print(f"📡 Servidor UDP pronto em {HOST}:{PORT_UDP}")

    def datagram_received(self, data, addr):
        mensagem = data.decode(errors="ignore")
        print(f"Ping recebido: {mensagem!r} de {addr}")
        try:
            self.transport.sendto(data, addr)
        except Exception as e:
            print(f"⚠ Erro UDP: {e}")


# ==== MAIN ====
async def main():
    tcp_server = await asyncio.start_server(handle_client, HOST, PORT_TCP)
    print(f"Servidor TCP rodando em {HOST}:{PORT_TCP}")

    loop = asyncio.get_running_loop()
    await loop.create_datagram_endpoint(
        lambda: PingServerProtocol(),
        local_addr=(HOST, PORT_UDP),
    )

    async with tcp_server:
        await tcp_server.serve_forever()


if __name__ == "__main__":
    asyncio.run(main())
