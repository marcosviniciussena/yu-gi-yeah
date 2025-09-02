# cliente.py
import socket
import time
import json

HOST = "127.0.0.1"  # Endereço do servidor
PORT = 5000         # Mesma porta do servidor

# Cria socket
cliente = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
cliente.connect((HOST, PORT))

# Lista local de cartas do jogador
minhas_cartas = []

print("Comandos:")
print(" - 'listar' → ver cartas disponíveis")
print(" - 'pegar <numero>' → tentar pegar uma carta")
print(" - 'mao' → ver suas cartas")
print(" - 'sair' → encerrar")

while True:
    msg = input("> ")
    if msg.lower() == "sair":
        break
    elif msg.lower() == "mao":
        if minhas_cartas:
            print("Suas cartas:")
            for i, carta in enumerate(minhas_cartas, 1):
                print(f"{i}. {carta['nome']} (ATK {carta['ataque']} / DEF {carta['defesa']})")
        else:
            print("Você ainda não tem cartas.")
        continue

    # Marca o tempo antes do envio
    inicio = time.perf_counter()

    cliente.sendall(msg.encode())       # Envia mensagem
    resposta = cliente.recv(1024)           # Recebe resposta

    # Marca o tempo após a resposta
    fim = time.perf_counter()
    atraso = (fim - inicio) * 1000  # em milissegundos

    print("Resposta do servidor:", resposta.decode().strip())
    print(f"Atraso: {atraso:.2f} ms\n")

    
    # Se o servidor enviou uma carta (prefixo "CARTA")
    if resposta.startswith("CARTA "):
        carta_json = resposta[6:].strip()
        carta = json.loads(carta_json)
        minhas_cartas.append(carta)
        print(f"Você recebeu a carta: {carta['nome']} (ATK {carta['ataque']} / DEF {carta['defesa']})")
    else:
        print("Servidor:", resposta)

cliente.close()
