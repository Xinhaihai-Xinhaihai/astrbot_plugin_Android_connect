"""Owner configuration editor, restricted to registered AstrBot objects."""

import asyncio
import copy
import hashlib
import json
import math
import time
from pathlib import Path

from aiohttp import web

from .config_labels import describe, groups
from .panel import PluginPanel


def serialized(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, allow_nan=False)


def revision(value):
    return hashlib.sha256(serialized(value).encode()).hexdigest()


def validate(value, old, schema=None, path="配置", depth=0):
    if depth > 24:
        raise ValueError("配置嵌套过深")
    schema = schema if isinstance(schema, dict) else {}
    kind = schema.get("type")
    expected = {"int": int, "float": (int, float), "bool": bool, "string": str,
                "text": str, "list": list, "template_list": list, "object": dict,
                "dict": dict}.get(kind, type(old) if old is not None else None)
    if value is None and (old is None or schema.get("nullable")):
        return
    if expected and (not isinstance(value, expected) or
                     (isinstance(value, bool) and expected is not bool)):
        raise ValueError(f"{path} 的值类型不正确")
    if isinstance(value, float) and not math.isfinite(value):
        raise ValueError(f"{path} 必须为有限数值")
    if isinstance(value, dict):
        for key, item in value.items():
            if not isinstance(key, str) or len(key) > 300:
                raise ValueError("配置键无效")
            child = schema.get("items", {}).get(key, {}) if isinstance(schema.get("items"), dict) else {}
            validate(item, old.get(key) if isinstance(old, dict) else None, child, f"{path}.{key}", depth + 1)
        if kind == "object" and isinstance(old, dict) and set(old) - set(value):
            raise ValueError(f"{path} 缺少已有字段，请保留未修改的配置")
    elif isinstance(value, list):
        for index, item in enumerate(value):
            items = schema.get("items")
            item_schema = {"type": items} if isinstance(items, str) else (
                items if isinstance(items.get("type"), str) else {"type": "object", "items": items}
            ) if isinstance(items, dict) else {}
            if kind == "template_list":
                if not isinstance(item, dict):
                    raise ValueError(f"{path}[{index}] 必须为模板对象")
                template = schema.get("templates", {}).get(item.get("__template_key") or item.get("template"))
                if not isinstance(template, dict):
                    raise ValueError(f"{path}[{index}] 缺少有效的模板选择")
                item_schema = {"type": "object", "items": template.get("items", {})}
            previous = None
            if not item_schema and isinstance(old, list) and old and all(type(x) is type(old[0]) for x in old):
                previous = old[0]
            validate(item, previous, item_schema, f"{path}[{index}]", depth + 1)
    choices = schema.get("options")
    if isinstance(choices, list) and choices and not isinstance(value, (list, dict)) and value not in choices:
        # Existing legacy values remain editable without forcing an unrelated migration.
        if value != old:
            raise ValueError(f"{path} 不在允许的选项中")


