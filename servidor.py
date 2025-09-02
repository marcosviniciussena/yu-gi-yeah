import socket
import threading

HOST = '127.0.0.1'
PORT = 5000

# Estado do recurso único (a carta)
carta_disponivel = True
carta_lock = threading.Lock()  # para evitar corrida de dados

def handle_client(conn, addr):
    global carta_disponivel
    print(f"[NOVA CONEXÃO] Cliente {addr} conectado.")

    while True:
        try:
            data = conn.recv(1024)
            if not data:
                break
            msg = data.decode().strip().lower()

            if msg == "pegar":
                with carta_lock:  # garante exclusão mútua
                    if carta_disponivel:
                        carta_disponivel = False
                        conn.sendall(b"Você pegou a carta! Agora ela é sua.\n")
                        print(f"[{addr}] ficou com a carta!")
                    else:
                        conn.sendall(b"A carta já foi pega por outro cliente.\n")
            else:
                conn.sendall(b"Envie 'pegar' para tentar conseguir a carta.\n")

        except:
            break

    conn.close()
    print(f"[DESCONECTADO] Cliente {addr} saiu.")


def start_server():
    server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server_socket.bind((HOST, PORT))
    server_socket.listen()
    print(f"[SERVIDOR INICIADO] Ouvindo em {HOST}:{PORT}...")

    while True:
        conn, addr = server_socket.accept()
        thread = threading.Thread(target=handle_client, args=(conn, addr))
        thread.start()
        print(f"[ATIVOS] {threading.active_count() - 1} clientes conectados.")


if __name__ == "__main__":
    start_server()
