package io.github.astrbot.androidconnect;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private final int BG = Color.rgb(245,246,250), INK = Color.rgb(28,35,57), MUTED = Color.rgb(112,121,143), BLUE = Color.rgb(82,103,232);
    private final Handler handler = new Handler();
    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private Prefs prefs; private Api api;
    private LinearLayout root, body, navigation, feed;
    private TextView statusText;
    private EditText message;
    private Button send;
    private CheckBox useDesktop, usePhone;
    private String page = "聊天", draft = "", lastFeed = "";
    private boolean connected, refreshing, posting, resumed, desktopWanted, phoneWanted;
    private JSONObject status = new JSONObject();
    private JSONArray jobs = new JSONArray();
    private final Runnable refreshLoop = new Runnable() {
        @Override public void run() { if (resumed) { refresh(false); handler.postDelayed(this, 3500); } }
    };
    interface Work { JSONObject run() throws Exception; }
    interface Done { void accept(JSONObject result) throws Exception; }
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        prefs = new Prefs(this); api = new Api(prefs);
        page = prefs.url().isEmpty() ? "设置" : "聊天";
        if (saved != null) { draft = saved.getString("draft", ""); page = saved.getString("page", page); }
        show(page);
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        if (message != null) draft = message.getText().toString();
        out.putString("draft", draft); out.putString("page", page); super.onSaveInstanceState(out);
    }
    @Override protected void onResume() { super.onResume(); resumed = true; handler.post(refreshLoop); }
    @Override protected void onPause() { resumed = false; handler.removeCallbacks(refreshLoop); super.onPause(); }
    @Override protected void onDestroy() { executor.shutdownNow(); super.onDestroy(); }
    private int dp(int value) { return (int)(value * getResources().getDisplayMetrics().density + .5f); }
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private TextView text(String value, int size, int color) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color); t.setLineSpacing(dp(3),1); return t;
    }
    private GradientDrawable background(int color, int radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d;
    }
    private Button button(String title, Runnable click) {
        Button b = new Button(this); b.setText(title); b.setAllCaps(false); b.setTextColor(BLUE); b.setTextSize(14);
        b.setMinHeight(dp(48)); b.setOnClickListener(v -> click.run()); return b;
    }
    private LinearLayout card(LinearLayout parent, String title, String subtitle) {
        LinearLayout card = column(); card.setPadding(dp(18),dp(18),dp(18),dp(18)); card.setBackground(background(Color.WHITE,18));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1,-2); lp.bottomMargin = dp(14); parent.addView(card,lp);
        if (!title.isEmpty()) { TextView t=text(title,18,INK); t.setTypeface(null,Typeface.BOLD); card.addView(t); }
        if (!subtitle.isEmpty()) { TextView t=text(subtitle,13,MUTED); t.setPadding(0,dp(7),0,dp(9)); card.addView(t); }
        return card;
    }
    private EditText field(LinearLayout parent, String label, String value, String hint, boolean secret) {
        parent.addView(text(label,13,MUTED)); EditText f = new EditText(this); f.setSingleLine(true); f.setTextSize(15);
        f.setText(value); f.setHint(hint); f.setTextColor(INK);
        f.setInputType(secret ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        parent.addView(f,new LinearLayout.LayoutParams(-1,dp(54))); return f;
    }
    private void show(String selected) {
        if (message != null) draft=message.getText().toString();
        message=null; feed=null; page=selected; lastFeed="";
        root=column(); root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v,insets)-> {
            root.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout top=column(); top.setPadding(dp(22),dp(18),dp(22),dp(12));
        TextView brand=text("ASTRBOT  /  CONNECT",11,BLUE); brand.setLetterSpacing(.12f); top.addView(brand);
        LinearLayout titleRow=new LinearLayout(this); titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=text(page,28,INK); title.setTypeface(null,Typeface.BOLD); titleRow.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        statusText=text(connected?"● 已连接":"○ 未连接",12,connected?Color.rgb(26,148,115):MUTED); titleRow.addView(statusText);
        top.addView(titleRow); root.addView(top);
        body=column(); body.setPadding(dp(18),0,dp(18),0); root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        switch(page) { case "聊天": chat(); break; case "任务": tasks(); break; case "控制": controls(); break; default: settings(); }
        navigation=new LinearLayout(this); navigation.setPadding(dp(8),dp(4),dp(8),dp(4)); navigation.setBackgroundColor(Color.WHITE);
        for(String item:new String[]{"聊天","任务","控制","设置"}) {
            Button tab=button(item,()->show(item)); tab.setTextColor(page.equals(item)?BLUE:MUTED);
            tab.setBackground(background(page.equals(item)?Color.rgb(237,239,255):Color.WHITE,12));
            navigation.addView(tab,new LinearLayout.LayoutParams(0,dp(54),1));
        }
        root.addView(navigation); setContentView(root); root.requestApplyInsets();
    }
    private LinearLayout scrolling() {
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        LinearLayout content=column(); scroll.addView(content); body.addView(scroll,new LinearLayout.LayoutParams(-1,-1)); return content;
    }
    private void chat() {
        LinearLayout intro=card(body,"把想做的事，交给 AstrBot","手机发送 · 电脑执行 · 随时回来查看结果");
        LinearLayout permissionRow=new LinearLayout(this);
        useDesktop=new CheckBox(this); useDesktop.setText("本次可操作电脑"); useDesktop.setTextSize(12); useDesktop.setChecked(desktopWanted);
        usePhone=new CheckBox(this); usePhone.setText("本次可操作手机"); usePhone.setTextSize(12); usePhone.setChecked(phoneWanted);
        permissionRow.addView(useDesktop,new LinearLayout.LayoutParams(0,-2,1)); permissionRow.addView(usePhone,new LinearLayout.LayoutParams(0,-2,1)); intro.addView(permissionRow);
        useDesktop.setOnCheckedChangeListener((v,c)->desktopWanted=c); usePhone.setOnCheckedChangeListener((v,c)->phoneWanted=c);
        ScrollView scroll=new ScrollView(this); feed=column(); scroll.addView(feed); body.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        renderFeed();
        LinearLayout composer=column(); composer.setPadding(0,dp(8),0,dp(8));
        message=new EditText(this); message.setTextSize(15); message.setMinLines(2); message.setMaxLines(5); message.setGravity(Gravity.TOP);
        message.setHint("例如：整理电脑里的项目，完成后告诉我结果…"); message.setText(draft);
        message.setBackground(background(Color.WHITE,14)); message.setPadding(dp(14),dp(12),dp(14),dp(12)); composer.addView(message);
        LinearLayout actions=new LinearLayout(this); actions.addView(button("新对话",()->{
            prefs.newSession(); jobs=new JSONArray(); lastFeed=""; renderFeed();
        }),new LinearLayout.LayoutParams(0,dp(48),1));
        send=button("发送任务",this::submit); send.setBackground(background(BLUE,12)); send.setTextColor(Color.WHITE); send.setEnabled(!posting);
        actions.addView(send,new LinearLayout.LayoutParams(0,dp(48),1)); composer.addView(actions); body.addView(composer);
    }
    private void tasks() {
        LinearLayout content=scrolling();
        LinearLayout summary=card(content,"执行记录","任务保存在电脑端；手机断开不会取消电脑任务。");
        summary.addView(button("刷新记录",()->refresh(true)));
        feed=column(); content.addView(feed); renderFeed();
    }
    private String stateName(String state) {
        return switch(state) {case "queued"->"等待执行";case "running"->"正在执行";case "completed"->"已完成";case "cancelled"->"已停止";case "interrupted"->"重启中断";default->"执行失败";};
    }
    private String when(double timestamp) { return new SimpleDateFormat("MM-dd HH:mm",Locale.CHINA).format(new Date((long)(timestamp*1000))); }
    private void renderFeed() {
        if(feed==null) return;
        String signature=page+jobs.toString(); if(signature.equals(lastFeed)) return; lastFeed=signature; feed.removeAllViews();
        String session=prefs.id("session"); int shown=0;
        for(int step=0;step<jobs.length();step++) {
            int index=page.equals("聊天")?jobs.length()-1-step:step;
            JSONObject job=jobs.optJSONObject(index); if(job==null) continue;
            if(page.equals("聊天")&&!session.equals(job.optString("session"))) continue;
            shown++;
            String state=job.optString("state");
            LinearLayout c=card(feed,page.equals("聊天")?"你":"任务 · "+stateName(state),when(job.optDouble("created")));
            TextView prompt=text(job.optString("prompt"),15,INK); prompt.setTextIsSelectable(true); c.addView(prompt);
            TextView badge=text(stateName(state)+(job.optInt("control")==1?"  ·  电脑":"")+(job.optInt("phone")==1?"  ·  手机":""),12,BLUE);
            badge.setPadding(0,dp(12),0,dp(8)); c.addView(badge);
            String reply=job.optString("result"); if(reply.isEmpty()) reply=job.optString("error");
            if(!reply.isEmpty()) {TextView r=text(reply,14,INK); r.setTextIsSelectable(true); c.addView(r);}
            c.addView(button("查看操作记录",()->details(job.optString("id"))));
            if(state.equals("queued")||state.equals("running")) c.addView(button("停止这个任务",()->call(()->api.call("POST","/jobs/"+job.optString("id")+"/cancel",Api.json()),r->refresh(true))));
        }
        if(shown==0) card(feed,page.equals("聊天")?"从一句话开始":"暂时没有任务","连接电脑后，发送第一条消息。你可以先只聊天，再按需开启设备控制。");
    }
    private void details(String id) {
        call(()->api.call("GET","/jobs/"+id,null),r->{
            StringBuilder report=new StringBuilder(stateName(r.optString("state"))+"\n\n");
            JSONArray events=r.optJSONArray("events");
            if(events!=null)for(int i=events.length()-1;i>=0;i--){JSONObject e=events.getJSONObject(i);report.append(when(e.optDouble("created"))).append("  ").append(e.optString("action")).append("\n").append(e.optString("detail")).append("\n\n");}
            new AlertDialog.Builder(this).setTitle("操作记录").setMessage(report.toString()).setPositiveButton("关闭",null).show();
        });
    }
    private void submit() {
        String prompt=message.getText().toString().trim(); if(prompt.isEmpty()||posting)return;
        JSONObject job=Api.json("id",UUID.randomUUID().toString(),"session",prefs.id("session"),"prompt",prompt,
            "control",desktopWanted,"phone",phoneWanted,"device",prefs.id("device"));
        // Preserve the exact request ID across a timeout and an explicit retry.
        try {String pending=prefs.get("pending",""); if(!pending.isEmpty()) {
            JSONObject old=new JSONObject(pending);
            if(old.optString("prompt").equals(prompt)&&old.optString("session").equals(prefs.id("session"))
               &&old.optBoolean("control")==desktopWanted&&old.optBoolean("phone")==phoneWanted)job=old;
        }}catch(Exception ignored){}
        prefs.put("pending",job.toString()); final JSONObject request=job; posting=true; send.setEnabled(false);
        executor.execute(()->{
            try {api.call("POST","/jobs",request);runOnUiThread(()->{
                posting=false;prefs.put("pending","");draft="";if(message!=null)message.setText("");if(send!=null)send.setEnabled(true);refresh(true);
            });}catch(Exception e){runOnUiThread(()->{posting=false;if(send!=null)send.setEnabled(true);error(e.getMessage()+"\n消息已保留，再次发送相同内容会使用原任务 ID。");});}
        });
    }
    private void controls() {
        LinearLayout content=scrolling();
        LinearLayout desktop=card(content,"电脑控制",status.optBoolean("desktop_enabled")?"授权至 "+when(status.optDouble("control_until")):"当前关闭 · 开启后可让 Agent 使用键鼠与截图");
        desktop.addView(text(status.optBoolean("desktop_allowed")?"电脑端总开关已允许":"先在 AstrBot 插件配置开启电脑控制总开关",13,MUTED));
        desktop.addView(button("开启限时电脑控制",()->chooseHours("电脑授权时长",hours->call(
            ()->api.call("POST","/control",Api.json("enabled",true,"hours",hours)),r->{status=r;show("控制");}))));
        desktop.addView(button("关闭电脑控制",()->call(()->api.call("POST","/control",Api.json("enabled",false)),r->{status=r;show("控制");})));
        desktop.addView(button("打开电脑遥控面板",this::remoteDesktop));
        LinearLayout phone=card(content,"手机控制",PhoneControlService.enabled()?"授权至 "+when(PhoneControlService.authorizedUntil/1000.0):"当前关闭 · Agent 可读取界面、点击、滑动与输入");
        phone.addView(text(PhoneControlService.connectionState,13,MUTED));
        phone.addView(button("开启限时手机控制",this::enablePhone));
        phone.addView(button("关闭手机控制",()->{PhoneControlService.disableLocally(this);call(
            ()->api.call("POST","/devices/"+prefs.id("device")+"/disable",Api.json()),r->show("控制"));show("控制");}));
        phone.addView(button("查看本机操作记录",()->new AlertDialog.Builder(this).setTitle("手机操作记录").setMessage(prefs.get("phone_log","暂无记录")).setPositiveButton("关闭",null).show()));
        LinearLayout stop=card(content,"紧急停止","撤销电脑授权，停止当前与排队任务，并立即关闭本机手机控制。");
        Button b=button("停止所有任务与设备控制",()->{
            PhoneControlService.disableLocally(this);
            call(()->api.call("POST","/stop",Api.json()),r->{desktopWanted=false;phoneWanted=false;refresh(true);});
        });b.setTextColor(Color.rgb(198,57,72));stop.addView(b);
    }
    interface Hours {void selected(int hours);}
    private void chooseHours(String title,Hours callback) {
        new AlertDialog.Builder(this).setTitle(title).setItems(new String[]{"1 小时","2 小时","4 小时","8 小时"},(d,index)->callback.selected(new int[]{1,2,4,8}[index])).setNegativeButton("取消",null).show();
    }
    private void enablePhone() {
        if(PhoneAccessibilityService.instance==null){
            new AlertDialog.Builder(this).setTitle("授权手机无障碍服务")
                .setMessage("开启后，AstrBot 可以读取非密码界面、点击、滑动和输入。只在你在控制页开启的时限内生效，通知栏可随时停止。请在系统设置里手动启用“AstrBot 手机控制”。")
                .setPositiveButton("前往系统设置",(d,w)->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))).setNegativeButton("取消",null).show();return;
        }
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},10);error("请允许通知后再开启，通知栏会提供停止按钮。");return;}
        chooseHours("手机授权时长",hours->{
            startForegroundService(new Intent(this,PhoneControlService.class).putExtra("hours",hours));
            handler.postDelayed(()->show("控制"),300);
        });
    }
    private void settings() {
        LinearLayout content=scrolling();
        LinearLayout connect=card(content,"连接电脑","局域网填写电脑 IP；外出使用 Cloudflare Tunnel 的 HTTPS 地址。");
        EditText url=field(connect,"服务器地址",prefs.url(),"http://192.168.1.10:8765",false);
        String savedToken="";try{savedToken=prefs.token();}catch(Exception ignored){}
        EditText token=field(connect,"连接密钥",savedToken,"从 AstrBot 插件配置复制",true);
        connect.addView(button("保存并测试连接",()->{
            try{
                String normalized=Api.validateUrl(url.getText().toString());String secret=token.getText().toString().trim();
                if(secret.length()<32)throw new Exception("连接密钥至少需要 32 个字符");
                PhoneControlService.disableLocally(this); prefs.put("url",normalized);prefs.token(secret); connected=false;refresh(true);
            }catch(Exception e){error(e.getMessage());}
        }));
        LinearLayout guide=card(content,"连接方式","首版支持局域网 + Cloudflare Tunnel");
        guide.addView(text("局域网\n电脑插件 listen_host 设为 0.0.0.0，手机与电脑连同一 Wi-Fi。\n\nCloudflare Tunnel\n将隧道转发到 http://127.0.0.1:8765，手机填写隧道的 HTTPS 地址。\n\n两个地址使用同一连接密钥；切换地址后请重新开启手机控制。",14,INK));
        LinearLayout access=card(content,"授权与隐私","电脑、手机与 PowerShell 各有独立总开关，默认关闭。");
        access.addView(button("打开手机无障碍设置",()->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        access.addView(text("密钥使用 Android Keystore 加密保存。聊天和任务记录保存在电脑端。截图与界面文本可能发送给 AstrBot 配置的模型。手机锁屏或系统授权界面不可被 Agent 绕过。",13,MUTED));
        card(content,"AstrBot Connect 0.1.0","Android 8.0+ · Windows AstrBot 4.29.4+\n项目：Xinhaihai-Xinhaihai / astrbot_plugin_Android_connect");
    }
    private void remoteDesktop() {
        if(!status.optBoolean("desktop_enabled")){error("请先开启电脑控制");return;}
        LinearLayout panel=column();panel.setPadding(dp(12),dp(8),dp(12),dp(8));
        TextView hint=text("刷新截图后，点击画面即可点击电脑对应位置。",13,MUTED);panel.addView(hint);
        ImageView image=new ImageView(this);image.setAdjustViewBounds(true);image.setScaleType(ImageView.ScaleType.FIT_CENTER);panel.addView(image,new LinearLayout.LayoutParams(-1,dp(260)));
        final int[] dims={0,0}; final Bitmap[] picture={null};
        Runnable capture=()->call(()->api.call("POST","/desktop",Api.json("action","screenshot")),r->{
            byte[] data=Base64.decode(r.getString("image"),Base64.DEFAULT);picture[0]=BitmapFactory.decodeByteArray(data,0,data.length);image.setImageBitmap(picture[0]);dims[0]=r.getInt("width");dims[1]=r.getInt("height");
        });
        image.setOnTouchListener((v,event)->{
            if(event.getAction()!=MotionEvent.ACTION_UP||picture[0]==null)return true;
            float scale=Math.min((float)image.getWidth()/picture[0].getWidth(),(float)image.getHeight()/picture[0].getHeight());
            float left=(image.getWidth()-picture[0].getWidth()*scale)/2,top=(image.getHeight()-picture[0].getHeight()*scale)/2;
            float nx=(event.getX()-left)/(picture[0].getWidth()*scale),ny=(event.getY()-top)/(picture[0].getHeight()*scale);
            if(nx>=0&&ny>=0&&nx<1&&ny<1)call(()->api.call("POST","/desktop",Api.json("action","click","arguments",Api.json("x",(int)(nx*dims[0]),"y",(int)(ny*dims[1])))),r->capture.run());return true;
        });
        panel.addView(button("刷新电脑画面",capture));
        EditText input=field(panel,"向电脑输入文字","","支持中文",false);
        panel.addView(button("输入到电脑",()->call(()->api.call("POST","/desktop",Api.json("action","type_text","arguments",Api.json("text",input.getText().toString()))),r->capture.run())));
        panel.addView(button("发送 Enter",()->call(()->api.call("POST","/desktop",Api.json("action","hotkey","arguments",Api.json("keys",new JSONArray().put("enter")))),r->capture.run())));
        ScrollView scroll=new ScrollView(this);scroll.addView(panel);new AlertDialog.Builder(this).setTitle("电脑遥控").setView(scroll).setNegativeButton("关闭",null).show();capture.run();
    }
    private void refresh(boolean explicit) {
        if(refreshing||prefs.url().isEmpty())return;refreshing=true;
        executor.execute(()->{
            try {JSONObject s=api.call("GET","/status",null);JSONArray j=api.call("GET","/jobs",null).getJSONArray("jobs");
                runOnUiThread(()->{refreshing=false;connected=true;status=s;jobs=j;if(statusText!=null){statusText.setText("● 已连接");statusText.setTextColor(Color.rgb(26,148,115));}renderFeed();if(explicit){if(page.equals("控制"))show("控制");else Toast.makeText(this,"连接成功，记录已更新",Toast.LENGTH_SHORT).show();}});
            }catch(Exception e){runOnUiThread(()->{refreshing=false;connected=false;if(statusText!=null){statusText.setText("○ 未连接");statusText.setTextColor(MUTED);}if(explicit)error(e.getMessage());});}
        });
    }
    private void call(Work work, Done done) {
        executor.execute(()->{try{JSONObject result=work.run();runOnUiThread(()->{try{done.accept(result);}catch(Exception e){error(e.getMessage());}});}catch(Exception e){runOnUiThread(()->error(e.getMessage()));}});
    }
    private void error(String message) {if(!isFinishing())new AlertDialog.Builder(this).setTitle("提示").setMessage(message).setPositiveButton("知道了",null).show();}
}
