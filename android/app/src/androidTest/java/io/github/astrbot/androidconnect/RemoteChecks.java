package io.github.astrbot.androidconnect;

import android.app.*;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.*;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

final class RemoteChecks {
    static Button find(View view,String label){
        if(view instanceof Button&&label.contentEquals(((Button)view).getText()))return (Button)view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){Button found=find(((ViewGroup)view).getChildAt(i),label);if(found!=null)return found;}return null;
    }
    static void await(Instrumentation test,BooleanSupplier condition,String error) throws Exception {
        long limit=System.nanoTime()+15_000_000_000L;while(System.nanoTime()<limit){AtomicBoolean ready=new AtomicBoolean();test.runOnMainSync(()->ready.set(condition.getAsBoolean()));if(ready.get())return;Thread.sleep(100);}throw new Exception(error);
    }
    static void screenshot(Instrumentation test,Activity app,String name){
        test.runOnMainSync(()->{try{View view=app.getWindow().getDecorView();Bitmap bitmap=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);view.draw(new Canvas(bitmap));File dir=new File(test.getTargetContext().getExternalFilesDir(null),"screenshots");dir.mkdirs();try(FileOutputStream output=new FileOutputStream(new File(dir,name+".png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,output);}bitmap.recycle();}catch(Exception e){throw new RuntimeException(e);}});
    }
    static void run(Instrumentation test) throws Exception {
        DesktopInput queue=new DesktopInput();queue.hold("tap","w",true);queue.hold("tap","w",false);
        if(queue.packet("test",1).getJSONArray("keys").length()!=1||queue.packet("test",2).getJSONArray("keys").length()!=0)throw new Exception("Quick taps were lost");
        queue.hold("virtual","w",true);queue.hold("keyboard","w",true);queue.hold("virtual","w",false);
        queue.packet("test",3);queue.packet("test",4);if(queue.packet("test",5).getJSONArray("keys").length()!=1)throw new Exception("One source released another source's key");
        Prefs prefs=new Prefs(test.getTargetContext());String oldUrl=prefs.url(),oldToken=prefs.token();Activity activity=null;
        try(ConversationChecks.MockBridge server=new ConversationChecks.MockBridge(prefs.id("session"))) {
            prefs.put("url","http://127.0.0.1:"+server.port());prefs.token("instrumentation-only-token-000000000000");
            PluginPanelActivity panel=(PluginPanelActivity)test.startActivitySync(new Intent(test.getTargetContext(),PluginPanelActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));activity=panel;
            await(test,()->find(panel.getWindow().getDecorView(),"停用 示例插件")!=null,"Plugin list not loaded");screenshot(test,panel,"插件管理");
            test.runOnMainSync(()->find(panel.getWindow().getDecorView(),"停用 示例插件").performClick());
            test.runOnMainSync(()->panel.confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
            await(test,()->!server.pluginEnabled,"Plugin switch did not reach host");
            test.runOnMainSync(panel::finish);
            DesktopActivity desktop=(DesktopActivity)test.startActivitySync(new Intent(test.getTargetContext(),DesktopActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));activity=desktop;
            await(test,()->server.latestInput!=null,"Desktop session did not start");
            test.runOnMainSync(()->find(desktop.getWindow().getDecorView(),"横屏 / 竖屏").performClick());
            await(test,()->desktop.getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE,"Landscape did not open");Thread.sleep(600);
            test.runOnMainSync(()->{Button w=find(desktop.getWindow().getDecorView(),"W");long now=SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,10,10,0);w.dispatchTouchEvent(down);down.recycle();});
            await(test,()->server.inputHistory.stream().anyMatch(s->s.contains("\"w\"")),"Virtual key press missing");
            test.runOnMainSync(()->{Button w=find(desktop.getWindow().getDecorView(),"W");long now=SystemClock.uptimeMillis();MotionEvent up=MotionEvent.obtain(now,now,MotionEvent.ACTION_UP,10,10,0);w.dispatchTouchEvent(up);up.recycle();});
            await(test,()->server.latestInput.optJSONArray("keys").length()==0,"Virtual key release missing");
            test.runOnMainSync(()->desktop.dispatchKeyEvent(new KeyEvent(0,0,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_A,0,0,1,0,0,InputDevice.SOURCE_KEYBOARD)));
            await(test,()->server.latestInput.optJSONArray("keys").toString().contains("a"),"External keyboard missing");
            test.runOnMainSync(()->{desktop.dispatchKeyEvent(new KeyEvent(0,0,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_A,0,0,1,0,0,InputDevice.SOURCE_KEYBOARD));desktop.dispatchKeyEvent(new KeyEvent(0,0,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BUTTON_A,0,0,2,0,0,InputDevice.SOURCE_GAMEPAD));});
            await(test,()->server.latestInput.optJSONArray("keys").toString().contains("space"),"Gamepad mapping missing");
            screenshot(test,desktop,"横屏遥控");int before=server.releases;test.runOnMainSync(desktop::finish);activity=null;
            await(test,()->server.releases>before,"Leaving desktop did not release keys");
        } finally {if(activity!=null){Activity current=activity;test.runOnMainSync(current::finish);}prefs.put("url",oldUrl);prefs.token(oldToken);}
    }
}
