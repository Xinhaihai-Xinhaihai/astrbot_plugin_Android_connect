package io.github.astrbot.androidconnect;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import java.io.File;
import java.io.FileOutputStream;

public class SmokeInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    private Button find(View view, String label) {
        if (view instanceof Button && label.contentEquals(((Button)view).getText())) return (Button)view;
        if(view instanceof ViewGroup) for(int i=0;i<((ViewGroup)view).getChildCount();i++){
            Button result=find(((ViewGroup)view).getChildAt(i),label);if(result!=null)return result;
        }
        return null;
    }
    @Override public void onStart() {
        Bundle results=new Bundle();
        try {
            if(!Api.validateUrl("http://192.168.1.10:8765").equals("http://192.168.1.10:8765"))throw new Exception("LAN URL rejected");
            for(String bad:new String[]{"http://example.com","http://192.168.999.1","https://user:pass@example.com","https://example.com/?token=x"}){
                boolean rejected=false;try{Api.validateUrl(bad);}catch(Exception expected){rejected=true;}if(!rejected)throw new Exception("Unsafe URL accepted");
            }
            Intent launch=new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            MainActivity activity=(MainActivity)startActivitySync(launch);waitForIdleSync();
            for(String page:new String[]{"聊天","任务","控制","设置"}){
                runOnMainSync(()->{Button button=find(activity.getWindow().getDecorView(),page);if(button==null)throw new IllegalStateException("Missing tab "+page);button.performClick();});
                waitForIdleSync();Thread.sleep(250);
                runOnMainSync(()->{
                    try{
                        View view=activity.getWindow().getDecorView();
                        Bitmap bitmap=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);
                        view.draw(new Canvas(bitmap));
                        File dir=new File(getTargetContext().getExternalFilesDir(null),"screenshots");dir.mkdirs();
                        try(FileOutputStream output=new FileOutputStream(new File(dir,page+".png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,output);}
                    }catch(Exception e){throw new RuntimeException(e);}
                });
            }
            runOnMainSync(activity::finish);
            ConversationChecks.run(this);
            RemoteChecks.run(this);
            results.putString("stream","PASS: four navigation pages, endpoint validation, new conversation lifecycle, four repeat modes, plugin switch, landscape, virtual keys, external keyboard, gamepad and exit release\n");finish(0,results);
        }catch(Exception e){results.putString("stream","FAIL: "+e+"\n");finish(1,results);}
    }
}
