#!/usr/bin/env python3
"""
IENA Multicast Data Stream Sender

Sends IENA-format UDP packets to a multicast group so that any receiver
on the network can join the group and receive the stream — no specific
target IP address is needed.

Multiple senders can run simultaneously with different --key and/or
--group values so receivers can discover and choose between streams.

IENA Packet Structure (big-endian):
  Header (16 bytes):
    - Key        : uint16  — stream identifier
    - Size       : uint16  — total packet size in 16-bit words
    - Time_High  : uint32  — microseconds since midnight
    - Time_Low   : uint16  — sub-microsecond fraction
    - Status     : uint16  — status / endianity word
    - Sequence   : uint16  — packet sequence counter
    - N2         : uint16  — number of parameters in payload
  Payload:
    - N x float32 — one per variable (dynamic, based on --vars)
  Trailer (2 bytes):
    - 0xDEAD end-of-packet marker

On startup the sender writes a XidML metadata file describing the
stream's parameters so that receivers (including the Java GUI) can
discover variable names, units, and ranges without hardcoding.

Usage:
  python iena_multicast_sender.py [--group GROUP] [--port PORT] [--rate HZ]
                                  [--key KEY] [--ttl TTL] [--iface IFACE]
                                  [--vars NAMES] [--config-out PATH]

Examples:
  # Send stream with key 0x0A01 on default multicast group (a-z)
  python iena_multicast_sender.py

  # Send 3 custom variables
  python iena_multicast_sender.py --vars temperature,pressure,altitude --config-out /tmp/my_stream.xidml

  # Run a second stream with a different key and group
  python iena_multicast_sender.py --key 0x0B02 --group 239.1.1.2

Dependencies:
  (standard library only)
"""

import argparse
import random
import socket
import struct
import time
from datetime import datetime
from xml.dom.minidom import getDOMImplementation


IENA_TRAILER = 0xDEAD


def generate_xidml(var_names: list[str], iena_key: int, output_path: str):
    """Generate a XidML metadata file describing the stream parameters."""
    impl = getDOMImplementation()
    doc = impl.createDocument(None, 'XidML', None)
    root = doc.documentElement
    root.setAttribute('version', '3.0')

    instr = doc.createElement('Instrumentation')
    root.appendChild(instr)

    pkg = doc.createElement('Package')
    pkg.setAttribute('name', 'MyStream')
    pkg.setAttribute('ienaKey', f'0x{iena_key:04X}')
    instr.appendChild(pkg)

    param_set = doc.createElement('ParameterSet')
    pkg.appendChild(param_set)

    for idx, name in enumerate(var_names):
        param = doc.createElement('Parameter')
        param.setAttribute('name', name)
        param.setAttribute('index', str(idx))

        fmt_el = doc.createElement('DataFormat')
        fmt_el.appendChild(doc.createTextNode('Float32'))
        param.appendChild(fmt_el)

        range_max = idx + 1
        min_el = doc.createElement('RangeMinimum')
        min_el.appendChild(doc.createTextNode('0.0'))
        param.appendChild(min_el)

        max_el = doc.createElement('RangeMaximum')
        max_el.appendChild(doc.createTextNode(str(float(range_max))))
        param.appendChild(max_el)

        param_set.appendChild(param)

    xml_str = doc.toprettyxml(indent='  ', encoding='UTF-8')
    with open(output_path, 'wb') as f:
        f.write(xml_str)
    print(f"Wrote XidML config to {output_path} ({len(var_names)} parameters)")


def build_iena_packet(key: int, sequence: int, values: list[float],
                      num_params: int) -> bytes:
    """Build a single IENA packet with the given parameter values."""
    now = datetime.now()
    midnight = now.replace(hour=0, minute=0, second=0, microsecond=0)
    us_since_midnight = int((now - midnight).total_seconds() * 1_000_000)
    time_high = us_since_midnight & 0xFFFFFFFF
    time_low = 0

    payload = struct.pack(f'>{num_params}f', *values)

    total_bytes = 16 + len(payload) + 2
    size_words = total_bytes // 2

    status = 0x0000

    header = struct.pack('>HH I HH HH',
                         key,
                         size_words,
                         time_high,
                         time_low,
                         status,
                         sequence & 0xFFFF,
                         num_params)

    trailer = struct.pack('>H', IENA_TRAILER)

    return header + payload + trailer


def generate_random_values(num_params: int) -> list[float]:
    """Generate one random sample per variable. Range of var[i] is [0, i+1]."""
    return [random.uniform(0, i + 1) for i in range(num_params)]


def main():
    default_vars = ','.join(chr(ord('a') + i) for i in range(26))

    parser = argparse.ArgumentParser(
        description='IENA multicast data stream sender')
    parser.add_argument('--group', default='239.1.1.1',
                        help='Multicast group address (default: 239.1.1.1)')
    parser.add_argument('--port', type=int, default=5001,
                        help='Destination UDP port (default: 5001)')
    parser.add_argument('--rate', type=float, default=10.0,
                        help='Packets per second (default: 10)')
    parser.add_argument('--key', type=lambda x: int(x, 0), default=0x0A01,
                        help='IENA stream key (default: 0x0A01)')
    parser.add_argument('--ttl', type=int, default=1,
                        help='Multicast TTL / hop limit (default: 1)')
    parser.add_argument('--iface', default='',
                        help='Source interface IP for multicast (default: OS choice)')
    parser.add_argument('--vars', default=default_vars,
                        help='Comma-separated variable names (default: a through z)')
    parser.add_argument('--config-out', default='stream_config.xidml',
                        help='Path to write XidML config file (default: stream_config.xidml)')
    args = parser.parse_args()

    param_names = [v.strip() for v in args.vars.split(',') if v.strip()]
    num_params = len(param_names)

    if num_params == 0:
        parser.error('At least one variable name is required')

    # Generate XidML metadata file
    generate_xidml(param_names, args.key, args.config_out)

    # --- create UDP socket for multicast sending ---
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)

    # set multicast TTL (how many hops the packet can traverse)
    sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL,
                    struct.pack('b', args.ttl))

    # optionally bind to a specific interface
    if args.iface:
        sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_IF,
                        socket.inet_aton(args.iface))

    interval = 1.0 / args.rate
    sequence = 0

    print(f"IENA Multicast Sender")
    print(f"  Group : {args.group}:{args.port}")
    print(f"  Key   : 0x{args.key:04X}")
    print(f"  Rate  : {args.rate} Hz")
    print(f"  TTL   : {args.ttl}")
    if args.iface:
        print(f"  Iface : {args.iface}")
    print(f"  Vars  : {', '.join(f'{n}[0..{i+1}]' for i, n in enumerate(param_names))}")
    print(f"  Config: {args.config_out}")
    print("Press Ctrl+C to stop.\n")

    try:
        while True:
            t_start = time.monotonic()

            values = generate_random_values(num_params)
            packet = build_iena_packet(args.key, sequence, values, num_params)
            sock.sendto(packet, (args.group, args.port))

            if sequence % max(1, int(args.rate)) == 0:
                preview = ', '.join(
                    f'{n}={v:.2f}' for n, v in zip(param_names[:5], values[:5]))
                print(f"[seq {sequence:>6d}] {preview}, ...")

            sequence += 1

            elapsed = time.monotonic() - t_start
            if elapsed < interval:
                time.sleep(interval - elapsed)

    except KeyboardInterrupt:
        print(f"\nStopped after {sequence} packets.")
    finally:
        sock.close()


if __name__ == '__main__':
    main()
