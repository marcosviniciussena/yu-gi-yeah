# servidor.py
import asyncio
import json
import threading
import random

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


async def handle_client(reader, writer):
    addr = writer.get_extra_info('peername')
    print(f"Cliente conectado: {addr}")
    clientes[addr] = writer

    while True:
        try:
            data = await reader.readline()
            if not data:
                break

            msg = data.decode().strip()
            print(f"[{addr}] {msg}")

            if msg == "listar":
                resposta = "Cartas disponíveis:\n"
                for c in BARALHO:
                    resposta += f"{c['id']}: {c['nome']} (ATK {c['ataque']} / DEF {c['defesa']})\n"
                writer.write((resposta + "\n").encode())

            elif msg.startswith("pegar "):
                try:
                    cid = int(msg.split()[1])
                    carta = next((c for c in BARALHO if c["id"] == cid), None)
                    if carta:
                        BARALHO.remove(carta)
                        writer.write((f"CARTA {json.dumps(carta)}\n").encode())
                    else:
                        writer.write("Carta não disponível\n".encode())
                except:
                    writer.write("Comando inválido\n".encode())

            elif msg == "duelo":
                fila_duelo.append((addr, writer))
                writer.write("Você entrou na fila para duelo...\n".encode())
                if len(fila_duelo) >= 2:
                    p1 = fila_duelo.pop(0)
                    p2 = fila_duelo.pop(0)
                    threading.Thread(target=partida, args=(p1, p2), daemon=True).start()

            else:
                writer.write("Comando não reconhecido\n".encode())

            await writer.drain()

        except ConnectionResetError:
            break

    print(f"Cliente desconectado: {addr}")
    del clientes[addr]
    writer.close()
    await writer.wait_closed()


def partida(p1, p2):
    """Executa uma partida 1vs1 em thread separada"""
    addr1, w1 = p1
    addr2, w2 = p2

    try:
        w1.write("Duelo iniciado contra outro jogador!\n".encode())
        w2.write("Duelo iniciado contra outro jogador!\n".encode())

        # Simples lógica de batalha: cada jogador recebe uma carta aleatória
        carta1 = random.choice([
            {"nome": "Espadachim", "ataque": 5, "defesa": 4},
            {"nome": "Arqueiro", "ataque": 4, "defesa": 5},
        ])
        carta2 = random.choice([
            {"nome": "Orc", "ataque": 6, "defesa": 3},
            {"nome": "Troll", "ataque": 3, "defesa": 7},
        ])

        w1.write(f"Sua carta: {carta1['nome']} (ATK {carta1['ataque']} / DEF {carta1['defesa']})\n".encode())
        w2.write(f"Sua carta: {carta2['nome']} (ATK {carta2['ataque']} / DEF {carta2['defesa']})\n".encode())

        # Determina vencedor pelo ataque
        if carta1["ataque"] > carta2["ataque"]:
            vencedor = "Jogador 1 venceu!"
        elif carta2["ataque"] > carta1["ataque"]:
            vencedor = "Jogador 2 venceu!"
        else:
            vencedor = "Empate!"

        w1.write((vencedor + "\n").encode())
        w2.write((vencedor + "\n").encode())

        asyncio.run_coroutine_threadsafe(w1.drain(), asyncio.get_event_loop())
        asyncio.run_coroutine_threadsafe(w2.drain(), asyncio.get_event_loop())

    except Exception as e:
        print("Erro na partida:", e)


async def main():
    server = await asyncio.start_server(handle_client, "127.0.0.1", 5000)
    addr = server.sockets[0].getsockname()
    print(f"Servidor rodando em {addr}")

    async with server:
        await server.serve_forever()

if __name__ == "__main__":
    asyncio.run(main())
