package io.github.astrbot.androidconnect;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

/** State transitions are retained; mouse movement is coalesced, never replayed after failure. */
final class DesktopInput {
    private final Map<String,String> sources=new HashMap<>();
    private final ArrayDeque<JSONObject> transitions=new ArrayDeque<>();
    private double dx,dy;
    private int wheel;
    private Double x,y;
    synchronized void hold(String source,String action,boolean pressed) {
        String before=sources.get(source);
        if(pressed) sources.put(source,action); else sources.remove(source);
        if(Objects.equals(before,sources.get(source)))return;
        if(transitions.size()>=32)throw new IllegalStateException("输入积压，请重新连接桌面");
        transitions.add(state());
    }
    private JSONObject state() {
        Set<String> keys=new TreeSet<>(),buttons=new TreeSet<>();
        for(String held:sources.values())for(String action:held.split("\\+"))if(action.startsWith("mouse:"))buttons.add(action.substring(6));else keys.add(action);
        return Api.json("keys",new JSONArray(keys),"buttons",new JSONArray(buttons));
    }
    synchronized void move(double horizontal,double vertical) {dx=Math.max(-2000,Math.min(2000,dx+horizontal));dy=Math.max(-2000,Math.min(2000,dy+vertical));}
    synchronized void point(double horizontal,double vertical){x=horizontal;y=vertical;}
    synchronized void scroll(int amount){wheel=Math.max(-20,Math.min(20,wheel+amount));}
    synchronized boolean pending(){return !transitions.isEmpty()||Math.abs(dx)>=1||Math.abs(dy)>=1||wheel!=0||x!=null;}
    synchronized JSONObject packet(String session,long sequence) throws Exception {
        JSONObject result=transitions.isEmpty()?state():transitions.removeFirst();
        result.put("session",session).put("sequence",sequence).put("dx",(int)dx).put("dy",(int)dy).put("scroll",wheel);
        if(x!=null){result.put("x",x).put("y",y);x=null;y=null;}
        dx-=(int)dx;dy-=(int)dy;wheel=0;return result;
    }
    synchronized void clear(){sources.clear();transitions.clear();dx=0;dy=0;wheel=0;x=null;y=null;}
}
