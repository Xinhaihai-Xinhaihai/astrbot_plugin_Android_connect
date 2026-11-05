"""Chinese presentation labels; configuration keys and stored values remain untouched."""

import copy
import re

LABELS = dict(line.split("|", 1) for line in """
provider_sources|模型服务商
provider|模型列表
provider_settings|对话与模型调用
platform|消息平台
platform_settings|消息收发
platform_specific|平台专用设置
agent_runner|Agent 运行器
subagent_orchestrator|子 Agent 协作
provider_stt_settings|语音识别
provider_tts_settings|语音合成
provider_ltm_settings|群聊上下文与主动回复
content_safety|内容审核
admins_id|管理员账号
wake_prefix|唤醒前缀
t2i|将回复转换为图片
t2i_word_threshold|转为图片的文字长度阈值
t2i_strategy|图片渲染方式
t2i_endpoint|远程图片渲染服务地址
t2i_use_file_service|通过文件服务发送图片
t2i_active_template|当前图片模板
http_proxy|HTTP 代理地址
no_proxy|不经过代理的地址
timezone|时区
callback_api_base|公网回调地址
log_level|日志级别
log_file_enable|将日志保存到文件
log_file_path|日志文件路径
log_file_max_mb|单个日志文件大小上限（MB）
temp_dir_max_size|临时目录大小上限
trace_enable|记录调用追踪
trace_db_path|追踪数据库路径
trace_db_max_mb|追踪数据库大小上限（MB）
trace_retention_days|追踪记录保留天数
pip_install_arg|依赖安装附加参数
pypi_index_url|Python 软件源地址
default_kb_collection|默认知识库
kb_names|启用的知识库
kb_fusion_top_k|融合检索候选条数
kb_final_top_k|最终检索结果条数
kb_agentic_mode|由 Agent 选择知识库检索
persona|默认人格
persona_pool|可用人格
provider_pool|可用模型
plugin_set|此配置启用的插件集合
disable_builtin_commands|禁用的内置命令
disable_metrics|关闭匿名使用统计
config_version|配置格式版本
enable|启用
enabled|启用
id|标识
name|名称
type|类型
provider_type|服务能力类型
provider_source_id|所属模型服务商
model|模型名称
api_base|API 基础地址
api_key|API 密钥
key|API 密钥列表
custom_headers|自定义请求头
custom_extra_body|附加请求参数
timeout|请求超时（秒）
proxy|代理地址
max_context_tokens|上下文令牌数上限
modalities|支持的输入类型
reasoning|推理设置
effort|推理强度
unique_session|隔离不同用户的会话
rate_limit|消息速率限制
rate_limit.time|限流时间窗口（秒）
rate_limit.count|窗口内允许的消息数
rate_limit.strategy|超出限制时的处理方式
no_permission_reply|无权限时发送提示
empty_mention_waiting|仅提及机器人时等待后续消息
empty_mention_waiting_need_reply|等待后续消息时发送提示
friend_message_needs_wake_prefix|私聊也需要唤醒前缀
ignore_bot_self_message|忽略机器人自身发送的消息
ignore_at_all|忽略提及全体成员的消息
segmented_reply|分段回复
only_llm_result|仅对模型回复进行分段
interval_method|分段发送间隔的计算方式
interval|分段发送间隔
log_base|间隔计算的对数底数
words_count_threshold|分段文字长度阈值
regex|分段匹配规则
content_cleanup_rule|分段内容清理规则
reply_prefix|回复前缀
forward_threshold|合并转发阈值
enable_id_white_list|启用账号白名单
id_whitelist|账号白名单
id_whitelist_log|记录白名单拦截日志
wl_ignore_admin_on_group|群聊中的管理员不受白名单限制
wl_ignore_admin_on_friend|私聊中的管理员不受白名单限制
reply_with_mention|回复时提及发送者
reply_with_quote|回复时引用原消息
path_mapping|文件路径映射
also_use_in_response|同时审核回复内容
baidu_aip|百度内容审核
secret_key|服务密钥
internal_keywords|本地关键词过滤
extra_keywords|附加过滤关键词
web_search|启用联网搜索
web_search_link|在回复中显示搜索来源链接
display_reasoning_text|显示模型推理内容
identifier|向模型提供用户身份信息
group_name_display|向模型提供群聊名称
datetime_system_prompt|向模型提供当前日期和时间
prompt_prefix|附加提示词前缀
streaming_response|流式回复
show_tool_use_status|显示工具调用状态
show_tool_call_result|显示工具调用结果
buffer_intermediate_messages|缓冲中间回复
unsupported_streaming_strategy|平台不支持流式回复时的处理方式
file_extract|文件内容提取
moonshotai_api_key|Moonshot API 密钥
proactive_capability|主动执行能力
add_cron_tools|向 Agent 提供定时任务工具
runner_type|运行器类型
config|运行器参数
provider_id|使用的模型标识
dual_output|同时发送语音和文字
use_file_service|通过文件服务发送音频
trigger_probability|语音回复触发概率
group_icl_enable|启用群聊上下文学习
group_message_max_cnt|群聊上下文消息数上限
image_caption|将图片转为文字描述
image_caption_provider_id|图片描述使用的模型
image_caption_prompt|图片描述提示词
group_message_history_enable|记录群聊消息历史
group_message_history_max_cnt|群聊历史消息数上限
active_reply|主动回复
whitelist|白名单
method|触发方式
possibility_reply|主动回复概率
system_prompt|系统提示词
begin_dialogs|预设对话
tools|允许使用的工具
skills|允许使用的技能
custom_error_message|自定义错误提示
main_enable|启用子 Agent 协作
agents|子 Agent 列表
remove_main_duplicate_tools|从主 Agent 移除重复工具
router_system_prompt|任务分配提示词
computer_use_runtime|电脑操作运行环境
computer_use_require_admin|电脑操作仅允许管理员使用
computer_use_local_permissions|本机电脑操作权限
sandbox|沙箱环境
dashboard|电脑控制台设置
description|说明
""".strip().splitlines())

