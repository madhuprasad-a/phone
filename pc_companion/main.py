#!/usr/bin/env python3
"""
MirrorSync PC Companion
------------------------
Connects to the MirrorSync Android app over the same WiFi network.

Requires:
  pip install -r requirements.txt
  ffmpeg + ffplay installed and on PATH (for screen mirroring)

Ports (must match the Android app):
  5001 - screen mirror (raw H264 stream, viewed via ffplay)
  5002 - file transfer
  5003 - clipboard sync
"""

import json
import os
import socket
import struct
import subprocess
import threading
import tkinter as tk
from tkinter import filedialog, messagebox, ttk

try:
    import pyperclip
except ImportError:
    pyperclip = None


class MirrorSyncApp:
    def __init__(self, root):
        self.root = root
        self.root.title("MirrorSync - PC Companion")
        self.root.geometry("560x480")

        self.ip_var = tk.StringVar(value="192.168.1.")
        self.mirror_proc = None
        self.clip_sock = None
        self.clip_thread_running = False

        self._build_ui()

    # ---------- UI ----------
    def _build_ui(self):
        top = ttk.Frame(self.root, padding=10)
        top.pack(fill="x")

        ttk.Label(top, text="Phone IP:").pack(side="left")
        ttk.Entry(top, textvariable=self.ip_var, width=20).pack(side="left", padx=6)

        notebook = ttk.Notebook(self.root)
        notebook.pack(fill="both", expand=True, padx=10, pady=10)

        mirror_tab = ttk.Frame(notebook)
        files_tab = ttk.Frame(notebook)
        clip_tab = ttk.Frame(notebook)

        notebook.add(mirror_tab, text="Screen Mirror")
        notebook.add(files_tab, text="File Transfer")
        notebook.add(clip_tab, text="Clipboard Sync")

        self._build_mirror_tab(mirror_tab)
        self._build_files_tab(files_tab)
        self._build_clip_tab(clip_tab)

        self.status_var = tk.StringVar(value="Ready")
        ttk.Label(self.root, textvariable=self.status_var, relief="sunken", anchor="w").pack(
            fill="x", side="bottom"
        )

    def _build_mirror_tab(self, parent):
        ttk.Label(
            parent,
            text="Starts ffplay pointed at the phone's screen-mirror socket (port 5001).\n"
                 "Requires ffmpeg/ffplay installed on this PC.",
            wraplength=480,
            justify="left",
        ).pack(pady=10)
        ttk.Button(parent, text="Start Mirroring", command=self.start_mirror).pack(pady=6)
        ttk.Button(parent, text="Stop Mirroring", command=self.stop_mirror).pack(pady=6)

    def _build_files_tab(self, parent):
        btn_frame = ttk.Frame(parent)
        btn_frame.pack(fill="x", pady=6)
        ttk.Button(btn_frame, text="Refresh List", command=self.list_remote_files).pack(side="left", padx=4)
        ttk.Button(btn_frame, text="Download Selected", command=self.download_file).pack(side="left", padx=4)
        ttk.Button(btn_frame, text="Upload File...", command=self.upload_file).pack(side="left", padx=4)

        self.file_listbox = tk.Listbox(parent, height=15)
        self.file_listbox.pack(fill="both", expand=True, padx=4, pady=4)

    def _build_clip_tab(self, parent):
        ttk.Label(
            parent,
            text="Two-way clipboard sync with the phone (port 5003).\n"
                 "Note: on Android 10+, phone->PC sync only works while the\n"
                 "MirrorSync app is in the foreground on the phone.",
            wraplength=480,
            justify="left",
        ).pack(pady=10)
        self.clip_btn = ttk.Button(parent, text="Start Clipboard Sync", command=self.toggle_clip_sync)
        self.clip_btn.pack(pady=6)

    # ---------- Screen mirror ----------
    def start_mirror(self):
        ip = self.ip_var.get().strip()
        if self.mirror_proc is not None:
            messagebox.showinfo("MirrorSync", "Mirroring already running.")
            return
        url = f"tcp://{ip}:5001"
        try:
            self.mirror_proc = subprocess.Popen(
                ["ffplay", "-fflags", "nobuffer", "-flags", "low_delay", "-framedrop", "-i", url]
            )
            self.status_var.set(f"Mirroring from {ip}...")
        except FileNotFoundError:
            messagebox.showerror(
                "MirrorSync",
                "ffplay not found. Install ffmpeg and make sure it's on your PATH.",
            )
            self.mirror_proc = None

    def stop_mirror(self):
        if self.mirror_proc:
            self.mirror_proc.terminate()
            self.mirror_proc = None
            self.status_var.set("Mirroring stopped.")

    # ---------- File transfer ----------
    def _connect_file_socket(self):
        ip = self.ip_var.get().strip()
        sock = socket.create_connection((ip, 5002), timeout=5)
        return sock

    def list_remote_files(self):
        try:
            sock = self._connect_file_socket()
            sock.sendall(b"LIST\n")
            data = b""
            sock.settimeout(5)
            while True:
                chunk = sock.recv(4096)
                if not chunk:
                    break
                data += chunk
                if b"\n" in data:
                    break
            sock.close()
            files = json.loads(data.decode().strip())
            self.file_listbox.delete(0, tk.END)
            for f in files:
                self.file_listbox.insert(tk.END, f"{f['name']}  ({f['size']} bytes)")
            self.status_var.set(f"Found {len(files)} file(s) on phone.")
        except Exception as e:
            messagebox.showerror("MirrorSync", f"Could not list files: {e}")

    def download_file(self):
        sel = self.file_listbox.curselection()
        if not sel:
            messagebox.showinfo("MirrorSync", "Select a file first.")
            return
        name = self.file_listbox.get(sel[0]).split("  (")[0]
        save_path = filedialog.asksaveasfilename(initialfile=name)
        if not save_path:
            return
        try:
            sock = self._connect_file_socket()
            sock.sendall(f"GET {name}\n".encode())
            size_bytes = self._recv_exact(sock, 8)
            size = struct.unpack(">q", size_bytes)[0]
            if size < 0:
                messagebox.showerror("MirrorSync", "File not found on phone.")
                sock.close()
                return
            with open(save_path, "wb") as f:
                remaining = size
                while remaining > 0:
                    chunk = sock.recv(min(8192, remaining))
                    if not chunk:
                        break
                    f.write(chunk)
                    remaining -= len(chunk)
            sock.close()
            self.status_var.set(f"Downloaded {name} ({size} bytes).")
        except Exception as e:
            messagebox.showerror("MirrorSync", f"Download failed: {e}")

    def upload_file(self):
        path = filedialog.askopenfilename()
        if not path:
            return
        name = os.path.basename(path)
        size = os.path.getsize(path)
        try:
            sock = self._connect_file_socket()
            sock.sendall(f"PUT {name} {size}\n".encode())
            with open(path, "rb") as f:
                while True:
                    chunk = f.read(8192)
                    if not chunk:
                        break
                    sock.sendall(chunk)
            sock.close()
            self.status_var.set(f"Uploaded {name} ({size} bytes).")
            self.list_remote_files()
        except Exception as e:
            messagebox.showerror("MirrorSync", f"Upload failed: {e}")

    @staticmethod
    def _recv_exact(sock, n):
        data = b""
        while len(data) < n:
            chunk = sock.recv(n - len(data))
            if not chunk:
                raise ConnectionError("Socket closed early")
            data += chunk
        return data

    # ---------- Clipboard sync ----------
    def toggle_clip_sync(self):
        if pyperclip is None:
            messagebox.showerror("MirrorSync", "Install pyperclip: pip install pyperclip")
            return
        if self.clip_thread_running:
            self.clip_thread_running = False
            if self.clip_sock:
                try:
                    self.clip_sock.close()
                except Exception:
                    pass
            self.clip_btn.config(text="Start Clipboard Sync")
            self.status_var.set("Clipboard sync stopped.")
        else:
            ip = self.ip_var.get().strip()
            try:
                self.clip_sock = socket.create_connection((ip, 5003), timeout=5)
            except Exception as e:
                messagebox.showerror("MirrorSync", f"Could not connect: {e}")
                return
            self.clip_thread_running = True
            self.clip_btn.config(text="Stop Clipboard Sync")
            self.status_var.set("Clipboard sync running.")
            threading.Thread(target=self._clip_sender_loop, daemon=True).start()
            threading.Thread(target=self._clip_receiver_loop, daemon=True).start()

    def _clip_sender_loop(self):
        last = None
        while self.clip_thread_running:
            try:
                current = pyperclip.paste()
                if current != last:
                    last = current
                    self.clip_sock.sendall(f"TEXT:{current}\n".encode())
            except Exception:
                break
            threading.Event().wait(1)

    def _clip_receiver_loop(self):
        buf = b""
        while self.clip_thread_running:
            try:
                chunk = self.clip_sock.recv(4096)
                if not chunk:
                    break
                buf += chunk
                while b"\n" in buf:
                    line, buf = buf.split(b"\n", 1)
                    text = line.decode(errors="ignore")
                    if text.startswith("TEXT:"):
                        pyperclip.copy(text[5:])
            except Exception:
                break


if __name__ == "__main__":
    root = tk.Tk()
    app = MirrorSyncApp(root)
    root.mainloop()
