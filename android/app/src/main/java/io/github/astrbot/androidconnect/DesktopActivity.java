package io.github.astrbot.androidconnect;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.hardware.input.InputManager;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
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
    private VirtualPad pad;private Bitmap bitmap;private boolean frameBusy,live=true,resumed;
    private TextureView videoView;private DesktopVideo video;private Surface videoSurface;private LinearLayout chrome;
    private boolean videoSupported,editing,fill;private int dialogDepth,videoWidth,videoHeight;
    private String videoMode;private int videoSize=1280;private Button editButton;
    private DesktopChannel channel;private boolean streamInput;private DesktopTouch gestures;private DesktopPointer pointer;
    private boolean directTouch;private float sensitivity=1,mouseWheel;
    private final Runnable hideTools=()->{if(!editing&&dialogDepth==0&&live)chrome.setVisibility(View.GONE);};
    private volatile String session;private volatile boolean inputReady;private long sequence;
    private float stickX,stickY;private int frameDelay=250;private long lastHttpInput;
    private final Runnable frames=new Runnable(){public void run(){if(resumed){if(live)capture();handler.postDelayed(this,frameDelay);}}};
    private final Runnable stick=new Runnable(){public void run(){if(resumed){if(inputReady)input.move(stickX*18,stickY*18);handler.postDelayed(this,50);}}};
    private int dp(int v){return (int)(v*getResources().getDisplayMetrics().density+.5f);}
    private Button button(String title,Runnable action){Button b=new Button(this);b.setText(title);b.setTextSize(12);b.setAllCaps(false);b.setTextColor(Color.WHITE);GradientDrawable bg=new GradientDrawable();bg.setColor(Color.rgb(46,60,91));bg.setCornerRadius(dp(10));b.setBackground(bg);b.setPadding(dp(10),dp(4),dp(10),dp(4));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,dp(44));lp.setMargins(dp(3),dp(4),dp(3),dp(4));b.setLayoutParams(lp);b.setOnClickListener(v->action.run());return b;}
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE|WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs=new Prefs(this);api=new Api(prefs);loadMappings();videoMode=prefs.get("video_mode","30");videoSize=Integer.parseInt(prefs.get("video_size","1280"));frameDelay=Integer.parseInt(prefs.get("frame_delay","250"));
        directTouch=prefs.get("touch_mode","trackpad").equals("direct");sensitivity=Float.parseFloat(prefs.get("mouse_sensitivity","1"));
        gestures=new DesktopTouch(this,new DesktopTouch.Sink(){
            public void move(float x,float y){input.move(x*2.5/getResources().getDisplayMetrics().density,y*2.5/getResources().getDisplayMetrics().density);}
            public boolean point(float x,float y){float[] point=DesktopActivity.this.point(x,y);if(point==null)return false;input.point(point[0],point[1]);return true;}
            public void hold(boolean down){DesktopActivity.this.hold("touch:drag","mouse:left",down);}
            public void click(boolean right){String action=right?"mouse:right":"mouse:left";DesktopActivity.this.hold("touch:tap",action,true);DesktopActivity.this.hold("touch:tap",action,false);}
            public void scroll(int steps){input.scroll(steps);}
        });gestures.configure(directTouch,sensitivity);
        ((InputManager)getSystemService(INPUT_SERVICE)).registerInputDeviceListener(this,handler);
        FrameLayout root=new FrameLayout(this);root.setBackgroundColor(Color.BLACK);
        videoView=new TextureView(this);root.addView(videoView,new FrameLayout.LayoutParams(-1,-1));
        screen=new ImageView(this);screen.setScaleType(ImageView.ScaleType.FIT_CENTER);screen.setFocusable(true);screen.setFocusableInTouchMode(true);root.addView(screen,new FrameLayout.LayoutParams(-1,-1));
        pointer=new DesktopPointer(this);root.addView(pointer,new FrameLayout.LayoutParams(-1,-1));
        pad=new VirtualPad(this,prefs,this::hold);root.addView(pad,new FrameLayout.LayoutParams(-1,-1));
        chrome=new LinearLayout(this);chrome.setOrientation(LinearLayout.VERTICAL);chrome.setBackgroundColor(0xcc141822);root.addView(chrome,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));
        HorizontalScrollView tools=new HorizontalScrollView(this);LinearLayout bar=new LinearLayout(this);tools.addView(bar);chrome.addView(tools);
        bar.addView(button("返回",this::finish));bar.addView(button("横屏 / 竖屏",()->setRequestedOrientation(getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE?ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT:ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)));
        bar.addView(button("虚拟按键",()->{disconnect();pad.setVisibility(pad.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE);if(live)connect();}));
        editButton=button("编辑按键",()->{editing=!editing;disconnect();pad.setVisibility(View.VISIBLE);pad.edit(editing);editButton.setText(editing?"完成布局":"编辑按键");if(editing){chrome.setVisibility(View.VISIBLE);info.setText("拖动按键调整位置；轻点按键修改或删除；可横竖屏分别查看布局");}else{pad.save();connect();}});bar.addView(editButton);
        bar.addView(button("添加按键",()->{if(!editing)editButton.performClick();pad.add();}));
        bar.addView(button("按键透明度",()->{if(!editing)editButton.performClick();pad.opacityDialog();}));
        bar.addView(button("适应 / 铺满",()->{fill=!fill;screen.setScaleType(fill?ImageView.ScaleType.CENTER_CROP:ImageView.ScaleType.FIT_CENTER);pointer.fill(fill);transformVideo();}));
        bar.addView(button("暂停 / 继续",()->{live=!live;if(!live)disconnect();else connect();}));
        bar.addView(button("画质 / 刷新率",this::quality));bar.addView(button("手柄映射",this::mappingDialog));
        bar.addView(button("鼠标与触控",this::mouseSettings));
        bar.addView(button("锁定 / 释放外接鼠标",()->{if(screen.hasPointerCapture())screen.releasePointerCapture();else if(inputReady){screen.requestFocus();screen.requestPointerCapture();}}));
        bar.addView(button("输入文字",this::typeText));bar.addView(button("释放按键",()->{disconnect();connect();}));
        info=new TextView(this);info.setTextColor(Color.WHITE);info.setTextSize(12);info.setText("连接桌面中 · USB / 蓝牙键鼠及手柄可直接接入手机");chrome.addView(info);
        Button menu=button("菜单",()->{chrome.setVisibility(chrome.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE);immersive();handler.removeCallbacks(hideTools);handler.postDelayed(hideTools,7000);});FrameLayout.LayoutParams menuPosition=new FrameLayout.LayoutParams(dp(62),dp(42),Gravity.TOP|Gravity.END);menuPosition.topMargin=dp(66);menuPosition.rightMargin=dp(8);root.addView(menu,menuPosition);menu.setAlpha(.7f);
        screen.setOnTouchListener((v,event)->touch(event));screen.setOnGenericMotionListener((v,e)->mouse(e));
        screen.setOnCapturedPointerListener((v,e)->{if(!inputReady)return true;if(e.getActionMasked()==MotionEvent.ACTION_MOVE)input.move(e.getX(),e.getY());mouseButtons(e);scrollMouse(e);return true;});
        videoView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener(){
            public void onSurfaceTextureAvailable(SurfaceTexture texture,int w,int h){videoSurface=new Surface(texture);startVideo();}
            public void onSurfaceTextureSizeChanged(SurfaceTexture texture,int w,int h){transformVideo();}
            public boolean onSurfaceTextureDestroyed(SurfaceTexture texture){stopVideo();if(videoSurface!=null)videoSurface.release();videoSurface=null;return true;}
            public void onSurfaceTextureUpdated(SurfaceTexture texture){}
        });
        setContentView(root);immersive();screen.requestFocus();handler.postDelayed(hideTools,7000);
        inputWorker.scheduleWithFixedDelay(this::flush,0,16,TimeUnit.MILLISECONDS);
    }
    private void immersive(){
        if(Build.VERSION.SDK_INT>=30){getWindow().setDecorFitsSystemWindows(false);WindowInsetsController c=getWindow().getInsetsController();if(c!=null){c.hide(WindowInsets.Type.systemBars());c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);}}
        else getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        if(Build.VERSION.SDK_INT>=28){WindowManager.LayoutParams p=getWindow().getAttributes();if(p.layoutInDisplayCutoutMode!=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES){p.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;getWindow().setAttributes(p);}}
    }
    private void transformVideo(){if(videoWidth==0||videoView.getWidth()==0)return;float sx=(float)videoView.getWidth()/videoWidth,sy=(float)videoView.getHeight()/videoHeight,scale=fill?Math.max(sx,sy):Math.min(sx,sy);float w=videoWidth*scale,h=videoHeight*scale;Matrix matrix=new Matrix();matrix.setScale(w/videoView.getWidth(),h/videoView.getHeight());matrix.postTranslate((videoView.getWidth()-w)/2,(videoView.getHeight()-h)/2);videoView.setTransform(matrix);}
    private void startVideo(){
        if(!inputReady||!resumed||!live||video!=null||!videoSupported||videoMode.equals("jpeg"))return;
        videoView.setVisibility(View.VISIBLE);if(videoSurface==null)return;
        String current=session;screen.setImageDrawable(null);
        try{video=new DesktopVideo(prefs,videoSurface,current,Integer.parseInt(videoMode),videoSize,new DesktopVideo.Listener(){
            public void status(int width,int height,int fps){runOnUiThread(()->{if(!Objects.equals(current,session))return;videoWidth=width;videoHeight=height;transformVideo();info.setText("H.264 · 目标 "+videoMode+" FPS · 实际解码 "+fps+" FPS · "+width+"×"+height);});}
            public void error(String message){runOnUiThread(()->{if(Objects.equals(current,session))fail(message);});}
        });}catch(Exception e){fail(e.getMessage());}
    }
    private void stopVideo(){if(video!=null){video.close();video=null;}videoWidth=0;videoHeight=0;}
    private void hold(String source,String action,boolean down){if(!inputReady)return;try{input.hold(source,action,down);}catch(Exception e){fail(e.getMessage());}}
    boolean readyForInput(){return inputReady&&resumed&&hasWindowFocus();}
    String connectionInfo(){return info.getText().toString()+" [ready="+inputReady+", resumed="+resumed+", live="+live+", session="+(session!=null)+", focus="+hasWindowFocus()+", destroyed="+isDestroyed()+"]";}
    private void connect(){
        if(!resumed||!hasWindowFocus()||!live||editing||dialogDepth>0||session!=null)return;input.clear();String next=UUID.randomUUID().toString();session=next;inputReady=false;
        inputWorker.execute(()->{try{JSONObject status=api.call("GET","/status",null);JSONObject opened=api.call("POST","/desktop/session",Api.json("session",next));runOnUiThread(()->{if(!next.equals(session))return;sequence=0;videoSupported=status.optBoolean("desktop_video");streamInput=status.optBoolean("desktop_input_stream");pointer.update(opened.optJSONObject("cursor"));
            if(streamInput){try{channel=new DesktopChannel(prefs,next,new DesktopChannel.Listener(){
                public void ready(JSONObject cursor){runOnUiThread(()->{if(next.equals(session)){pointer.update(cursor);connected();}});}
                public void cursor(JSONObject cursor){runOnUiThread(()->{if(next.equals(session))pointer.update(cursor);});}
                public void error(String text){runOnUiThread(()->{if(next.equals(session))fail(text);});}
            });}catch(Exception e){fail(e.getMessage());}}else connected();
        });}catch(Exception e){if(next.equals(session))runOnUiThread(()->{if(next.equals(session))fail(e.getMessage());});}});
    }
    private void connected(){inputReady=true;info.setText(videoSupported&&!videoMode.equals("jpeg")?"正在连接 H.264 视频…":(streamInput?"实时输入":"兼容输入")+" · "+(directTouch?"触屏定位":"触摸板")+" · 长按拖拽，双指滚动");startVideo();}
    private void disconnect(){String old=session;session=null;inputReady=false;if(gestures!=null)gestures.cancel();if(channel!=null){channel.close();channel=null;}if(screen!=null&&screen.hasPointerCapture())screen.releasePointerCapture();if(pointer!=null)pointer.clear();stopVideo();input.clear();mouseWheel=0;stickX=0;stickY=0;if(old!=null)inputWorker.execute(()->{try{api.call("POST","/desktop/release",Api.json("session",old));}catch(Exception ignored){}});}
    private void flush(){String current=session;if(current==null||!inputReady)return;try{
        if(streamInput){DesktopChannel active=channel;if(active!=null)active.flush(input,current);}
        else {long now=System.nanoTime();if(now-lastHttpInput<50_000_000L)return;lastHttpInput=now;JSONObject reply=api.call("POST","/desktop/input",input.packet(current,++sequence));runOnUiThread(()->{if(current.equals(session))pointer.update(reply.optJSONObject("cursor"));});}
    }catch(Exception e){if(current.equals(session)){inputReady=false;runOnUiThread(()->{if(current.equals(session))fail(e.getMessage());});}}}
    private void fail(String message){live=false;disconnect();if(!isFinishing()){chrome.setVisibility(View.VISIBLE);info.setText("已暂停并释放按键："+message+"；点“暂停 / 继续”重连，或切换截图兼容模式");}}
    private void capture(){
        if(frameBusy||!inputReady||(videoSupported&&!videoMode.equals("jpeg")))return;frameBusy=true;String current=session;videoView.setVisibility(View.INVISIBLE);
        frameWorker.execute(()->{try{
            JSONObject result=api.call("POST","/desktop",Api.json("action","screenshot","arguments",Api.json("max_size",frameDelay==250?960:1600)));
            byte[] data=Base64.decode(result.getString("image"),Base64.DEFAULT);Bitmap next=BitmapFactory.decodeByteArray(data,0,data.length);if(next==null)throw new Exception("电脑画面无效");
            runOnUiThread(()->{frameBusy=false;if(resumed&&Objects.equals(current,session)){bitmap=next;screen.setImageBitmap(next);}});
        }catch(Exception e){runOnUiThread(()->{frameBusy=false;if(Objects.equals(current,session))fail(e.getMessage());});}});
    }
    private float[] point(float px,float py){
        int width=videoWidth>0?videoWidth:bitmap==null?0:bitmap.getWidth(),height=videoHeight>0?videoHeight:bitmap==null?0:bitmap.getHeight();if(width==0||height==0)return null;float sx=(float)screen.getWidth()/width,sy=(float)screen.getHeight()/height,scale=fill?Math.max(sx,sy):Math.min(sx,sy);float w=width*scale,h=height*scale;
        float x=(px-(screen.getWidth()-w)/2)/w,y=(py-(screen.getHeight()-h)/2)/h;return x>=0&&x<=1&&y>=0&&y<=1?new float[]{x,y}:null;
    }
    private boolean touch(MotionEvent e){
        if(!inputReady)return true;
        if((e.getSource()&InputDevice.SOURCE_MOUSE)==InputDevice.SOURCE_MOUSE)return mouse(e);
        return gestures.handle(e);
    }
    private boolean mouse(MotionEvent e){
        if(!inputReady)return false;
        if((e.getSource()&InputDevice.SOURCE_MOUSE)!=InputDevice.SOURCE_MOUSE)return false;
        float[] p=point(e.getX(),e.getY());if(p!=null)input.point(p[0],p[1]);
        mouseButtons(e);scrollMouse(e);return true;
    }
    private void mouseButtons(MotionEvent e){int buttons=e.getActionMasked()==MotionEvent.ACTION_CANCEL?0:e.getButtonState();
        hold("mouse:left","mouse:left",(buttons&MotionEvent.BUTTON_PRIMARY)!=0);hold("mouse:right","mouse:right",(buttons&MotionEvent.BUTTON_SECONDARY)!=0);hold("mouse:middle","mouse:middle",(buttons&MotionEvent.BUTTON_TERTIARY)!=0);
    }
    private void scrollMouse(MotionEvent e){if(e.getActionMasked()==MotionEvent.ACTION_SCROLL){mouseWheel+=e.getAxisValue(MotionEvent.AXIS_VSCROLL);int ticks=(int)mouseWheel;input.scroll(ticks);mouseWheel-=ticks;}}
    private void mouseSettings(){
        LinearLayout box=new LinearLayout(this);box.setPadding(dp(20),dp(8),dp(20),dp(8));box.setOrientation(LinearLayout.VERTICAL);
        TextView help=new TextView(this);help.setText("触摸板：滑动移动指针，轻点在当前指针位置单击。\n触屏定位：指针移动到手指触摸的位置。\n两种模式均支持长按后拖拽、双指轻点右键、双指上下滑动滚动。\n外接鼠标可在菜单锁定，用于相对移动；按 Esc 或切换窗口可释放。");box.addView(help);
        RadioGroup modes=new RadioGroup(this);RadioButton trackpad=new RadioButton(this);trackpad.setId(View.generateViewId());trackpad.setText("触摸板（推荐）");modes.addView(trackpad);RadioButton direct=new RadioButton(this);direct.setId(View.generateViewId());direct.setText("触屏定位");modes.addView(direct);modes.check(directTouch?direct.getId():trackpad.getId());box.addView(modes);
        TextView value=new TextView(this);box.addView(value);SeekBar speed=new SeekBar(this);speed.setMax(25);speed.setProgress(Math.round((sensitivity-.5f)*10));value.setText("触摸板灵敏度："+String.format(Locale.ROOT,"%.1f",sensitivity)+" 倍");box.addView(speed);
        speed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int p,boolean user){value.setText("触摸板灵敏度："+String.format(Locale.ROOT,"%.1f",.5f+p/10f)+" 倍");}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}});
        showDialog(new AlertDialog.Builder(this).setTitle("鼠标与触控").setView(box).setPositiveButton("保存",(d,w)->{directTouch=modes.getCheckedRadioButtonId()==direct.getId();sensitivity=.5f+speed.getProgress()/10f;prefs.put("touch_mode",directTouch?"direct":"trackpad");prefs.put("mouse_sensitivity",Float.toString(sensitivity));gestures.configure(directTouch,sensitivity);}).setNegativeButton("取消",null).create());
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
        if(e.getKeyCode()==KeyEvent.KEYCODE_ESCAPE&&screen.hasPointerCapture()){screen.releasePointerCapture();input.clear();return true;}
        boolean game=(e.getSource()&InputDevice.SOURCE_GAMEPAD)==InputDevice.SOURCE_GAMEPAD;String action=game?mappings.getOrDefault(e.getKeyCode(),keyboard(e.getKeyCode())):keyboard(e.getKeyCode());
        if(action!=null&&inputReady){if(e.getRepeatCount()==0)hold("key:"+e.getDeviceId()+":"+e.getKeyCode(),action,e.getAction()==KeyEvent.ACTION_DOWN);return true;}return super.dispatchKeyEvent(e);
    }
    private void loadMappings(){int[] codes={KeyEvent.KEYCODE_BUTTON_A,KeyEvent.KEYCODE_BUTTON_B,KeyEvent.KEYCODE_BUTTON_X,KeyEvent.KEYCODE_BUTTON_Y,KeyEvent.KEYCODE_BUTTON_L1,KeyEvent.KEYCODE_BUTTON_R1,KeyEvent.KEYCODE_BUTTON_START,KeyEvent.KEYCODE_BUTTON_SELECT,KeyEvent.KEYCODE_BUTTON_L2,KeyEvent.KEYCODE_BUTTON_R2};String[] defaults={"space","esc","r","e","shift","ctrl","enter","tab","mouse:right","mouse:left"};for(int i=0;i<codes.length;i++)mappings.put(codes[i],prefs.get("gamepad_"+codes[i],defaults[i]));}
    private void mappingDialog(){
        disconnect();String[] labels={"A","B","X","Y","LB","RB","Start","Select","LT","RT"};int[] codes={96,97,99,100,102,103,108,109,104,105};
        showDialog(new AlertDialog.Builder(this).setTitle("手柄映射 · 左摇杆 WASD / 右摇杆鼠标").setItems(labels,(d,index)->{
            String[] options={"space","enter","esc","tab","shift","ctrl","e","r","f","q","mouse:left","mouse:right"};
            showDialog(new AlertDialog.Builder(this).setTitle(labels[index]+" → "+mappings.get(codes[index])).setItems(options,(dialog,pick)->{prefs.put("gamepad_"+codes[index],options[pick]);loadMappings();}).create());
        }).setNegativeButton("关闭",null).create());
    }
    private void showDialog(AlertDialog dialog){dialogDepth++;disconnect();dialog.setOnDismissListener(d->{dialogDepth=Math.max(0,dialogDepth-1);if(resumed){immersive();connect();}});dialog.show();}
    private void quality(){showDialog(new AlertDialog.Builder(this).setTitle("目标帧率 · 实际帧率受电脑与网络影响").setItems(new String[]{"H.264 · 60 FPS · 1280px","H.264 · 60 FPS · 1920px","H.264 · 30 FPS · 1280px","H.264 · 30 FPS · 1920px","截图兼容 · 960px · 最高约 4 FPS","截图兼容 · 1600px · 最高约 2 FPS"},(d,w)->{videoMode=w<2?"60":w<4?"30":"jpeg";videoSize=w%2==0?1280:1920;frameDelay=w==5?500:250;prefs.put("video_mode",videoMode);prefs.put("video_size",Integer.toString(videoSize));prefs.put("frame_delay",Integer.toString(frameDelay));live=true;}).create());}
    private void typeText(){EditText field=new EditText(this);field.setHint("向电脑当前焦点输入中文或文字");showDialog(new AlertDialog.Builder(this).setTitle("输入文字").setView(field).setPositiveButton("发送",(d,w)->{String text=field.getText().toString();frameWorker.execute(()->{try{api.call("POST","/desktop",Api.json("action","type_text","arguments",Api.json("text",text)));}catch(Exception e){runOnUiThread(()->fail(e.getMessage()));}});}).setNegativeButton("取消",null).create());}
    @Override public void onConfigurationChanged(Configuration config){
        super.onConfigurationChanged(config);
        immersive();
        pad.repositionForConfiguration();
        transformVideo();
        screen.requestFocus();
        handler.postDelayed(()->{
            if(isFinishing()||isDestroyed())return;
            transformVideo();
            pad.refreshLayout();
            screen.requestFocus();
            if(session!=null&&inputReady)startVideo();
        },80);
    }
    @Override public void onWindowFocusChanged(boolean focus){super.onWindowFocusChanged(focus);if(!focus&&session!=null)disconnect();else if(focus&&resumed&&live)connect();}
    @Override protected void onResume(){super.onResume();resumed=true;immersive();connect();handler.post(frames);handler.post(stick);}
    @Override protected void onPause(){resumed=false;handler.removeCallbacks(frames);handler.removeCallbacks(stick);disconnect();super.onPause();}
    @Override public void onInputDeviceAdded(int id){}
    @Override public void onInputDeviceChanged(int id){disconnect();if(resumed&&live)connect();}
    @Override public void onInputDeviceRemoved(int id){disconnect();if(resumed&&live)connect();}
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);stopVideo();((InputManager)getSystemService(INPUT_SERVICE)).unregisterInputDeviceListener(this);inputWorker.shutdown();frameWorker.shutdown();super.onDestroy();}
}
