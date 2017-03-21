package cocomeng.com.accessibilitydemo;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

public class AutoReplyService extends AccessibilityService {
    private final static String MM_PNAME = "com.tencent.mm";
    boolean hasAction = false;
    boolean locked = false;
    boolean background = false;
    private HashMap<String, Integer> names = new HashMap<String, Integer>();
    private KeyguardManager.KeyguardLock kl;
    private Handler handler = new Handler();
    private boolean debug = false;
    private Integer remaining = 0;
    private boolean sending = false;
    private int mHaveReceivedRedNumber = 0;
    private Queue<Notification> intentQueue = new LinkedList<>();
    private boolean isOpenDetail = false;
    private Integer jobType = 0;

    private List<AccessibilityNodeInfo> parents;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        android.util.Log.d("maptrix","connected");
        parents = new ArrayList<>();
        intentQueue.clear();
        jobType = 2;
        hasAction=false;
        sending=false;
        remaining = 0;
    }


    /**
     * 必须重写的方法，响应各种事件。
     *
     * @param event
     */
    @Override
    public void onAccessibilityEvent(final AccessibilityEvent event) {
        int eventType = event.getEventType();
        switch (eventType) {
            case AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED:// 通知栏事件
                android.util.Log.d("maptrix", "get notification event");
                List<CharSequence> texts = event.getText();
                if (!texts.isEmpty()) {
                    for (CharSequence text : texts) {
                        String content = text.toString();
                        android.util.Log.d("maptrix", "content"+content);
                        if (content.contains("请求添加") || content.contains("[微信红包]")){
                            if (!TextUtils.isEmpty(content)) {
                                if (isScreenLocked()) {
                                    locked = true;
                                    wakeAndUnlock();
                                    if (isAppForeground(MM_PNAME)) {
                                        background = false;
                                        sendNotifacationReply(event);
                                    } else {
                                        background = true;
                                        sendNotifacationReply(event);
                                    }
                                } else {
                                    locked = false;
                                    if (isAppForeground(MM_PNAME)) {
                                        background = false;
                                        sendNotifacationReply(event);
                                    } else {
                                        background = true;
                                        sendNotifacationReply(event);
                                    }
                                }
                            }
                        }

                    }
                }
                break;
            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED:
                String className = event.getClassName().toString();
                android.util.Log.d("maptrix", "class:"+className);
                // 接受好友的页面列表
                if (className.equals("com.tencent.mm.plugin.subapp.ui.friend.FMessageConversationUI")) {
                    test1();
                }
                //发送好友验证消息
                if (className.equals("com.tencent.mm.plugin.profile.ui.SayHiWithSnsPermissionUI")) {
                    test2();
                }
                //加完好友后的联系人页面(要发送文字消息）
                if (className.equals("com.tencent.mm.plugin.profile.ui.ContactInfoUI") && (sending || debug)){
                    test3();
                }
                //主页面（检查好友是否加完了，否则继续test1()），或者聊天页面（抢红包）
                if (className.equals("com.tencent.mm.ui.LauncherUI")){
                    android.util.Log.d("maptrix","job type is:"+jobType);
                    if(jobType == 1) {
                        test4();
                    } else if (jobType == 2){
                        //获取当前聊天页面的根布局
                        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
                        android.util.Log.d("maptrix","stating to find hongbao.....");
                        getLastPacket();

                    }
                }
                //开红包
                if (className.equals("com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyReceiveUI")) {
                    android.util.Log.d("maptrix","open hongbao.....");
                    AccessibilityNodeInfo rootNode = getRootInActiveWindow();
                    openRedPacket(rootNode);
                }
                //退出红包
                if (isOpenDetail && className.equals("com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyDetailUI")) {
                    jobType = 0;
                    isOpenDetail = false;
                    performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) ;
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) ;

                            handler.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    //先把当前完成的出队
                                    intentQueue.poll();
                                    android.util.Log.i("maptrix", "queue size:"+intentQueue.size());
                                    if(!intentQueue.isEmpty()){
                                        final Notification notification = intentQueue.element();
                                        back2Home();
                                        handler.postDelayed(new Runnable() {
                                            @Override
                                            public void run() {
                                                sendNotification(notification);
                                            }
                                        }, 500);
                                    }
                                    else {
                                        hasAction = false;
                                        back2Home();
                                    }

                                }
                            }, 500);
                        }
                    }, 500);
                }
                break;
        }
    }

    /**
     * 开始打开红包
     */
    private void openRedPacket(AccessibilityNodeInfo rootNode) {
        for (int i = 0; i < rootNode.getChildCount(); i++) {
            AccessibilityNodeInfo node = rootNode.getChild(i);
            if ("android.widget.Button".equals(node.getClassName())) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK);

                isOpenDetail = true;
            }
            openRedPacket(node);
        }
    }

    /**
     * 寻找窗体中的“发送”按钮，并且点击。
     */
    @SuppressLint("NewApi")
    private void send() {
        AccessibilityNodeInfo nodeInfo = getRootInActiveWindow();
        if (nodeInfo != null) {
            List<AccessibilityNodeInfo> list = nodeInfo
                    .findAccessibilityNodeInfosByText("发送");
            if (list != null && list.size() > 0) {
                for (final AccessibilityNodeInfo n : list) {
                    if (n.getClassName().equals("android.widget.Button") && n.isEnabled()) {
                        handler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                            }
                        }, 500);

                        sending = false;
                    }
                }

            }
        }
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) ;
            }
        }, 1000);
    }

    @SuppressLint("NewApi")
    private void test1() {
        AccessibilityNodeInfo nodeInfo = getRootInActiveWindow();
        Boolean hasClicked = false;
        if (nodeInfo != null) {
            List<AccessibilityNodeInfo> list = nodeInfo
                    .findAccessibilityNodeInfosByText("接受");
            remaining = list.size() - 1;
            //每次获取第一个好友请求
            if (list != null && list.size() > 0) {
                AccessibilityNodeInfo node = list.get(0);
                for(AccessibilityNodeInfo n : list){
                    android.util.Log.i("maptrix", "parent class:" + n.getParent().getClassName().toString());
                    List<AccessibilityNodeInfo> namelist = n.getParent().findAccessibilityNodeInfosByViewId("com.tencent.mm:id/aut");
                    if(namelist != null && namelist.size() > 0){
                        String name = namelist.get(0).getText().toString();
                        //如果当前名字的好友已经执行过2次操作了，但还没加上，说明有问题
                        Boolean hasName = names.containsKey(name);
                        if ( hasName && names.get(name) >=2){
                            android.util.Log.i("maptrix", "tried enough times of add friend name:"+name);
                            continue;
                        } else {
                            if (hasName) {
                                names.put(name, names.get(name) + 1);
                            } else {
                                names.put(name, 1);
                            }
                        }
                        android.util.Log.i("maptrix", "trying to add friend name:"+name);
                        n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        hasClicked = true;
                        break;
                    }
                }
            } else {
                //没有了好友请求就退出
                hasAction = false;
                names.clear();
                test4();
            }

            //一般到不了这里
            if(!hasClicked){
                hasAction = false;
                names.clear();
                test4();
            }

        }
    }

    @SuppressLint("NewApi")
    private void test2() {
        AccessibilityNodeInfo nodeInfo = getRootInActiveWindow();
        if (nodeInfo != null) {
            List<AccessibilityNodeInfo> list = nodeInfo
                    .findAccessibilityNodeInfosByText("完成");

            AccessibilityNodeInfo node = list.get(0);
            android.util.Log.i("maptrix", "finish btn clicked");
            sending = true;
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
    }

    @SuppressLint("NewApi")
    private void test3() {
        AccessibilityNodeInfo nodeInfo = getRootInActiveWindow();
        if (nodeInfo != null) {
            List<AccessibilityNodeInfo> list = nodeInfo
                    .findAccessibilityNodeInfosByText("发消息");
            if(list != null && list.size() > 0) {
                AccessibilityNodeInfo node = list.get(0);
                android.util.Log.i("maptrix", "sendMsg btn clicked");
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (fill()) {
                            send();
                        }
                    }
                }, 1000);
            } else {
                back2Home();
            }
        }
    }

    @SuppressLint("NewApi")
    private void test4() {
        //如果当前notification引入的微信的好友还存在，先完成这个
        if(remaining > 0) {
            final AccessibilityNodeInfo nodeInfo = getRootInActiveWindow();
            List<AccessibilityNodeInfo> list = nodeInfo
                    .findAccessibilityNodeInfosByViewId("com.tencent.mm:id/alq");
            AccessibilityNodeInfo node = list.get(0);
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    List<AccessibilityNodeInfo> list = nodeInfo
                            .findAccessibilityNodeInfosByText("新的朋友");
                    list.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK);
                }
            }, 1000);
        }
        //如果还有notification，就继续执行
        else{
            //先把当前完成的出队
            intentQueue.poll();
            android.util.Log.i("maptrix", "queue size:"+intentQueue.size());
            if(!intentQueue.isEmpty()){
                final Notification notification = intentQueue.element();
                back2Home();
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        sendNotification(notification);
                    }
                }, 500);
            }
            else {
                if(hasAction){
                    back2Home();
                }
                hasAction = false;
            }
        }
    }

    /**
     * @param event
     */
    private void sendNotifacationReply(AccessibilityEvent event) {
        hasAction = true;
        if (event.getParcelableData() != null
                && event.getParcelableData() instanceof Notification) {
            Notification notification = (Notification) event
                    .getParcelableData();
            //队列为空则立即执行，否则只是加入队列并返回
            if(intentQueue.isEmpty()){
                intentQueue.add(notification);
                android.util.Log.i("maptrix", "queue empty, current size:" + intentQueue.size());
                sendNotification(notification);
            } else {
                intentQueue.add(notification);
                android.util.Log.i("maptrix", "queue not empty, current size:" + intentQueue.size());
            }
        }
    }

    private void sendNotification(Notification notification){
        String content = notification.tickerText.toString();
        android.util.Log.i("maptrix", "sender content =" + content);
        PendingIntent pendingIntent = notification.contentIntent;
        if (content.contains("请求添加")){
            jobType = 1;
        }
        if (content.contains("[微信红包]")){
            jobType = 2;
//            back2Home();
        }
        try {
            pendingIntent.send();
        } catch (PendingIntent.CanceledException e) {
            e.printStackTrace();
            android.util.Log.i("maptrix", "error" + e.toString());
        }
        //延迟1s后开始第一步
//        if(jobType == 1) {
//            handler.postDelayed(new Runnable() {
//                @Override
//                public void run() {
//                    test1();
//                }
//            }, 1000);
//        }
    }

    @SuppressLint("NewApi")
    private boolean fill() {
        //以后加别的判断
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode != null) {
            return findEditText(rootNode, "感谢您的关注，小初等您好久了，如果您喜欢我们的蛋糕，您可以访问网址：https://excake.com，或者拨打电话：4008468686");
        }
        return false;
    }


    private boolean findEditText(AccessibilityNodeInfo rootNode, String content) {
        int count = rootNode.getChildCount();

//        android.util.Log.d("maptrix", "root class=" + rootNode.getClassName() + "," + rootNode.getText() + "," + count);
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo nodeInfo = rootNode.getChild(i);
            if (nodeInfo == null) {
//                android.util.Log.d("maptrix", "nodeinfo = null");
                continue;
            }

//            android.util.Log.d("maptrix", "class=" + nodeInfo.getClassName());
//            android.util.Log.e("maptrix", "ds=" + nodeInfo.getContentDescription());
//            if (nodeInfo.getContentDescription() != null) {
//                int nindex = nodeInfo.getContentDescription().toString().indexOf(name);
//                int cindex = nodeInfo.getContentDescription().toString().indexOf(scontent);
//                android.util.Log.e("maptrix", "nindex=" + nindex + " cindex=" + cindex);
//                if (nindex != -1) {
//                    itemNodeinfo = nodeInfo;
//                    android.util.Log.i("maptrix", "find node info");
//                }
//            }
            if ("android.widget.EditText".equals(nodeInfo.getClassName())) {
//                android.util.Log.i("maptrix", "==================");
                Bundle arguments = new Bundle();
                arguments.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT,
                        AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD);
                arguments.putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN,
                        true);
                nodeInfo.performAction(AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY,
                        arguments);