GROUPS = [
    ("models", "模型服务", ["provider_sources", "provider"]),
    ("chat", "对话与 Agent", ["provider_settings", "agent_runner", "subagent_orchestrator", "persona"]),
    ("platforms", "消息平台", ["platform", "platform_settings", "platform_specific", "wake_prefix"]),
    ("voice", "语音", ["provider_stt_settings", "provider_tts_settings"]),
    ("memory", "群聊与记忆", ["provider_ltm_settings"]),
    ("knowledge", "知识库", ["default_kb_collection", "kb_names", "kb_fusion_top_k", "kb_final_top_k", "kb_agentic_mode"]),
    ("safety", "权限与内容审核", ["admins_id", "content_safety", "plugin_set", "disable_builtin_commands"]),
    ("rendering", "回复图片", ["t2i", "t2i_word_threshold", "t2i_strategy", "t2i_endpoint", "t2i_use_file_service", "t2i_active_template"]),
    ("system", "系统与日志", ["http_proxy", "no_proxy", "timezone", "callback_api_base", "log_level", "log_file_enable",
                             "log_file_path", "log_file_max_mb", "temp_dir_max_size", "trace_enable", "trace_db_path",
                             "trace_db_max_mb", "trace_retention_days", "pip_install_arg", "pypi_index_url", "disable_metrics"]),
]


def groups(values):
    result, used = [], set()
    for identifier, name, keys in GROUPS:
        available = [key for key in keys if key in values]
        if available:
            result.append({"id": identifier, "name": name, "keys": available})
            used.update(available)
    remaining = [key for key in values if key not in used]
    if remaining:
        result.append({"id": "other", "name": "其他配置", "keys": remaining})
    return result


def label(path):
    parts = path.split(".")
    return LABELS.get(".".join(parts[-2:]), LABELS.get(parts[-1], parts[-1]))


def describe(values, schema, prefix=""):
    result = copy.deepcopy(schema) if isinstance(schema, dict) else {}
    for key, value in values.items():
        path = f"{prefix}.{key}" if prefix else key
        meta = result.setdefault(key, {})
        title = meta.get("description", "")
        if not re.search(r"[\u4e00-\u9fff]", title):
            meta["description"] = label(path)
        if re.search(r"token|secret|password|api_key|^key$|authorization", key, re.I):
            meta["secret"] = True
        if isinstance(value, dict):
            meta["items"] = describe(value, meta.get("items", {}), path)
        elif isinstance(value, list):
            objects = {}
            for item in value:
                if isinstance(item, dict):
                    objects.update(item)
            if objects:
                meta["items"] = describe(objects, meta.get("items", {}), path)
    return result
