package io.github.astrbot.androidconnect;

import android.app.*;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
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
    static void rotate(Instrumentation test,Activity activity,int requested,int expected)throws Exception{test.runOnMainSync(()->activity.setRequestedOrientation(requested));RemoteChecks.await(test,()->activity.getResources().getConfiguration().orientation==expected,"Config page did not rotate");}
    static void run(Instrumentation test)throws Exception{
        Prefs prefs=new Prefs(test.getTargetContext());String oldUrl=prefs.url(),oldToken=prefs.token(),oldMode=prefs.get("video_mode","30"),oldPad=prefs.get("virtual_keys_v1",""),oldPortrait=prefs.get("virtual_keys_v2_portrait",""),oldLandscape=prefs.get("virtual_keys_v2_landscape","");boolean oldPublicHttp=prefs.allowPublicHttp();Activity active=null;
        try(ConversationChecks.MockBridge server=new ConversationChecks.MockBridge(prefs.id("session"))){
            prefs.put("url","http://127.0.0.1:"+server.port());prefs.token("instrumentation-only-token-000000000000");prefs.put("virtual_keys_v1","");prefs.put("virtual_keys_v2_portrait","");prefs.put("virtual_keys_v2_landscape","");
            ConfigActivity config=(ConfigActivity)test.startActivitySync(new Intent(test.getTargetContext(),ConfigActivity.class).putExtra("kind","global").putExtra("id","default").putExtra("title","默认全局配置").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));active=config;
            RemoteChecks.await(test,()->field(config.getWindow().getDecorView(),"3")!=null,"Config fields not loaded");
            rotate(test,config,ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,Configuration.ORIENTATION_PORTRAIT);
            test.runOnMainSync(()->field(config.getWindow().getDecorView(),"3").setText("9"));
            rotate(test,config,ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,Configuration.ORIENTATION_LANDSCAPE);
            if(field(config.getWindow().getDecorView(),"9")==null)throw new Exception("Unsaved config draft was lost entering landscape");
            rotate(test,config,ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,Configuration.ORIENTATION_PORTRAIT);
            if(field(config.getWindow().getDecorView(),"9")==null)throw new Exception("Unsaved config draft was lost returning to portrait");
            test.runOnMainSync(()->{field(config.getWindow().getDecorView(),"9").setText("7");RemoteChecks.find(config.getWindow().getDecorView(),"保存到电脑").performClick();config.confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick();});
            RemoteChecks.await(test,()->server.configSaves==1,"Native form did not save");if(server.configValues.getInt("count")!=7||server.configValues.opt("count") instanceof Double)throw new Exception("Integer field save failed");
            RemoteChecks.await(test,()->RemoteChecks.find(config.getWindow().getDecorView(),"JSON 编辑").isEnabled(),"Config save never finished");
            test.runOnMainSync(()->{RemoteChecks.find(config.getWindow().getDecorView(),"JSON 编辑").performClick();view(config.getWindow().getDecorView(),EditText.class).setText("{\"count\":8,\"enabled\":false,\"message\":\"手机更新\"}");RemoteChecks.find(config.getWindow().getDecorView(),"保存到电脑").performClick();config.confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick();});
            RemoteChecks.await(test,()->server.configSaves==2,"JSON editor did not save");if(server.configValues.optBoolean("enabled",true))throw new Exception("JSON value not saved");
            RemoteChecks.screenshot(test,config,"全局配置编辑");test.runOnMainSync(config::finish);
            server.configValues=Api.json("provider_sources",new JSONArray().put(Api.json("id","demo-source","api_base","https://example.test","enable",false)),"platform",new JSONArray(),"timezone","Asia/Shanghai");
            server.configSchema=Api.json("provider_sources",Api.json("description","模型服务商","items",Api.json("id",Api.json("description","标识"),"api_base",Api.json("description","API 基础地址"),"enable",Api.json("description","启用"))));
            server.configGroups=new JSONArray().put(Api.json("id","models","name","模型服务","keys",new JSONArray().put("provider_sources"))).put(Api.json("id","platforms","name","消息平台","keys",new JSONArray().put("platform"))).put(Api.json("id","system","name","系统与日志","keys",new JSONArray().put("timezone")));
            GlobalConfigActivity overview=(GlobalConfigActivity)test.startActivitySync(new Intent(test.getTargetContext(),GlobalConfigActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));active=overview;
            RemoteChecks.await(test,()->RemoteChecks.find(overview.getWindow().getDecorView(),"默认全局配置")!=null,"Global profiles not loaded");
            RemoteChecks.screenshot(test,overview,"独立AstrBot配置入口");test.runOnMainSync(overview::finish);
            ConfigActivity grouped=(ConfigActivity)test.startActivitySync(new Intent(test.getTargetContext(),ConfigActivity.class).putExtra("kind","global").putExtra("id","default").putExtra("title","默认全局配置").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));active=grouped;
            RemoteChecks.await(test,()->RemoteChecks.find(grouped.getWindow().getDecorView(),"管理模型服务商（1 项）")!=null,"Grouped model form not loaded");
            test.runOnMainSync(()->{RemoteChecks.find(grouped.getWindow().getDecorView(),"管理模型服务商（1 项）").performClick();RemoteChecks.find(grouped.arrayEditor.getWindow().getDecorView(),"编辑 · demo-source").performClick();field(grouped.itemEditor.getWindow().getDecorView(),"https://example.test").setText("https://updated.example.test");grouped.itemEditor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();grouped.arrayEditor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();RemoteChecks.find(grouped.getWindow().getDecorView(),"保存到电脑").performClick();grouped.confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick();});
            RemoteChecks.await(test,()->server.configSaves==3,"Native model card did not save");
            if(!server.configValues.getJSONArray("provider_sources").getJSONObject(0).getString("api_base").equals("https://updated.example.test")||!server.configValues.getString("timezone").equals("Asia/Shanghai"))throw new Exception("List form lost values from another category");
            RemoteChecks.await(test,()->{Button loaded=RemoteChecks.find(grouped.getWindow().getDecorView(),"管理模型服务商（1 项）");return loaded!=null&&loaded.isEnabled()&&loaded.getWidth()>0;},"Saved category form did not finish rendering");test.waitForIdleSync();
            RemoteChecks.screenshot(test,grouped,"分类配置表单");test.runOnMainSync(grouped::finish);
            prefs.put("video_mode","jpeg");DesktopActivity desktop=(DesktopActivity)test.startActivitySync(new Intent(test.getTargetContext(),DesktopActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));active=desktop;
            RemoteChecks.awaitDesktop(test,desktop,"Desktop not connected");
            test.runOnMainSync(()->{RemoteChecks.find(desktop.getWindow().getDecorView(),"添加按键").performClick();VirtualPad pad=view(desktop.getWindow().getDecorView(),VirtualPad.class);field(pad.editor.getWindow().getDecorView(),"新按键").setText("复制");field(pad.editor.getWindow().getDecorView(),"f").setText("ctrl+c");pad.editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();RemoteChecks.find(desktop.getWindow().getDecorView(),"完成布局").performClick();});
            RemoteChecks.await(test,()->RemoteChecks.find(desktop.getWindow().getDecorView(),"复制")!=null,"Custom key not created");RemoteChecks.awaitDesktop(test,desktop,"Custom key editor did not restore input");
            test.runOnMainSync(()->{Button key=RemoteChecks.find(desktop.getWindow().getDecorView(),"复制");long t=SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(t,t,MotionEvent.ACTION_DOWN,10,10,0);key.dispatchTouchEvent(down);down.recycle();});
            RemoteChecks.await(test,()->server.inputHistory.stream().anyMatch(s->s.contains("\"ctrl\"")&&s.contains("\"c\"")),"Custom combination missing");
            for(String key:new String[]{"virtual_keys_v1","virtual_keys_v2_portrait","virtual_keys_v2_landscape"})if(!prefs.get(key,"").contains("ctrl+c"))throw new Exception("Layout not persisted: "+key);
            test.runOnMainSync(desktop::finish);server.latestInput=null;
            ArrayList<byte[]> units=new ArrayList<>();byte[] fixture;try(InputStream stream=test.getContext().getAssets().open("desktop-test.h264")){fixture=stream.readAllBytes();}
            ArrayList<Integer> starts=new ArrayList<>();for(int i=0;i<fixture.length-5;i++)if(fixture[i]==0&&fixture[i+1]==0&&fixture[i+2]==0&&fixture[i+3]==1&&(fixture[i+4]&31)==9)starts.add(i);
            for(int i=0;i<starts.size()-1;i++)units.add(Arrays.copyOfRange(fixture,starts.get(i),starts.get(i+1)));server.videoFrames=units.toArray(new byte[0][]);prefs.put("video_mode","60");
            DesktopActivity video=(DesktopActivity)test.startActivitySync(new Intent(test.getTargetContext(),DesktopActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));active=video;
            RemoteChecks.awaitDesktop(test,video,"Video window did not gain focus");
            RemoteChecks.await(test,()->contains(video.getWindow().getDecorView(),"实际解码")&&!contains(video.getWindow().getDecorView(),"实际解码 0 FPS"),"H.264 did not decode frames");
            test.runOnMainSync(()->{TextureView surface=view(video.getWindow().getDecorView(),TextureView.class);android.graphics.Bitmap decoded=surface.getBitmap();if(decoded==null)throw new IllegalStateException("Decoder did not render to full-screen surface");if(surface.getWidth()!=video.getWindow().getDecorView().getWidth())throw new IllegalStateException("Video is not full screen");Set<Integer> colors=new HashSet<>();for(int y=0;y<decoded.getHeight();y+=Math.max(1,decoded.getHeight()/20))for(int x=0;x<decoded.getWidth();x+=Math.max(1,decoded.getWidth()/20))colors.add(decoded.getPixel(x,y));if(colors.size()<8)throw new IllegalStateException("Decoded video is blank");try{File dir=new File(test.getTargetContext().getExternalFilesDir(null),"screenshots");dir.mkdirs();try(FileOutputStream output=new FileOutputStream(new File(dir,"视频解码帧.png"))){decoded.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output);}}catch(Exception e){throw new RuntimeException(e);}finally{decoded.recycle();}});
            RemoteChecks.screenshot(test,video,"全屏视频与自定义按键");test.runOnMainSync(video::finish);active=null;
        }finally{if(active!=null){Activity last=active;test.runOnMainSync(last::finish);}prefs.put("url",oldUrl);prefs.token(oldToken);prefs.put("video_mode",oldMode);prefs.put("virtual_keys_v1",oldPad);prefs.put("virtual_keys_v2_portrait",oldPortrait);prefs.put("virtual_keys_v2_landscape",oldLandscape);prefs.allowPublicHttp(oldPublicHttp);}
    }
}
