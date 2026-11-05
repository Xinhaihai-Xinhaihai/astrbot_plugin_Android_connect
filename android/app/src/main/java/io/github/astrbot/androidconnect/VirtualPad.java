package io.github.astrbot.androidconnect;

import android.app.*;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;

/** Normalized positions survive rotations; each button has an independent input source. */
final class VirtualPad extends FrameLayout {
    interface Hold {void apply(String source,String action,boolean pressed);}
    private final Activity activity;private final Prefs prefs;private final Hold hold;
    private final ArrayList<JSONObject> keys=new ArrayList<>();private boolean editing;
    private int opacity;private final String positionKey="virtual_keys_v1";
    AlertDialog editor;
    VirtualPad(Activity activity,Prefs prefs,Hold hold){super(activity);this.activity=activity;this.prefs=prefs;this.hold=hold;
        setMotionEventSplittingEnabled(true);setClipChildren(true);opacity=Integer.parseInt(prefs.get("pad_opacity","65"));load();}
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    private void load(){try{JSONArray list=new JSONArray(prefs.get(positionKey,"[]"));for(int i=0;i<Math.min(list.length(),32);i++)keys.add(list.getJSONObject(i));}catch(Exception ignored){}if(keys.isEmpty()&&prefs.get(positionKey,"").isEmpty())defaults();rebuild();}
    private void defaults(){String[] labels={"W","A","S","D","Shift","Ctrl","空格","E","R","Esc","左键","右键"};String[] actions={"w","a","s","d","shift","ctrl","space","e","r","esc","mouse:left","mouse:right"};for(int i=0;i<labels.length;i++)keys.add(Api.json("id",UUID.randomUUID().toString(),"label",labels[i],"action",actions[i],"x",(i%6)/5.0,"y",i<6?.78:1,"size",48,"alpha",100));}
    void edit(boolean value){editing=value;setBackgroundColor(value?0x332e3c5b:Color.TRANSPARENT);rebuild();}
    void save(){prefs.put(positionKey,new JSONArray(keys).toString());prefs.put("pad_opacity",Integer.toString(opacity));}
    private void rebuild(){removeAllViews();for(JSONObject key:keys){Button b=new Button(activity);b.setText(key.optString("label"));b.setAllCaps(false);b.setTextColor(Color.WHITE);b.setTextSize(12);b.setPadding(0,0,0,0);GradientDrawable bg=new GradientDrawable();bg.setColor(0xff30456b);bg.setCornerRadius(dp(14));bg.setStroke(dp(1),0xff8aa7d4);b.setBackground(bg);b.setAlpha(opacity/100f*key.optInt("alpha",100)/100f);b.setTag(key);addView(b,new FrameLayout.LayoutParams(dp(key.optInt("size",48)),dp(key.optInt("size",48))));
            final float[] start=new float[4];final boolean[] moved={false};
            b.setOnTouchListener((v,e)->{String source="virtual:"+key.optString("id"),action=key.optString("action");int event=e.getActionMasked();
                if(editing){if(event==MotionEvent.ACTION_DOWN){start[0]=e.getRawX();start[1]=e.getRawY();start[2]=b.getX();start[3]=b.getY();moved[0]=false;}else if(event==MotionEvent.ACTION_MOVE){float dx=e.getRawX()-start[0],dy=e.getRawY()-start[1];if(Math.abs(dx)+Math.abs(dy)>dp(6))moved[0]=true;b.setX(Math.max(0,Math.min(getWidth()-b.getWidth(),start[2]+dx)));b.setY(Math.max(0,Math.min(getHeight()-b.getHeight(),start[3]+dy)));}else if(event==MotionEvent.ACTION_UP){try{key.put("x",b.getX()/Math.max(1,getWidth()-b.getWidth())).put("y",b.getY()/Math.max(1,getHeight()-b.getHeight()));}catch(Exception ignored){}save();if(!moved[0])editKey(key,false);}return true;}
                if(event==MotionEvent.ACTION_DOWN){hold.apply(source,action,true);b.setPressed(true);}else if(event==MotionEvent.ACTION_UP||event==MotionEvent.ACTION_CANCEL){hold.apply(source,action,false);b.setPressed(false);}return true;});}post(this::position);}
    private void position(){for(int i=0;i<getChildCount();i++){View b=getChildAt(i);JSONObject k=(JSONObject)b.getTag();b.setX((float)k.optDouble("x",.5)*Math.max(0,getWidth()-b.getWidth()));b.setY((float)k.optDouble("y",.5)*Math.max(0,getHeight()-b.getHeight()));}}
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){super.onSizeChanged(w,h,oldw,oldh);post(this::position);}
    void add(){if(keys.size()>=32){Toast.makeText(activity,"最多添加 32 个按键",Toast.LENGTH_SHORT).show();return;}editKey(Api.json("id",UUID.randomUUID().toString(),"label","新按键","action","f","x",.5,"y",.5,"size",56,"alpha",100),true);}
    static boolean validAction(String action){String[] parts=action.split("\\+",-1);if(parts.length>4)return false;for(String key:parts)if(!key.matches("[a-z0-9]|f([1-9]|1[0-2])|space|enter|esc|tab|backspace|delete|insert|home|end|pageup|pagedown|up|down|left|right|shift|ctrl|alt|mouse:(left|right|middle)"))return false;return true;}
    private void editKey(JSONObject key,boolean added){LinearLayout box=new LinearLayout(activity);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(20),dp(8),dp(20),dp(8));EditText label=new EditText(activity);label.setHint("按键名称");label.setText(key.optString("label"));box.addView(label);EditText action=new EditText(activity);action.setSingleLine(true);action.setHint("例如 f、space、ctrl+c、mouse:left");action.setText(key.optString("action"));box.addView(action);
        TextView sizeLabel=new TextView(activity);box.addView(sizeLabel);SeekBar size=new SeekBar(activity);size.setMax(80);size.setProgress(key.optInt("size",56)-40);box.addView(size);sizeLabel.setText("按键大小："+(size.getProgress()+40));size.setOnSeekBarChangeListener(change(p->sizeLabel.setText("按键大小："+(p+40))));
        TextView alphaLabel=new TextView(activity);box.addView(alphaLabel);SeekBar alpha=new SeekBar(activity);alpha.setMax(90);alpha.setProgress(key.optInt("alpha",100)-10);box.addView(alpha);alphaLabel.setText("此按键不透明度："+(alpha.getProgress()+10)+"%");alpha.setOnSeekBarChangeListener(change(p->alphaLabel.setText("此按键不透明度："+(p+10)+"%")));
        editor=new AlertDialog.Builder(activity).setTitle(added?"添加虚拟按键":"修改 / 删除按键").setView(box).setPositiveButton("保存",null).setNegativeButton("取消",null).setNeutralButton(added?"": "删除",(d,w)->{if(!added){keys.remove(key);save();rebuild();}}).create();editor.show();editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String a=action.getText().toString().trim().toLowerCase(Locale.ROOT),name=label.getText().toString().trim();if(!validAction(a)||name.isEmpty()||name.length()>16){action.setError("名称 1–16 字；按键填英文键名，组合键用 + 连接（最多 4 个）");return;}try{key.put("label",name).put("action",a).put("size",size.getProgress()+40).put("alpha",alpha.getProgress()+10);}catch(Exception ignored){}if(added)keys.add(key);save();rebuild();editor.dismiss();});}
    interface Progress {void accept(int value);}
    private SeekBar.OnSeekBarChangeListener change(Progress action){return new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int value,boolean user){action.accept(value);}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}};}
    void opacityDialog(){LinearLayout box=new LinearLayout(activity);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(24),dp(10),dp(24),dp(10));TextView label=new TextView(activity);label.setText("全部按键不透明度："+opacity+"%");box.addView(label);SeekBar seek=new SeekBar(activity);seek.setMax(90);seek.setProgress(opacity-10);box.addView(seek);seek.setOnSeekBarChangeListener(change(p->{opacity=p+10;label.setText("全部按键不透明度："+opacity+"%");rebuild();save();}));new AlertDialog.Builder(activity).setTitle("按键透明度").setView(box).setPositiveButton("完成",null).show();}
}
