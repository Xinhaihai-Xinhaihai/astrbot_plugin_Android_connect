package io.github.astrbot.androidconnect;

import android.app.*;
import android.os.Bundle;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.*;
import android.text.method.PasswordTransformationMethod;
import android.view.*;
import android.widget.*;
import java.net.URLEncoder;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Native schema fields and a JSON editor share one in-memory document, never a disk cache. */
public class ConfigActivity extends Activity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Set<EditText> invalid=new HashSet<>();private Api api;private String kind,id,revision;
    private JSONObject values,schema;private boolean busy,dirty,jsonMode,readonly;
    private JSONArray groups=new JSONArray();private Spinner category;private int selectedGroup;private boolean showKeys;
    private LinearLayout fields;private TextView notice;private Button save,mode,reload;private EditText json;
    AlertDialog confirmation;
    AlertDialog arrayEditor,itemEditor;
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    private Button button(String text,Runnable action){Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setTextColor(0xff5267e8);android.graphics.drawable.GradientDrawable bg=new android.graphics.drawable.GradientDrawable();bg.setColor(0xffe9edff);bg.setCornerRadius(dp(10));b.setBackground(bg);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(4),0,dp(6));b.setLayoutParams(lp);b.setOnClickListener(v->action.run());return b;}
    private TextView label(String text,int size){TextView v=new TextView(this);v.setText(text);v.setTextSize(size);v.setTextColor(0xff202941);v.setPadding(0,dp(6),0,dp(6));return v;}
    @Override public void onCreate(Bundle saved){super.onCreate(saved);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);api=new Api(new Prefs(this));kind=getIntent().getStringExtra("kind");id=getIntent().getStringExtra("id");
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(16),dp(8),dp(16),dp(8));root.setBackgroundColor(0xfff5f6fa);root.setOnApplyWindowInsetsListener((v,i)->{v.setPadding(dp(16)+i.getSystemWindowInsetLeft(),dp(8)+i.getSystemWindowInsetTop(),dp(16)+i.getSystemWindowInsetRight(),dp(8)+i.getSystemWindowInsetBottom());return i;});
        root.addView(button("返回配置列表",this::leave));TextView title=label(getIntent().getStringExtra("title"),23);title.setTypeface(null,Typeface.BOLD);root.addView(title);notice=label("正在读取电脑配置…",13);root.addView(notice);
        LinearLayout bar=new LinearLayout(this);mode=button("JSON 编辑",this::switchMode);bar.addView(mode,new LinearLayout.LayoutParams(0,-2,1));bar.addView(button("重新读取",()->{if(dirty)new AlertDialog.Builder(this).setMessage("放弃尚未保存的修改并重新读取？").setNegativeButton("取消",null).setPositiveButton("重新读取",(d,w)->load()).show();else load();}),new LinearLayout.LayoutParams(0,-2,1));root.addView(bar);
        category=new Spinner(this);category.setVisibility(View.GONE);root.addView(category);category.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int position,long item){if(position!=selectedGroup){if(!collect()){category.setSelection(selectedGroup);return;}selectedGroup=position;render();}}public void onNothingSelected(AdapterView<?> p){}});
        CheckBox technical=new CheckBox(this);technical.setText("显示配置字段名（高级）");technical.setOnCheckedChangeListener((b,on)->{if(collect()){showKeys=on;render();}});root.addView(technical);
        ScrollView scroll=new ScrollView(this);fields=new LinearLayout(this);fields.setOrientation(LinearLayout.VERTICAL);scroll.addView(fields);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));save=button("保存到电脑",this::confirmSave);root.addView(save);reload=button("重载此插件",this::reloadPlugin);reload.setVisibility("plugin".equals(kind)?View.VISIBLE:View.GONE);root.addView(reload);setContentView(root);load();}
    private void enabled(boolean value){busy=!value;save.setEnabled(value&&!readonly);mode.setEnabled(value);reload.setEnabled(value&&!readonly);setFieldsEnabled(fields,value&&!readonly);}
    private void setFieldsEnabled(View view,boolean value){view.setEnabled(value);if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)setFieldsEnabled(((ViewGroup)view).getChildAt(i),value);}
    private void load(){if(busy)return;enabled(false);worker.execute(()->{try{JSONObject result=api.call("GET","/panel/config?kind="+URLEncoder.encode(kind,"UTF-8")+"&id="+URLEncoder.encode(id,"UTF-8"),null);runOnUiThread(()->accept(result));}catch(Exception e){runOnUiThread(()->error(e));}});}
    private void accept(JSONObject result){if(isFinishing())return;values=result.optJSONObject("values");schema=result.optJSONObject("schema");revision=result.optString("revision");readonly=result.optBoolean("readonly");dirty=false;
        groups=result.optJSONArray("groups");if(groups==null)groups=new JSONArray();ArrayList<String> names=new ArrayList<>();for(int i=0;i<groups.length();i++)names.add(groups.optJSONObject(i).optString("name"));selectedGroup=Math.min(selectedGroup,Math.max(0,names.size()-1));category.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,names));category.setSelection(selectedGroup);
        String locked=result.optJSONArray("locked")==null?"":result.optJSONArray("locked").toString();notice.setText(result.optString("notice")+(locked.equals("[]")||locked.isEmpty()?"":"\n控制台安全设置和连接权限在电脑端管理。"));render();enabled(true);}
    private void error(Exception e){if(isFinishing())return;enabled(true);new AlertDialog.Builder(this).setTitle("配置未完成").setMessage(e.getMessage()).setPositiveButton("知道了",null).show();}
    private boolean collect(){if(values==null)return false;if(jsonMode){try{values=new JSONObject(json.getText().toString());}catch(Exception e){json.setError("JSON 格式错误：请检查逗号、引号和括号");return false;}}else if(!invalid.isEmpty()){EditText field=invalid.iterator().next();field.requestFocus();Toast.makeText(this,"请先修正标红字段",Toast.LENGTH_SHORT).show();return false;}return true;}
    private void switchMode(){if(!collect())return;jsonMode=!jsonMode;render();}
    private void render(){fields.removeAllViews();invalid.clear();mode.setText(jsonMode?"表单编辑":"JSON 编辑");if(values==null)return;
        category.setVisibility(!jsonMode&&groups.length()>0?View.VISIBLE:View.GONE);
        if(jsonMode){json=new EditText(this);json.setTypeface(Typeface.MONOSPACE);json.setTextSize(13);json.setGravity(Gravity.TOP);json.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);try{json.setText(values.toString(2));}catch(Exception ignored){}json.addTextChangedListener(watch(()->dirty=true));fields.addView(json);return;}
        JSONArray selection=groups.length()>0?groups.optJSONObject(selectedGroup).optJSONArray("keys"):null;
        for(Iterator<String> it=values.keys();it.hasNext();){String key=it.next();if(selection!=null){boolean included=false;for(int i=0;i<selection.length();i++)if(key.equals(selection.optString(i)))included=true;if(!included)continue;}field(fields,key,new Ref(){public Object get(){return values.opt(key);}public void set(Object v)throws Exception{values.put(key,v);}},schema==null?null:schema.optJSONObject(key),0);}}
    interface Ref {Object get();void set(Object value)throws Exception;}
    private void field(LinearLayout parent,String key,Ref ref,JSONObject meta,int depth){Object value=ref.get();String name=meta==null?key:meta.optString("description",key);parent.addView(label(name,15));if(showKeys&&!name.equals(key))parent.addView(label(key,11));if(meta!=null&&!meta.optString("hint").isEmpty())parent.addView(label(meta.optString("hint"),12));
        if(value instanceof JSONObject&&depth<12){JSONObject object=(JSONObject)value;LinearLayout nested=new LinearLayout(this);nested.setOrientation(LinearLayout.VERTICAL);nested.setPadding(dp(12),0,0,0);nested.setVisibility(View.GONE);Button open=button("展开配置（"+object.length()+" 项）",()->nested.setVisibility(nested.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE));parent.addView(open);parent.addView(nested);for(Iterator<String> it=object.keys();it.hasNext();){String child=it.next();JSONObject items=meta==null?null:meta.optJSONObject("items");field(nested,child,new Ref(){public Object get(){return object.opt(child);}public void set(Object v)throws Exception{object.put(child,v);}},items==null?null:items.optJSONObject(child),depth+1);}return;}
        if(value instanceof Boolean){Switch toggle=new Switch(this);toggle.setText((Boolean)value?"已开启":"已关闭");toggle.setChecked((Boolean)value);toggle.setOnCheckedChangeListener((b,on)->{try{ref.set(on);dirty=true;toggle.setText(on?"已开启":"已关闭");}catch(Exception ignored){}});parent.addView(toggle);return;}
        if(value instanceof JSONArray){JSONArray array=(JSONArray)value;parent.addView(button("管理"+name+"（"+array.length()+" 项）",()->arrayForm(ref,name,meta)));return;}
        JSONArray options=meta==null?null:meta.optJSONArray("options");
        if(options!=null&&options.length()>0&&value instanceof String){ArrayList<String> raw=new ArrayList<>(),titles=new ArrayList<>();for(int i=0;i<options.length();i++){String option=options.optString(i);raw.add(option);titles.add(optionLabel(option));}if(!raw.contains(value.toString())){raw.add(value.toString());titles.add(value.toString());}Spinner choose=new Spinner(this);choose.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,titles));choose.setSelection(raw.indexOf(value.toString()));choose.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int i,long item){if(!raw.get(i).equals(ref.get()))try{ref.set(raw.get(i));dirty=true;}catch(Exception ignored){}}public void onNothingSelected(AdapterView<?> p){}});parent.addView(choose);return;}
        EditText edit=new EditText(this);boolean number=value instanceof Number,nullable=value==null||value==JSONObject.NULL;complexInput(edit,value);
        if(!number&&!nullable&&((meta!=null&&meta.optBoolean("secret"))||key.toLowerCase(Locale.ROOT).matches(".*(token|secret|password|api_key).*"))){edit.setTransformationMethod(PasswordTransformationMethod.getInstance());CheckBox reveal=new CheckBox(this);reveal.setText("显示此字段");reveal.setOnCheckedChangeListener((b,checked)->edit.setTransformationMethod(checked?null:PasswordTransformationMethod.getInstance()));parent.addView(reveal);}
        edit.addTextChangedListener(watch(()->{try{String text=edit.getText().toString();Object parsed=nullable?new JSONTokener(text).nextValue():value instanceof Integer||value instanceof Long?Long.valueOf(text):number?Double.valueOf(text):text;if(parsed instanceof Double&&!Double.isFinite((Double)parsed))throw new Exception();ref.set(parsed);dirty=true;invalid.remove(edit);edit.setError(null);}catch(Exception e){invalid.add(edit);edit.setError(nullable?"请填有效 JSON 值（如 null、[] 或带引号的文本）":"请输入有效数值");}}));parent.addView(edit);}
    private void complexInput(EditText edit,Object value){edit.setTextSize(14);edit.setText(value==null?"null":value.toString());edit.setInputType(value instanceof Number?InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED:InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);edit.setMinLines(1);edit.setMaxLines(7);}
    private String optionLabel(String value){return switch(value){
        case "stall"->"等待后处理";case "discard"->"丢弃超限消息";case "random"->"随机间隔";case "log"->"按文字长度计算（对数）";
        case "remote"->"远程服务";case "local"->"本机";case "possibility_reply"->"按概率回复";case "DEBUG"->"调试";case "INFO"->"信息";case "WARNING"->"警告";case "ERROR"->"错误";case "CRITICAL"->"严重错误";
        case "OpenAI Compatible"->"OpenAI 兼容接口";case "OpenAI Responses"->"OpenAI Responses 接口";default->value;};}
    private void arrayForm(Ref ref,String name,JSONObject meta){
        final JSONArray draft;try{draft=new JSONArray(ref.get().toString());}catch(Exception e){return;}
        boolean wasDirty=dirty;boolean[] applied={false};
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(16),dp(8),dp(16),dp(8));
        ScrollView scroll=new ScrollView(this);LinearLayout rows=new LinearLayout(this);rows.setOrientation(LinearLayout.VERTICAL);scroll.addView(rows);box.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        Runnable[] populate={null};populate[0]=()->{rows.removeAllViews();if(draft.length()==0)rows.addView(label("还没有条目，点击下方添加。",14));for(int i=0;i<draft.length();i++){
            final int index=i;Object item=draft.opt(i);String text="第 "+(i+1)+" 项";
            if(item instanceof JSONObject){JSONObject entry=(JSONObject)item;String title=entry.optString("name",entry.optString("id",entry.optString("model",entry.optString("type",""))));if(!title.isEmpty())text=title;}
            else if(!(meta!=null&&meta.optBoolean("secret")))text=String.valueOf(item);
            rows.addView(button("编辑 · "+text,()->editArrayItem(draft,index,draft.opt(index),meta,populate[0])));
            rows.addView(button("删除第 "+(index+1)+" 项",()->new AlertDialog.Builder(this).setTitle("从草稿中删除此条目？").setMessage("应用列表并保存到电脑后才会生效。").setNegativeButton("取消",null).setPositiveButton("删除",(d,w)->{draft.remove(index);populate[0].run();}).show()));
        }};
        box.addView(button("添加条目",()->{
            JSONObject templates=meta==null?null:meta.optJSONObject("config_template");
            if(templates!=null&&templates.length()>0){ArrayList<String> keys=new ArrayList<>(),titles=new ArrayList<>();for(Iterator<String> it=templates.keys();it.hasNext();){String key=it.next();keys.add(key);titles.add(optionLabel(key));}
                new AlertDialog.Builder(this).setTitle("选择类型").setItems(titles.toArray(new String[0]),(d,index)->editArrayItem(draft,draft.length(),templates.opt(keys.get(index)),meta,populate[0])).show();
            }else{Object sample=draft.length()>0?draft.opt(0):"";Object empty=sample instanceof JSONObject?new JSONObject():sample instanceof Number?0:sample instanceof Boolean?false:"";editArrayItem(draft,draft.length(),empty,meta,populate[0]);}
        }));
        box.addView(button("高级：编辑整个 JSON 列表",()->{
            EditText editor=new EditText(this);editor.setGravity(Gravity.TOP);editor.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);try{editor.setText(draft.toString(2));}catch(Exception ignored){}
            ScrollView view=new ScrollView(this);view.addView(editor);AlertDialog dialog=new AlertDialog.Builder(this).setTitle(name+" · JSON 列表").setView(view).setPositiveButton("应用到列表",null).setNegativeButton("取消",null).create();dialog.show();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{JSONArray changed=new JSONArray(editor.getText().toString());while(draft.length()>0)draft.remove(draft.length()-1);for(int i=0;i<changed.length();i++)draft.put(changed.get(i));populate[0].run();dialog.dismiss();}catch(Exception e){editor.setError("请输入完整的 JSON 数组");}});
        }));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(name).setView(box).setPositiveButton("应用到草稿",null).setNegativeButton("取消",null).create();arrayEditor=dialog;
        dialog.setOnDismissListener(d->{if(!applied[0])dirty=wasDirty;});dialog.show();dialog.getWindow().setLayout(-1,(int)(getResources().getDisplayMetrics().heightPixels*.9));populate[0].run();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{ref.set(draft);applied[0]=true;dirty=true;dialog.dismiss();render();}catch(Exception e){Toast.makeText(this,"列表未能应用",Toast.LENGTH_SHORT).show();}});
    }
    private void editArrayItem(JSONArray array,int index,Object original,JSONObject parentMeta,Runnable populate){
        final Object[] draft={original};try{if(original instanceof JSONObject)draft[0]=new JSONObject(original.toString());else if(original instanceof JSONArray)draft[0]=new JSONArray(original.toString());}catch(Exception e){return;}
        boolean wasDirty=dirty;Set<EditText> baseline=new HashSet<>(invalid);boolean[] applied={false};
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(20),dp(8),dp(20),dp(8));
        JSONObject items=parentMeta==null?null:parentMeta.optJSONObject("items");
        if(draft[0] instanceof JSONObject&&((JSONObject)draft[0]).length()>0){JSONObject object=(JSONObject)draft[0];for(Iterator<String> it=object.keys();it.hasNext();){String key=it.next();field(box,key,new Ref(){public Object get(){return object.opt(key);}public void set(Object value)throws Exception{object.put(key,value);}},items==null?null:items.optJSONObject(key),1);}}
        else if(draft[0] instanceof JSONObject){EditText editor=new EditText(this);editor.setText("{}");editor.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);box.addView(label("新条目 JSON 对象",15));box.addView(editor);editor.addTextChangedListener(watch(()->{try{draft[0]=new JSONObject(editor.getText().toString());invalid.remove(editor);editor.setError(null);}catch(Exception e){invalid.add(editor);editor.setError("请输入完整的 JSON 对象");}}));}
        else {JSONObject scalar=Api.json("description","条目内容","secret",parentMeta!=null&&parentMeta.optBoolean("secret"));field(box,"value",new Ref(){public Object get(){return draft[0];}public void set(Object value){draft[0]=value;}},scalar,1);}
        ScrollView scroll=new ScrollView(this);scroll.addView(box);AlertDialog dialog=new AlertDialog.Builder(this).setTitle(index<array.length()?"编辑条目":"添加条目").setView(scroll).setPositiveButton("应用到列表",null).setNegativeButton("取消",null).create();itemEditor=dialog;
        dialog.setOnDismissListener(d->{invalid.retainAll(baseline);if(!applied[0])dirty=wasDirty;});dialog.show();
        dialog.getWindow().setLayout(-1,(int)(getResources().getDisplayMetrics().heightPixels*.85));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{for(EditText edit:invalid)if(!baseline.contains(edit)){edit.requestFocus();return;}try{array.put(index,draft[0]);applied[0]=true;populate.run();dialog.dismiss();}catch(Exception e){Toast.makeText(this,"条目未能应用",Toast.LENGTH_SHORT).show();}});
    }
    private TextWatcher watch(Runnable action){return new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int before,int count){action.run();}public void afterTextChanged(Editable s){}};}
    private void confirmSave(){if(busy||readonly||!collect())return;confirmation=new AlertDialog.Builder(this).setTitle("保存到电脑").setMessage("将修改电脑上的这份配置，并自动保留旧版本备份。"+("global".equals(kind)?"全局配置保存后需要重启 AstrBot，使模型和平台完整生效。":"plugin".equals(kind)?"保存后可点重载插件生效。":"人格修改会立即更新。")).setNegativeButton("取消",null).setPositiveButton("确认保存",(d,w)->save()).show();}
    private void save(){JSONObject document=Api.json("kind",kind,"id",id,"revision",revision,"values",values);enabled(false);worker.execute(()->{try{JSONObject result=api.call("POST","/panel/config",document);runOnUiThread(()->{accept(result);Toast.makeText(this,"配置已保存到电脑",Toast.LENGTH_LONG).show();});}catch(Exception e){runOnUiThread(()->error(e));}});}
    private void reloadPlugin(){if(busy)return;if(dirty){Toast.makeText(this,"请先保存配置",Toast.LENGTH_SHORT).show();return;}new AlertDialog.Builder(this).setTitle("重载插件").setMessage("此插件正在处理的工作可能中断。").setNegativeButton("取消",null).setPositiveButton("重载",(d,w)->{enabled(false);worker.execute(()->{try{JSONObject result=api.call("POST","/panel/reload",Api.json("name",id));runOnUiThread(()->{enabled(true);notice.setText(result.optString("message"));});}catch(Exception e){runOnUiThread(()->error(e));}});}).show();}
    private void leave(){if(busy)return;if(dirty)new AlertDialog.Builder(this).setMessage("还有未保存的修改，确定离开？").setNegativeButton("继续编辑",null).setPositiveButton("放弃修改",(d,w)->finish()).show();else finish();}
    @Override public void onBackPressed(){leave();}
    @Override protected void onDestroy(){worker.shutdown();super.onDestroy();}
}
