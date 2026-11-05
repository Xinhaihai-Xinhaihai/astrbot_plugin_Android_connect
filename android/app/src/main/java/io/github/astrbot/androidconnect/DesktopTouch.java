package io.github.astrbot.androidconnect;

import android.os.Handler;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.content.Context;

/** Trackpad gestures remain relative; direct-touch mode uses mapped desktop positions. */
final class DesktopTouch {
    interface Sink {void move(float x,float y);boolean point(float x,float y);void hold(boolean down);void click(boolean right);void scroll(int steps);}
    private final Handler timer=new Handler();
    private final Sink sink;
    private final float slop,scrollStep;
    private float x,y,originX,originY,scroll;
    private int pointer=-1;
    private long started;
    private boolean moved,two,dragging,active,valid=true,direct;
    private float sensitivity=1;
    private final Runnable longPress=()->{if(active&&!moved&&!two&&valid){dragging=true;sink.hold(true);}};
    DesktopTouch(Context context,Sink sink){this.sink=sink;slop=ViewConfiguration.get(context).getScaledTouchSlop();scrollStep=36*context.getResources().getDisplayMetrics().density;}
    void configure(boolean direct,float sensitivity){cancel();this.direct=direct;this.sensitivity=sensitivity;}
    void cancel(){timer.removeCallbacks(longPress);if(dragging)sink.hold(false);active=false;dragging=false;pointer=-1;scroll=0;}
    boolean handle(MotionEvent e){
        int action=e.getActionMasked();
        if(action==MotionEvent.ACTION_CANCEL){cancel();return true;}
        if(action==MotionEvent.ACTION_DOWN){
            cancel();active=true;pointer=e.getPointerId(0);x=originX=e.getX();y=originY=e.getY();started=e.getEventTime();moved=false;two=false;
            valid=!direct||sink.point(x,y);timer.postDelayed(longPress,ViewConfiguration.getLongPressTimeout());return true;
        }
        if(!active)return true;
        if(action==MotionEvent.ACTION_POINTER_DOWN){
            timer.removeCallbacks(longPress);if(dragging){sink.hold(false);dragging=false;}two=true;
            x=(e.getX(0)+e.getX(1))/2;y=(e.getY(0)+e.getY(1))/2;originX=x;originY=y;return true;
        }
        if(action==MotionEvent.ACTION_MOVE){
            if(two){
                if(e.getPointerCount()<2)return true;
                float nextX=(e.getX(0)+e.getX(1))/2,nextY=(e.getY(0)+e.getY(1))/2;
                if(Math.hypot(nextX-originX,nextY-originY)>slop)moved=true;
                if(moved){scroll+=nextY-y;int steps=(int)(scroll/scrollStep);if(steps!=0){sink.scroll(steps);scroll-=steps*scrollStep;}}
                x=nextX;y=nextY;return true;
            }
            int index=e.findPointerIndex(pointer);if(index<0){cancel();return true;}
            float nextX=e.getX(index),nextY=e.getY(index);
            if(Math.hypot(nextX-originX,nextY-originY)>slop){moved=true;timer.removeCallbacks(longPress);}
            if(direct){valid=sink.point(nextX,nextY);}else sink.move((nextX-x)*sensitivity,(nextY-y)*sensitivity);
            x=nextX;y=nextY;return true;
        }
        if(action==MotionEvent.ACTION_POINTER_UP)return true;
        if(action==MotionEvent.ACTION_UP){
            timer.removeCallbacks(longPress);
            if(dragging)sink.hold(false);
            else if(!moved&&valid&&e.getEventTime()-started<600)sink.click(two);
            active=false;dragging=false;pointer=-1;scroll=0;return true;
        }
        return true;
    }
}
