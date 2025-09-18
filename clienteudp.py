import asyncio
import json
import time

HOST = "127.0.0.1"
PORT_TCP = 5000
PORT_UDP = 6000

minhas_cartas = []

async def handle_server(reader, queue):
    """Lê continuamente do servidor e coloca na fila"""
    try:
        while True:
            data = await reader.readline()
            if not data:
                print("⚠ Servidor fechou a conexão.")
                break
            linha = data.decode(errors="ignore").strip()
            if linha and linha != "END":
                await queue.put(linha)
    except (ConnectionResetError, OSError):
        print("⚠ Conexão perdida durante a leitura.")
        await queue.put(None)


async def jogo_tcp():
    try:
        reader, writer = await asyncio.open_connection(HOST, PORT_TCP)
    except (ConnectionRefusedError, OSError) as e:
        print(f"❌ Não foi possível conectar ao servidor: {e}")
        return

    print("Conectado ao servidor TCP.")
    print("Comandos:")
    print(" - 'listar' → ver cartas disponíveis")
    print(" - 'pegar' → receber pacote com 3 cartas")
    print(" - 'mao' → ver suas cartas")
    print(" - 'duelo' → entrar em uma partida")
    print(" - 'sair' → encerrar")

    queue = asyncio.Queue()
    # Inicia a corrotina que só lê o servidor
    asyncio.create_task(handle_server(reader, queue))
    asyncio.create_task(processar_fila(queue))

    loop = asyncio.get_running_loop()

    try:
        while True:
            msg = input("> ")

            if msg.lower() == "sair":
                break

            elif msg.lower() == "ping":
                await medir_ping(loop)
                continue

            # Envia comando ao servidor
            try:
                writer.write((msg + "\n").encode())
                await writer.drain()
            except (BrokenPipeError, ConnectionResetError, OSError):
                print("⚠ Conexão perdida com o servidor.")
                break

            # Recebe respostas do servidor através da fila
            resposta = []
            while True:
                try:
                    linha = await asyncio.wait_for(queue.get(), timeout=0.5)
                    resposta.append(linha)
                    # Para comandos simples, quebrar ao receber primeira linha relevante
                    if not linha.startswith("CARTA ") and msg != "listar":
                        break
                except asyncio.TimeoutError:
                    break

            if resposta:
                resposta_str = "\n".join(resposta)
                print("Resposta:", resposta_str)

                # Processa carta recebida
                for linha in resposta:
                    if linha.startswith("CARTA "):
                        try:
                            carta_json = linha[6:].strip()
                            carta = json.loads(carta_json)
                            minhas_cartas.append(carta)
                            print(f"Você recebeu: {carta['nome']} (ATK {carta['ataque']} / DEF {carta['defesa']})")
                        except json.JSONDecodeError as e:
                            print(f"⚠ Erro ao decodificar carta: {e}")

                # Exibe a mão se solicitado
                if msg.lower() == "mao":
                    if minhas_cartas:
                        for i, c in enumerate(minhas_cartas, 1):
                            print(f"{i}. {c['nome']} (ATK {c['ataque']} / DEF {c['defesa']})")
                    else:
                        print("Você ainda não tem cartas.")

    finally:
        writer.close()
        try:
            await writer.wait_closed()
        except Exception:
            pass
        print("❌ Conexão encerrada.")

async def processar_fila(queue):
    """Processa mensagens que chegam do servidor sem precisar de input"""
    while True:
        linha = await queue.get()
        if linha is None:  # sinal de saída
            break
        print(f"\n📩 Servidor: {linha}")  # imprime imediatamente


async def medir_ping(loop):
    """Envia ping via UDP e mede latência"""
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
            if not on_response.done():
                on_response.set_result(True)

        def error_received(self, exc):
            print(f"⚠ Erro UDP: {exc}")
            if not on_response.done():
                on_response.set_result(False)

    transport, _ = await loop.create_datagram_endpoint(
        lambda: PingClientProtocol(),
        remote_addr=(HOST, PORT_UDP),
    )

    try:
        await asyncio.wait_for(on_response, timeout=2.0)
    except asyncio.TimeoutError:
        print("⚠ Ping falhou (timeout).")
    finally:
        transport.close()


async def main():
    await jogo_tcp()


if __name__ == "__main__":
    asyncio.run(main())
