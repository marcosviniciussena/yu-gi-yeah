import asyncio
import json

HOST = "127.0.0.1"
PORT = 5000

cartas_disponiveis = [
    {"nome": "Dragão Vermelho", "ataque": 3000, "defesa": 2500},
    {"nome": "Mago Negro", "ataque": 2500, "defesa": 2100},
    {"nome": "Cavaleiro Branco", "ataque": 1800, "defesa": 1500},
    {"nome": "Elfa do Vento", "ataque": 1200, "defesa": 1600},
]
cartas_lock = asyncio.Lock()

jogadores_esperando = []
partidas = {}
partidas_lock = asyncio.Lock()


async def handle_client(reader: asyncio.StreamReader, writer: asyncio.StreamWriter):
    addr = writer.get_extra_info('peername')
    print(f"[NOVA CONEXÃO] Cliente {addr} conectado.")
    minhas_cartas = []

    try:
        while True:
            data = await reader.readline()
            if not data:
                break
            msg = data.decode().strip().lower()

            # Listar cartas
            if msg == "listar":
                async with cartas_lock:
                    if cartas_disponiveis:
                        lista = "\n".join(
                            f"{i+1}. {c['nome']} (ATK {c['ataque']} / DEF {c['defesa']})"
                            for i, c in enumerate(cartas_disponiveis)
                        )
                        writer.write(f"Cartas disponíveis:\n{lista}\n".encode())
                    else:
                        writer.write("Nenhuma carta disponível.\n".encode())
                await writer.drain()

            # Pegar carta
            elif msg.startswith("pegar"):
                partes = msg.split()
                if len(partes) == 2 and partes[1].isdigit():
                    idx = int(partes[1]) - 1
                    async with cartas_lock:
                        if 0 <= idx < len(cartas_disponiveis):
                            carta = cartas_disponiveis.pop(idx)
                            minhas_cartas.append(carta)
                            writer.write(f"CARTA {json.dumps(carta)}\n".encode())
                            print(f"[{addr}] pegou {carta['nome']}.")
                        else:
                            writer.write("Índice inválido ou carta não disponível.\n".encode())
                else:
                    writer.write("Uso correto: pegar <numero>\n".encode())
                await writer.drain()

            # Ver cartas na mão
            elif msg == "mao":
                if minhas_cartas:
                    lista = "\n".join(
                        f"{i+1}. {c['nome']} (ATK {c['ataque']} / DEF {c['defesa']})"
                        for i, c in enumerate(minhas_cartas)
                    )
                    writer.write(f"Sua mão:\n{lista}\n".encode())
                else:
                    writer.write("Você não tem cartas.\n".encode())
                await writer.drain()

            # Entrar na fila de duelo
            elif msg == "duelo":
                async with partidas_lock:
                    if writer not in jogadores_esperando:
                        jogadores_esperando.append(writer)
                        writer.write("Você entrou na fila de duelo. Aguardando adversário...\n".encode())
                        await writer.drain()

                        if len(jogadores_esperando) >= 2:
                            p1 = jogadores_esperando.pop(0)
                            p2 = jogadores_esperando.pop(0)
                            partida_id = f"{id(p1)}-{id(p2)}"
                            partidas[partida_id] = {"jogadores": [p1, p2], "maos": {p1: [], p2: []}}
                            p1.write("Partida iniciada! Você é o Jogador 1.\n".encode())
                            p2.write("Partida iniciada! Você é o Jogador 2.\n".encode())
                            await p1.drain()
                            await p2.drain()
                            print(f"[PARTIDA] Criada {partida_id}")

            # Jogar carta
            elif msg.startswith("jogar"):
                partes = msg.split()
                if len(partes) == 2 and partes[1].isdigit():
                    idx = int(partes[1]) - 1
                    for partida_id, partida in partidas.items():
                        if writer in partida["jogadores"]:
                            if 0 <= idx < len(minhas_cartas):
                                carta = minhas_cartas.pop(idx)
                                adversario = [c for c in partida["jogadores"] if c != writer][0]
                                adversario.write(f"O oponente jogou {carta['nome']} (ATK {carta['ataque']}, DEF {carta['defesa']})\n".encode())
                                writer.write(f"Você jogou {carta['nome']}.\n".encode())
                                await adversario.drain()
                                await writer.drain()
                            else:
                                writer.write("Carta inválida.\n".encode())
                                await writer.drain()

            else:
                writer.write("Comandos: 'listar', 'pegar <num>', 'mao', 'duelo', 'jogar <num>', 'sair'\n".encode())
                await writer.drain()

    except Exception as e:
        print(f"[ERRO] Cliente {addr} -> {e}")
    finally:
        writer.close()
        await writer.wait_closed()
        print(f"[DESCONECTADO] Cliente {addr} saiu.")


async def main():
    server = await asyncio.start_server(handle_client, HOST, PORT)
    addr = server.sockets[0].getsockname()
    print(f"[SERVIDOR INICIADO] Ouvindo em {addr}...")

    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    asyncio.run(main())
