package io.github.astrbot.androidconnect;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.text.InputType;
import android.widget.*;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ConnectionWizard {
    private final Activity activity;
    private final Prefs prefs;
    private final Api api;
    private final Runnable connected;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private NsdManager manager;
    private NsdManager.DiscoveryListener listener;
    private boolean discovering, resolving, dismissed;
    private final ArrayDeque<NsdServiceInfo> pending = new ArrayDeque<>();
    private final HashSet<String> found = new HashSet<>();
    private LinearLayout results;
    ConnectionWizard(Activity activity, Prefs prefs, Runnable connected) {
        this.activity=activity;this.prefs=prefs;this.api=new Api(prefs);this.connected=connected;
    }
    private LinearLayout column() {
        LinearLayout l=new LinearLayout(activity);l.setOrientation(LinearLayout.VERTICAL);l.setPadding(28,16,28,16);return l;
    }
    private void text(LinearLayout box,String value) {TextView t=new TextView(activity);t.setText(value);t.setTextSize(15);t.setPadding(0,8,0,12);box.addView(t);}
    private void button(LinearLayout box,String title,Runnable action) {Button b=new Button(activity);b.setText(title);b.setAllCaps(false);b.setOnClickListener(v->action.run());box.addView(b);}
    private EditText field(LinearLayout box,String hint,String value) {EditText e=new EditText(activity);e.setSingleLine();e.setHint(hint);e.setText(value);box.addView(e);return e;}
    private AlertDialog dialog(String title,LinearLayout content) {
        ScrollView scroll=new ScrollView(activity);scroll.addView(content);
        return new AlertDialog.Builder(activity).setTitle(title).setView(scroll).setNegativeButton("关闭",null).create();
    }
    void lan() {
        LinearLayout box=column();
        text(box,"1  电脑与手机连接同一个 Wi-Fi\n2  电脑安装插件，监听地址设为 0.0.0.0\n3  点击下方发现的电脑，输入电脑配对码");
        text(box,"正在搜索电脑…（mDNS 自动发现）");results=column();box.addView(results);
        button(box,"没搜到？手动输入电脑地址",()->pairPrompt("局域网配对",prefs.get("lan_url","http://192.168.1.10:8765"),"lan_url"));
        button(box,"查看电脑端准备步骤",()->info("电脑端准备",
            "① AstrBot 插件页：安装本项目，确保 AstrBot 已配置聊天模型。\n\n② 插件配置：listen_host 填 0.0.0.0，端口保持 8765，开启局域网自动发现，保存并重载。\n\n③ Windows 防火墙：允许 AstrBot/Python 在专用网络通信；发现使用 UDP 5353，连接使用 TCP 8765。\n\n④ 在插件配置开启“生成一次手机配对码”，保存重载后到日志查看 6 位码。也可管理员私聊 /android_pair。\n\n⑤ 配对码 2 分钟内有效，仅可使用一次。手机选中电脑后输入即可。\n\n搜索不到时：关闭路由器访客网络/AP 隔离，检查防火墙；仍不行可用 ipconfig 查看电脑 IPv4 地址并手动填写。"));
        AlertDialog dialog=dialog("局域网 · 发现电脑",box);dialog.setOnDismissListener(d->stopDiscovery());dialog.show();startDiscovery();
    }
    private void startDiscovery() {
        dismissed=false;manager=activity.getSystemService(NsdManager.class);
        listener=new NsdManager.DiscoveryListener(){
            public void onDiscoveryStarted(String type){discovering=true;}
            public void onServiceFound(NsdServiceInfo service){activity.runOnUiThread(()->{pending.add(service);resolveNext();});}
            public void onServiceLost(NsdServiceInfo service){}
            public void onDiscoveryStopped(String type){discovering=false;}
            public void onStartDiscoveryFailed(String type,int code){activity.runOnUiThread(()->text(results,"自动搜索未启动，请手动输入电脑地址。"));}
            public void onStopDiscoveryFailed(String type,int code){discovering=false;}
        };
        try {manager.discoverServices("_astrbot-connect._tcp.",NsdManager.PROTOCOL_DNS_SD,listener);}
        catch(Exception e){text(results,"系统不允许自动发现，可使用手动地址配对。");}
    }
    private void resolveNext() {
        if(resolving||pending.isEmpty()||dismissed)return;resolving=true;
        manager.resolveService(pending.remove(),new NsdManager.ResolveListener(){
            public void onResolveFailed(NsdServiceInfo service,int code){activity.runOnUiThread(()->{resolving=false;resolveNext();});}
            public void onServiceResolved(NsdServiceInfo service){activity.runOnUiThread(()->{
                resolving=false;
                if(!dismissed&&service.getHost()!=null){String host=service.getHost().getHostAddress();
                    if(host!=null&&!host.contains(":")){String address="http://"+host+":"+service.getPort();
                        if(found.add(address))button(results,service.getServiceName()+"\n"+address,()->pairPrompt("与电脑配对",address,"lan_url"));}}
                resolveNext();
            });}
        });
    }
    private void stopDiscovery(){dismissed=true;if(manager!=null&&listener!=null)try{manager.stopServiceDiscovery(listener);}catch(Exception ignored){}}
    void remote() {
        LinearLayout box=column();
        text(box,"通过 Cloudflare Tunnel，手机在外也能连接家中电脑。推荐使用 HTTPS 隧道；也可在路由器转发 TCP 8765，并显式启用公网 IPv4 HTTP 直连。");
        button(box,"快速试用：临时 HTTPS 地址",()->info("临时隧道 · 适合首次测试",
            "1. 先让插件在电脑启动，端口默认 8765。\n\n2. Windows PowerShell 安装 cloudflared：\nwinget install --id Cloudflare.cloudflared --exact\n\n3. 重新打开 PowerShell，运行：\ncloudflared tunnel --url http://127.0.0.1:8765\n\n4. 终端会显示 https://随机名称.trycloudflare.com，复制到手机下方地址框。终端需保持运行。\n\n5. 电脑插件生成一次配对码，手机点击“使用配对码连接”。\n\n临时地址会随重启变化，不保证持续可用。夜间长期使用请选择固定域名隧道。"));
        button(box,"长期使用：固定域名隧道",()->info("固定域名 · 适合长期远程",
            "1. 已有域名（阿里云等）：在 dash.cloudflare.com 进入 Domains → Onboard a domain，填根域名，选择 Free。\n\n2. 将 Cloudflare 分配的两条名称服务器复制到域名注册商的“DNS 服务器”设置，等待 Cloudflare 显示 Active。域名仍在原平台续费，无须重新购买。\n\n3. Cloudflare 控制台进入 Networking → Tunnels，创建隧道；旧界面可能为 Networks → Connectors。选择 Windows，按控制台命令安装 cloudflared；确认 Healthy。\n\n4. 隧道内进入 Routes → Add route → Published application。子域名填 astrbot，选择自己的域名，Path 留空。\n\n5. Service URL 填 http://127.0.0.1:8765；若分两栏，类型 HTTP，地址 127.0.0.1:8765。安装服务时按系统提示使用管理员 PowerShell。\n\n6. 手机填写 https://astrbot.你的域名，再用电脑新生成的配对码连接。\n\n隧道安装令牌不填进手机。此地址转发插件接口；本版本使用插件配对/密钥鉴权，不支持 Cloudflare Access 浏览器登录页。"));
        text(box,"公网 IP 直连：路由器把 TCP 8765 转发到电脑局域网 IPv4，Windows 防火墙放行 TCP 8765；先在设置页启用“允许公网 IPv4 使用 HTTP”，再在这里填 http://公网IP:8765。公网 HTTP 会明文传输聊天、连接密钥、截图和遥控输入；能用 Tunnel 或 HTTPS 时应优先使用。");
        button(box,"使用远程地址 + 配对码连接",()->pairPrompt("远程配对",prefs.get("remote_url","https://"),"remote_url"));
        button(box,"连接失败排查",()->info("远程连接排查",
            "502：确认 AstrBot 插件已启动，隧道服务地址与端口正确。\n\n1033/隧道离线：检查 cloudflared 是否在运行，Cloudflare 控制台是否显示 Healthy。\n\n返回网页而不是插件数据：检查地址是否指向插件 8765 端口，以及是否出现 Cloudflare Access 登录页。\n\n配对码失效：电脑重新生成，2 分钟内输入。尝试超过 5 次会关闭配对窗口。\n\n已经配对但 401：电脑连接密钥可能被更换，请重新配对。\n\n手机可联网但无响应：检查 Tunnel 域名是否可达，以及电脑是否进入睡眠。"));
        dialog("Cloudflare · 远程连接",box).show();
    }
    private void pairPrompt(String title,String address,String profile) {
        LinearLayout box=column();text(box,"在电脑插件配置开启“生成一次手机配对码”，从 AstrBot 日志查看 6 位码；也可管理员私聊 /android_pair。配对成功会保存插件生成的连接密钥；GitHub PAT 不需要，也不要填到手机。");
        EditText url=field(box,"电脑地址",address);EditText code=field(box,"6 位一次性配对码","");code.setInputType(InputType.TYPE_CLASS_NUMBER);
        TextView progress=new TextView(activity);box.addView(progress);
        AlertDialog dialog=dialog(title,box);
        button(box,"配对并保存",()->{
            String endpoint=url.getText().toString().trim(),pin=code.getText().toString().trim();String normalized;
            try{normalized=Api.validateUrl(endpoint,prefs.allowPublicHttp());}
            catch(Exception e){progress.setText(e.getMessage());return;}
            progress.setText("正在配对…");
            worker.execute(()->{try{
                org.json.JSONObject result=api.pair(normalized,pin,prefs.allowPublicHttp());String token=result.getString("token");
                if(token.length()<32)throw new Exception("电脑返回了无效密钥");
                PhoneControlService.disableLocally(activity);prefs.token(token);prefs.put("url",normalized);prefs.put(profile,normalized);
                activity.runOnUiThread(()->{dialog.dismiss();stopDiscovery();connected.run();Toast.makeText(activity,"配对成功",Toast.LENGTH_SHORT).show();});
            }catch(Exception e){activity.runOnUiThread(()->progress.setText(e.getMessage()));}});
        });dialog.show();
    }
    private void info(String title,String message){LinearLayout box=column();text(box,message);dialog(title,box).show();}
}
