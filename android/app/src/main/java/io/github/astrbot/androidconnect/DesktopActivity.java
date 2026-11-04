package io.github.astrbot.androidconnect;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.hardware.input.InputManager;
import android.graphics.*;
import android.os.*;
import android.util.Base64;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import java.util.*;
import java.util.concurrent.*;

public class DesktopActivity extends Activity implements InputManager.InputDeviceListener {
    private final Handler handler=new Handler();
    private final ScheduledExecutorService inputWorker=Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService frameWorker=Executors.newSingleThreadExecutor();
    private final DesktopInput input=new DesktopInput();
    private final Map<Integer,String> mappings=new HashMap<>();
    private Api api;private Prefs prefs;private ImageView screen;private TextView info;
    private LinearLayout pad;private Bitmap bitmap;private boolean frameBusy,live=true,resumed;
    private volatile String session;private volatile boolean inputReady;private long sequence;
    private float lastX,lastY,stickX,stickY;private boolean touchMoved;private int frameDelay=250;
    private final Runnable frames=new Runnable(){public void run(){if(resumed){if(live)capture();handler.postDelayed(this,frameDelay);}}};
    private final Runnable stick=new Runnable(){public void run(){if(resumed){if(inputReady)input.move(stickX*18,stickY*18);handler.postDelayed(this,50);}}};
    private int dp(int v){return (int)(v*getResources().getDisplayMetrics().density+.5f);}
    private Button button(String title,Runnable action){Button b=new Button(this);b.setText(title);b.setTextSize(12);b.setAllCaps(false);b.setOnClickListener(v->action.run());return b;}
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE|WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs=new Prefs(this);api=new Api(prefs);loadMappings();
        ((InputManager)getSystemService(INPUT_SERVICE)).registerInputDeviceListener(this,handler);
        LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setBackgroundColor(Color.rgb(20,24,34));
        root.setOnApplyWindowInsetsListener((v,i)->{v.setPadding(i.getSystemWindowInsetLeft(),i.getSystemWindowInsetTop(),i.getSystemWindowInsetRight(),i.getSystemWindowInsetBottom());return i;});
        HorizontalScrollView tools=new HorizontalScrollView(this);LinearLayout bar=new LinearLayout(this);tools.addView(bar);root.addView(tools);
        bar.addView(button("返回",this::finish));bar.addView(button("横屏 / 竖屏",()->setRequestedOrientation(getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE?ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT:ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)));
        bar.addView(button("虚拟按键",()->pad.setVisibility(pad.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE)));
        bar.addView(button("暂停 / 继续",()->{live=!live;if(!live)disconnect();else connect();}));
        bar.addView(button("画质 / 刷新率",this::quality));bar.addView(button("手柄映射",this::mappingDialog));
        bar.addView(button("输入文字",this::typeText));bar.addView(button("释放按键",()->{disconnect();connect();}));
        info=new TextView(this);info.setTextColor(Color.WHITE);info.setTextSize(12);info.setText("连接桌面中 · USB / 蓝牙键鼠及手柄可直接接入手机");root.addView(info);
        FrameLayout canvas=new FrameLayout(this);screen=new ImageView(this);screen.setScaleType(ImageView.ScaleType.FIT_CENTER);screen.setFocusable(true);screen.setFocusableInTouchMode(true);canvas.addView(screen,new FrameLayout.LayoutParams(-1,-1));root.addView(canvas,new LinearLayout.LayoutParams(-1,0,1));
        pad=new LinearLayout(this);pad.setOrientation(1);pad.setBackgroundColor(0x66202a40);FrameLayout.LayoutParams overlay=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);canvas.addView(pad,overlay);
        addKeys(new String[]{"W","A","S","D","Shift","Ctrl"},new String[]{"w","a","s","d","shift","ctrl"});
        addKeys(new String[]{"空格","E","R","Esc","左键","右键"},new String[]{"space","e","r","esc","mouse:left","mouse:right"});
        screen.setOnTouchListener((v,event)->touch(event));screen.setOnGenericMotionListener((v,e)->mouse(e));
        setContentView(root);screen.requestFocus();
        inputWorker.scheduleWithFixedDelay(this::flush,0,60,TimeUnit.MILLISECONDS);
    }
    private void addKeys(String[] labels,String[] actions){
        LinearLayout row=new LinearLayout(this);pad.addView(row);
        for(int i=0;i<labels.length;i++){
            final String action=actions[i],source="virtual:"+action;Button b=button(labels[i],()->{});b.setAlpha(.8f);
            b.setOnTouchListener((v,e)->{if(e.getActionMasked()==MotionEvent.ACTION_DOWN){hold(source,action,true);v.setPressed(true);}else if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL){hold(source,action,false);v.setPressed(false);}return true;});row.addView(b,new LinearLayout.LayoutParams(0,dp(42),1));
        }
    }
    private void hold(String source,String action,boolean down){if(!inputReady)return;try{input.hold(source,action,down);}catch(Exception e){fail(e.getMessage());}}
    private void connect(){
        if(!resumed||session!=null)return;input.clear();String next=UUID.randomUUID().toString();session=next;inputReady=false;
        inputWorker.execute(()->{try{api.call("POST","/desktop/session",Api.json("session",next));if(next.equals(session)){sequence=0;inputReady=true;runOnUiThread(()->info.setText("桌面已连接 · 触摸拖动鼠标，轻点单击 · 离开自动释放按键"));}}catch(Exception e){if(next.equals(session))runOnUiThread(()->fail(e.getMessage()));}});
    }
    private void disconnect(){String old=session;session=null;inputReady=false;input.clear();stickX=0;stickY=0;if(old!=null)inputWorker.execute(()->{try{api.call("POST","/desktop/release",Api.json("session",old));}catch(Exception ignored){}});}
    private void flush(){String current=session;if(current==null||!inputReady)return;try{api.call("POST","/desktop/input",input.packet(current,++sequence));}catch(Exception e){if(current.equals(session)){inputReady=false;runOnUiThread(()->{if(current.equals(session))fail(e.getMessage());});}}}
    private void fail(String message){live=false;disconnect();if(!isFinishing())info.setText("已暂停并释放按键："+message+"；点“暂停 / 继续”重连");}
    private void capture(){
        if(frameBusy||!inputReady)return;frameBusy=true;String current=session;
        frameWorker.execute(()->{try{
            JSONObject result=api.call("POST","/desktop",Api.json("action","screenshot","arguments",Api.json("max_size",frameDelay==250?960:1600)));
            byte[] data=Base64.decode(result.getString("image"),Base64.DEFAULT);Bitmap next=BitmapFactory.decodeByteArray(data,0,data.length);if(next==null)throw new Exception("电脑画面无效");
            runOnUiThread(()->{frameBusy=false;if(resumed&&Objects.equals(current,session)){bitmap=next;screen.setImageBitmap(next);}});
        }catch(Exception e){runOnUiThread(()->{frameBusy=false;if(Objects.equals(current,session))fail(e.getMessage());});}});
    }
    private float[] point(float px,float py){
        if(bitmap==null)return null;float scale=Math.min((float)screen.getWidth()/bitmap.getWidth(),(float)screen.getHeight()/bitmap.getHeight());float w=bitmap.getWidth()*scale,h=bitmap.getHeight()*scale;
        float x=(px-(screen.getWidth()-w)/2)/w,y=(py-(screen.getHeight()-h)/2)/h;return x>=0&&x<=1&&y>=0&&y<=1?new float[]{x,y}:null;
    }
    private boolean touch(MotionEvent e){
        if((e.getSource()&InputDevice.SOURCE_MOUSE)==InputDevice.SOURCE_MOUSE)return mouse(e);
        if(e.getActionMasked()==MotionEvent.ACTION_DOWN){lastX=e.getX();lastY=e.getY();touchMoved=false;}
        else if(e.getActionMasked()==MotionEvent.ACTION_MOVE){float dx=e.getX()-lastX,dy=e.getY()-lastY;if(Math.abs(dx)+Math.abs(dy)>2)touchMoved=true;input.move(dx*1.5,dy*1.5);lastX=e.getX();lastY=e.getY();}
        else if(e.getActionMasked()==MotionEvent.ACTION_UP&&!touchMoved){float[] p=point(e.getX(),e.getY());if(p!=null){input.point(p[0],p[1]);hold("tap","mouse:left",true);hold("tap","mouse:left",false);}}return true;
    }
    private boolean mouse(MotionEvent e){
        if((e.getSource()&InputDevice.SOURCE_MOUSE)!=InputDevice.SOURCE_MOUSE)return false;
        float[] p=point(e.getX(),e.getY());if(p!=null)input.point(p[0],p[1]);
        int buttons=e.getActionMasked()==MotionEvent.ACTION_CANCEL?0:e.getButtonState();
        hold("mouse:left","mouse:left",(buttons&MotionEvent.BUTTON_PRIMARY)!=0);hold("mouse:right","mouse:right",(buttons&MotionEvent.BUTTON_SECONDARY)!=0);hold("mouse:middle","mouse:middle",(buttons&MotionEvent.BUTTON_TERTIARY)!=0);
        if(e.getActionMasked()==MotionEvent.ACTION_SCROLL)input.scroll((int)e.getAxisValue(MotionEvent.AXIS_VSCROLL));return true;
    }
    @Override public boolean dispatchGenericMotionEvent(MotionEvent e){
        if((e.getSource()&InputDevice.SOURCE_JOYSTICK)==InputDevice.SOURCE_JOYSTICK){
            float x=e.getAxisValue(MotionEvent.AXIS_X),y=e.getAxisValue(MotionEvent.AXIS_Y),hx=e.getAxisValue(MotionEvent.AXIS_HAT_X),hy=e.getAxisValue(MotionEvent.AXIS_HAT_Y);
            hold("stick:w","w",y<-.35||hy<-.35);hold("stick:s","s",y>.35||hy>.35);hold("stick:a","a",x<-.35||hx<-.35);hold("stick:d","d",x>.35||hx>.35);
            stickX=deadzone(e.getAxisValue(MotionEvent.AXIS_Z));stickY=deadzone(e.getAxisValue(MotionEvent.AXIS_RZ));
            hold("trigger:left",mappings.get(KeyEvent.KEYCODE_BUTTON_L2),Math.max(e.getAxisValue(MotionEvent.AXIS_LTRIGGER),e.getAxisValue(MotionEvent.AXIS_BRAKE))>.4);hold("trigger:right",mappings.get(KeyEvent.KEYCODE_BUTTON_R2),Math.max(e.getAxisValue(MotionEvent.AXIS_RTRIGGER),e.getAxisValue(MotionEvent.AXIS_GAS))>.4);return true;
        }return super.dispatchGenericMotionEvent(e);
    }
    private float deadzone(float value){return Math.abs(value)<.2?0:value;}
    static String keyboard(int code){
        if(code>=KeyEvent.KEYCODE_A&&code<=KeyEvent.KEYCODE_Z)return String.valueOf((char)('a'+code-KeyEvent.KEYCODE_A));
        if(code>=KeyEvent.KEYCODE_0&&code<=KeyEvent.KEYCODE_9)return String.valueOf((char)('0'+code-KeyEvent.KEYCODE_0));
        if(code>=KeyEvent.KEYCODE_F1&&code<=KeyEvent.KEYCODE_F12)return "f"+(code-KeyEvent.KEYCODE_F1+1);
        return switch(code){case KeyEvent.KEYCODE_SPACE->"space";case KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_NUMPAD_ENTER->"enter";case KeyEvent.KEYCODE_ESCAPE->"esc";case KeyEvent.KEYCODE_TAB->"tab";case KeyEvent.KEYCODE_DEL->"backspace";case KeyEvent.KEYCODE_FORWARD_DEL->"delete";case KeyEvent.KEYCODE_DPAD_UP->"up";case KeyEvent.KEYCODE_DPAD_DOWN->"down";case KeyEvent.KEYCODE_DPAD_LEFT->"left";case KeyEvent.KEYCODE_DPAD_RIGHT->"right";case KeyEvent.KEYCODE_SHIFT_LEFT->"shiftleft";case KeyEvent.KEYCODE_SHIFT_RIGHT->"shiftright";case KeyEvent.KEYCODE_CTRL_LEFT->"ctrlleft";case KeyEvent.KEYCODE_CTRL_RIGHT->"ctrlright";case KeyEvent.KEYCODE_ALT_LEFT->"altleft";case KeyEvent.KEYCODE_ALT_RIGHT->"altright";case KeyEvent.KEYCODE_MOVE_HOME->"home";case KeyEvent.KEYCODE_MOVE_END->"end";case KeyEvent.KEYCODE_PAGE_UP->"pageup";case KeyEvent.KEYCODE_PAGE_DOWN->"pagedown";case KeyEvent.KEYCODE_MINUS->"-";case KeyEvent.KEYCODE_EQUALS->"=";case KeyEvent.KEYCODE_COMMA->",";case KeyEvent.KEYCODE_PERIOD->".";case KeyEvent.KEYCODE_SLASH->"/";case KeyEvent.KEYCODE_BACKSLASH->"\\";case KeyEvent.KEYCODE_LEFT_BRACKET->"[";case KeyEvent.KEYCODE_RIGHT_BRACKET->"]";case KeyEvent.KEYCODE_SEMICOLON->";";case KeyEvent.KEYCODE_APOSTROPHE->"'";case KeyEvent.KEYCODE_GRAVE->"`";default->null;};
    }
    @Override public boolean dispatchKeyEvent(KeyEvent e){
        boolean game=(e.getSource()&InputDevice.SOURCE_GAMEPAD)==InputDevice.SOURCE_GAMEPAD;String action=game?mappings.getOrDefault(e.getKeyCode(),keyboard(e.getKeyCode())):keyboard(e.getKeyCode());
        if(action!=null&&inputReady){if(e.getRepeatCount()==0)hold("key:"+e.getDeviceId()+":"+e.getKeyCode(),action,e.getAction()==KeyEvent.ACTION_DOWN);return true;}return super.dispatchKeyEvent(e);
    }
    private void loadMappings(){int[] codes={KeyEvent.KEYCODE_BUTTON_A,KeyEvent.KEYCODE_BUTTON_B,KeyEvent.KEYCODE_BUTTON_X,KeyEvent.KEYCODE_BUTTON_Y,KeyEvent.KEYCODE_BUTTON_L1,KeyEvent.KEYCODE_BUTTON_R1,KeyEvent.KEYCODE_BUTTON_START,KeyEvent.KEYCODE_BUTTON_SELECT,KeyEvent.KEYCODE_BUTTON_L2,KeyEvent.KEYCODE_BUTTON_R2};String[] defaults={"space","esc","r","e","shift","ctrl","enter","tab","mouse:right","mouse:left"};for(int i=0;i<codes.length;i++)mappings.put(codes[i],prefs.get("gamepad_"+codes[i],defaults[i]));}
    private void mappingDialog(){
        disconnect();String[] labels={"A","B","X","Y","LB","RB","Start","Select","LT","RT"};int[] codes={96,97,99,100,102,103,108,109,104,105};
        new AlertDialog.Builder(this).setTitle("手柄映射 · 左摇杆 WASD / 右摇杆鼠标").setItems(labels,(d,index)->{
            String[] options={"space","enter","esc","tab","shift","ctrl","e","r","f","q","mouse:left","mouse:right"};
            new AlertDialog.Builder(this).setTitle(labels[index]+" → "+mappings.get(codes[index])).setItems(options,(dialog,pick)->{prefs.put("gamepad_"+codes[index],options[pick]);loadMappings();connect();}).setOnCancelListener(dialog->connect()).show();
        }).setNegativeButton("关闭",(d,w)->connect()).setOnCancelListener(d->connect()).show();
    }
    private void quality(){disconnect();new AlertDialog.Builder(this).setTitle("画面质量（实际帧率取决于网络）").setItems(new String[]{"流畅：960px，最高约 4 帧/秒","清晰：1600px，最高约 2 帧/秒"},(d,w)->{frameDelay=w==0?250:500;connect();}).setOnCancelListener(d->connect()).show();}
    private void typeText(){disconnect();EditText field=new EditText(this);field.setHint("向电脑当前焦点输入中文或文字");new AlertDialog.Builder(this).setTitle("输入文字").setView(field).setPositiveButton("发送",(d,w)->{String text=field.getText().toString();frameWorker.execute(()->{try{api.call("POST","/desktop",Api.json("action","type_text","arguments",Api.json("text",text)));runOnUiThread(this::connect);}catch(Exception e){runOnUiThread(()->fail(e.getMessage()));}});}).setNegativeButton("取消",(d,w)->connect()).setOnCancelListener(d->connect()).show();}
    @Override public void onConfigurationChanged(Configuration config){super.onConfigurationChanged(config);disconnect();connect();}
    @Override public void onWindowFocusChanged(boolean focus){super.onWindowFocusChanged(focus);if(!focus&&session!=null)disconnect();else if(focus&&resumed&&live)connect();}
    @Override protected void onResume(){super.onResume();resumed=true;connect();handler.post(frames);handler.post(stick);}
    @Override protected void onPause(){resumed=false;handler.removeCallbacks(frames);handler.removeCallbacks(stick);disconnect();super.onPause();}
    @Override public void onInputDeviceAdded(int id){}
    @Override public void onInputDeviceChanged(int id){disconnect();if(resumed&&live)connect();}
    @Override public void onInputDeviceRemoved(int id){disconnect();if(resumed&&live)connect();}
    @Override protected void onDestroy(){((InputManager)getSystemService(INPUT_SERVICE)).unregisterInputDeviceListener(this);inputWorker.shutdown();frameWorker.shutdown();super.onDestroy();}
}
