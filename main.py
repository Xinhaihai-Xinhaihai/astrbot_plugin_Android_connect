import secrets

from astrbot.api import AstrBotConfig
from astrbot.api.star import Context, Star, StarTools

from .bridge.agent import AgentBackend
from .bridge.desktop import Desktop
from .bridge.service import BridgeService


class AndroidConnectPlugin(Star):
    def __init__(self, context: Context, config: AstrBotConfig):
        super().__init__(context)
        self.config = config
        self.bridge = None

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
        self.logger.info("Android Connect 已启动；连接密钥在插件配置或 %s", directory / "token.txt")

    async def terminate(self):
        if self.bridge:
            await self.bridge.close()
            self.bridge = None
