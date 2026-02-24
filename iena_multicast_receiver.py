#!/usr/bin/env python3
"""
IENA Multicast Data Stream Receiver & Live Plotter

Joins one or more multicast groups, discovers active IENA streams
(identified by their Key field), lets the user choose which stream
to plot, and displays the selected variables as a scrolling real-time
plot.

Supports reading a XidML metadata file (--config) to dynamically
determine parameter names and count instead of hardcoding a-z.

Workflow:
  1. Join the multicast group(s) and listen for a few seconds.
  2. Show all discovered IENA streams (key, source address, packet rate).
  3. User picks one stream.
  4. Live plot begins for the selected variables.

Usage:
  python iena_multicast_receiver.py [--group GROUP [GROUP ...]] [--port PORT]
                                    [--scan SECONDS] [--vars a,c,z]
                                    [--window SECONDS] [--iface IFACE]
                                    [--config PATH]

Examples:
  # Scan default group, pick a stream, plot first 3 variables from XidML
  python iena_multicast_receiver.py --config stream_config.xidml

  # Scan two groups
  python iena_multicast_receiver.py --group 239.1.1.1 239.1.1.2

  # Skip scan — connect directly to a known key
  python iena_multicast_receiver.py --key 0x0A01

Dependencies:
  pip install matplotlib numpy
"""

import argparse
import collections
import socket
import struct
import threading
import time
from xml.etree import ElementTree

import matplotlib.pyplot as plt
import matplotlib.animation as animation
import numpy as np


IENA_TRAILER = 0xDEAD
HEADER_SIZE = 16
TRAILER_SIZE = 2


def load_xidml(config_path: str) -> list[str]:
    """Parse a XidML file and return parameter names ordered by index."""
    tree = ElementTree.parse(config_path)
    root = tree.getroot()

    params = []
    for param in root.iter('Parameter'):
        name = param.get('name')
        index = int(param.get('index', str(len(params))))
        params.append((index, name))

    params.sort(key=lambda p: p[0])
    return [name for _, name in params]


# ---------------------------------------------------------------------------
# Packet parsing
# ---------------------------------------------------------------------------

def parse_iena_packet(data: bytes, num_params: int) -> dict | None:
    """Parse a raw IENA packet. Returns dict or None if malformed."""
    payload_size = num_params * 4
    expected_size = HEADER_SIZE + payload_size + TRAILER_SIZE

    if len(data) < expected_size:
        return None

    key, size_words, time_high, time_low, status, seq, n2 = struct.unpack_from(
        '>HH I HH HH', data, 0)

    trailer, = struct.unpack_from('>H', data, HEADER_SIZE + payload_size)
    if trailer != IENA_TRAILER:
        return None

    values = list(struct.unpack_from(f'>{num_params}f', data, HEADER_SIZE))

    return {
        'key': key,
        'size': size_words,
        'time_us': time_high,
        'status': status,
        'sequence': seq,
        'n_params': n2,
        'values': values,
    }


# ---------------------------------------------------------------------------
# Multicast socket helper
# ---------------------------------------------------------------------------

def create_multicast_socket(groups: list[str], port: int,
                            iface: str = '') -> socket.socket:
    """Create a UDP socket that joins the given multicast groups."""
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)

    # bind to all interfaces on the given port
    sock.bind(('', port))

    # join each multicast group
    iface_bytes = socket.inet_aton(iface) if iface else socket.inet_aton('0.0.0.0')
    for grp in groups:
        mreq = struct.pack('4s4s', socket.inet_aton(grp), iface_bytes)
        sock.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, mreq)

    return sock


# ---------------------------------------------------------------------------
# Stream discovery
# ---------------------------------------------------------------------------

def discover_streams(groups: list[str], port: int, duration: float,
                     iface: str = '', num_params: int = 26) -> dict:
    """
    Listen for `duration` seconds and return a dict of discovered streams:
      { (key, source_ip, group): { 'count': int, 'first': float, 'last': float } }
    """
    sock = create_multicast_socket(groups, port, iface)
    sock.settimeout(0.5)

    streams: dict[tuple, dict] = {}
    deadline = time.monotonic() + duration

    print(f"Scanning for IENA streams on {', '.join(groups)}:{port} "
          f"for {duration:.0f}s ...")

    while time.monotonic() < deadline:
        try:
            data, (src_ip, src_port) = sock.recvfrom(4096)
        except socket.timeout:
            continue

        pkt = parse_iena_packet(data, num_params)
        if pkt is None:
            continue

        # we don't know which group the packet came from at the socket
        # level, so we just record the source IP
        ident = (pkt['key'], src_ip)
        now = time.monotonic()
        if ident not in streams:
            streams[ident] = {'count': 0, 'first': now, 'last': now}
        streams[ident]['count'] += 1
        streams[ident]['last'] = now

    sock.close()
    return streams


