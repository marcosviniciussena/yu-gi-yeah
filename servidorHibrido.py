# servidor.py
import asyncio
import json

# Lista de cartas do "baralho"
BARALHO = [
    {"id": 1, "nome": "Dragão", "ataque": 8, "defesa": 5},
    {"id": 2, "nome": "Mago", "ataque": 6, "defesa": 4},
    {"id": 3, "nome": "Guerreiro", "ataque": 7, "defesa": 6},
    {"id": 4, "nome": "Elfo", "ataque": 5, "defesa": 7},
]

# Clientes conectados
clientes = {}

# Fila de jogadores esperando duelo
fila_duelo = []

# Cada jogador terá sua mão
maos = {}


async def handle_client(reader, writer):
    addr = writer.get_extra_info('peername')
    print(f"Cliente conectado: {addr}")
    clientes[addr] = writer
    maos[writer] = []  # inicia mão vazia para o jogador

    while True:
        try:
            data = await reader.readline()
            if not data:
                break

            msg = data.decode().strip()
            print(f"[{addr}] {msg}")

            if msg == "listar":
                if BARALHO:
                    resposta = "Cartas disponíveis:\n"
                    for c in BARALHO:
                        resposta += f"{c['id']}: {c['nome']} (ATK {c['ataque']} / DEF {c['defesa']})\n"
                else:
                    resposta = "Não há cartas disponíveis.\n"
                writer.write((resposta + "\n").encode())

            elif msg.startswith("pegar "):
                try:
                    cid = int(msg.split()[1])
                    carta = next((c for c in BARALHO if c["id"] == cid), None)
                    if carta:
                        BARALHO.remove(carta)
                        maos[writer].append(carta)
                        writer.write((f"CARTA {json.dumps(carta)}\n").encode())
                        print(f"[{addr}] pegou {carta['nome']}.")
                    else:
                        writer.write("Carta não disponível\n".encode())
                except ValueError:
                    writer.write("Comando inválido\n".encode())

            elif msg == "mao":
                mao = maos.get(writer, [])
                if mao:
                    lista = "\n".join(
                        f"{i+1}. {c['nome']} (ATK {c['ataque']} / DEF {c['defesa']})"
                        for i, c in enumerate(mao)
                    )
                    writer.write(f"Sua mão:\n{lista}\n".encode())
                else:
                    writer.write("Você não tem cartas.\n".encode())

            elif msg == "duelo":
                if writer not in fila_duelo:
                    fila_duelo.append(writer)
                    writer.write("Você entrou na fila de duelo...\n".encode())

                if len(fila_duelo) >= 2:
                    p1 = fila_duelo.pop(0)
                    p2 = fila_duelo.pop(0)
                    await resolver_partida(p1, p2)

            elif msg == "sair":
                writer.write("Saindo do jogo...\n".encode())
                break

            else:
                writer.write("Comandos: listar, pegar <id>, mao, duelo, sair\n".encode())

            await writer.drain()

        except ConnectionResetError:
            break

    print(f"Cliente desconectado: {addr}")
    clientes.pop(addr, None)
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
        p1.write("Você venceu o duelo!\n".encode())
        p2.write("Você perdeu o duelo!\n".encode())
    elif dano2 > dano1:
        p1.write("Você perdeu o duelo!\n".encode())
        p2.write("Você venceu o duelo!\n".encode())
    else:
        p1.write("O duelo terminou em empate!\n".encode())
        p2.write("O duelo terminou em empate!\n".encode())

    await p1.drain()
    await p2.drain()


async def main():
    server = await asyncio.start_server(handle_client, "127.0.0.1", 5000)
    addr = server.sockets[0].getsockname()
    print(f"Servidor rodando em {addr}")

    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    asyncio.run(main())
