package io.github.astrbot.androidconnect;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.util.concurrent.*;
import okhttp3.*;
import okio.ByteString;
import org.json.JSONObject;

/** A bounded decoder queue keeps network stalls from accumulating old frames. */
final class DesktopVideo implements AutoCloseable {
    interface Listener { void status(int width,int height,int fps); void error(String message); }
    private final OkHttpClient client=new OkHttpClient.Builder().connectTimeout(10,TimeUnit.SECONDS)
        .readTimeout(0,TimeUnit.SECONDS).pingInterval(10,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build();
    private final ArrayBlockingQueue<byte[]> packets=new ArrayBlockingQueue<>(16);
    private final ExecutorService decoder=Executors.newSingleThreadExecutor();
    private final Listener listener;private volatile boolean closed;private WebSocket socket;
    DesktopVideo(Prefs prefs,Surface surface,String session,int fps,int size,Listener listener) throws Exception {
        this.listener=listener;
        String base=Api.validateUrl(prefs.url(),prefs.allowPublicHttp());String token=prefs.token();
        if(token.length()<32)throw new Exception("请先填写连接密钥");
        String address=base+"/api/v1/desktop/video?session="+session+"&fps="+fps+"&size="+size;
        socket=client.newWebSocket(new Request.Builder().url(address).header("Authorization","Bearer "+token).build(),new WebSocketListener(){
            private boolean initialized;
            @Override public void onMessage(WebSocket ws,String text){
                if(closed)return;
                try{
                    JSONObject message=new JSONObject(text);
                    if(message.has("error")){fail(message.getString("error"));return;}
                    int w=message.getInt("width"),h=message.getInt("height");
                    if(initialized||!"h264".equals(message.getString("codec"))||w<2||h<2||w>1920||h>1920)throw new Exception("视频参数无效");
                    initialized=true;decoder.execute(()->decode(surface,w,h));
                }catch(Exception e){fail(e.getMessage());}
            }
            @Override public void onMessage(WebSocket ws,ByteString bytes){
                if(closed)return;
                if(!initialized||bytes.size()>2*1024*1024||!packets.offer(bytes.toByteArray()))fail("视频解码积压，请降低分辨率或帧率后重连");
            }
            @Override public void onFailure(WebSocket ws,Throwable t,Response response){
                if(closed)return;
                String message=response==null?"视频连接失败，请检查网络":response.code()==530?"Cloudflare 530：电脑隧道未连接":response.code()==403?"桌面授权已结束":response.code()==409?"旧视频流正在关闭，请稍后重新连接":"视频服务不可用（HTTP "+response.code()+"），请升级插件或选择截图兼容模式";
                fail(message);
            }
            @Override public void onClosed(WebSocket ws,int code,String reason){if(!closed)fail("视频连接已关闭");}
        });
    }
    private void decode(Surface surface,int width,int height){
        MediaCodec codec=null;
        try{
            codec=MediaCodec.createDecoderByType("video/avc");MediaFormat format=MediaFormat.createVideoFormat("video/avc",width,height);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,2*1024*1024);
            if(android.os.Build.VERSION.SDK_INT>=30)format.setInteger(MediaFormat.KEY_LOW_LATENCY,1);
            codec.configure(format,surface,null,0);codec.start();listener.status(width,height,0);
            MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();byte[] pending=null;long count=0,started=System.nanoTime(),lastFrame=started;
            while(!closed){
                if(pending==null)pending=packets.poll();
                if(pending!=null){int index=codec.dequeueInputBuffer(0);if(index>=0){ByteBuffer target=codec.getInputBuffer(index);if(target==null||target.capacity()<pending.length)throw new Exception("手机解码器不支持此画面尺寸");target.clear();target.put(pending);codec.queueInputBuffer(index,0,pending.length,System.nanoTime()/1000,0);pending=null;}}
                int out;while((out=codec.dequeueOutputBuffer(info,0))>=0){codec.releaseOutputBuffer(out,true);count++;lastFrame=System.nanoTime();}
                long now=System.nanoTime();if(now-started>=1_000_000_000L){listener.status(width,height,(int)(count*1_000_000_000L/(now-started)));count=0;started=now;}
                if(now-lastFrame>8_000_000_000L)throw new Exception("8 秒未解码到画面，请切换截图兼容模式");
                Thread.sleep(2);
            }
        }catch(Exception e){if(!closed)fail(e.getMessage()==null?"手机 H.264 解码失败":e.getMessage());}
        finally{if(codec!=null){try{codec.stop();}catch(Exception ignored){}codec.release();}}
    }
    private void fail(String message){if(closed)return;close();listener.error(message);}
    @Override public void close(){closed=true;if(socket!=null)socket.cancel();packets.clear();decoder.shutdownNow();client.dispatcher().executorService().shutdown();client.connectionPool().evictAll();}
}