def choose_stream(streams: dict) -> int | None:
    """Display discovered streams and let the user pick one. Returns the key."""
    if not streams:
        print("\nNo IENA streams found.")
        return None

    entries = []
    for (key, src_ip), info in sorted(streams.items()):
        elapsed = max(info['last'] - info['first'], 0.001)
        rate = info['count'] / elapsed
        entries.append((key, src_ip, info['count'], rate))

    print(f"\n{'#':>3}  {'Key':>8}  {'Source IP':<17}  {'Packets':>8}  {'Rate':>8}")
    print('-' * 55)
    for idx, (key, src_ip, count, rate) in enumerate(entries, 1):
        print(f"{idx:>3}  0x{key:04X}    {src_ip:<17}  {count:>8}  {rate:>7.1f}/s")

    print()
    while True:
        try:
            choice = input("Select stream number (or 'q' to quit): ").strip()
        except (EOFError, KeyboardInterrupt):
            return None
        if choice.lower() == 'q':
            return None
        try:
            idx = int(choice)
            if 1 <= idx <= len(entries):
                return entries[idx - 1][0]  # return key
        except ValueError:
            pass
        print(f"Please enter a number between 1 and {len(entries)}.")


# ---------------------------------------------------------------------------
# Filtered receiver (only keeps packets matching the chosen key)
# ---------------------------------------------------------------------------

class IenaMulticastReceiver:
    """Threaded multicast receiver that accumulates data for one IENA key."""

    def __init__(self, groups: list[str], port: int, key_filter: int,
                 window: float, param_names: list[str], iface: str = ''):
        self.groups = groups
        self.port = port
        self.key_filter = key_filter
        self.window = window
        self.param_names = param_names
        self.num_params = len(param_names)
        self.iface = iface
        self.lock = threading.Lock()

        maxlen = 10_000
        self.timestamps = collections.deque(maxlen=maxlen)
        self.series = {n: collections.deque(maxlen=maxlen) for n in self.param_names}

        self.packet_count = 0
        self._running = True

    def start(self):
        t = threading.Thread(target=self._listen, daemon=True)
        t.start()

    def _listen(self):
        sock = create_multicast_socket(self.groups, self.port, self.iface)
        sock.settimeout(1.0)
        print(f"Receiving stream key=0x{self.key_filter:04X} on "
              f"{', '.join(self.groups)}:{self.port} ...")

        while self._running:
            try:
                data, addr = sock.recvfrom(4096)
            except socket.timeout:
                continue

            pkt = parse_iena_packet(data, self.num_params)
            if pkt is None or pkt['key'] != self.key_filter:
                continue

            now = time.time()
            with self.lock:
                self.timestamps.append(now)
                for i, name in enumerate(self.param_names):
                    self.series[name].append(pkt['values'][i])
                self.packet_count += 1

        sock.close()

    def get_data(self, var_names: list[str]):
        """Return (relative_times, {name: values}) trimmed to the window."""
        with self.lock:
            if not self.timestamps:
                empty = np.array([])
                return empty, {n: empty for n in var_names}

            ts = np.array(self.timestamps)
            now = ts[-1]
            cutoff = now - self.window
            mask = ts >= cutoff
            rel = ts[mask] - now

            result = {}
            for name in var_names:
                arr = np.array(self.series[name])
                result[name] = arr[mask]

            return rel, result

    def stop(self):
        self._running = False


# ---------------------------------------------------------------------------
# Live plot
# ---------------------------------------------------------------------------