//                nodeInfo.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
//                ClipData clip = ClipData.newPlainText("label", content);
//                ClipboardManager clipboardManager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
//                clipboardManager.setPrimaryClip(clip);
//                nodeInfo.performAction(AccessibilityNodeInfo.ACTION_PASTE);
                Bundle args = new Bundle();
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        content);
                nodeInfo.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                return true;
            }

            if (findEditText(nodeInfo, content)) {
                return true;
            }
        }

        return false;
    }

    @Override
    public void onInterrupt() {

    }

    /**
     * 判断指定的应用是否在前台运行
     *
     * @param packageName
     * @return
     */
    private boolean isAppForeground(String packageName) {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ComponentName cn = am.getRunningTasks(1).get(0).topActivity;
        String currentPackageName = cn.getPackageName();
        if (!TextUtils.isEmpty(currentPackageName) && currentPackageName.equals(packageName)) {
            return true;
        }

        return false;
    }


    /**
     * 将当前应用运行到前台
     */
    private void bring2Front() {
        ActivityManager activtyManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningTaskInfo> runningTaskInfos = activtyManager.getRunningTasks(3);
        for (ActivityManager.RunningTaskInfo runningTaskInfo : runningTaskInfos) {
            if (this.getPackageName().equals(runningTaskInfo.topActivity.getPackageName())) {
                activtyManager.moveTaskToFront(runningTaskInfo.id, ActivityManager.MOVE_TASK_WITH_HOME);
                return;
            }
        }
    }

    /**
     * 回到系统桌面
     */
    private void back2Home() {
        Intent home = new Intent(Intent.ACTION_MAIN);

        home.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        home.addCategory(Intent.CATEGORY_HOME);

        startActivity(home);
    }


    /**
     * 系统是否在锁屏状态
     *
     * @return
     */
    private boolean isScreenLocked() {
        KeyguardManager keyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        return keyguardManager.inKeyguardRestrictedInputMode();
    }

    private void wakeAndUnlock() {
        //获取电源管理器对象
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);

        //获取PowerManager.WakeLock对象，后面的参数|表示同时传入两个值，最后的是调试用的Tag
        PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "bright");

        //点亮屏幕
        wl.acquire(1000);

        //得到键盘锁管理器对象
        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        kl = km.newKeyguardLock("unLock");

        //解锁
        kl.disableKeyguard();

    }

    private void release() {

        if (locked && kl != null) {
            android.util.Log.d("maptrix", "release the lock");
            //得到键盘锁管理器对象
            kl.reenableKeyguard();
            locked = false;
        }
    }

    private void getLastPacket() {
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();//获取当前窗口的根节点
        recycle(rootNode);
//        android.util.Log.d("maptrix","parents.size() == "+ parents.size()+" "+" mHaveReceivedRedNumber == "+mHaveReceivedRedNumber);
        if(parents.size()>0 && mHaveReceivedRedNumber < parents.size()){
            parents.get(parents.size() - 1).performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        parents.clear();//清楚parents 个数避免一直累加
        mHaveReceivedRedNumber = 0;
    }

    public void recycle(AccessibilityNodeInfo info) {
        if (info.getChildCount() == 0) {
//            android.util.Log.d("maptrix"," if ChildCount == "+info.getChildCount());
            if (info.getText() != null) {
                if ("领取红包".equals(info.getText().toString())) {
//                    android.util.Log.d("maptrix"," 检测到红包，判断是否可点击 == "+info.isClickable());
                    if (info.isClickable()) {//判断是否可点击
//                        android.util.Log.d("maptrix"," performAction.ACTION_CLICK "+info.getChildCount());
                        info.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    }
                    AccessibilityNodeInfo parent = info.getParent();
                    while (parent != null) {
                        if (parent.isClickable()) {//如果其父view 可点击则保存起来
                            parents.add(parent);
//                            android.util.Log.d("maptrix"," recycle.parents size == "+parents.size());
                            break;
                        }
                        parent = parent.getParent();
                    }
                }
                if(info.getText().toString().contains("你领取了")){
                    mHaveReceivedRedNumber++;
                }
            }
        } else {
            for (int i = 0; i < info.getChildCount(); i++) {
//                android.util.Log.d("maptrix"," else ChildCount == "+info.getChildCount());
                if (info.getChild(i) != null) {
//                    android.util.Log.d("maptrix"," for if  the child sum  == "+ i);
                    recycle(info.getChild(i));
                }
            }
        }
    }
}
