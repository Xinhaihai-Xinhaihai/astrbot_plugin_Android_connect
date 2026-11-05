package io.github.astrbot.androidconnect;

import android.app.*;
import android.content.Intent;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.util.*;

final class ConfigVideoChecks {
    static <T> T view(View root,Class<T> type){if(type.isInstance(root))return type.cast(root);if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){T found=view(((ViewGroup)root).getChildAt(i),type);if(found!=null)return found;}return null;}
    static EditText field(View root,String text){if(root instanceof EditText&&text.contentEquals(((EditText)root).getText()))return (EditText)root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){EditText result=field(((ViewGroup)root).getChildAt(i),text);if(result!=null)return result;}return null;}
    static boolean contains(View root,String part){if(root instanceof TextView&&((TextView)root).getText().toString().contains(part))return true;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)if(contains(((ViewGroup)root).getChildAt(i),part))return true;return false;}
    static void run(Instrumentation test)throws Exception{
        Prefs prefs=new Prefs(test.getTargetContext());String oldUrl=prefs.url(),oldToken=prefs.token(),oldMode=prefs.get("video_mode","30"),oldPad=prefs.get("virtual_keys_v1","");Activity active=null;
        try(ConversationChecks.MockBridge server=new ConversationChecks.MockBridge(prefs.id("session"))){
            prefs.put("url","http://127.0.0.1:"+server.port());prefs.token("instrumentation-only-token-000000000000");prefs.put("virtual_keys_v1","");
            ConfigActivity config=(ConfigActivity)test.startActivitySync(new Intent(test.getTargetContext(),ConfigActivity.class).putExtra("kind","global").putExtra("id","default").putExtra("title","默认全局配置").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));active=config;
            RemoteChecks.await(test,()->field(config.getWindow().getDecorView(),"3")!=null,"Config fields not loaded");
            test.runOnMainSync(()->{field(config.getWindow().getDecorView(),"3").setText("7");RemoteChecks.find(config.getWindow().getDecorView(),"保存到电脑").performClick();config.confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick();});
            RemoteChecks.await(test,()->server.configSaves==1,"Native form did not save");if(server.configValues.getInt("count")!=7||server.configValues.opt("count") instanceof Double)throw new Exception("Integer field save failed");
            RemoteChecks.await(test,()->RemoteChecks.find(config.getWindow().getDecorView(),"JSON 编辑").isEnabled(),"Config save never finished");
            test.runOnMainSync(()->{RemoteChecks.find(config.getWindow().getDecorView(),"JSON 编辑").performClick();view(config.getWindow().getDecorView(),EditText.class).setText("{\"count\":8,\"enabled\":false,\"message\":\"手机更新\"}");RemoteChecks.find(config.getWindow().getDecorView(),"保存到电脑").performClick();config.confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick();});
            RemoteChecks.await(test,()->server.configSaves==2,"JSON editor did not save");if(server.configValues.optBoolean("enabled",true))throw new Exception("JSON value not saved");
            RemoteChecks.screenshot(test,config,"全局配置编辑");test.runOnMainSync(config::finish);
            prefs.put("video_mode","jpeg");DesktopActivity desktop=(DesktopActivity)test.startActivitySync(new Intent(test.getTargetContext(),DesktopActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));active=desktop;
            RemoteChecks.await(test,()->server.latestInput!=null,"Desktop not connected");
            test.runOnMainSync(()->{RemoteChecks.find(desktop.getWindow().getDecorView(),"添加按键").performClick();VirtualPad pad=view(desktop.getWindow().getDecorView(),VirtualPad.class);field(pad.editor.getWindow().getDecorView(),"新按键").setText("复制");field(pad.editor.getWindow().getDecorView(),"f").setText("ctrl+c");pad.editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();RemoteChecks.find(desktop.getWindow().getDecorView(),"完成布局").performClick();});
            RemoteChecks.await(test,()->RemoteChecks.find(desktop.getWindow().getDecorView(),"复制")!=null,"Custom key not created");RemoteChecks.await(test,desktop::readyForInput,"Custom key editor did not restore input");
            test.runOnMainSync(()->{Button key=RemoteChecks.find(desktop.getWindow().getDecorView(),"复制");long t=SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(t,t,MotionEvent.ACTION_DOWN,10,10,0);key.dispatchTouchEvent(down);down.recycle();});
            RemoteChecks.await(test,()->server.inputHistory.stream().anyMatch(s->s.contains("\"ctrl\"")&&s.contains("\"c\"")),"Custom combination missing");
            if(!prefs.get("virtual_keys_v1","").contains("ctrl+c"))throw new Exception("Layout not persisted");
            test.runOnMainSync(desktop::finish);server.latestInput=null;
            ArrayList<byte[]> units=new ArrayList<>();byte[] fixture;try(InputStream stream=test.getContext().getAssets().open("desktop-test.h264")){fixture=stream.readAllBytes();}
            ArrayList<Integer> starts=new ArrayList<>();for(int i=0;i<fixture.length-5;i++)if(fixture[i]==0&&fixture[i+1]==0&&fixture[i+2]==0&&fixture[i+3]==1&&(fixture[i+4]&31)==9)starts.add(i);
            for(int i=0;i<starts.size()-1;i++)units.add(Arrays.copyOfRange(fixture,starts.get(i),starts.get(i+1)));server.videoFrames=units.toArray(new byte[0][]);prefs.put("video_mode","60");
            DesktopActivity video=(DesktopActivity)test.startActivitySync(new Intent(test.getTargetContext(),DesktopActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));active=video;
            RemoteChecks.await(test,()->contains(video.getWindow().getDecorView(),"实际解码")&&!contains(video.getWindow().getDecorView(),"实际解码 0 FPS"),"H.264 did not decode frames");
            test.runOnMainSync(()->{TextureView surface=view(video.getWindow().getDecorView(),TextureView.class);if(surface.getBitmap()==null)throw new IllegalStateException("Decoder did not render to full-screen surface");if(surface.getWidth()!=video.getWindow().getDecorView().getWidth())throw new IllegalStateException("Video is not full screen");});
            RemoteChecks.screenshot(test,video,"全屏视频与自定义按键");test.runOnMainSync(video::finish);active=null;
        }finally{if(active!=null){Activity last=active;test.runOnMainSync(last::finish);}prefs.put("url",oldUrl);prefs.token(oldToken);prefs.put("video_mode",oldMode);prefs.put("virtual_keys_v1",oldPad);}
    }
}
