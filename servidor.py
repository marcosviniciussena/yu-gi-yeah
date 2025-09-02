import socket
import threading
import json

HOST = '127.0.0.1'
PORT = 5000

# Deck inicial de cartas
cartas_disponiveis = [
    {"nome": "Dragão Vermelho", "ataque": 3000, "defesa": 2500},
    {"nome": "Mago Negro", "ataque": 2500, "defesa": 2100},
    {"nome": "Cavaleiro Branco", "ataque": 1800, "defesa": 1500},
    {"nome": "Elfa do Vento", "ataque": 1200, "defesa": 1600}
]
cartas_lock = threading.Lock()

def handle_client(conn, addr):
    global cartas_disponiveis
    print(f"[NOVA CONEXÃO] Cliente {addr} conectado.")

    while True:
        try:
            data = conn.recv(1024)
            if not data:
                break
            msg = data.decode().strip().lower()

            if msg == "listar":
                with cartas_lock:
                    if cartas_disponiveis:
                        lista = "\n".join(
                            f"{i+1}. {c['nome']} (ATK {c['ataque']} / DEF {c['defesa']})"
                            for i, c in enumerate(cartas_disponiveis)
                        )
                        conn.sendall(f"Cartas disponíveis:\n{lista}\n".encode())
                    else:
                        conn.sendall("Nenhuma carta disponível.\n")

            elif msg.startswith("pegar"):
                partes = msg.split()
                if len(partes) == 2 and partes[1].isdigit():
                    idx = int(partes[1]) - 1
                    with cartas_lock:
                        if 0 <= idx < len(cartas_disponiveis):
                            carta = cartas_disponiveis.pop(idx)
                            # Envia a carta em formato JSON
                            conn.sendall(f"CARTA {json.dumps(carta)}\n".encode())
                            print(f"[{addr}] pegou a carta {carta['nome']}.")
                        else:
                            conn.sendall("Índice inválido ou carta não disponível.\n")
                else:
                    conn.sendall("Uso correto: pegar <numero>\n")

            else:
                conn.sendall("Comandos: 'listar', 'pegar <numero>', 'sair'\n")

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
