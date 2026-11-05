package io.github.astrbot.androidconnect;

import android.app.*;
import android.content.Intent;
import android.os.Bundle;
import android.graphics.Typeface;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.concurrent.*;

/** AstrBot profiles and personas have their own entry, separate from installed plugins. */
public class GlobalConfigActivity extends Activity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private LinearLayout list;private Button refresh;private boolean busy;
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    private TextView label(String text,int size){TextView v=new TextView(this);v.setText(text);v.setTextSize(size);v.setTextColor(0xff202941);v.setPadding(0,dp(10),0,dp(10));return v;}
    private Button button(String text,Runnable action){Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setTextColor(0xff5267e8);b.setOnClickListener(v->action.run());return b;}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(0xfff5f6fa);
        root.setOnApplyWindowInsetsListener((v,i)->{v.setPadding(dp(16)+i.getSystemWindowInsetLeft(),dp(8)+i.getSystemWindowInsetTop(),dp(16)+i.getSystemWindowInsetRight(),dp(8)+i.getSystemWindowInsetBottom());return i;});
        root.addView(button("返回控制页",this::finish));TextView heading=label("AstrBot 配置",25);heading.setTypeface(null,Typeface.BOLD);root.addView(heading);
        root.addView(label("选择配置方案，按模型、消息平台、对话与 Agent、语音等类别编辑。人格在下方单独管理。",14));
        refresh=button("刷新配置方案",this::load);root.addView(refresh);
        ScrollView scroll=new ScrollView(this);list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);scroll.addView(list);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);load();
    }
    private void load(){
        if(busy)return;busy=true;refresh.setEnabled(false);
        worker.execute(()->{try{JSONObject response=new Api(new Prefs(this)).call("GET","/panel/configs",null);runOnUiThread(()->{if(isFinishing())return;busy=false;refresh.setEnabled(true);render(response.optJSONArray("configs"));});}
            catch(Exception e){runOnUiThread(()->{if(isFinishing())return;busy=false;refresh.setEnabled(true);new AlertDialog.Builder(this).setTitle("读取配置失败").setMessage(e.getMessage()).setPositiveButton("知道了",null).show();});}});
    }
    private void render(JSONArray entries){
        list.removeAllViews();
        for(String kind:new String[]{"global","persona"}){
            TextView heading=label(kind.equals("global")?"全局配置方案":"人格与提示词",20);heading.setTypeface(null,Typeface.BOLD);list.addView(heading);int count=0;
            if(entries!=null)for(int i=0;i<entries.length();i++){
                JSONObject item=entries.optJSONObject(i);if(item==null||!kind.equals(item.optString("kind")))continue;count++;
                String title=item.optString("name");if(kind.equals("global")&&item.optString("id").equals("default"))title="默认全局配置";
                final String selected=title;list.addView(button(title,()->startActivity(new Intent(this,ConfigActivity.class).putExtra("kind",kind).putExtra("id",item.optString("id")).putExtra("title",selected))));
            }
            if(count==0)list.addView(label("暂无"+(kind.equals("global")?"配置方案":"人格"),14));
        }
    }
    @Override protected void onDestroy(){worker.shutdown();super.onDestroy();}
}
