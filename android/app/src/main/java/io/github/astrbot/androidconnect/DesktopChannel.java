package io.github.astrbot.androidconnect;

import java.util.concurrent.TimeUnit;
import okhttp3.*;
import org.json.JSONObject;

/** Ordered, bounded input stream; stalled connections never replay saved movement. */
final class DesktopChannel implements AutoCloseable {
    interface Listener {void ready(JSONObject cursor);void cursor(JSONObject cursor);void error(String text);}
    private final OkHttpClient client=new OkHttpClient.Builder().connectTimeout(8,TimeUnit.SECONDS)
        .readTimeout(0,TimeUnit.SECONDS).pingInterval(10,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build();
    private final Listener listener;
    private WebSocket socket;
    private volatile boolean closed,ready;
    private long sent,acknowledged,lastAcknowledged=System.nanoTime(),lastSent;
    DesktopChannel(Prefs prefs,String session,Listener listener)throws Exception {
        this.listener=listener;
        String token=prefs.token();if(token.length()<32)throw new Exception("请先填写连接密钥");
        socket=client.newWebSocket(new Request.Builder().url(Api.validateUrl(prefs.url(),prefs.allowPublicHttp())+"/api/v1/desktop/input?session="+session)
            .header("Authorization","Bearer "+token).build(),new WebSocketListener(){
            @Override public void onMessage(WebSocket ws,String text){
                if(closed)return;
                try{
                    JSONObject data=new JSONObject(text);
                    if(data.has("error")){fail(data.getString("error"));return;}
                    if(data.optBoolean("ready")){
                        synchronized(DesktopChannel.this){if(ready)throw new Exception();ready=true;lastAcknowledged=System.nanoTime();}
                        listener.ready(data.optJSONObject("cursor"));return;
                    }
                    synchronized(DesktopChannel.this){
                        long sequence=data.getLong("sequence");
                        if(sequence<=acknowledged||sequence>sent)throw new Exception();
                        acknowledged=sequence;lastAcknowledged=System.nanoTime();
                    }
                    listener.cursor(data.optJSONObject("cursor"));
                }catch(Exception e){fail("电脑返回的输入确认无效，请重新连接");}
            }
            @Override public void onFailure(WebSocket ws,Throwable error,Response response){
                fail(response==null?"实时输入连接中断，请检查网络":response.code()==403?"电脑控制授权已结束":response.code()==530?"Cloudflare 隧道未连接（530）":"实时输入连接失败（HTTP "+response.code()+"）");
            }
            @Override public void onClosed(WebSocket ws,int code,String reason){fail("实时输入连接已关闭");}
        });
    }
    synchronized void flush(DesktopInput input,String session)throws Exception{
        if(closed||!ready)return;
        long now=System.nanoTime();
        if(sent>acknowledged&&now-lastAcknowledged>1_500_000_000L)throw new Exception("网络长时间未确认鼠标输入，已暂停控制");
        if(sent-acknowledged>=8)return;
        if(!input.pending()&&now-lastSent<250_000_000L)return;
        if(socket.queueSize()>8192)throw new Exception("输入网络积压，请重新连接");
        if(!socket.send(input.packet(session,++sent).toString()))throw new Exception("输入连接已关闭");
        lastSent=now;
    }
    private void fail(String message){if(closed)return;close();listener.error(message);}
    @Override public void close(){closed=true;ready=false;if(socket!=null)socket.cancel();client.dispatcher().executorService().shutdown();client.connectionPool().evictAll();}
}
