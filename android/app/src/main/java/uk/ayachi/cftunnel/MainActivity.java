package uk.ayachi.cftunnel;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.*;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.*;
import android.widget.*;
import java.util.*;
import uk.ayachi.cftunnel.core.Config;

/** Presentation only. Service, JNI ownership and configuration storage are unchanged. */
public final class MainActivity extends Activity {
    private static final int BG=0xFFF4F6FA,INK=0xFF17243B,MUTED=0xFF58677E,
        BLUE=0xFF245DDB,LINE=0xFFE2E8F2,PALE=0xFFEDF3FF,GREEN=0xFF16745C;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Map<String,EditText> fields=new LinkedHashMap<>();
    private final ScrollView[] pages=new ScrollView[3];
    private final Button[] tabs=new Button[3];
    private ConfigStore store;
    private LinearLayout clientSection,agentSection,advanced;
    private EditText clientToken,agentToken;
    private CheckBox insecure;
    private TextView status,statePill,upRate,downRate,totals,sessionCount,endpoint,background,log,logHint;
    private Button start,stop,diagnose,clientMode,agentMode,advancedToggle,startFromConfig;
    private boolean agentSelected,logPaused;
    private int selectedTab;
    private String lastLog="";
    // Rotation-only draft: credentials are never put into saved instance state.
    private static final class Draft {
        final Map<String,String> values=new LinkedHashMap<>();
        String client,agent;boolean mode,insecure,advanced,paused;int tab;
        final int[] scroll=new int[3];
    }
    private final Runnable refresh=new Runnable(){public void run(){
        renderState();handler.postDelayed(this,1000);
    }};

    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);store=new ConfigStore(this);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(Color.WHITE);
        LinearLayout shell=column();shell.setBackgroundColor(BG);setContentView(shell);
        shell.setOnApplyWindowInsetsListener((v,i)->{
            v.setPadding(i.getSystemWindowInsetLeft(),i.getSystemWindowInsetTop(),i.getSystemWindowInsetRight(),i.getSystemWindowInsetBottom());return i;
        });
        FrameLayout content=new FrameLayout(this);
        shell.addView(content,new LinearLayout.LayoutParams(-1,0,1));
        for(int n=0;n<3;n++){
            ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);
            scroll.setVerticalScrollBarEnabled(false);pages[n]=scroll;content.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        }
        buildOverview(page(0));buildConfig(page(1));buildLogs(page(2));
        LinearLayout nav=row();nav.setPadding(dp(12),dp(8),dp(12),dp(8));nav.setBackgroundColor(Color.WHITE);nav.setElevation(dp(6));shell.addView(nav);
        String[] names={"概览","配置","日志"};
        for(int n=0;n<3;n++){
            final int index=n;tabs[n]=action(nav,names[n],false,v->selectTab(index));
            tabs[n].setLayoutParams(weight());
        }
        Draft draft=(Draft)getLastNonConfigurationInstance();
        if(draft!=null){
            for(Map.Entry<String,String> e:draft.values.entrySet())fields.get(e.getKey()).setText(e.getValue());
            clientToken.setText(draft.client);agentToken.setText(draft.agent);insecure.setChecked(draft.insecure);
            selectMode(draft.mode);setAdvanced(draft.advanced);logPaused=draft.paused;
            selectTab(draft.tab);
            for(int n=0;n<3;n++){final int i=n;pages[n].post(()->pages[i].scrollTo(0,draft.scroll[i]));}
        }else{
            selectMode(store.get("mode","client").equals("agent"));
            setAdvanced(insecure.isChecked()||!value("connect_host").isEmpty());selectTab(0);
        }
        renderState();
    }
    private LinearLayout page(int index){
        FrameLayout frame=new FrameLayout(this);pages[index].addView(frame);
        LinearLayout box=column();box.setPadding(dp(20),dp(24),dp(20),dp(24));
        int available=getResources().getDisplayMetrics().widthPixels;
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(Math.min(available,dp(680)),-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL);
        frame.addView(box,lp);return box;
    }
    private void heading(LinearLayout p,String title,String subtitle){
        TextView brand=text(p,"CF  /  TCP TUNNEL",11,MUTED);brand.setLetterSpacing(.12f);bold(brand);
        space(p,8);bold(text(p,title,28,INK));text(p,subtitle,14,MUTED);space(p,20);
    }
    private void buildOverview(LinearLayout p){
        heading(p,"你的设备，随时连接","把远端服务带到手机上的一个本地端口。");
        LinearLayout hero=card(p);hero.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xFF142B50,0xFF244B7A}));
        ((GradientDrawable)hero.getBackground()).setCornerRadius(dp(24));
        statePill=text(hero,"●  未启动",13,0xFFBFD6FC);bold(statePill);
        space(hero,14);status=text(hero,"尚未启动",23,Color.WHITE);bold(status);space(hero,8);
        text(hero,"已监听不代表远端已连通；实际状态以当前会话为准。",12,0xFFD0DCF0);
        space(hero,18);
        LinearLayout actions=row();hero.addView(actions);
        start=action(actions,"启动隧道",true,v->prepareStart());start.setLayoutParams(weight());
        stop=action(actions,"停止",false,v->stopService(new Intent(this,TunnelService.class)));stop.setLayoutParams(weight());
        space(p,4);
        LinearLayout rates=row();p.addView(rates);
        LinearLayout down=card(rates);down.setLayoutParams(weight());
        text(down,"↓  下载",13,MUTED);downRate=text(down,"0 B/s",20,INK);bold(downRate);
        LinearLayout up=card(rates);up.setLayoutParams(weight());
        text(up,"↑  上传",13,MUTED);upRate=text(up,"0 B/s",20,INK);bold(upRate);
        LinearLayout summary=card(p);sessionCount=text(summary,"0 个活动会话",17,INK);bold(sessionCount);
        totals=text(summary,"累计下载 0 B · 上传 0 B",13,MUTED);
        LinearLayout local=card(p);section(local,"连接入口","使用已保存的配置");
        endpoint=text(local,"127.0.0.1:13389",21,INK);endpoint.setTypeface(Typeface.MONOSPACE);endpoint.setTextIsSelectable(true);
        text(local,"Client 模式下，在 Windows App 或浏览器中使用这个地址。HTTPS 请带上 https:// 前缀。",13,MUTED);
        action(local,"复制本地地址",false,v->{
            if(store.get("mode","client").equals("agent")){toast("当前保存的是 Agent 模式，请先配置 Client 入口");return;}
            copy("本地入口","127.0.0.1:"+store.get("local_port","13389"));
        });
        LinearLayout bg=card(p);section(bg,"后台运行","切换到其他 App 时继续转发");
        background=text(bg,"",13,MUTED);
        action(bg,"允许后台持续转发",false,v->BackgroundPolicy.requestExemption(this));
        action(bg,"打开系统应用设置",false,v->BackgroundPolicy.openAppSettings(this));
        text(bg,"隧道运行时保持 CPU 唤醒，停止时释放；耗电会增加。",12,MUTED);
        text(p,"本机 TCP 转发 · 非 VPN\n1.1.2 · Native Release",12,MUTED);
    }
    private void buildConfig(LinearLayout p){
        heading(p,"连接配置","配置只在保存或启动时写入；运行中的隧道不受编辑影响。");
        LinearLayout role=card(p);section(role,"使用方式","选择这台手机的角色");
        LinearLayout options=row();role.addView(options);
        clientMode=action(options,"Client · 访问",false,v->selectMode(false));clientMode.setLayoutParams(weight());
        agentMode=action(options,"Agent · 提供",false,v->selectMode(true));agentMode.setLayoutParams(weight());
        LinearLayout server=card(p);section(server,"隧道服务器","填写 Worker 与目标 Agent");
        add(server,"url","Worker URL","wss://tcp-tunnel.example.com",false);
        add(server,"id","Agent ID","main",false);
        clientSection=card(p);section(clientSection,"访问远端服务","Client");
        clientToken=token(clientSection,"Client Token","client");
        add(clientSection,"local_port","手机本地端口","13389",true);
        add(clientSection,"remote_port","远端服务端口","3389",true);
        text(clientSection,"RDP 示例：13389 → 3389。Windows App 的电脑名称填 127.0.0.1:13389，网关选“无”；登录时使用远程电脑的账户。HTTPS 改为对应服务端口。",13,MUTED);
        agentSection=card(p);section(agentSection,"提供本地服务","Agent");
        agentToken=token(agentSection,"Agent Token","agent");
        add(agentSection,"target_host","目标主机","127.0.0.1",false);
        add(agentSection,"ports","允许转发的端口","3389",false);
        text(agentSection,"多个端口用逗号分隔。请给手机使用独立 Agent ID；此处 127.0.0.1 指手机自身。",13,MUTED);
        LinearLayout extra=card(p);
        advancedToggle=action(extra,"高级选项  ＋",false,v->setAdvanced(advanced.getVisibility()!=View.VISIBLE));
        advanced=column();extra.addView(advanced);
        add(advanced,"connect_host","指定连接 IP / CNAME（可留空）","",false);
        insecure=new CheckBox(this);insecure.setText("跳过服务器证书验证");insecure.setTextSize(14);insecure.setTextColor(INK);insecure.setMinHeight(dp(48));
        insecure.setButtonTintList(ColorStateList.valueOf(BLUE));insecure.setChecked(store.get("insecure","false").equals("true"));advanced.addView(insecure);
        text(advanced,"仅用于排障；关闭验证后无法确认服务器身份。",12,0xFFA14D15);
        action(p,"保存配置",true,v->{try{save();renderState();toast("已保存，运行中的隧道不会改变");}catch(Exception e){error(e);}});
        startFromConfig=action(p,"返回概览并启动",false,v->{selectTab(0);prepareStart();});
    }
    private void buildLogs(LinearLayout p){
        heading(p,"运行日志","查看连接过程与后台状态。");
        LinearLayout tools=card(p);section(tools,"诊断工具","排查连接问题");
        diagnose=action(tools,"RDP 连通性自检",false,v->new AlertDialog.Builder(this)
            .setTitle("RDP 初始握手自检")
            .setMessage("通过当前 Client 隧道发送一次 RDP 初始协商，不发送账户密码。仅适用于 RDP 端口；成功不代表完成登录，测试连接随后会关闭。")
            .setNegativeButton("取消",null).setPositiveButton("开始自检",(d,w)->{
                try{startService(new Intent(this,TunnelService.class).setAction(TunnelService.DIAGNOSE));}catch(Exception e){error(e);}
            }).show());
        action(tools,"复制完整日志",false,v->copy("TCP tunnel logs",LiveState.logs()));
        action(tools,"暂停 / 恢复日志刷新",false,v->{logPaused=!logPaused;renderLog();});
        logHint=text(p,"实时更新",12,MUTED);
        LinearLayout terminal=card(p);terminal.setBackground(shape(0xFF132138,20,0xFF132138));
        log=text(terminal,"等待启动隧道…",12,0xFFD1DEF2);log.setTypeface(Typeface.MONOSPACE);log.setTextIsSelectable(true);log.setLineSpacing(dp(4),1);
    }
    private void renderState(){
        if(status==null)return;
        boolean active=LiveState.active;
        statePill.setText(active?(LiveState.agent?"●  AGENT  ·  运行中":"●  CLIENT  ·  运行中"):"○  隧道已停止");
        statePill.setTextColor(active?0xFFA1E6D0:0xFFBFD6FC);status.setText(LiveState.status.replace("C++ ",""));
        downRate.setText(LiveState.size(LiveState.downRate)+"/s");upRate.setText(LiveState.size(LiveState.upRate)+"/s");
        sessionCount.setText(LiveState.sessions+" 个活动会话");totals.setText("累计下载 "+LiveState.size(LiveState.down)+" · 上传 "+LiveState.size(LiveState.up));
        enable(start,!active);enable(startFromConfig,!active);enable(stop,active);enable(diagnose,active&&!LiveState.agent);
        endpoint.setText(store.get("mode","client").equals("agent")?"Agent · "+store.get("id","main"):"127.0.0.1:"+store.get("local_port","13389"));
        if(selectedTab==2)renderLog();
    }
    private void renderLog(){
        logHint.setText(logPaused?"已暂停显示 · 隧道与日志记录仍在运行":"实时更新 · 仅显示最近记录");
        if(logPaused)return;
        String latest=LiveState.logs();
        if(!latest.equals(lastLog)){lastLog=latest;log.setText(latest.isEmpty()?"等待启动隧道…":latest);}
    }
    private void selectTab(int n){selectedTab=n;
        for(int i=0;i<3;i++){pages[i].setVisibility(i==n?View.VISIBLE:View.GONE);tabs[i].setSelected(i==n);tabs[i].setTextColor(i==n?BLUE:MUTED);tabs[i].setBackground(ripple(i==n?PALE:Color.WHITE,14));}
        if(n==2)renderLog();
    }
    private void selectMode(boolean agent){agentSelected=agent;
        clientSection.setVisibility(agent?View.GONE:View.VISIBLE);agentSection.setVisibility(agent?View.VISIBLE:View.GONE);
        clientMode.setSelected(!agent);agentMode.setSelected(agent);
        clientMode.setBackground(ripple(!agent?PALE:Color.WHITE,12));agentMode.setBackground(ripple(agent?PALE:Color.WHITE,12));
        clientMode.setTextColor(!agent?BLUE:MUTED);agentMode.setTextColor(agent?BLUE:MUTED);
    }
    private void setAdvanced(boolean open){advanced.setVisibility(open?View.VISIBLE:View.GONE);advancedToggle.setText(open?"高级选项  −":"高级选项  ＋");}
    private void enable(Button b,boolean yes){b.setEnabled(yes);b.setAlpha(yes?1:.4f);}
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);return v;}
    private LinearLayout.LayoutParams weight(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.setMargins(dp(3),0,dp(3),dp(12));return p;}
    private LinearLayout card(LinearLayout parent){LinearLayout v=column();v.setPadding(dp(18),dp(18),dp(18),dp(18));v.setBackground(shape(Color.WHITE,22,LINE));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(14);parent.addView(v,lp);return v;}
    private GradientDrawable shape(int color,int radius,int border){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));d.setStroke(dp(1),border);return d;}
    private RippleDrawable ripple(int color,int radius){return new RippleDrawable(ColorStateList.valueOf(0x22245DDB),shape(color,radius,color),null);}
    private void section(LinearLayout p,String title,String subtitle){bold(text(p,title,18,INK));text(p,subtitle,12,MUTED);space(p,12);}
    private void space(LinearLayout p,int height){View v=new View(this);p.addView(v,new LinearLayout.LayoutParams(1,dp(height)));}
    private TextView text(LinearLayout p,String s,int size,int color){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);v.setPadding(0,dp(3),0,dp(3));v.setLineSpacing(dp(2),1);p.addView(v,new LinearLayout.LayoutParams(-1,-2));return v;}
    private void bold(TextView v){v.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));}
    private Button action(LinearLayout p,String s,boolean primary,View.OnClickListener listener){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextSize(14);b.setTextColor(primary?Color.WHITE:BLUE);bold(b);
        b.setMinHeight(dp(50));b.setMinimumHeight(dp(50));b.setPadding(dp(12),dp(10),dp(12),dp(10));b.setStateListAnimator(null);b.setBackground(ripple(primary?BLUE:PALE,14));b.setOnClickListener(listener);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(8);p.addView(b,lp);return b;}
    private EditText input(LinearLayout p,String label,boolean number){
        TextView l=text(p,label,13,MUTED);bold(l);
        EditText e=new EditText(this);e.setId(View.generateViewId());l.setLabelFor(e.getId());e.setContentDescription(label);e.setSingleLine(true);e.setTextSize(16);e.setTextColor(INK);e.setHintTextColor(MUTED);
        e.setInputType(number?InputType.TYPE_CLASS_NUMBER:InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setBackground(fieldBackground());e.setPadding(dp(14),dp(12),dp(14),dp(12));e.setMinHeight(dp(52));e.setSaveEnabled(false);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(4);lp.bottomMargin=dp(14);p.addView(e,lp);return e;
    }
    private android.graphics.drawable.StateListDrawable fieldBackground(){
        android.graphics.drawable.StateListDrawable d=new android.graphics.drawable.StateListDrawable();
        d.addState(new int[]{android.R.attr.state_focused},shape(Color.WHITE,12,BLUE));d.addState(new int[]{},shape(0xFFF8FAFD,12,LINE));return d;
    }
    private void add(LinearLayout p,String key,String label,String def,boolean number){EditText e=input(p,label,number);e.setText(store.get(key,def));fields.put(key,e);}
    private EditText token(LinearLayout p,String label,String role){EditText e=input(p,label,false);
        e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);e.setTransformationMethod(PasswordTransformationMethod.getInstance());
        e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        try{e.setText(store.token(role));}catch(Exception ex){toast("无法解密已保存 Token，请重新填写");}
        action(p,"显示 / 隐藏 Token",false,v->{int cursor=e.getSelectionStart();boolean hidden=e.getTransformationMethod()!=null;
            e.setTransformationMethod(hidden?null:PasswordTransformationMethod.getInstance());e.setSelection(Math.max(0,cursor));
        });space(p,12);return e;
    }
    @Override public Object onRetainNonConfigurationInstance(){
        Draft d=new Draft();for(Map.Entry<String,EditText> e:fields.entrySet())d.values.put(e.getKey(),e.getValue().getText().toString());
        d.client=clientToken.getText().toString();d.agent=agentToken.getText().toString();d.mode=agentSelected;d.insecure=insecure.isChecked();
        d.tab=selectedTab;d.advanced=advanced.getVisibility()==View.VISIBLE;d.paused=logPaused;
        for(int i=0;i<3;i++)d.scroll[i]=pages[i].getScrollY();return d;
    }
    private String value(String key){return fields.get(key).getText().toString().trim();}
    private void save()throws Exception{
        boolean agent=agentSelected;String ct=clientToken.getText().toString().trim(),at=agentToken.getText().toString().trim();
        new Config(agent,value("url"),value("id"),agent?at:ct,value("connect_host"),insecure.isChecked(),Integer.parseInt(value("local_port")),Integer.parseInt(value("remote_port")),value("target_host"),value("ports"));
        Map<String,String> m=new LinkedHashMap<>();for(String k:fields.keySet())m.put(k,value(k));
        m.put("mode",agent?"agent":"client");m.put("insecure",String.valueOf(insecure.isChecked()));
        store.save(m,ct,at);
    }
    private void prepareStart(){
        try{save();}catch(Exception e){error(e);return;}
        if(insecure.isChecked())new AlertDialog.Builder(this).setTitle("证书验证已关闭").setMessage("TLS 仍加密，但无法确认服务器身份，Token 可能被冒充服务器截获。只在排障时使用。").setNegativeButton("取消",null).setPositiveButton("继续",(d,w)->requestStart()).show();
        else requestStart();
    }
    private void requestStart(){
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},7);return;}
        startTunnel();
    }
    private void startTunnel(){
        try{
            startForegroundService(new Intent(this,TunnelService.class).setAction(TunnelService.START));
            if(!BackgroundPolicy.exempt(this)||BackgroundPolicy.restricted(this)) {
                new AlertDialog.Builder(this).setTitle("允许后台持续转发")
                    .setMessage("切到 Windows App 后，TCP 隧道仍需持续运行。请允许忽略电池优化，并在应用设置中允许后台活动、取消电池和后台流量限制。CPU 唤醒本身不能解除这些网络限制。")
                    .setNegativeButton("暂不设置",null)
                    .setPositiveButton("去设置",(d,w)->BackgroundPolicy.requestExemption(this)).show();
            }
        }catch(Exception e){error(e);}
    }
    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){super.onRequestPermissionsResult(r,p,g);if(r==7){if(g.length==0||g[0]!=PackageManager.PERMISSION_GRANTED)toast("通知未授权，状态栏可能不显示；建议在系统设置中允许通知");startTunnel();}}
    private int dp(int n){return (int)(getResources().getDisplayMetrics().density*n+0.5f);}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    private void copy(String name,String s){((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText(name,s));toast("已复制");}
    private void error(Exception e){new AlertDialog.Builder(this).setTitle("请检查配置").setMessage(String.valueOf(e.getMessage())).setPositiveButton("确定",null).show();}
    @Override public void onResume(){super.onResume();LiveState.uiVisible=true;
        background.setText(BackgroundPolicy.summary(this));
        if(LiveState.active)LiveState.log("UI_FOREGROUND; "+BackgroundPolicy.diagnostics(this));
        handler.post(refresh);
    }
    @Override public void onPause(){LiveState.uiVisible=false;
        // Never leave a revealed credential visible in the resumed UI.
        clientToken.setTransformationMethod(PasswordTransformationMethod.getInstance());
        agentToken.setTransformationMethod(PasswordTransformationMethod.getInstance());
        if(LiveState.active)LiveState.log("UI_BACKGROUND; Activity refresh stopped; service/native forwarding remains active");
        handler.removeCallbacks(refresh);super.onPause();
    }
}
