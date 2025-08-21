# cliente.py
import socket

HOST = "servidor"  # Endereço do servidor
PORT = 5000         # Mesma porta do servidor

# Cria socket
cliente = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
cliente.connect((HOST, PORT))

mensagem = "Olá, servidor via Docker!"
cliente.sendall(mensagem.encode())

resposta = cliente.recv(1024)
print("Resposta do servidor:", resposta.decode())

cliente.close()