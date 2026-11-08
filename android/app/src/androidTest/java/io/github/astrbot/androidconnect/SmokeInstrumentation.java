package io.github.astrbot.androidconnect;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioButton;
import java.io.File;
import java.io.FileOutputStream;

public class SmokeInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public boolean onException(Object object,Throwable error){
        Bundle result=new Bundle();result.putString("stream","FAIL: "+android.util.Log.getStackTraceString(error)+"\n");finish(1,result);return true;
    }
    private void stage(String name){Bundle progress=new Bundle();progress.putString("stream","Checking: "+name+"\n");sendStatus(0,progress);}
    private Button find(View view, String label) {
        if (view instanceof Button && label.contentEquals(((Button)view).getText())) return (Button)view;
        if(view instanceof ViewGroup) for(int i=0;i<((ViewGroup)view).getChildCount();i++){
            Button result=find(((ViewGroup)view).getChildAt(i),label);if(result!=null)return result;
        }
        return null;
    }
    private View tagged(View view, String tag) {
        if (tag.equals(view.getTag())) return view;
        if(view instanceof ViewGroup) for(int i=0;i<((ViewGroup)view).getChildCount();i++){
            View result=tagged(((ViewGroup)view).getChildAt(i),tag);if(result!=null)return result;
        }
        return null;
    }
    private void rotate(MainActivity activity,int requested,int expected) throws Exception {
        runOnMainSync(()->activity.setRequestedOrientation(requested));
        RemoteChecks.await(this,()->activity.getResources().getConfiguration().orientation==expected,"Main page did not rotate");
    }
    @Override public void onStart() {
        Bundle results=new Bundle();
        try {
            // A fresh emulator otherwise shows Android's first-use full-screen tutorial,
            // which owns focus until a person acknowledges it. Keep this out of app code.
            try(android.os.ParcelFileDescriptor command=getUiAutomation().executeShellCommand("settings put secure immersive_mode_confirmations confirmed");
                java.io.FileInputStream output=new java.io.FileInputStream(command.getFileDescriptor())){output.readAllBytes();}
            if(!Api.validateUrl("http://192.168.1.10:8765").equals("http://192.168.1.10:8765"))throw new Exception("LAN URL rejected");
            for(String bad:new String[]{"http://example.com","http://192.168.999.1","https://user:pass@example.com","https://example.com/?token=x"}){
                boolean rejected=false;try{Api.validateUrl(bad);}catch(Exception expected){rejected=true;}if(!rejected)throw new Exception("Unsafe URL accepted");
            }
            boolean publicRejected=false;try{Api.validateUrl("http://8.8.8.8:8765");}catch(Exception expected){publicRejected=true;}if(!publicRejected)throw new Exception("Public HTTP enabled by default");
            if(!Api.validateUrl("http://8.8.8.8:8765",true).equals("http://8.8.8.8:8765"))throw new Exception("Allowed public HTTP IPv4 was rejected");
            boolean publicDomainRejected=false;try{Api.validateUrl("http://example.com",true);}catch(Exception expected){publicDomainRejected=true;}if(!publicDomainRejected)throw new Exception("Public HTTP domain was accepted");
            if(!Api.validateUrl("https://example.com").equals("https://example.com"))throw new Exception("HTTPS domain was rejected");
            Intent launch=new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            MainActivity activity=(MainActivity)startActivitySync(launch);waitForIdleSync();
            rotate(activity,ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,Configuration.ORIENTATION_PORTRAIT);
            runOnMainSync(()->{Button chat=find(activity.getWindow().getDecorView(),"聊天");if(chat==null)throw new IllegalStateException("Missing chat tab");chat.performClick();});
            RemoteChecks.await(this,()->tagged(activity.getWindow().getDecorView(),"message_draft")!=null,"Chat composer did not open");
            runOnMainSync(()->{
                EditText draft=(EditText)tagged(activity.getWindow().getDecorView(),"message_draft");draft.setText("rotation-draft");
                CheckBox desktop=(CheckBox)tagged(activity.getWindow().getDecorView(),"desktop_wanted");if(!desktop.isChecked())desktop.performClick();
                CheckBox phone=(CheckBox)tagged(activity.getWindow().getDecorView(),"phone_wanted");if(!phone.isChecked())phone.performClick();
                RadioButton five=(RadioButton)find(activity.getWindow().getDecorView(),"5 次");if(five==null)throw new IllegalStateException("Missing repeat option");five.performClick();
            });
            rotate(activity,ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,Configuration.ORIENTATION_LANDSCAPE);
            rotate(activity,ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,Configuration.ORIENTATION_PORTRAIT);
            runOnMainSync(()->{
                EditText draft=(EditText)tagged(activity.getWindow().getDecorView(),"message_draft");CheckBox desktop=(CheckBox)tagged(activity.getWindow().getDecorView(),"desktop_wanted");CheckBox phone=(CheckBox)tagged(activity.getWindow().getDecorView(),"phone_wanted");RadioButton five=(RadioButton)find(activity.getWindow().getDecorView(),"5 次");
                if(draft==null||!draft.getText().toString().equals("rotation-draft"))throw new IllegalStateException("Chat draft was lost after rotation");
                if(desktop==null||!desktop.isChecked()||phone==null||!phone.isChecked())throw new IllegalStateException("Chat permissions were lost after rotation");
                if(five==null||!five.isChecked())throw new IllegalStateException("Repeat count was lost after rotation");
            });
            runOnMainSync(()->find(activity.getWindow().getDecorView(),"设置").performClick());
            RemoteChecks.await(this,()->tagged(activity.getWindow().getDecorView(),"server_url")!=null,"Settings page did not open");
            runOnMainSync(()->((EditText)tagged(activity.getWindow().getDecorView(),"server_url")).setText("http://192.168.1.20:8766"));
            rotate(activity,ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,Configuration.ORIENTATION_LANDSCAPE);
            rotate(activity,ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,Configuration.ORIENTATION_PORTRAIT);
            runOnMainSync(()->{View url=tagged(activity.getWindow().getDecorView(),"server_url");if(!(url instanceof EditText)||!((EditText)url).getText().toString().equals("http://192.168.1.20:8766"))throw new IllegalStateException("Settings URL draft was lost after rotation");});
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
            stage("conversation and repeat modes");ConversationChecks.run(this);
            stage("plugin panel and desktop inputs");RemoteChecks.run(this);
            stage("trackpad, direct touch, drag cancellation and two-finger gestures");PointerChecks.run(this);
            stage("configuration editor, custom keys and H.264 video");ConfigVideoChecks.run(this);
            results.putString("stream","PASS: four navigation pages, rotation state, portrait/landscape layouts, public IPv4 HTTP, endpoint validation, new conversation lifecycle, four repeat modes, plugin switch, virtual keys, external keyboard, gamepad and exit release\n");finish(0,results);
        }catch(Exception e){results.putString("stream","FAIL: "+e+"\n");finish(1,results);}
    }
}
