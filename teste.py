import asyncio
import time

HOST = "127.0.0.1"
PORT_TCP = 5000

# contador global de cartas
carta_global = 1
lock = asyncio.Lock()

# métricas
metricas = {
    "erros": 0,
    "respostas": 0,
    "tempos": [],
}


async def jogador_fake(id: int, rodadas=2):
    global carta_global

    try:
        reader, writer = await asyncio.open_connection(HOST, PORT_TCP)
        print(f"[J{id}] conectado")

        # pegar 2 cartas de forma sequencial
        for _ in range(2):
            async with lock:
                carta_id = carta_global
                carta_global += 1

            inicio = time.perf_counter()
            writer.write(f"pegar {carta_id}\n".encode())
            await writer.drain()
            await ler_resposta(reader, id, inicio)

        # ver a mão
        inicio = time.perf_counter()
        writer.write(b"mao\n")
        await writer.drain()
        await ler_resposta(reader, id, inicio)

        # entrar em duelos
        for _ in range(rodadas):
            inicio = time.perf_counter()
            writer.write(b"duelo\n")
            await writer.drain()
            await ler_resposta(reader, id, inicio)

        # sair
        writer.write(b"sair\n")
        await writer.drain()
        writer.close()
        await writer.wait_closed()
        print(f"[J{id}] desconectado")

    except Exception as e:
        metricas["erros"] += 1
        print(f"[J{id}] erro: {e}")


async def ler_resposta(reader, jid, inicio):
    """Lê resposta do servidor até encontrar END"""
    resposta = []
    try:
        while True:
            data = await reader.readline()
            if not data:
                break
            linha = data.decode(errors="ignore").strip()
            if linha == "" or linha == "END":
                break
            resposta.append(linha)

        fim = time.perf_counter()
        atraso = (fim - inicio) * 1000
        metricas["tempos"].append(atraso)
        metricas["respostas"] += 1

        if resposta:
            print(f"[J{jid}] resposta: {' | '.join(resposta)} | atraso={atraso:.2f}ms")

    except Exception as e:
        metricas["erros"] += 1
        print(f"[J{jid}] falha ao ler: {e}")


async def main():
    N = 10  # número de jogadores simultâneos
    rodadas = 2

    inicio_total = time.perf_counter()
    tarefas = [jogador_fake(i, rodadas) for i in range(1, N + 1)]
    await asyncio.gather(*tarefas)
    fim_total = time.perf_counter()

    print("\n=== RELATÓRIO FINAL ===")
    print(f"Jogadores simulados: {N}")
    print(f"Rodadas de duelo por jogador: {rodadas}")
    print(f"Tempo total de execução: {(fim_total - inicio_total):.2f}s")
    print(f"Total de respostas recebidas: {metricas['respostas']}")
    print(f"Total de erros: {metricas['erros']}")

    if metricas["tempos"]:
        media = sum(metricas["tempos"]) / len(metricas["tempos"])
        maximo = max(metricas["tempos"])
        minimo = min(metricas["tempos"])
        print(f"Atraso médio: {media:.2f} ms")
        print(f"Atraso mínimo: {minimo:.2f} ms")
        print(f"Atraso máximo: {maximo:.2f} ms")
    print("========================\n")


if __name__ == "__main__":
    asyncio.run(main())
