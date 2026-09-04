#!/usr/bin/env python3
import socket
import json
import time
import math
import os
from datetime import datetime, timezone

def _load_secrets():
    sec = {}
    path = os.path.join(os.path.dirname(__file__), "..", "secrets.properties")
    if os.path.exists(path):
        with open(path, "r") as f:
            for line in f:
                if "=" in line and not line.strip().startswith("#"):
                    k, v = line.strip().split("=", 1)
                    sec[k.strip()] = v.strip()
    return sec

_sec = _load_secrets()
TARGET_MAC = _sec.get("WIZ_MAC", "")
WIZ_PORT = 38899
DEFAULT_IP = _sec.get("WIZ_IP", "192.168.1.100")
DEFAULT_LAT = float(_sec.get("CIRCADIAN_LAT", "51.5074"))
DEFAULT_LON = float(_sec.get("CIRCADIAN_LON", "-0.1278"))

def discover_tv_strip(timeout=1.5):
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    sock.settimeout(timeout)
    msg = json.dumps({'method': 'getSystemConfig', 'params': {}}).encode('utf-8')
    sock.sendto(msg, ('255.255.255.255', WIZ_PORT))
    
    start = time.time()
    while time.time() - start < timeout:
        try:
            data, addr = sock.recvfrom(2048)
            resp = json.loads(data.decode('utf-8', errors='ignore'))
            mac = resp.get('result', {}).get('mac', '').lower().replace(':', '')
            if TARGET_MAC and mac == TARGET_MAC:
                sock.close()
                return addr[0]
        except socket.timeout:
            break
        except Exception:
            pass
    sock.close()
    return DEFAULT_IP

def calculate_solar_circadian(dt=None, lat=DEFAULT_LAT, lon=DEFAULT_LON):
    if dt is None:
        dt = datetime.now()
    lat = lat
    lon = lon
    day_of_year = dt.timetuple().tm_yday
    utc_dt = dt.astimezone(timezone.utc)
    utc_hour = utc_dt.hour + utc_dt.minute / 60.0 + utc_dt.second / 3600.0
    gamma = 2.0 * math.pi / 365.0 * (day_of_year - 1 + (utc_hour - 12.0) / 24.0)
    
    eqtime = 229.18 * (0.000075 + 0.001868 * math.cos(gamma) - 0.032077 * math.sin(gamma)
                       - 0.014615 * math.cos(2 * gamma) - 0.040849 * math.sin(2 * gamma))
    decl = (0.006918 - 0.399912 * math.cos(gamma) + 0.070257 * math.sin(gamma)
            - 0.006758 * math.cos(2 * gamma) + 0.000907 * math.sin(2 * gamma)
            - 0.002697 * math.cos(3 * gamma) + 0.00148 * math.sin(3 * gamma))
    
    time_offset = eqtime + 4.0 * lon
    tst = (utc_hour * 60.0 + time_offset) % 1440.0
    ha = math.radians((tst / 4.0) - 180.0)
    
    lat_rad = math.radians(lat)
    sin_elev = math.sin(lat_rad) * math.sin(decl) + math.cos(lat_rad) * math.cos(decl) * math.cos(ha)
    elevation = math.degrees(math.asin(max(-1.0, min(1.0, sin_elev))))
    
    local_hour = dt.hour + dt.minute / 60.0
    if elevation > 5.0:
        temp = 6500
        dimming = 80
    elif elevation >= -6.0:
        factor = (elevation - (-6.0)) / (5.0 - (-6.0))
        temp = int(3200 + (6500 - 3200) * factor)
        dimming = int(45 + (80 - 45) * factor)
    elif elevation >= -18.0:
        factor = (elevation - (-18.0)) / ((-6.0) - (-18.0))
        temp = int(2400 + (3200 - 2400) * factor)
        dimming = int(30 + (45 - 30) * factor)
    else:
        if local_hour >= 23.0 or local_hour < 6.0:
            temp = 2200
            dimming = 18
        else:
            temp = 2400
            dimming = 30
    return elevation, temp, dimming

def send_wiz(ip, payload):
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.sendto(json.dumps(payload).encode('utf-8'), (ip, WIZ_PORT))
    sock.close()

if __name__ == '__main__':
    print("🔍 Discovering WiZ TV Strip...")
    ip = discover_tv_strip()
    print(f"✅ WiZ TV Strip active at: {ip}\n")
    
    now = datetime.now()
    elev, temp, dim = calculate_solar_circadian(now)
    print(f"🌍 Solar Calculation ({now.strftime('%H:%M:%S')}):")
    print(f"   • Sun Elevation: {elev:.2f}°")
    print(f"   • Calibrated Color Temp: {temp}K")
    print(f"   • Optimal Dimming: {dim}%\n")
    
    print("🎮 Simulating Launcher Navigation (D-Pad App Switch)...")
    apps = [
        ("Netflix", (229, 9, 20)),
        ("Spotify", (30, 215, 96)),
        ("YouTube", (255, 0, 0)),
        ("Plex", (229, 160, 13)),
        ("Apple TV", (180, 180, 190)),
        ("Xbox Series X", (16, 124, 16))
    ]
    
    for name, (r, g, b) in apps:
        print(f"   👉 Focused tile: {name:14s} (RGB: {r},{g},{b})")
        send_wiz(ip, {'method': 'setPilot', 'params': {'state': True, 'r': r, 'g': g, 'b': b, 'dimming': 75}})
        time.sleep(1.0)
    
    print("\n🎬 App Launched / Outside Launcher -> Applying Solar Circadian Bias Light...")
    send_wiz(ip, {'method': 'setPilot', 'params': {'state': True, 'temp': temp, 'dimming': dim}})
    print("✨ TV is now in scientific bias lighting mode (fatigue reduction + maximum contrast)!")
