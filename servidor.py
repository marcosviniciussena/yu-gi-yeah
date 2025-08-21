# servidor.py
import socket

HOST = "0.0.0.0"  # Endereço do servidor (localhost)
PORT = 5000         # Porta para conexão

# Cria o socket TCP/IP
servidor = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
servidor.bind((HOST, PORT))
servidor.listen()

print(f"Servidor escutando em {HOST}:{PORT}...")

conn, addr = servidor.accept()
print(f"Conectado por {addr}")

while True:
    data = conn.recv(1024)  # recebe até 1024 bytes
    if not data:
        break
    print("Mensagem recebida:", data.decode())
    conn.sendall(data)  # devolve a mesma mensagem (echo)

conn.close()