class ConfigPanel:
    self_locked = {"api_token", "listen_host", "listen_port", "allow_plugin_management",
                   "allow_config_management", "allow_desktop_control", "allow_shell",
                   "allow_phone_control", "enable_pairing"}
    persona_fields = ("system_prompt", "begin_dialogs", "tools", "skills", "custom_error_message")

    def __init__(self, context, directory, lock=None):
        self.context = context
        self.directory = Path(directory) / "config_backups"
        self.lock = lock or asyncio.Lock()

    def profiles(self):
        manager = getattr(self.context, "astrbot_config_mgr", None)
        profiles = getattr(manager, "confs", None)
        return profiles if isinstance(profiles, dict) else {"default": self.context.get_config()}

    async def listing(self):
        manager = getattr(self.context, "astrbot_config_mgr", None)
        names = getattr(manager, "abconf_data", None) or {}
        result = [{"kind": "global", "id": key,
                   "name": "默认全局配置" if key == "default" else names.get(key, {}).get("name", key)}
                  for key in self.profiles()]
        pm = getattr(self.context, "persona_manager", None)
        if pm and callable(getattr(pm, "get_all_personas", None)):
            result += [{"kind": "persona", "id": p.persona_id, "name": p.persona_id}
                       for p in await pm.get_all_personas()]
        return {"configs": result}

    async def resolve(self, kind, identifier):
        if not isinstance(identifier, str) or not identifier or len(identifier) > 200:
            raise ValueError("配置标识无效")
        if kind == "plugin":
            star = self.context.get_registered_star(identifier)
            if not star or getattr(star, "config", None) is None:
                raise web.HTTPNotFound(reason="插件没有注册配置")
            config = star.config
            locked = sorted(self.self_locked & set(config)) if PluginPanel.protected(star) else []
            return config, getattr(config, "schema", {}) or {}, locked, bool(getattr(star, "reserved", False))
        if kind == "global":
            config = self.profiles().get(identifier)
            if config is None:
                raise web.HTTPNotFound(reason="配置方案不存在")
            return config, self.global_schema(), ["dashboard"] if "dashboard" in config else [], False
        if kind == "persona":
            pm = getattr(self.context, "persona_manager", None)
            if not pm:
                raise web.HTTPServiceUnavailable(reason="当前 AstrBot 没有人格管理接口")
            persona = next((p for p in await pm.get_all_personas() if p.persona_id == identifier), None)
            if persona is None:
                raise web.HTTPNotFound(reason="人格不存在")
            values = {key: copy.deepcopy(getattr(persona, key, None)) for key in self.persona_fields}
            schema = {"system_prompt": {"type": "text", "description": "人格系统提示词"},
                      "begin_dialogs": {"type": "list", "items": "string"},
                      "tools": {"type": "list", "items": "string", "nullable": True},
                      "skills": {"type": "list", "items": "string", "nullable": True},
                      "custom_error_message": {"type": "text", "nullable": True}}
            return values, schema, [], False
        raise ValueError("未知配置类别")

    @staticmethod
    def global_schema():
        try:
            from astrbot.core.config.default import CONFIG_METADATA_2
            result = {}
            for group in CONFIG_METADATA_2.values():
                if isinstance(group, dict):
                    result.update(group.get("metadata", {}))
            return result
        except ImportError:
            return {}

    @staticmethod
    def snapshot(config):
        path = getattr(config, "config_path", None)
        if path and Path(path).is_file():
            if Path(path).stat().st_size > 512 * 1024:
                raise ValueError("配置超过 512 KB，请在电脑编辑")
            return json.loads(Path(path).read_text(encoding="utf-8-sig"))
        return copy.deepcopy(dict(config))

    async def read(self, kind, identifier):
        config, schema, locked, readonly = await self.resolve(kind, identifier)
        values = self.snapshot(config)
        # Desktop dashboard credentials / 2FA and bridge access controls stay on the PC.
        visible = {key: value for key, value in values.items() if key not in locked}
        if kind != "plugin":
            schema = describe(visible, schema)
        if kind == "global" and "provider_sources" in visible:
            model_schema = schema.get("provider", {})
            templates = model_schema.pop("config_template", {})
            for value in templates.values():
                if isinstance(value, dict):
                    value["enable"] = False
            schema["provider_sources"]["config_template"] = templates
            source_items = {**model_schema.get("items", {}), **schema["provider_sources"].get("items", {})}
            schema["provider_sources"]["items"] = source_items
            model_schema["config_template"] = {"聊天模型": {
                "id": "new_model", "provider_source_id": "", "model": "", "enable": False,
                "max_context_tokens": 32768, "modalities": ["text"], "custom_extra_body": {}}
            }
        return {"kind": kind, "id": identifier, "values": visible, "schema": schema,
                "groups": groups(visible) if kind == "global" else [],
                "revision": revision(values), "locked": locked, "readonly": readonly,
                "notice": "保存全局配置后请重启 AstrBot，使模型和平台等设置完整生效。" if kind == "global"
                else "保存人格后立即更新。" if kind == "persona"
                else "保存后可重载第三方插件；Android Connect 自身需在电脑端重载。"}

    async def save(self, kind, identifier, values, expected):
        async with self.lock:
            config, schema, locked, readonly = await self.resolve(kind, identifier)
            if readonly:
                raise web.HTTPForbidden(reason="内置插件配置请在电脑端管理")
            old = self.snapshot(config)
            if not isinstance(expected, str) or expected != revision(old):
                raise web.HTTPConflict(reason="电脑配置已经变化，请重新读取后再编辑，避免覆盖他人的修改")
            if not isinstance(values, dict) or len(serialized(values).encode()) > 512 * 1024:
                raise ValueError("配置必须为不超过 512 KB 的 JSON 对象")
            if set(values) != set(old) - set(locked):
                raise ValueError("请保留全部已有配置项；连接权限和控制台登录设置需在电脑编辑")
            for key, value in values.items():
                validate(value, old.get(key), schema.get(key, {}), key)
            merged = {**old, **copy.deepcopy(values)}
            self.directory.mkdir(parents=True, exist_ok=True)
            prefix = hashlib.sha256(f"{kind}:{identifier}".encode()).hexdigest()[:16]
            backup = self.directory / f"{prefix}-{time.time_ns()}.json"
            backup.write_text(serialized(old), encoding="utf-8")
            if kind == "persona":
                await self.context.persona_manager.update_persona(identifier, **values)
            else:
                # Use AstrBot's writer instead of accepting paths from the phone.
                try:
                    config.save_config(merged)
                except Exception:
                    config.clear()
                    config.update(old)
                    raise
            for previous in sorted(self.directory.glob(f"{prefix}-*.json"), reverse=True)[10:]:
                previous.unlink()
            result = await self.read(kind, identifier)
            result["saved"] = True
            result["reload_required"] = kind != "persona"
            return result

    async def reload(self, name):
        async with self.lock:
            star = self.context.get_registered_star(name) if isinstance(name, str) else None
            if not star:
                raise web.HTTPNotFound(reason="插件不存在")
            if PluginPanel.protected(star):
                raise web.HTTPForbidden(reason="请在电脑端重载此插件")
            if not star.activated:
                return {"reloaded": False, "message": "插件处于停用状态，下次启用时读取新配置"}
            result = await self.context._star_manager.reload(name)
            if isinstance(result, tuple) and not result[0]:
                raise web.HTTPConflict(reason="配置已保存，但插件重载失败，请检查电脑端日志")
            return {"reloaded": True, "message": "插件已重载"}
