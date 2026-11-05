package io.github.astrbot.androidconnect;

import android.app.Instrumentation;
import android.os.SystemClock;
import android.view.MotionEvent;
import java.util.*;

final class PointerChecks {
    static final class Sink implements DesktopTouch.Sink{
        float dx,dy;int points,clicks,right,scroll;boolean held;final List<Boolean> transitions=new ArrayList<>();
        public void move(float x,float y){dx+=x;dy+=y;}public boolean point(float x,float y){points++;return true;}
        public void hold(boolean down){held=down;transitions.add(down);}public void click(boolean r){if(r)right++;else clicks++;}public void scroll(int n){scroll+=n;}
    }
    static void event(DesktopTouch touch,long start,int action,float x,float y){
        MotionEvent event=MotionEvent.obtain(start,SystemClock.uptimeMillis(),action,x,y,0);touch.handle(event);event.recycle();
    }
    static void two(DesktopTouch touch,long start,int action,float y){
        MotionEvent.PointerProperties[] properties=new MotionEvent.PointerProperties[2];MotionEvent.PointerCoords[] positions=new MotionEvent.PointerCoords[2];
        for(int i=0;i<2;i++){properties[i]=new MotionEvent.PointerProperties();properties[i].id=i;properties[i].toolType=MotionEvent.TOOL_TYPE_FINGER;positions[i]=new MotionEvent.PointerCoords();positions[i].x=100+i*60;positions[i].y=y;}
        MotionEvent event=MotionEvent.obtain(start,SystemClock.uptimeMillis(),action,2,properties,positions,0,0,1,1,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0);touch.handle(event);event.recycle();
    }
    static void run(Instrumentation test)throws Exception{
        Sink sink=new Sink();DesktopTouch[] touch={null};test.runOnMainSync(()->touch[0]=new DesktopTouch(test.getTargetContext(),sink));
        test.runOnMainSync(()->{long now=SystemClock.uptimeMillis();event(touch[0],now,MotionEvent.ACTION_DOWN,400,300);event(touch[0],now,MotionEvent.ACTION_UP,400,300);});
        if(sink.points!=0||sink.clicks!=1)throw new Exception("Trackpad tap must click current cursor without teleport");
        test.runOnMainSync(()->{long now=SystemClock.uptimeMillis();event(touch[0],now,MotionEvent.ACTION_DOWN,100,100);event(touch[0],now,MotionEvent.ACTION_MOVE,220,140);event(touch[0],now,MotionEvent.ACTION_UP,220,140);});
        if(sink.dx!=120||sink.dy!=40||sink.clicks!=1)throw new Exception("Trackpad movement or accidental click");
        long start=SystemClock.uptimeMillis();test.runOnMainSync(()->event(touch[0],start,MotionEvent.ACTION_DOWN,100,100));Thread.sleep(700);
        test.runOnMainSync(()->{event(touch[0],start,MotionEvent.ACTION_MOVE,180,130);event(touch[0],start,MotionEvent.ACTION_CANCEL,180,130);});
        if(sink.held||!sink.transitions.equals(Arrays.asList(true,false)))throw new Exception("Long-press drag did not release on cancel");
        test.runOnMainSync(()->{long now=SystemClock.uptimeMillis();event(touch[0],now,MotionEvent.ACTION_DOWN,100,100);two(touch[0],now,MotionEvent.ACTION_POINTER_DOWN|(1<<8),100);two(touch[0],now,MotionEvent.ACTION_MOVE,400);two(touch[0],now,MotionEvent.ACTION_POINTER_UP|(1<<8),400);event(touch[0],now,MotionEvent.ACTION_UP,100,400);});
        if(sink.scroll<=0||sink.clicks!=1||sink.right!=0)throw new Exception("Two-finger scroll clicked or failed");
        test.runOnMainSync(()->{long now=SystemClock.uptimeMillis();event(touch[0],now,MotionEvent.ACTION_DOWN,100,100);two(touch[0],now,MotionEvent.ACTION_POINTER_DOWN|(1<<8),100);two(touch[0],now,MotionEvent.ACTION_POINTER_UP|(1<<8),100);event(touch[0],now,MotionEvent.ACTION_UP,100,100);});
        if(sink.right!=1)throw new Exception("Two-finger tap did not right-click");
        test.runOnMainSync(()->{touch[0].configure(true,1);long now=SystemClock.uptimeMillis();event(touch[0],now,MotionEvent.ACTION_DOWN,400,300);event(touch[0],now,MotionEvent.ACTION_UP,400,300);touch[0].cancel();});
        if(sink.points!=1||sink.clicks!=2)throw new Exception("Direct touch did not map position");
        DesktopInput queue=new DesktopInput();for(int i=0;i<5;i++){queue.move(.3,0);queue.packet("test",i);}queue.move(.6,0);
        if(queue.packet("test",6).getInt("dx")!=1)throw new Exception("Subpixel mouse movements were lost");
    }
}
