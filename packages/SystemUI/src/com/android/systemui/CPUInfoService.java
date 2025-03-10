/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.PowerManager;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;

import java.lang.StringBuffer;

public class CPUInfoService extends Service {
    private View mView;
    private Thread mCurCPUThread;
    private final String TAG = "CPUInfoService";
    private PowerManager mPowerManager;
    private boolean mPowerProfilesSupported;
    private int mNumCpus = 1;
    private String[] mCurrFreq=null;
    private String[] mCurrGov=null;

    private static final String NUM_OF_CPUS_PATH = "/sys/devices/system/cpu/present";
    private int CPU_TEMP_DIVIDER = 1;
    private String CPU_TEMP_SENSOR = "";
    private boolean mCpuTempAvail;

    private class CPUView extends View {
        private Paint mOnlinePaint;
        private Paint mOfflinePaint;
        private float mAscent;
        private int mFH;
        private int mMaxWidth;

        private int mNeededWidth;
        private int mNeededHeight;
        private String mCpuTemp;

        private String mPowerProfile;
        private boolean mDataAvail;

        private Handler mCurCPUHandler = new Handler() {
            public void handleMessage(Message msg) {
                if(msg.obj==null){
                    return;
                }
                if(msg.what==1){
                    String msgData = (String) msg.obj;
                    try {
                        String[] parts=msgData.split(";");
                        mCpuTemp=parts[0];

                        String[] cpuParts=parts[1].split("\\|");
                        for(int i=0; i<cpuParts.length; i++){
                            String cpuInfo=cpuParts[i];
                            String cpuInfoParts[]=cpuInfo.split(":");
                            if(cpuInfoParts.length==2){
                                mCurrFreq[i]=cpuInfoParts[0];
                                mCurrGov[i]=cpuInfoParts[1];
                            } else {
                                mCurrFreq[i]="0";
                                mCurrGov[i]="";
                            }
                        }
                        if (mPowerProfilesSupported) {
                            mPowerProfile = parts[3];
                        }
                        mDataAvail = true;
                        updateDisplay();
                    } catch(ArrayIndexOutOfBoundsException e) {
                        Log.e(TAG, "illegal data " + msgData);
                    }
                }
            }
        };

        CPUView(Context c) {
            super(c);
            float density = c.getResources().getDisplayMetrics().density;
            int paddingPx = Math.round(5 * density);
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx);
            setBackgroundColor(Color.argb(0x60, 0, 0, 0));

            final int textSize = Math.round(12 * density);

            mOnlinePaint = new Paint();
            mOnlinePaint.setAntiAlias(true);
            mOnlinePaint.setTextSize(textSize);
            mOnlinePaint.setColor(Color.WHITE);
            mOnlinePaint.setShadowLayer(5.0f, 0.0f, 0.0f, Color.BLACK);

            mOfflinePaint = new Paint();
            mOfflinePaint.setAntiAlias(true);
            mOfflinePaint.setTextSize(textSize);
            mOfflinePaint.setColor(Color.RED);

            mAscent = mOnlinePaint.ascent();
            float descent = mOnlinePaint.descent();
            mFH = (int)(descent - mAscent + .5f);

            final String maxWidthStr="cpuX interactive 0000000";
            mMaxWidth = (int)mOnlinePaint.measureText(maxWidthStr);

            updateDisplay();
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            mCurCPUHandler.removeMessages(1);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(resolveSize(mNeededWidth, widthMeasureSpec),
                    resolveSize(mNeededHeight, heightMeasureSpec));
        }

        private String getCPUInfoString(int i) {
            String freq=mCurrFreq[i];
            String gov=mCurrGov[i];
            return "cl" + i + ": " + gov + " " + String.format("%8s", toMHz(freq));
        }

        private String getCpuTemp(String cpuTemp) {
            if (CPU_TEMP_DIVIDER > 1) {
                return String.format("%s",
                        Integer.parseInt(cpuTemp) / CPU_TEMP_DIVIDER);
            } else {
                return cpuTemp;
            }
        }

        @Override
        public void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (!mDataAvail) {
                return;
            }

            final int W = mNeededWidth;
            final int RIGHT = getWidth()-1;

            int x = RIGHT - mPaddingRight;
            int top = mPaddingTop + 2;
            int bottom = mPaddingTop + mFH - 2;

            int y = mPaddingTop - (int)mAscent;

            if(!mCpuTemp.equals("0")) {
                canvas.drawText("Temp " + getCpuTemp(mCpuTemp) + "°C",
                        RIGHT-mPaddingRight-mMaxWidth, y-1, mOnlinePaint);
                y += mFH;
            }

