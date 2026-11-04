"""LAN discovery via mDNS/DNS-SD, compatible with Android NsdManager."""

import ipaddress
import socket

from zeroconf import ServiceInfo
from zeroconf.asyncio import AsyncZeroconf


class Discovery:
    def __init__(self):
        self.zeroconf = None
        self.info = None

    async def start(self, port):
        addresses = set()
        for item in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            address = item[4][0]
            parsed = ipaddress.ip_address(address)
            if parsed.is_private and not parsed.is_loopback:
                addresses.add(socket.inet_aton(address))
        if not addresses:
            return
        self.zeroconf = AsyncZeroconf()
        host = socket.gethostname().replace(".", "-")[:40]
        self.info = ServiceInfo(
            "_astrbot-connect._tcp.local.",
            f"AstrBot-{host}._astrbot-connect._tcp.local.",
            addresses=list(addresses), port=port,
            properties={"version": "0.1.2", "pairing": "pin"},
            server=f"{host}.local.",
        )
        await self.zeroconf.async_register_service(self.info, allow_name_change=True)

    async def close(self):
        if self.zeroconf:
            if self.info:
                await self.zeroconf.async_unregister_service(self.info)
            await self.zeroconf.async_close()
