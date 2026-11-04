package io.github.astrbot.androidconnect;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.graphics.Color;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PluginPanelActivity extends Activity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private Api api;
    private LinearLayout list;
    private TextView summary;
    private Button refresh;
    private boolean busy;
    AlertDialog confirmation;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        api=new Api(new Prefs(this));
        LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(24,12,24,12);root.setBackgroundColor(Color.rgb(245,246,250));
        root.setOnApplyWindowInsetsListener((v,i)->{v.setPadding(24+i.getSystemWindowInsetLeft(),12+i.getSystemWindowInsetTop(),24+i.getSystemWindowInsetRight(),12+i.getSystemWindowInsetBottom());return i;});
        Button back=new Button(this);back.setText("返回控制页");back.setOnClickListener(v->finish());root.addView(back);
        TextView title=new TextView(this);title.setText("AstrBot 插件管理");title.setTextSize(25);root.addView(title);
        summary=new TextView(this);summary.setText("查看全部插件，按需启用或停用。连接插件保持开启。");root.addView(summary);
        refresh=new Button(this);refresh.setText("刷新插件列表");refresh.setOnClickListener(v->load(null));root.addView(refresh);
        ScrollView scroll=new ScrollView(this);list=new LinearLayout(this);list.setOrientation(1);scroll.addView(list);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);load(null);
    }
    private void load(JSONObject change) {
        if(busy)return;busy=true;refresh.setEnabled(false);setEnabled(list,false);
        worker.execute(()->{
            try {
                JSONObject result=change==null?api.call("GET","/panel/plugins",null):api.call("POST","/panel/plugins",change);
                runOnUiThread(()->{if(isFinishing())return;busy=false;refresh.setEnabled(true);render(result.optJSONArray("plugins"));});
            }catch(Exception e){runOnUiThread(()->{if(isFinishing())return;busy=false;refresh.setEnabled(true);list.removeAllViews();summary.setText("读取失败，请刷新确认实际状态");new AlertDialog.Builder(this).setTitle("插件管理").setMessage(e.getMessage()).setPositiveButton("知道了",null).show();});}
        });
    }
    private void setEnabled(LinearLayout parent,boolean value) {for(int i=0;i<parent.getChildCount();i++)parent.getChildAt(i).setEnabled(value);}
    private void render(JSONArray plugins) {
        list.removeAllViews();int count=plugins==null?0:plugins.length(),enabled=0;
        for(int i=0;i<count;i++)if(plugins.optJSONObject(i).optBoolean("enabled"))enabled++;
        summary.setText("已启用 "+enabled+" / "+count+" 个插件 · 开关会立即作用于电脑");
        for(boolean section:new boolean[]{true,false}) {
            TextView heading=new TextView(this);heading.setText(section?"已启用":"已停用");heading.setTextSize(19);heading.setPadding(0,24,0,10);list.addView(heading);
            for(int i=0;i<count;i++) {
                JSONObject plugin=plugins.optJSONObject(i);if(plugin.optBoolean("enabled")!=section)continue;
                TextView label=new TextView(this);label.setText(plugin.optString("display_name")+"  "+plugin.optString("version")+"\n"+plugin.optString("description"));label.setPadding(8,16,8,8);list.addView(label);
                Button toggle=new Button(this);toggle.setText(plugin.optBoolean("protected")?"由电脑管理（保持连接）":section?"停用 "+plugin.optString("display_name"):"启用 "+plugin.optString("display_name"));toggle.setEnabled(!plugin.optBoolean("protected"));
                toggle.setOnClickListener(v->confirmation=new AlertDialog.Builder(this).setTitle(section?"停用插件":"启用插件").setMessage(plugin.optString("display_name")+"\n这会立即改变电脑端插件状态。").setNegativeButton("取消",null).setPositiveButton("确认",(d,w)->load(Api.json("name",plugin.optString("name"),"enabled",!section))).show());list.addView(toggle);
            }
        }
    }
    @Override protected void onDestroy(){worker.shutdown();super.onDestroy();}
}
