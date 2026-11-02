#!/usr/bin/env python3
"""
开发期代理中继（本机环境专用，不进生产）。

背景：FlClash 混合端口只绑 127.0.0.1:7890，Docker 容器够不到宿主 loopback；
而本机的透明代理 TUN 又会重置容器直连流量（实测 dashscope/github 全 reset）。
方案：宿主上起一个 0.0.0.0:7891 的 TCP 中继转发到 127.0.0.1:7890，
容器用 host.docker.internal:7891 即可复用宿主代理——不改 FlClash 配置、不留系统改动。

用法（后台跑）：python scripts/relay_proxy.py [监听端口=7891] [上游=127.0.0.1:7890]
配套：deploy/.env 加 JAVA_OPTS=-Dhttps.proxyHost=host.docker.internal -Dhttps.proxyPort=7891
"""
import socket, sys, threading

LISTEN = int(sys.argv[1]) if len(sys.argv) > 1 else 7891
UP = ("127.0.0.1", int(sys.argv[2].split(":")[1]) if len(sys.argv) > 2 else 7890)

def pipe(src, dst):
    try:
        while True:
            data = src.recv(65536)
            if not data:
                break
            dst.sendall(data)
    except OSError:
        pass
    finally:
        try: dst.shutdown(socket.SHUT_WR)
        except OSError: pass

def handle(conn):
    try:
        up = socket.create_connection(UP, timeout=15)
    except OSError:
        conn.close(); return
    threading.Thread(target=pipe, args=(conn, up), daemon=True).start()
    threading.Thread(target=pipe, args=(up, conn), daemon=True).start()

def main():
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", LISTEN))
    srv.listen(128)
    print(f"relay listening 0.0.0.0:{LISTEN} -> {UP[0]}:{UP[1]}", flush=True)
    while True:
        c, _ = srv.accept()
        threading.Thread(target=handle, args=(c,), daemon=True).start()

if __name__ == "__main__":
    main()
