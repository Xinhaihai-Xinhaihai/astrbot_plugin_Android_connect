import secrets

from astrbot.api import AstrBotConfig
from astrbot.api.event import AstrMessageEvent, filter
from astrbot.api.star import Context, Star, StarTools

from .bridge.agent import AgentBackend
from .bridge.desktop import Desktop
from .bridge.discovery import Discovery
from .bridge.service import BridgeService


class AndroidConnectPlugin(Star):
    def __init__(self, context: Context, config: AstrBotConfig):
        super().__init__(context)
        self.config = config
        self.bridge = None
        self.discovery = None

    async def initialize(self):
        directory = StarTools.get_data_dir("astrbot_plugin_Android_connect")
        if not self.config.get("api_token"):
            self.config["api_token"] = secrets.token_urlsafe(32)
            self.config.save_config()
        if len(self.config["api_token"]) < 32:
            raise ValueError("手机连接密钥必须至少包含 32 个字符")
        (directory / "token.txt").write_text(self.config["api_token"], encoding="utf-8")
        backend = AgentBackend(self.context, self.config, Desktop())
        service = BridgeService(self.config, directory, backend, backend.manual_desktop)
        await service.start()
        self.bridge = service
        if self.config.get("enable_pairing", False):
            code = service.begin_pairing()
            self.logger.info("Android Connect 配对码：%s（2 分钟内一次有效）", code)
            self.config["enable_pairing"] = False
            self.config.save_config()
        if self.config.get("enable_lan_discovery", True) and self.config.get(
            "listen_host", "127.0.0.1"
        ) in ("0.0.0.0", "::"):
            self.discovery = Discovery()
            try:
                await self.discovery.start(int(self.config.get("listen_port", 8765)))
            except Exception:
                self.logger.exception("局域网发现未启动；仍可手动填写地址连接")
        self.logger.info("Android Connect 已启动；连接密钥在插件配置或 %s", directory / "token.txt")

    async def terminate(self):
        if self.discovery:
            await self.discovery.close()
            self.discovery = None
        if self.bridge:
            await self.bridge.close()
            self.bridge = None

    @filter.permission_type(filter.PermissionType.ADMIN)
    @filter.command("android_pair")
    async def pair_phone(self, event: AstrMessageEvent):
        if not event.is_private_chat():
            yield event.plain_result("请在管理员私聊中使用此命令，配对码不发送到群聊。")
            return
        if not self.bridge:
            yield event.plain_result("手机连接服务尚未启动。")
            return
        code = self.bridge.begin_pairing()
        yield event.plain_result(f"手机配对码：{code}。2 分钟内一次有效，请在 APK 中输入。")
