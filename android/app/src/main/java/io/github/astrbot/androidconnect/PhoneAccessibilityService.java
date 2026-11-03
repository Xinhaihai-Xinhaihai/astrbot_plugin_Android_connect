package io.github.astrbot.androidconnect;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONArray;
import org.json.JSONObject;

public class PhoneAccessibilityService extends AccessibilityService {
    static volatile PhoneAccessibilityService instance;
    interface Result { void done(String result); }
    @Override protected void onServiceConnected() { instance = this; }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { PhoneControlService.disableLocally(this); }
    @Override public void onDestroy() {
        instance = null; PhoneControlService.disableLocally(this); super.onDestroy();
    }
    private void check(String action) throws Exception {
        if (!PhoneControlService.enabled()) throw new Exception("手机控制授权已关闭或到期");
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        if (keyguard.isKeyguardLocked()) throw new Exception("手机已锁屏，请主人先解锁");
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            String pkg = String.valueOf(root.getPackageName());
            if ((pkg.equals(getPackageName()) && !action.equals("home") && !action.equals("open_app")) || pkg.equals("com.android.settings")
                || pkg.contains("permissioncontroller") || pkg.equals("com.android.systemui"))
                throw new Exception("不能远程操作本应用、系统设置或系统授权界面");
        }
    }
    void execute(String action, JSONObject args, Result callback) {
        try {
            check(action);
            switch (action) {
                case "inspect": {
                    JSONArray nodes = new JSONArray();
                    AccessibilityNodeInfo root = getRootInActiveWindow();
                    collect(root, nodes, 0, new int[]{0});
                    callback.done(Api.json("nodes", nodes,
                        "width", getResources().getDisplayMetrics().widthPixels,
                        "height", getResources().getDisplayMetrics().heightPixels).toString()); return;
                }
                case "tap": gesture(args.getInt("x"), args.getInt("y"), args.getInt("x"), args.getInt("y"), 80, callback); return;
                case "swipe": gesture(args.getInt("x1"), args.getInt("y1"), args.getInt("x2"), args.getInt("y2"), 450, callback); return;
                case "type_text": {
                    String text = args.getString("text");
                    if (text.length() > 4000) throw new Exception("输入文字过长");
                    AccessibilityNodeInfo root = getRootInActiveWindow();
                    AccessibilityNodeInfo focus = root == null ? null : root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                    if (focus == null || !focus.isEditable() || focus.isPassword()) throw new Exception("未找到可编辑的非密码输入框");
                    Bundle bundle = new Bundle(); bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                    callback.done(Api.json("ok", focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)).toString()); return;
                }
                case "back": callback.done(Api.json("ok", performGlobalAction(GLOBAL_ACTION_BACK)).toString()); return;
                case "home": callback.done(Api.json("ok", performGlobalAction(GLOBAL_ACTION_HOME)).toString()); return;
                case "open_app": {
                    String pkg = args.getString("package");
                    if (!pkg.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+") || pkg.equals(getPackageName())
                        || pkg.equals("com.android.settings") || pkg.contains("permissioncontroller")) throw new Exception("不允许打开此应用");
                    Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
                    if (launch == null) throw new Exception("应用未安装或无法启动");
                    startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    callback.done(Api.json("ok", true).toString()); return;
                }
                default: throw new Exception("未知手机操作");
            }
        } catch (Exception e) { callback.done(Api.json("error", e.getMessage()).toString()); }
    }
    private void collect(AccessibilityNodeInfo node, JSONArray output, int depth, int[] visited) throws Exception {
        if (node == null || depth > 18 || output.length() >= 70 || ++visited[0] > 300) return;
        if (node.isVisibleToUser() && !node.isPassword()) {
            Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
            String text = node.getText() == null ? "" : node.getText().toString();
            String desc = node.getContentDescription() == null ? "" : node.getContentDescription().toString();
            if (!text.isEmpty() || !desc.isEmpty() || node.isClickable() || node.isEditable())
                output.put(Api.json("text", text.substring(0, Math.min(text.length(), 120)),
                    "description", desc.substring(0, Math.min(desc.length(), 100)),
                    "bounds", new JSONArray(new int[]{bounds.left, bounds.top, bounds.right, bounds.bottom}),
                    "clickable", node.isClickable(), "editable", node.isEditable()));
        }
        for (int i = 0; i < node.getChildCount(); i++) collect(node.getChild(i), output, depth + 1, visited);
    }
    private void gesture(int x1, int y1, int x2, int y2, int duration, Result callback) throws Exception {
        int w = getResources().getDisplayMetrics().widthPixels, h = getResources().getDisplayMetrics().heightPixels;
        if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0 || x1 >= w || x2 >= w || y1 >= h || y2 >= h) throw new Exception("手机坐标超出屏幕");
        check("gesture"); Path path = new Path(); path.moveTo(x1, y1); if (x1 != x2 || y1 != y2) path.lineTo(x2, y2);
        GestureDescription description = new GestureDescription.Builder()
            .addStroke(new GestureDescription.StrokeDescription(path, 0, duration)).build();
        boolean accepted = dispatchGesture(description, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription gesture) { callback.done("{\"ok\":true}"); }
            @Override public void onCancelled(GestureDescription gesture) { callback.done("{\"error\":\"手势被取消\"}"); }
        }, null);
        if (!accepted) callback.done("{\"error\":\"系统拒绝手势\"}");
    }
}
