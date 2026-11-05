"""Small owner panel using AstrBot's plugin lifecycle manager."""

import asyncio

from aiohttp import web


class PluginPanel:
    def __init__(self, context):
        self.context = context
        self.lock = asyncio.Lock()

    def manager(self):
        manager = getattr(self.context, "_star_manager", None)
        if not manager or not all(callable(getattr(manager, key, None)) for key in
                                  ("turn_on_plugin", "turn_off_plugin")):
            raise web.HTTPServiceUnavailable(reason="当前 AstrBot 不支持插件管理接口，请升级电脑端")
        return manager

    @staticmethod
    def protected(star):
        return bool(getattr(star, "reserved", False)) or any(
            str(getattr(star, field, "")).lower() == "astrbot_plugin_android_connect"
            for field in ("name", "root_dir_name")
        )

    def listing(self):
        self.manager()
        return {"plugins": [{
            "name": star.name,
            "display_name": getattr(star, "display_name", None) or star.name,
            "description": str(getattr(star, "desc", "") or "")[:2000],
            "version": str(getattr(star, "version", "") or ""),
            "author": str(getattr(star, "author", "") or ""),
            "config_available": getattr(star, "config", None) is not None,
            "enabled": bool(star.activated), "protected": self.protected(star),
        } for star in self.context.get_all_stars() if star.name]}

    async def change(self, name, enabled):
        if not isinstance(name, str) or not name or len(name) > 200 or type(enabled) is not bool:
            raise ValueError("插件名称或开关格式不正确")
        async with self.lock:
            star = self.context.get_registered_star(name)
            if not star:
                raise web.HTTPNotFound(reason="插件不存在，请刷新列表")
            if self.protected(star):
                raise web.HTTPForbidden(reason="连接插件和 AstrBot 内置插件需在电脑端管理")
            if bool(star.activated) != enabled:
                method = self.manager().turn_on_plugin if enabled else self.manager().turn_off_plugin
                await method(name)
            return self.listing()
