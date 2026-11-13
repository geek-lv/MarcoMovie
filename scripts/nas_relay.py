#!/usr/bin/env python3
"""TCP relay: forwards local adb traffic to remote device via NAS."""
import socket, threading, sys, time

RELAYS = [
    (15555, '192.168.31.120', 5555),  # phone
    (15556, '192.168.31.76', 5555),   # TV
]

def relay_thread(listen_port, remote_host, remote_port):
    def handle(client):
        try:
            remote = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            remote.settimeout(5)
            remote.connect((remote_host, remote_port))
            remote.settimeout(None)
            print(f'[{listen_port}] relay {client.getpeername()} -> {remote_host}:{remote_port}', flush=True)

            def forward(src, dst):
                try:
                    while True:
                        data = src.recv(8192)
                        if not data:
                            break
                        dst.sendall(data)
                except:
                    pass
                finally:
                    try: dst.shutdown(socket.SHUT_WR)
                    except: pass

            t1 = threading.Thread(target=forward, args=(client, remote), daemon=True)
            t2 = threading.Thread(target=forward, args=(remote, client), daemon=True)
            t1.start(); t2.start()
            t1.join(timeout=30); t2.join(timeout=30)
        except Exception as e:
            print(f'[{listen_port}] error: {e}', flush=True)
        finally:
            try: client.close()
            except: pass

    listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    listener.bind(('0.0.0.0', listen_port))
    listener.listen(5)
    print(f'[{listen_port}] listening -> {remote_host}:{remote_port}', flush=True)
    while True:
        client, addr = listener.accept()
        t = threading.Thread(target=handle, args=(client,), daemon=True)
        t.start()

for lp, rh, rp in RELAYS:
    threading.Thread(target=relay_thread, args=(lp, rh, rp), daemon=True).start()

print('All relays started', flush=True)
while True:
    time.sleep(60)
