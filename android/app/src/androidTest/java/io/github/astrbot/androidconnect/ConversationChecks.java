package io.github.astrbot.androidconnect;

import android.app.Instrumentation;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.RadioButton;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Exercise conversation switching against a delayed local HTTP response. */
final class ConversationChecks {
    private static void check(boolean condition, String message) {
        if(!condition)throw new IllegalStateException(message);
    }
    private static View find(View view, String text, boolean input) {
        if(input&&view instanceof EditText)return view;
        if(!input&&view instanceof TextView&&text.contentEquals(((TextView)view).getText()))return view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++) {
            View match=find(((ViewGroup)view).getChildAt(i),text,input);if(match!=null)return match;
        }
        return null;
    }
    private static View view(MainActivity activity, String text) { return find(activity.getWindow().getDecorView(),text,false); }
    private static EditText input(MainActivity activity) { return (EditText)find(activity.getWindow().getDecorView(),"",true); }
    private static void click(MainActivity activity, String label) {
        View button=view(activity,label);check(button instanceof Button,"Missing button: "+label);button.performClick();
    }
    private static boolean composerVisible(MainActivity app) {
        Rect inputArea=new Rect(),sendArea=new Rect(),navigationArea=new Rect();
        return input(app).getGlobalVisibleRect(inputArea)
            &&view(app,"发送任务").getGlobalVisibleRect(sendArea)
            &&view(app,"聊天").getGlobalVisibleRect(navigationArea)
            &&inputArea.height()>20&&sendArea.height()>20&&sendArea.bottom<=navigationArea.top;
    }
    private static void awaitUi(Instrumentation test, BooleanSupplier condition, String error) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<deadline) {
            AtomicBoolean ready=new AtomicBoolean();
            test.runOnMainSync(()->ready.set(condition.getAsBoolean()));
            if(ready.get())return;
            Thread.sleep(100);
        }
        throw new IllegalStateException(error);
    }
    static void run(Instrumentation test) throws Exception {
        Prefs prefs=new Prefs(test.getTargetContext());
        String oldUrl=prefs.url(),oldToken=prefs.token(),oldSession=prefs.id("session");
        String oldStarted=prefs.get("session_started",""),oldPending=prefs.get("pending","");
        MainActivity activity=null;
        try(MockBridge server=new MockBridge(oldSession)) {
            prefs.put("url","http://127.0.0.1:"+server.port());prefs.token("instrumentation-only-token-000000000000");
            activity=(MainActivity)test.startActivitySync(new Intent(test.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            MainActivity app=activity;
            awaitUi(test,()->view(app,"旧对话的测试消息")!=null,"Original conversation did not load");
            test.runOnMainSync(()->{
                input(app).setText("旧草稿");click(app,"新对话");
                check(!oldSession.equals(prefs.id("session")),"New conversation reused the original session");
                check(input(app).getText().length()==0,"Old draft was not cleared");
                check(input(app).hasFocus(),"New conversation did not focus the composer");
                check(view(app,"新对话已创建")!=null,"New conversation has no visible feedback");
                check(view(app,"旧对话的测试消息")==null,"Old messages leaked into the new conversation");
                click(app,"任务");
                check(view(app,"旧对话的测试消息")!=null,"New conversation erased cached task history");
                click(app,"聊天");input(app).setText("测试新对话");click(app,"发送任务");
            });
            check(server.submitted.await(10,TimeUnit.SECONDS),"No message reached the bridge");
            check(prefs.id("session").equals(server.request.getString("session")),"Message used the wrong conversation");
            check(server.request.getInt("repeat_count")==1,"Default task mode was not one");
            String activeSession=prefs.id("session");
            Thread.sleep(350);
            awaitUi(test,()->composerVisible(app),"Composer is hidden or overlaps navigation while keyboard is open");
            test.runOnMainSync(()->{
                check(!view(app,"新对话").isEnabled(),"Conversation switch was enabled while submitting");
                for(String mode:new String[]{"1 次","5 次","10 次","一直"})
                    check(!view(app,mode).isEnabled(),"Repeat mode changed during submission");
                click(app,"新对话");
                check(activeSession.equals(prefs.id("session")),"Conversation changed while submitting");
                input(app).setText("发送期间写的新草稿");
                click(app,"设置");click(app,"聊天");
                check(!view(app,"新对话").isEnabled(),"Reopening chat unlocked an in-flight conversation");
            });
            server.release.countDown();
            awaitUi(test,()->view(app,"发送任务").isEnabled()&&view(app,"模拟回复")!=null,"Submission did not finish");
            test.runOnMainSync(()->{
                check("发送期间写的新草稿".contentEquals(input(app).getText()),"Delayed response erased the next draft");
                check(view(app,"新对话").isEnabled(),"New conversation stayed disabled after submission");
                click(app,"新对话");
                check(view(app,"新对话已创建")!=null,"Repeated new conversation has no visible feedback");
                check(view(app,"模拟回复")==null,"Previous response leaked into another conversation");
            });
            for(int count:new int[]{5,10,0}) {
                int before=server.submissions;
                test.runOnMainSync(()->{
                    check(((RadioButton)view(app,"1 次")).isChecked(),"Next task did not default to one");
                    click(app,count==0?"一直":count+" 次");
                    input(app).setText("测试次数 "+count);click(app,"发送任务");
                });
                awaitUi(test,()->server.submissions>before&&view(app,"发送任务").isEnabled(),"Repeat mode did not submit");
                check(server.request.getInt("repeat_count")==count,"Wrong repeat count reached the bridge");
                String progress=count==0?"已完成 2 次 · 一直":"已完成 "+count+" / "+count+" 次";
                awaitUi(test,()->view(app,progress)!=null,"Repeat progress did not render");
                test.runOnMainSync(()->check(((RadioButton)view(app,"1 次")).isChecked(),"Successful send did not reset to one"));
                if(count==0) {
                    test.waitForIdleSync();Thread.sleep(300);
                    test.runOnMainSync(()->{
                        check(view(app,"停止这个任务")!=null,"Waiting loop has no stop button");
                        try {
                            View decor=app.getWindow().getDecorView();
                            Bitmap bitmap=Bitmap.createBitmap(decor.getWidth(),decor.getHeight(),Bitmap.Config.ARGB_8888);
                            decor.draw(new Canvas(bitmap));
                            File dir=new File(test.getTargetContext().getExternalFilesDir(null),"screenshots");dir.mkdirs();
                            try(FileOutputStream output=new FileOutputStream(new File(dir,"次数模式.png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,output);}
                        } catch(Exception e){throw new RuntimeException(e);}
                        click(app,"停止这个任务");
                    });
                    awaitUi(test,()->server.cancellations==1&&view(app,"已停止")!=null,"Loop cancellation did not reach the bridge");
                }
            }
        } finally {
            if(activity!=null){MainActivity app=activity;test.runOnMainSync(app::finish);}
            prefs.put("url",oldUrl);prefs.token(oldToken);prefs.put("session",oldSession);
            prefs.put("session_started",oldStarted);prefs.put("pending",oldPending);
        }
    }
    private static final class MockBridge implements AutoCloseable {
        private final ServerSocket listener;
        private final ExecutorService workers=Executors.newCachedThreadPool();
        final CountDownLatch submitted=new CountDownLatch(1),release=new CountDownLatch(1);
        volatile JSONObject request;
        volatile int submissions,cancellations;
        private volatile JSONArray jobs;
        MockBridge(String session) throws Exception {
            jobs=new JSONArray().put(Api.json("id",UUID.randomUUID().toString(),"session",session,
                "prompt","旧对话的测试消息","result","旧回复","state","completed","created",System.currentTimeMillis()/1000.0));
            listener=new ServerSocket(0,10,InetAddress.getByName("127.0.0.1"));
            workers.execute(()->{
                while(!listener.isClosed())try {
                    Socket socket=listener.accept();workers.execute(()->serve(socket));
                } catch(Exception e) { if(!listener.isClosed())throw new RuntimeException(e); }
            });
        }
        int port(){return listener.getLocalPort();}
        private void serve(Socket socket) {
            try(socket) {
                socket.setSoTimeout(12000);InputStream stream=socket.getInputStream();
                ByteArrayOutputStream headerBytes=new ByteArrayOutputStream();int value,last=0;
                while((value=stream.read())!=-1) {
                    headerBytes.write(value);last=(last<<8)|value;
                    if(last==0x0d0a0d0a)break;
                    if(headerBytes.size()>32768)throw new IllegalStateException("Header too long");
                }
                String header=headerBytes.toString(StandardCharsets.US_ASCII.name());
                Matcher length=Pattern.compile("(?im)^Content-Length: *([0-9]+)").matcher(header);
                byte[] body=new byte[length.find()?Integer.parseInt(length.group(1)):0];
                for(int offset=0;offset<body.length;){int read=stream.read(body,offset,body.length-offset);if(read<0)throw new IllegalStateException("Truncated body");offset+=read;}
                JSONObject response=Api.json();
                if(header.startsWith("POST /api/v1/jobs ")) {
                    request=new JSONObject(new String(body,StandardCharsets.UTF_8));submissions++;submitted.countDown();
                    if(!release.await(12,TimeUnit.SECONDS))throw new IllegalStateException("Reply was never released");
                    JSONObject job=new JSONObject(request.toString());
                    int count=job.optInt("repeat_count",1);
                    job.put("state",count==0?"waiting":"completed").put("result","模拟回复").put("created",System.currentTimeMillis()/1000.0)
                        .put("runs_completed",count==0?2:count).put("runs_started",count==0?2:count);
                    jobs=new JSONArray().put(job).put(jobs.getJSONObject(0));response=job;
                } else if(header.startsWith("GET /api/v1/jobs "))response=Api.json("jobs",jobs);
                else if(header.startsWith("GET /api/v1/status "))response=Api.json("repeat_modes",new JSONArray().put(1).put(5).put(10).put(0));
                else if(header.startsWith("POST /api/v1/jobs/")&&header.contains("/cancel ")) {
                    JSONObject cancelled=new JSONObject(jobs.getJSONObject(0).toString()).put("state","cancelled");
                    jobs=new JSONArray().put(cancelled).put(jobs.getJSONObject(1));response=cancelled;cancellations++;
                }
                byte[] json=response.toString().getBytes(StandardCharsets.UTF_8);
                String headers="HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nConnection: close\r\nContent-Length: "+json.length+"\r\n\r\n";
                socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(json);socket.getOutputStream().flush();
            } catch(Exception e){if(!listener.isClosed())throw new RuntimeException(e);}
        }
        @Override public void close() throws Exception {release.countDown();listener.close();workers.shutdownNow();}
    }
}
