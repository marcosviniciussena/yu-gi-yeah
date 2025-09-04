import asyncio
import json
import time

HOST = "127.0.0.1"
PORT = 5000

minhas_cartas = []

async def main():
    reader, writer = await asyncio.open_connection(HOST, PORT)
    print("Conectado ao servidor.")

    print("Comandos:")
    print(" - 'listar' → ver cartas disponíveis")
    print(" - 'pegar <numero>' → tentar pegar uma carta")
    print(" - 'mao' → ver suas cartas")
    print(" - 'duelo' → entrar em uma partida")
    print(" - 'jogar <num>' → jogar uma carta da sua mão")
    print(" - 'sair' → encerrar")

    while True:
        msg = input("> ")
        if msg.lower() == "sair":
            break

        inicio = time.perf_counter()
        writer.write((msg + "\n").encode())
        await writer.drain() #aqui ele envia a mensagem e a tarefa para de executar até que o buffer esvazie.

        # data = await reader.readline()  # lê até o \n (mais seguro que read(1024))
        data = await reader.read(1024)
        fim = time.perf_counter()
        atraso = (fim - inicio) * 1000

        resposta = data.decode().strip()
        print("Resposta:", resposta)
        print(f"Atraso: {atraso:.2f} ms\n")

        if resposta.startswith("CARTA "):
            carta_json = resposta[6:].strip()
            carta = json.loads(carta_json)
            minhas_cartas.append(carta)
            print(f"Você recebeu: {carta['nome']} (ATK {carta['ataque']} / DEF {carta['defesa']})")

    writer.close()
    await writer.wait_closed()

asyncio.run(main())
