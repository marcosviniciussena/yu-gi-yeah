import asyncio
import json
import time

HOST = "127.0.0.1"
PORT_TCP = 5000
PORT_UDP = 6000

minhas_cartas = []


async def jogo_tcp():
    reader, writer = await asyncio.open_connection(HOST, PORT_TCP)
    print("Conectado ao servidor TCP.")

    print("Comandos:")
    print(" - 'listar' → ver cartas disponíveis")
    print(" - 'pegar <numero>' → tentar pegar uma carta")
    print(" - 'mao' → ver suas cartas")
    print(" - 'duelo' → entrar em uma partida")
    print(" - 'ping' → medir latência UDP")
    print(" - 'sair' → encerrar")

    loop = asyncio.get_running_loop()

    while True:
        msg = input("> ")

        if msg.lower() == "sair":
            break

        elif msg.lower() == "ping":
            await medir_ping(loop)
            continue

        inicio = time.perf_counter()
        writer.write((msg + "\n").encode())
        await writer.drain()

        resposta = []
        while True:
            data = await reader.readline()
            if not data:
                break
            linha = data.decode().strip()
            if linha == "END" or linha == "":
                break
            resposta.append(linha)

        fim = time.perf_counter()
        atraso = (fim - inicio) * 1000

        if resposta:
            resposta_str = "\n".join(resposta)
            print("Resposta:", resposta_str)
            print(f"Atraso (TCP): {atraso:.2f} ms\n")

            if resposta_str.startswith("CARTA "):
                carta_json = resposta_str[6:].strip()
                carta = json.loads(carta_json)
                minhas_cartas.append(carta)
                print(f"Você recebeu: {carta['nome']} (ATK {carta['ataque']} / DEF {carta['defesa']})")

    writer.close()
    await writer.wait_closed()


async def medir_ping(loop):
    on_response = loop.create_future()

    class PingClientProtocol:
        def __init__(self):
            self.start_time = time.perf_counter()

        def connection_made(self, transport):
            self.transport = transport
            self.transport.sendto(b"ping", (HOST, PORT_UDP))

        def datagram_received(self, data, addr):
            fim = time.perf_counter()
            atraso = (fim - self.start_time) * 1000
            print(f"Resposta UDP: {data.decode()} de {addr} | Ping: {atraso:.2f} ms")
            on_response.set_result(True)

    transport, protocol = await loop.create_datagram_endpoint(
        lambda: PingClientProtocol(),
        remote_addr=(HOST, PORT_UDP),
    )

    try:
        await on_response
    finally:
        transport.close()


async def main():
    await jogo_tcp()


if __name__ == "__main__":
    asyncio.run(main())