def run_live_plot(receiver: IenaMulticastReceiver, selected: list[str],
                  window: float):
    """Matplotlib live plot for the selected variables."""
    fig, ax = plt.subplots(figsize=(12, 6))
    fig.canvas.manager.set_window_title('IENA Multicast — Live Data')
    lines = {}
    colors = plt.cm.tab10.colors
    for i, name in enumerate(selected):
        line, = ax.plot([], [], label=name,
                        color=colors[i % len(colors)], linewidth=1.5)
        lines[name] = line

    ax.set_xlabel('Time (s)')
    ax.set_ylabel('Value')
    ax.set_title(f'IENA Stream 0x{receiver.key_filter:04X} — Live')
    ax.legend(loc='upper left')
    ax.grid(True, alpha=0.3)
    ax.set_xlim(-window, 0)

    def update(frame):
        rel_t, data = receiver.get_data(selected)
        for name in selected:
            if len(rel_t) > 0:
                lines[name].set_data(rel_t, data[name])
        ax.set_xlim(-window, 0)

        if any(len(data[n]) > 0 for n in selected):
            all_vals = np.concatenate(
                [data[n] for n in selected if len(data[n]) > 0])
            if len(all_vals) > 0:
                lo, hi = np.min(all_vals), np.max(all_vals)
                margin = max((hi - lo) * 0.1, 0.5)
                ax.set_ylim(lo - margin, hi + margin)

        status_text.set_text(f'Packets: {receiver.packet_count}')
        return list(lines.values()) + [status_text]

    status_text = ax.text(0.98, 0.98, '', transform=ax.transAxes,
                          ha='right', va='top', fontsize=9,
                          fontfamily='monospace',
                          bbox=dict(boxstyle='round,pad=0.3',
                                    facecolor='wheat', alpha=0.8))

    ani = animation.FuncAnimation(  # noqa: F841
        fig, update, interval=100, blit=False, cache_frame_data=False)

    try:
        plt.tight_layout()
        plt.show()
    except KeyboardInterrupt:
        pass


# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(
        description='IENA multicast receiver — discover & plot streams')
    parser.add_argument('--group', nargs='+', default=['239.1.1.1'],
                        help='Multicast group(s) to join (default: 239.1.1.1)')
    parser.add_argument('--port', type=int, default=5001,
                        help='UDP port (default: 5001)')
    parser.add_argument('--scan', type=float, default=5.0,
                        help='Discovery scan duration in seconds (default: 5)')
    parser.add_argument('--key', type=lambda x: int(x, 0), default=None,
                        help='Skip scan — directly receive this IENA key '
                             '(hex, e.g. 0x0A01)')
    parser.add_argument('--vars', default=None,
                        help='Comma-separated variables to plot (default: first 3 from config)')
    parser.add_argument('--window', type=float, default=30.0,
                        help='Scrolling plot window in seconds (default: 30)')
    parser.add_argument('--iface', default='',
                        help='Interface IP to use for multicast (default: OS choice)')
    parser.add_argument('--config', default=None,
                        help='Path to XidML config file for parameter names/count')
    args = parser.parse_args()

    # Determine parameter names from XidML or fall back to a-z
    if args.config:
        try:
            param_names = load_xidml(args.config)
            print(f"Loaded {len(param_names)} parameters from {args.config}: "
                  f"{', '.join(param_names)}")
        except Exception as e:
            parser.error(f"Failed to parse XidML config '{args.config}': {e}")
    else:
        param_names = [chr(ord('a') + i) for i in range(26)]

    num_params = len(param_names)

    # Determine which variables to plot
    if args.vars:
        selected = [v.strip() for v in args.vars.split(',')]
    else:
        selected = param_names[:3]

    for v in selected:
        if v not in param_names:
            parser.error(
                f"Unknown variable '{v}'. Choose from: {', '.join(param_names)}")

    # --- determine which stream key to use ---
    if args.key is not None:
        stream_key = args.key
        print(f"Skipping scan — using key 0x{stream_key:04X}")
    else:
        streams = discover_streams(args.group, args.port, args.scan,
                                   args.iface, num_params)
        stream_key = choose_stream(streams)
        if stream_key is None:
            print("No stream selected. Exiting.")
            return

    print(f"\nPlotting variables: {', '.join(selected)}")
    print(f"Window: {args.window}s | Groups: {', '.join(args.group)}:{args.port}")

    # --- start receiver & plot ---
    receiver = IenaMulticastReceiver(
        args.group, args.port, stream_key, args.window, param_names, args.iface)
    receiver.start()

    try:
        run_live_plot(receiver, selected, args.window)
    finally:
        receiver.stop()
        print(f"\nReceived {receiver.packet_count} packets total.")


if __name__ == '__main__':
    main()