            for(int i=0; i<mCurrFreq.length; i++){
                String s=getCPUInfoString(i);
                String freq=mCurrFreq[i];
                if(!freq.equals("0")){
                        canvas.drawText(s, RIGHT-mPaddingRight-mMaxWidth,
                            y-1, mOnlinePaint);
                } else {
                    canvas.drawText(s, RIGHT-mPaddingRight-mMaxWidth,
                        y-1, mOfflinePaint);
                }
                y += mFH;
            }
        }

        void updateDisplay() {
            if (!mDataAvail) {
                return;
            }
            final int NW = mPowerProfilesSupported ? (mNumCpus + 1) : mNumCpus;

            int neededWidth = mPaddingLeft + mPaddingRight + mMaxWidth;
            int neededHeight = mPaddingTop + mPaddingBottom + (mFH*((mCpuTempAvail?1:0)+NW));
            if (neededWidth != mNeededWidth || neededHeight != mNeededHeight) {
                mNeededWidth = neededWidth;
                mNeededHeight = neededHeight;
                requestLayout();
            } else {
                invalidate();
            }
        }

        private String toMHz(String mhzString) {
            return new StringBuilder().append(Integer.valueOf(mhzString) / 1000).append(" MHz").toString();
        }

        public Handler getHandler(){
            return mCurCPUHandler;
        }
    }

    protected class CurCPUThread extends Thread {
        private boolean mInterrupt = false;
        private Handler mHandler;

        private static final String CURRENT_CPU = "/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq";
        private static final String CPU_ROOT = "/sys/devices/system/cpu/cpu";
        private static final String CPU_CUR_TAIL = "/cpufreq/scaling_cur_freq";
        private static final String CPU_GOV_TAIL = "/cpufreq/scaling_governor";

        public CurCPUThread(Handler handler, int numCpus){
            mHandler=handler;
            mNumCpus = numCpus;
        }

        public void interrupt() {
            mInterrupt = true;
        }

        @Override
        public void run() {
            try {
                while (!mInterrupt) {
                    sleep(500);
                    StringBuffer sb=new StringBuffer();
                    String cpuTemp = CPUInfoService.readOneLine(CPU_TEMP_SENSOR);
                    sb.append(cpuTemp == null ? "0" : cpuTemp);
                    sb.append(";");

                    for(int i=0; i<mNumCpus; i++){
                        final String freqFile=CPU_ROOT+i+CPU_CUR_TAIL;
                        String currFreq = CPUInfoService.readOneLine(freqFile);
                        final String govFile=CPU_ROOT+i+CPU_GOV_TAIL;
                        String currGov = CPUInfoService.readOneLine(govFile);

                        if(currFreq==null){
                            currFreq="0";
                            currGov="";
                        }

                        sb.append(currFreq+":"+currGov+"|");
                    }
                    sb.deleteCharAt(sb.length()-1);
                    mHandler.sendMessage(mHandler.obtainMessage(1, sb.toString()));
                }
            } catch (InterruptedException e) {
                return;
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        mNumCpus = getNumOfCpus();
        mCurrFreq = new String[mNumCpus];
        mCurrGov = new String[mNumCpus];

        CPU_TEMP_DIVIDER = getResources().getInteger(R.integer.config_cpuTempDivider);
        CPU_TEMP_SENSOR = getResources().getString(R.string.config_cpuTempSensor);

        mCpuTempAvail = readOneLine(CPU_TEMP_SENSOR) != null;

        mView = new CPUView(this);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.RIGHT | Gravity.TOP;
        params.setTitle("CPU Info");

        mCurCPUThread = new CurCPUThread(mView.getHandler(), mNumCpus);
        mCurCPUThread.start();

        Log.d(TAG, "started CurCPUThread");

        WindowManager wm = (WindowManager)getSystemService(WINDOW_SERVICE);
        wm.addView(mView, params);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mCurCPUThread.isAlive()) {
            mCurCPUThread.interrupt();
            try {
                mCurCPUThread.join();
            } catch (InterruptedException e) {
            }
        }
        Log.d(TAG, "stopped CurCPUThread");
        ((WindowManager)getSystemService(WINDOW_SERVICE)).removeView(mView);
        mView = null;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static String readOneLine(String fname) {
        BufferedReader br;
        String line = null;
        try {
            br = new BufferedReader(new FileReader(fname), 512);
            try {
                line = br.readLine();
            } finally {
                br.close();
            }
        } catch (Exception e) {
            return null;
        }
        return line;
    }

    private static int getNumOfCpus() {
        int numOfCpu = 1;
        String numOfCpus = readOneLine(NUM_OF_CPUS_PATH);
        String[] cpuCount = numOfCpus.split("-");
        if (cpuCount.length > 1) {
            try {
                int cpuStart = Integer.parseInt(cpuCount[0]);
                int cpuEnd = Integer.parseInt(cpuCount[1]);

                numOfCpu = cpuEnd - cpuStart + 1;

                if (numOfCpu < 0)
                    numOfCpu = 1;
            } catch (NumberFormatException ex) {
                numOfCpu = 1;
            }
        }
        return numOfCpu;
    }
}
