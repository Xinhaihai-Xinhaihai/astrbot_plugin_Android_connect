package io.github.astrbot.androidconnect;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import org.json.JSONObject;

/** Display the host's acknowledged pointer, including in screenshot compatibility mode. */
final class DesktopPointer extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private float x=.5f,y=.5f;private int width,height;private boolean known,fill;
    DesktopPointer(Context context){super(context);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    void update(JSONObject cursor){if(cursor==null)return;x=(float)cursor.optDouble("x",.5);y=(float)cursor.optDouble("y",.5);width=cursor.optInt("width");height=cursor.optInt("height");known=cursor.optBoolean("visible",true);invalidate();}
    void fill(boolean value){fill=value;invalidate();}
    void clear(){known=false;invalidate();}
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);if(!known||width<=0||height<=0)return;
        float scale=fill?Math.max((float)getWidth()/width,(float)getHeight()/height):Math.min((float)getWidth()/width,(float)getHeight()/height);
        float cx=(getWidth()-width*scale)/2+x*width*scale,cy=(getHeight()-height*scale)/2+y*height*scale;
        float radius=8*getResources().getDisplayMetrics().density;
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(4);paint.setColor(Color.BLACK);canvas.drawCircle(cx,cy,radius,paint);
        paint.setStrokeWidth(2);paint.setColor(Color.WHITE);canvas.drawCircle(cx,cy,radius,paint);
        canvas.drawLine(cx-radius-4,cy,cx+radius+4,cy,paint);canvas.drawLine(cx,cy-radius-4,cx,cy+radius+4,paint);
    }
}
