# cliente.py
import socket
import time

HOST = "127.0.0.1"  # Endereço do servidor
PORT = 5000         # Mesma porta do servidor

# Cria socket
cliente = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
cliente.connect((HOST, PORT))

while True:
    msg = input("Digite uma mensagem ('sair' para encerrar): ")
    if msg.lower() == "sair":
        break

    # Marca o tempo antes do envio
    inicio = time.perf_counter()

    cliente.sendall(msg.encode())       # Envia mensagem
    data = cliente.recv(1024)           # Recebe resposta

    # Marca o tempo após a resposta
    fim = time.perf_counter()
    atraso = (fim - inicio) * 1000  # em milissegundos

    print("Resposta do servidor:", data.decode())
    print(f"Atraso: {atraso:.2f} ms\n")

cliente.close()